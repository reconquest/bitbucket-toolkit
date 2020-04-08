package io.reconquest.toolz;

import java.util.Date;

import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.config.RunMode;
import com.atlassian.scheduler.config.Schedule;

public abstract class Routine {
  public abstract String getId();

  public Schedule getSchedule() {
    return Schedule.runOnce(new Date());
  }

  public RunMode getRunMode() {
    return RunMode.RUN_ONCE_PER_CLUSTER;
  }

  protected final JobRunnerResponse success() {
    return JobRunnerResponse.success();
  }

  protected final JobRunnerResponse failed(String cause) {
    return JobRunnerResponse.failed(cause);
  }

  protected final JobRunnerResponse failed(Throwable cause) {
    return JobRunnerResponse.failed(cause);
  }

  public abstract JobRunnerResponse run();

  public String getDurationSince(long startedAt) {
    return String.format("%.03f", ((float) (System.nanoTime() - (long) startedAt)) / 1000000);
  }
}
