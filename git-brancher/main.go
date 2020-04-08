package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/reconquest/pkg/log"

	"github.com/docopt/docopt-go"
	"github.com/go-git/go-billy/v5/memfs"
	"github.com/go-git/go-git/v5"
	"github.com/go-git/go-git/v5/config"
	"github.com/go-git/go-git/v5/plumbing"
	"github.com/go-git/go-git/v5/plumbing/object"
	"github.com/go-git/go-git/v5/storage/memory"
)

var (
	version = "[manual build]"
	usage   = "git-brancher " + version + `

Usage:
  git-brancher [options] <open> <merge> [-f <file>...]
  git-brancher -h | --help
  git-brancher --version

Options:
  -f --file <file>  Put specified file into master branch.
  -p --push <url>     Push to the specified URL.
  -h --help           Show this screen.
  --version           Show version.
`
)

func main() {
	args, err := docopt.ParseArgs(usage, nil, "git-brancher "+version)
	if err != nil {
		log.Fatalf(err, "unable to parse args")
	}

	var opts struct {
		PushURL       string   `docopt:"--push"`
		Files         []string `docopt:"--file"`
		BranchesOpen  int      `docopt:"<open>"`
		BranchesMerge int      `docopt:"<merge>"`
	}

	err = args.Bind(&opts)
	if err != nil {
		log.Fatalf(err, "unable to bind args")
	}

	repo, err := git.Init(memory.NewStorage(), memfs.New())
	if err != nil {
		log.Fatalf(err, "unable to init git repository")
	}

	worktree, err := repo.Worktree()
	if err != nil {
		log.Fatalf(err, "unable to init worktree")
	}

	startedAt := time.Now()
	master, err := commitBranch(repo, worktree, "master", opts.Files...)
	if err != nil {
		log.Fatalf(err, "unable to create branch: master")
	}

	for i := 0; i < opts.BranchesOpen; i++ {
		err := worktree.Checkout(&git.CheckoutOptions{
			Hash: master.Hash(),
		})
		if err != nil {
			log.Fatalf(err, "unable to checkout to master")
		}

		_, err = commitBranch(repo, worktree, fmt.Sprintf("pr-open-%v", i))
		if err != nil {
			log.Fatalf(err, "unable to create pr-open branch")
		}
	}

	log.Infof(
		nil,
		"creating %d open branches took: %s",
		opts.BranchesOpen,
		time.Since(startedAt),
	)

	for i := 0; i < opts.BranchesMerge; i++ {
		err := worktree.Checkout(&git.CheckoutOptions{
			Hash: master.Hash(),
		})
		if err != nil {
			log.Fatalf(err, "unable to checkout to master")
		}

		_, err = commitBranch(repo, worktree, fmt.Sprintf("x-pr-merge-%v", i))
		if err != nil {
			log.Fatalf(err, "unable to create target branch for pr-merge")
		}

		_, err = commitBranch(repo, worktree, fmt.Sprintf("pr-merge-%v", i))
		if err != nil {
			log.Fatalf(err, "unable to create branch for pr-merge")
		}
	}

	log.Infof(nil, "creating %d merge branches took: %s", opts.BranchesMerge, time.Since(startedAt))

	if opts.PushURL != "" {
		startedAt = time.Now()
		_, err := repo.CreateRemote(&config.RemoteConfig{
			Name: "origin",
			URLs: []string{
				opts.PushURL,
			},
			Fetch: []config.RefSpec{
				"+refs/heads/*:refs/remotes/origin/*",
			},
		})
		if err != nil {
			log.Fatalf(err, "unable to create git origin for %s", opts.PushURL)
		}

		err = repo.Push(&git.PushOptions{
			RemoteName: "origin",
			RefSpecs: []config.RefSpec{
				"refs/heads/*:refs/heads/*",
			},
		})
		if err != nil {
			log.Fatalf(err, "unable to push to %s", opts.PushURL)
		}

		log.Infof(
			nil,
			"pushing %d+%d*2 branches took: %s",
			opts.BranchesOpen,
			opts.BranchesMerge,
			time.Since(startedAt),
		)
	}
}

func commitBranch(
	repo *git.Repository,
	worktree *git.Worktree,
	name string,
	filenames ...string,
) (*plumbing.Reference, error) {
	if len(filenames) > 0 {
		for _, filename := range filenames {
			file, err := worktree.Filesystem.Create(filepath.Base(filename))
			if err != nil {
				return nil, err
			}

			realfile, err := os.Open(filename)
			if err != nil {
				return nil, err
			}

			defer realfile.Close()

			_, err = io.Copy(file, realfile)
			if err != nil {
				return nil, err
			}

			err = file.Close()
			if err != nil {
				return nil, err
			}

			_, err = worktree.Add(filepath.Base(filename))
			if err != nil {
				return nil, err
			}
		}
	} else {
		file, err := worktree.Filesystem.Create(name)
		if err != nil {
			return nil, err
		}

		_, err = file.Write([]byte(name))
		if err != nil {
			return nil, err
		}

		err = file.Close()
		if err != nil {
			return nil, err
		}

		_, err = worktree.Add(name)
		if err != nil {
			return nil, err
		}
	}

	commit, err := worktree.Commit(name, &git.CommitOptions{
		Author: &object.Signature{
			Name:  "x",
			Email: "x",
		},
	})
	if err != nil {
		return nil, err
	}

	ref := plumbing.NewHashReference(plumbing.ReferenceName("refs/heads/"+name), commit)

	err = repo.Storer.SetReference(ref)
	if err != nil {
		return nil, err
	}

	return ref, nil
}
