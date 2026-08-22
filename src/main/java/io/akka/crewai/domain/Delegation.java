package io.akka.crewai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One delegation, from a name to a string — SPEC-001 R4 through R18.
 *
 * <p>The whole capability has one exit: a string. A coworker that does not exist, a delegate that
 * failed, a delegate that ran out of time and a delegate that answered all leave the same way, so
 * a delegating agent reading the result cannot tell a missing coworker from a broken one except
 * by reading the text.
 */
public final class Delegation {

  private static final String FAILURE_PREFIX = "Error executing task with agent '";

  private final Roster roster;
  private final Delegate delegate;
  private final BudgetLedger ledger;

  public Delegation(Roster roster, Delegate delegate, BudgetLedger ledger) {
    this.roster = roster;
    this.delegate = delegate;
    this.ledger = ledger;
  }

  public DelegationResult run(DelegationRequest request) {
    Optional<AgentSpec> found = roster.find(CoworkerName.sanitize(request.candidateName()));
    if (found.isEmpty()) {
      return new DelegationResult(roster.notFoundText(), List.of(), 0);
    }

    AgentSpec agent = found.get();
    String sanitizedRole = agent.sanitizedRole();
    DelegatedTask task = DelegatedTask.of(request.work(), agent, request.context());
    List<Announcement> announcements = new ArrayList<>();

    for (int attempt = 1; ; attempt++) {
      announcements.add(Announcement.STARTED);
      AttemptOutcome outcome = attemptOnce(task, attempt, agent);

      if (outcome instanceof AttemptOutcome.Succeeded succeeded) {
        announcements.add(Announcement.COMPLETED);
        return new DelegationResult(succeeded.output(), announcements, attempt);
      }

      AttemptOutcome.Failed failed = (AttemptOutcome.Failed) outcome;
      if (failed.failureClass().isAnnounced()) {
        announcements.add(Announcement.ERROR);
      }
      if (failed.failureClass().escapes()) {
        throw new DelegateAbortedException(failed.message(), announcements, attempt);
      }
      RetryRule.Decision decision =
          ledger.recordFailure(sanitizedRole, failed.failureClass(), agent.retryLimit());
      // A budget that never advances would otherwise be an unbounded loop rather than an
      // answer, and a ledger reached over a network is not guaranteed to advance. R10 puts
      // the ceiling at one more attempt than the limit, so a correct ledger never meets this.
      boolean budgetIsNotMoving = attempt > agent.retryLimit();
      if (decision.verdict() == RetryVerdict.GIVE_UP || budgetIsNotMoving) {
        return new DelegationResult(
            failureText(sanitizedRole, failed.message()), announcements, attempt);
      }
    }
  }

  /**
   * One attempt, including everything the agent's wall-clock limit changes about it: whether the
   * limit is usable at all, how an ordinary failure is worded, and what an overrun says.
   */
  private AttemptOutcome attemptOnce(DelegatedTask task, int attemptNumber, AgentSpec agent) {
    Optional<Integer> limit = agent.maxExecutionTimeSeconds();
    if (limit.isEmpty()) {
      return delegate.attempt(task, attemptNumber);
    }
    if (limit.get() <= 0) {
      return AttemptOutcome.failed(
          FailureClass.RETRYABLE, "Max Execution time must be a positive integer greater than zero");
    }
    return withinLimit(task, attemptNumber, limit.get());
  }

  private AttemptOutcome withinLimit(DelegatedTask task, int attemptNumber, int limitSeconds) {
    // A virtual thread per attempt rather than a pooled platform thread: the attempt may be
    // abandoned at the limit and never joined (SPEC-001 D1), and starting a platform thread for
    // each one cost about a hundred microseconds against a rule that otherwise runs in one.
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Callable<AttemptOutcome> work = () -> delegate.attempt(task, attemptNumber);
      Future<AttemptOutcome> future = executor.submit(work);
      try {
        AttemptOutcome outcome = future.get(limitSeconds, TimeUnit.SECONDS);
        if (outcome instanceof AttemptOutcome.Failed failed
            && failed.failureClass() == FailureClass.RETRYABLE) {
          return AttemptOutcome.failed(
              FailureClass.RETRYABLE, "Task execution failed: " + failed.message());
        }
        return outcome;
      } catch (TimeoutException e) {
        future.cancel(true);
        return AttemptOutcome.failed(
            FailureClass.TIMEOUT,
            "Task '"
                + task.description()
                + "' execution timed out after "
                + limitSeconds
                + " seconds. Consider increasing max_execution_time or optimizing the task.");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return AttemptOutcome.failed(FailureClass.FATAL, "interrupted");
      } catch (java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return AttemptOutcome.failed(
            FailureClass.RETRYABLE, "Task execution failed: " + cause.getMessage());
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private String failureText(String sanitizedRole, String message) {
    return FAILURE_PREFIX + sanitizedRole + "'. Error: " + message;
  }
}
