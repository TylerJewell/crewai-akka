package io.akka.crewai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R10, R11. */
class RetryRuleTest {

  @Test
  void attemptsAreOneMoreThanTheLimit() {
    for (int limit : List.of(0, 1, 2, 3)) {
      int spent = 0;
      int attempts = 0;
      while (true) {
        attempts++;
        RetryRule.Decision decision = RetryRule.decide(FailureClass.RETRYABLE, spent, limit);
        spent = decision.spentAfter();
        if (decision.verdict() == RetryVerdict.GIVE_UP) {
          break;
        }
      }
      assertThat(attempts).describedAs("limit %d", limit).isEqualTo(limit + 1);
      assertThat(spent).describedAs("limit %d", limit).isEqualTo(limit + 1);
    }
  }

  @Test
  void theLimitCountsRetriesNotAttempts() {
    assertThat(RetryRule.decide(FailureClass.RETRYABLE, 0, 2))
        .isEqualTo(new RetryRule.Decision(RetryVerdict.RETRY, 1));
    assertThat(RetryRule.decide(FailureClass.RETRYABLE, 1, 2))
        .isEqualTo(new RetryRule.Decision(RetryVerdict.RETRY, 2));
    assertThat(RetryRule.decide(FailureClass.RETRYABLE, 2, 2))
        .isEqualTo(new RetryRule.Decision(RetryVerdict.GIVE_UP, 3));
  }

  /**
   * All five classes, with the count each leaves behind. Three of the four that do not retry
   * arrive by different routes in the source and are one rule here; enumerating them is what
   * keeps the fourth from being assumed to behave like the other three.
   */
  @Test
  void everyFailureClassAndWhatItCosts() {
    List<String> observed = new ArrayList<>();
    for (FailureClass failureClass : FailureClass.values()) {
      RetryRule.Decision decision = RetryRule.decide(failureClass, 0, 2);
      observed.add(failureClass + "=" + decision.verdict() + ",spent=" + decision.spentAfter());
    }

    assertThat(observed)
        .containsExactly(
            "RETRYABLE=RETRY,spent=1",
            "PASSTHROUGH=GIVE_UP,spent=0",
            "PROVIDER=GIVE_UP,spent=0",
            "TIMEOUT=GIVE_UP,spent=0",
            "FATAL=GIVE_UP,spent=0");
  }

  @Test
  void aBudgetAlreadySpentGivesUpOnTheFirstFailure() {
    assertThat(RetryRule.decide(FailureClass.RETRYABLE, 3, 2))
        .isEqualTo(new RetryRule.Decision(RetryVerdict.GIVE_UP, 4));
  }

  @Test
  void aNonRetryingClassLeavesAnAlreadySpentBudgetAlone() {
    assertThat(RetryRule.decide(FailureClass.TIMEOUT, 3, 2))
        .isEqualTo(new RetryRule.Decision(RetryVerdict.GIVE_UP, 3));
  }
}
