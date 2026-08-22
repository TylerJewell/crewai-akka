package io.akka.crewai.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** SPEC-001 R6-R9, R12, R13, R15-R18. */
class DelegationTest {

  private static final AgentSpec ANALYST = new AgentSpec("Senior Research Analyst", 2);
  private static final AgentSpec WRITER = new AgentSpec("Writer", 2);
  private static final Roster ROSTER = new Roster(List.of(ANALYST, WRITER));

  private static DelegationRequest ask(String coworker) {
    return new DelegationRequest(ToolKind.DELEGATE, "Summarise the findings", "They are long.",
        coworker, null, null);
  }

  private static Delegation delegation(Delegate delegate) {
    return new Delegation(ROSTER, delegate, new InMemoryBudgetLedger());
  }

  // R6, R5 -------------------------------------------------------------------------

  @Test
  void aDelegationThatFindsNobodyAnnouncesNothing() {
    DelegationResult result = delegation(ScriptedDelegate.alwaysSucceeding("x")).run(ask("Chef"));

    assertThat(result.announcements()).isEmpty();
    assertThat(result.output()).isEqualTo(ROSTER.notFoundText());
    assertThat(result.attempts()).isZero();
  }

  // R7 -----------------------------------------------------------------------------

  @Test
  void theDelegateIsAskedForTheWorkVerbatim() {
    List<DelegatedTask> seen = new ArrayList<>();
    Delegate recording =
        (task, attempt) -> {
          seen.add(task);
          return AttemptOutcome.succeeded("done");
        };

    delegation(recording).run(ask("writer"));

    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).description()).isEqualTo("Summarise the findings");
    assertThat(seen.get(0).context()).isEqualTo("They are long.");
    assertThat(seen.get(0).role()).isEqualTo("Writer");
    assertThat(seen.get(0).expectedOutput())
        .isEqualTo(
            "Your best answer to your coworker asking you this, accounting for the context shared.");
  }

  @Test
  void aQuestionIsAskedForInTheSameShapeAsATask() {
    List<DelegatedTask> seen = new ArrayList<>();
    Delegate recording =
        (task, attempt) -> {
          seen.add(task);
          return AttemptOutcome.succeeded("done");
        };

    delegation(recording)
        .run(new DelegationRequest(ToolKind.ASK, "What did you find?", "Nothing yet.", "writer",
            null, null));

    assertThat(seen.get(0).description()).isEqualTo("What did you find?");
    assertThat(seen.get(0).expectedOutput())
        .isEqualTo(
            "Your best answer to your coworker asking you this, accounting for the context shared.");
  }

  // R8, R9, R15 --------------------------------------------------------------------

  @Test
  void aSucceedingDelegateComesBackWithItsOwnOutput() {
    DelegationResult result =
        delegation(ScriptedDelegate.alwaysSucceeding("the summary")).run(ask("Writer"));

    assertThat(result.output()).isEqualTo("the summary");
    assertThat(result.announcements()).containsExactly(Announcement.STARTED, Announcement.COMPLETED);
    assertThat(result.attempts()).isEqualTo(1);
  }

  @Test
  void aFailedDelegateComesBackAsTextNamingTheSanitisedRole() {
    DelegationResult result =
        delegation(ScriptedDelegate.alwaysFailing(FailureClass.RETRYABLE, "boom"))
            .run(ask("Senior Research Analyst"));

    assertThat(result.output())
        .isEqualTo("Error executing task with agent 'senior research analyst'. Error: boom");
  }

  @Test
  void announcementsPairEveryAttempt() {
    DelegationResult failing =
        delegation(ScriptedDelegate.alwaysFailing(FailureClass.RETRYABLE, "boom")).run(ask("Writer"));
    assertThat(failing.announcements())
        .containsExactly(
            Announcement.STARTED, Announcement.ERROR,
            Announcement.STARTED, Announcement.ERROR,
            Announcement.STARTED, Announcement.ERROR);

    DelegationResult recovering =
        delegation(
                ScriptedDelegate.of(
                    AttemptOutcome.failed(FailureClass.RETRYABLE, "boom"),
                    AttemptOutcome.succeeded("recovered")))
            .run(ask("Writer"));
    assertThat(recovering.announcements())
        .containsExactly(
            Announcement.STARTED, Announcement.ERROR,
            Announcement.STARTED, Announcement.COMPLETED);
    assertThat(recovering.output()).isEqualTo("recovered");
  }

  // R11 ----------------------------------------------------------------------------

  @Test
  void aFailureClassThatDoesNotRetryEndsTheDelegationOnTheFirstAttempt() {
    for (FailureClass failureClass :
        List.of(FailureClass.PASSTHROUGH, FailureClass.PROVIDER, FailureClass.TIMEOUT)) {
      InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
      DelegationResult result =
          new Delegation(ROSTER, ScriptedDelegate.alwaysFailing(failureClass, "stopped"), ledger)
              .run(ask("Writer"));

      assertThat(result.attempts()).describedAs("%s", failureClass).isEqualTo(1);
      assertThat(ledger.spent("writer")).describedAs("%s", failureClass).isZero();
      assertThat(result.output())
          .isEqualTo("Error executing task with agent 'writer'. Error: stopped");
    }
  }

  /**
   * The five classes and what each of them announces. Three announce the failure and two do not,
   * and which two is not derivable from whether they retry — a passthrough and a provider refusal
   * both end the delegation on the first attempt and only one of them is announced.
   */
  @Test
  void everyFailureClassAndWhatTheDelegationSaysAboutIt() {
    List<String> observed = new ArrayList<>();
    for (FailureClass failureClass : FailureClass.values()) {
      Delegation delegation =
          new Delegation(
              ROSTER, ScriptedDelegate.alwaysFailing(failureClass, "stopped"),
              new InMemoryBudgetLedger());
      List<Announcement> trace;
      try {
        trace = delegation.run(ask("Writer")).announcements();
      } catch (DelegateAbortedException e) {
        trace = e.announcements();
      }
      observed.add(failureClass + "=" + trace);
    }

    assertThat(observed)
        .containsExactly(
            "RETRYABLE=[STARTED, ERROR, STARTED, ERROR, STARTED, ERROR]",
            "PASSTHROUGH=[STARTED]",
            "PROVIDER=[STARTED, ERROR]",
            "TIMEOUT=[STARTED, ERROR]",
            "FATAL=[STARTED]");
  }

  @Test
  @Timeout(10)
  void aBudgetThatNeverAdvancesGivesUpRatherThanLooping() {
    BudgetLedger stuck = (role, failureClass, retryLimit) -> new RetryRule.Decision(
        RetryVerdict.RETRY, 1);
    List<Integer> asked = new ArrayList<>();

    DelegationResult result =
        new Delegation(
                ROSTER,
                (task, attempt) -> {
                  asked.add(attempt);
                  return AttemptOutcome.failed(FailureClass.RETRYABLE, "boom");
                },
                stuck)
            .run(ask("Writer"));

    assertThat(asked).containsExactly(1, 2, 3);
    assertThat(result.attempts()).isEqualTo(3);
    assertThat(result.output())
        .isEqualTo("Error executing task with agent 'writer'. Error: boom");
  }

  @Test
  void aFatalFailureLeavesThroughTheCallerRatherThanBecomingText() {
    InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
    Delegation delegation =
        new Delegation(ROSTER, ScriptedDelegate.alwaysFailing(FailureClass.FATAL, "interrupted"),
            ledger);

    assertThatThrownBy(() -> delegation.run(ask("Writer")))
        .isInstanceOf(DelegateAbortedException.class)
        .hasMessage("interrupted");
    assertThat(ledger.spent("writer")).isZero();
  }

  // R12, R13 -----------------------------------------------------------------------

  @Test
  void aSpentBudgetStaysSpentAcrossDelegationsAgainstTheSameLedger() {
    InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
    Delegation delegation =
        new Delegation(ROSTER, ScriptedDelegate.alwaysFailing(FailureClass.RETRYABLE, "boom"), ledger);

    assertThat(delegation.run(ask("Writer")).attempts()).isEqualTo(3);
    assertThat(delegation.run(ask("Writer")).attempts()).isEqualTo(1);
    assertThat(delegation.run(ask("Writer")).attempts()).isEqualTo(1);
    assertThat(ledger.spent("writer")).isEqualTo(5);
  }

  @Test
  void aSuccessDoesNotGiveTheBudgetBack() {
    InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();

    DelegationResult first =
        new Delegation(
                ROSTER,
                ScriptedDelegate.of(
                    AttemptOutcome.failed(FailureClass.RETRYABLE, "boom"),
                    AttemptOutcome.succeeded("ok")),
                ledger)
            .run(ask("Writer"));
    assertThat(first.attempts()).isEqualTo(2);
    assertThat(ledger.spent("writer")).isEqualTo(1);

    DelegationResult second =
        new Delegation(
                ROSTER, ScriptedDelegate.alwaysFailing(FailureClass.RETRYABLE, "boom"), ledger)
            .run(ask("Writer"));
    assertThat(second.attempts()).isEqualTo(2);
  }

  @Test
  void onlyTheDelegatesBudgetMoves() {
    InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();

    new Delegation(ROSTER, ScriptedDelegate.alwaysFailing(FailureClass.RETRYABLE, "boom"), ledger)
        .run(ask("Writer"));

    assertThat(ledger.spent("writer")).isEqualTo(3);
    assertThat(ledger.spent("senior research analyst")).isZero();
  }

  // R16, R17, R18 ------------------------------------------------------------------

  @Test
  void aWallClockLimitChangesTheWording() {
    Roster limited = new Roster(List.of(new AgentSpec("Writer", 2, 30)));
    DelegationResult result =
        new Delegation(
                limited,
                ScriptedDelegate.alwaysFailing(FailureClass.RETRYABLE, "bad value"),
                new InMemoryBudgetLedger())
            .run(ask("Writer"));

    assertThat(result.output())
        .isEqualTo(
            "Error executing task with agent 'writer'. Error: Task execution failed: bad value");
    assertThat(result.attempts()).isEqualTo(3);
  }

  @Test
  void aNonPositiveLimitBurnsTheBudgetWithoutAskingTheDelegate() {
    for (int limit : List.of(0, -5)) {
      Roster limited = new Roster(List.of(new AgentSpec("Writer", 2, limit)));
      InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
      List<Integer> asked = new ArrayList<>();

      DelegationResult result =
          new Delegation(
                  limited,
                  (task, attempt) -> {
                    asked.add(attempt);
                    return AttemptOutcome.succeeded("never reached");
                  },
                  ledger)
              .run(ask("Writer"));

      assertThat(asked).describedAs("limit %d", limit).isEmpty();
      assertThat(result.attempts()).describedAs("limit %d", limit).isEqualTo(3);
      assertThat(ledger.spent("writer")).describedAs("limit %d", limit).isEqualTo(3);
      assertThat(result.output())
          .isEqualTo(
              "Error executing task with agent 'writer'. Error: Max Execution time must be a"
                  + " positive integer greater than zero");
    }
  }

  @Test
  @Timeout(10)
  void anOverrunIsAttemptedOnceAndReturnsWhenTheLimitExpires() {
    Roster limited = new Roster(List.of(new AgentSpec("Writer", 2, 1)));
    InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
    List<Integer> asked = new ArrayList<>();

    long startedAt = System.nanoTime();
    DelegationResult result =
        new Delegation(
                limited,
                (task, attempt) -> {
                  asked.add(attempt);
                  try {
                    Thread.sleep(3000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return AttemptOutcome.succeeded("too late");
                },
                ledger)
            .run(ask("Writer"));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(asked).hasSize(1);
    assertThat(result.attempts()).isEqualTo(1);
    assertThat(ledger.spent("writer")).isZero();
    assertThat(result.output())
        .isEqualTo(
            "Error executing task with agent 'writer'. Error: Task 'Summarise the findings'"
                + " execution timed out after 1 seconds. Consider increasing max_execution_time"
                + " or optimizing the task.");
    // SPEC-001 D1: the port returns at the limit rather than waiting for the work.
    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void theCoworkerNameMayArriveUnderAnyOfTheThreeKeys() {
    Delegate ok = ScriptedDelegate.alwaysSucceeding("done");

    assertThat(
            delegation(ok)
                .run(new DelegationRequest(ToolKind.DELEGATE, "t", "c", null, "writer", null))
                .output())
        .isEqualTo("done");
    assertThat(
            delegation(ok)
                .run(new DelegationRequest(ToolKind.DELEGATE, "t", "c", "", "", "writer"))
                .output())
        .isEqualTo("done");
    assertThat(
            delegation(ok)
                .run(new DelegationRequest(ToolKind.DELEGATE, "t", "c", "writer", "nobody", null))
                .output())
        .isEqualTo("done");
  }
}
