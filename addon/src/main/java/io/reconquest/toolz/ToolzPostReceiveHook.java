package io.reconquest.toolz;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import com.atlassian.bitbucket.event.repository.RepositoryCreatedEvent;
import com.atlassian.bitbucket.hook.HookService;
import com.atlassian.bitbucket.hook.repository.DisableRepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.EnableRepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.PostRepositoryHook;
import com.atlassian.bitbucket.hook.repository.PostRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.hook.repository.SynchronousPreferred;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestCreateRequest;
import com.atlassian.bitbucket.pull.PullRequestMergeRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.MinimalRef;
import com.atlassian.bitbucket.repository.StandardRefType;
import com.atlassian.bitbucket.scope.RepositoryScope;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.event.api.EventListener;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SynchronousPreferred
public class ToolzPostReceiveHook implements PostRepositoryHook<RepositoryHookRequest> {
  private static Logger log = LoggerFactory.getLogger(ToolzPostReceiveHook.class);

  public static final String HOOK_KEY = Toolz.KEY + ":" + "post-receive-hook";

  private HookService hookService;
  private RepositoryHookService repositoryHookService;
  private PullRequestService pullRequestService;
  private SecurityService securityService;
  private UserService userService;

  private static final int POOL_SIZE = 16;

  public ToolzPostReceiveHook(
      @ComponentImport UserService userService,
      @ComponentImport SecurityService securityService,
      @ComponentImport PullRequestService pullRequestService,
      @ComponentImport RepositoryHookService repositoryHookService) {
    this.userService = userService;
    this.securityService = securityService;
    this.pullRequestService = pullRequestService;
    this.repositoryHookService = repositoryHookService;
  }

  @Override
  public void postUpdate(
      @Nonnull PostRepositoryHookContext context, @Nonnull RepositoryHookRequest request) {
    // this thing will not allow to merge
    DisableRepositoryHookRequest.Builder builder = new DisableRepositoryHookRequest.Builder(
        new RepositoryScope(request.getRepository()), "io.reconquest.snake:snake-merge-check-hook");
    repositoryHookService.disable(builder.build());

    AtomicInteger pullRequestsToOpen = new AtomicInteger(0);
    AtomicInteger pullRequestsToMerge = new AtomicInteger(0);

    ApplicationUser user = userService.getUserById(1);

    List<MinimalRef> refs = request.getRefChanges().stream()
        .map((x) -> {
          return x.getRef();
        })
        .filter((x) -> x.getType().equals(StandardRefType.BRANCH))
        .collect(Collectors.toList());

    List<MinimalRef> refsOpen = refs.stream()
        .filter((ref) -> ref.getDisplayId().startsWith("pr-open-"))
        .collect(Collectors.toList());

    List<MinimalRef> refsMerge = refs.stream()
        .filter((ref) -> ref.getDisplayId().startsWith("pr-merge-"))
        .collect(Collectors.toList());

    ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE);

    refsOpen.stream().forEach((ref) -> {
      executor.submit(() -> {
        long startedAt = System.nanoTime();
        int current = pullRequestsToOpen.incrementAndGet();

        securityService.impersonating(user, "user").call(() -> {
          newPullRequest(ref, request, false);
          return null;
        });

        log.warn(
            "{}/{} [open] created pr: {} ms",
            new Object[] {current, refsOpen.size(), getDurationSince(startedAt)});
      });
    });

    refsMerge.stream().forEach((ref) -> {
      executor.submit(() -> {
        securityService.impersonating(user, "user").call(() -> {
          long refStartedAt = System.nanoTime();

          int current = pullRequestsToMerge.incrementAndGet();

          PullRequest pr = newPullRequest(ref, request, true);

          log.warn(
              "{}/{} [merge] created pr: {} ms",
              new Object[] {current, refsMerge.size(), getDurationSince(refStartedAt)});

          long mergeStartedAt = System.nanoTime();

          PullRequestMergeRequest.Builder mergeBuilder = new PullRequestMergeRequest.Builder(pr);
          mergeBuilder.autoSubject(true);
          pullRequestService.merge(mergeBuilder.build());

          log.warn("{}/{} merged pr: {} ms (with create: {} ms)", new Object[] {
            current,
            refsMerge.size(),
            getDurationSince(mergeStartedAt),
            getDurationSince(refStartedAt)
          });

          return null;
        });
      });
    });
  }

  private PullRequest newPullRequest(
      MinimalRef ref, RepositoryHookRequest request, boolean isMerge) {
    PullRequestCreateRequest.Builder prBuilder = new PullRequestCreateRequest.Builder();

    prBuilder.fromRefId(ref.getId());
    prBuilder.fromRepository(request.getRepository());
    prBuilder.toRepository(request.getRepository());
    if (isMerge) {
      prBuilder.toBranchId("x-" + ref.getDisplayId());
    } else {
      prBuilder.toBranchId("master");
    }
    prBuilder.title(ref.getDisplayId());

    return pullRequestService.create(prBuilder.build());
  }

  @EventListener
  public void onRepositoryCreatedEvent(RepositoryCreatedEvent event) {
    String name = event.getRepository().toString();
    if (name.toLowerCase().startsWith("pr-")) {
      log.debug("Enabling post-receive hook for repository: {}", event.getRepository().toString());

      EnableRepositoryHookRequest.Builder builder = new EnableRepositoryHookRequest.Builder(
          new RepositoryScope(event.getRepository()), HOOK_KEY);
      EnableRepositoryHookRequest request = builder.build();
      repositoryHookService.enable(request);
    }
  }

  public String getDurationSince(long startedAt) {
    return String.format("%.03f", ((float) (System.nanoTime() - (long) startedAt)) / 1000000);
  }
}
