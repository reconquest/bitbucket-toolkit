package io.reconquest.toolz;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.project.ProjectCreateRequest;
import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.repository.RepositoryCreateRequest;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.scheduler.JobRunnerResponse;

import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.NonNull;

public class RepositoriesRoutine extends Routine {
  private static Logger log = LoggerFactory.getLogger(Toolz.KEY);
  private String prefix;
  private Integer repositories;
  private Integer projects;
  private ProjectService projectService;
  private RepositoryService repositoryService;

  public JobRunnerResponse run() {
    AtomicInteger totalRepos = new AtomicInteger(0);
    Integer expectedTotalRepos = this.repositories * this.projects;

    long allStartedAt = System.nanoTime();
    IntStream.range(0, this.projects).forEach((projectIndex) -> {
      final String projKey =
          prefix + "-" + RandomStringUtils.random(4, true, false) + String.valueOf(projectIndex);

      ProjectCreateRequest.Builder projBuilder = new ProjectCreateRequest.Builder();
      projBuilder.key(projKey);
      projBuilder.name(projKey);

      Project project = projectService.create(projBuilder.build());

      log.warn(
          "{}/{} project created: {}",
          new Object[] {projectIndex + 1, this.projects, project.getKey()});

      long projStartedAt = System.nanoTime();
      IntStream.range(0, this.repositories).forEach((repoIndex) -> {
        long repoStartedAt = System.nanoTime();

        final String repoName = projKey + "_" + String.valueOf(repoIndex);

        RepositoryCreateRequest.Builder repoBuilder = new RepositoryCreateRequest.Builder();
        repoBuilder.project(project);
        repoBuilder.name(repoName);
        repoBuilder.scmId("git");

        repositoryService.create(repoBuilder.build());
        Integer created = totalRepos.incrementAndGet();

        log.warn(
            "{}/{} repositories created: {} ms",
            new Object[] {created, expectedTotalRepos, getDurationSince(repoStartedAt)});
      });

      log.warn("{}/{} project completed creating {} repositories: {} ms", new Object[] {
        projectIndex + 1, this.projects, this.repositories, getDurationSince(projStartedAt)
      });
    });

    log.warn("total: {} projects and {} repositories ({} each) created: {} ms", new Object[] {
      this.projects, totalRepos.get(), this.repositories, getDurationSince(allStartedAt)
    });

    return success();
  }

  public String getId() {
    return "repository-factory";
  }

  public RepositoriesRoutine(
      @NonNull String prefix,
      @NonNull Integer repositories,
      @NonNull Integer projects,
      @ComponentImport ProjectService projectService,
      @ComponentImport RepositoryService repositoryService) {
    this.repositoryService = repositoryService;
    this.prefix = prefix;
    this.projectService = projectService;
    this.repositories = repositories;
    this.projects = projects;
  }
}
