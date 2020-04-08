package io.reconquest.toolz.rest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.user.UserAdminService;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.plugins.rest.common.security.AnonymousAllowed;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerRuntimeException;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.config.JobId;
import com.atlassian.scheduler.config.JobRunnerKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reconquest.toolz.RepositoriesRoutine;
import io.reconquest.toolz.Routine;
import io.reconquest.toolz.Toolz;

@Path("/")
public class ToolzRest implements JobRunner {
  private static Logger log = LoggerFactory.getLogger(Toolz.KEY);
  private final JobRunnerKey runnerKey = JobRunnerKey.of(Toolz.KEY + ":runner");
  private Map<JobId, Routine> routines = new HashMap<>();

  private SchedulerService schedulerService;
  private ProjectService projectService;
  private RepositoryService repositoryService;
  private SecurityService securityService;
  private UserService userService;
  private UserAdminService userAdminService;
  private boolean registered = false;

  public ToolzRest(
      @ComponentImport UserService userService,
      @ComponentImport UserAdminService userAdminService,
      @ComponentImport SchedulerService schedulerService,
      @ComponentImport SecurityService securityService,
      @ComponentImport ProjectService projectService,
      @ComponentImport RepositoryService repositoryService) {
    this.userAdminService = userAdminService;
    this.userService = userService;
    this.securityService = securityService;
    this.repositoryService = repositoryService;
    this.projectService = projectService;
    this.schedulerService = schedulerService;
  }

  public void registerRoutine(Routine routine) {
    if (!registered) {
      schedulerService.registerJobRunner(runnerKey, this);
      registered = true;
    }

    try {
      JobConfig config = JobConfig.forJobRunnerKey(runnerKey)
          .withSchedule(routine.getSchedule())
          .withRunMode(routine.getRunMode());

      JobId id = JobId.of(routine.getId() + ":" + UUID.randomUUID().toString());

      schedulerService.scheduleJob(id, config);
      routines.put(id, routine);

      log.info("Registered routine: {}", routine.getId());
    } catch (SchedulerRuntimeException | SchedulerServiceException e) {
      e.printStackTrace();

      log.error("unable to schedule routine job {}", routine.getId(), e);
    }
  }

  public JobRunnerResponse runJob(JobRunnerRequest request) {
    Routine routine = routines.get(request.getJobId());
    if (routine != null) {
      log.debug("Running job: {}", request.getJobId().toString());

      JobRunnerResponse result = securityService
          .impersonating(userService.getUserById(1), "a")
          .call(() -> {
            return routine.run();
          });

      routines.remove(request.getJobId());

      return result;
    }

    return JobRunnerResponse.failed("No such job");
  }

  @Path("/repositories")
  @GET
  @AnonymousAllowed
  @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
  public Response getMessage(
      @QueryParam("prefix") String prefix,
      @QueryParam("repositories") Integer repositories,
      @QueryParam("projects") Integer projects) {
    registerRoutine(
        new RepositoriesRoutine(prefix, repositories, projects, projectService, repositoryService));
    return Response.ok().build();
  }
}
