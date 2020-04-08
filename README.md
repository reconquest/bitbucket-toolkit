# bitbucket-toolkit

This is the toolkit for testing the performance of Bitbucket add-ons related to
pull requests and post-receive hooks such as [Snake CI](https://snake-ci.com/).

## Requirements

* [task — alternative tool for make](https://taskfile.dev/)
* [stacket — terminal cli for creating projects/repositories, installing add-ons](https://github.com/kovetskiy/stacket)

# Features

*Creating any number of*:
* open pull-requests
* merged pull-requests (opening and merging them automatically)
* projects
* repositories

## Installation

1. Install git-brancher: 

```bash
$ cd git-brancher
$ go get -v 
$ go install
```

2. Build & Install the add-on into your running dev Bitbucket instance

```bash
cd addon
task atlas:install
```

## Usage examples

* Creating 30000 projects with 5 repositories in each project:
    ```
    http -a admin:admin GET 'https://bitbucket.local/rest/toolz/1.0/repositories?prefix=y&projects=30000&repositories=5'
    ```

* Creating 100000 open & 100000 closed pull requests
    ```
    cd ./addon
    ./taskutils/pull-request snake-ci.yaml 10000 10000
    ```

    As result, it will produce 300000 branches and 300000 commits and will merge
    Pull Requests in parallel mode (16 threads).

    Why we create 2 brancher for merging PRs? Because we can't merge into master
    concurrently, it will produce mid-merge states and PullRequest resource will
    be out of date after any merge, so it's much safer and convienient to have
    two special branches for merging.

## How does it work

The add-on exposes REST API for creating projects & repositories with the
specified prefix using
[ProjectService](https://docs.atlassian.com/bitbucket-server/javadoc/6.8.0/api/com/atlassian/bitbucket/project/ProjectService.html)
&
[RepositoryService](https://docs.atlassian.com/bitbucket-server/javadoc/6.8.0/api/com/atlassian/bitbucket/repository/RepositoryService.html) components.

The add-on registers post-receive hook that analyzes received changeset and
creates PR for each branch that starts with `pr-open-` or `pr-merge-`. If branch
starts with `pr-merge-`, the PR will be merged into `x-pr-merge-` branch with
the same suffix as `pr-merge` has.

`git-brancher` is a tool written in Go that creates Git branches & Git commits
into in-memory repository and pushes it into remote Bitbucket repository.

# Tips

Start Bitbucket server in Docker container

```bash
docker run \
  --detach \
  --name "6.10.0.bitbucket" \
  -p 7990:7990 \
  -p 7999:7999 \
  -e SERVER_PROXY_NAME=bitbucket.local \
  -e SERVER_PROXY_PORT=443 \
  -e SERVER_SECURE=true \
  -e ELASTICSEARCH_ENABLED=false \
  -v $(readlink -f docker)/6.10.0:/var/atlassian/application-data/bitbucket \
  atlassian/bitbucket-server:6.10.0
```

See also:
https://confluence.atlassian.com/bitbucketserver/automated-setup-for-bitbucket-server-776640098.html
