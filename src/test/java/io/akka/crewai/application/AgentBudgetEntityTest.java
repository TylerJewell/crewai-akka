package io.akka.crewai.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.crewai.domain.FailureClass;
import io.akka.crewai.domain.RetryVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R11, R12 at the journal, without a runtime.
 *
 * <p>What this reaches that the integration test does not is which events were written. A
 * failure class that must leave the budget alone has to write nothing at all — a state that
 * happens to come out unchanged after writing an event would replay differently, and only the
 * event list says which happened.
 */
class AgentBudgetEntityTest {

  private static EventSourcedTestKit<AgentBudgetEntity.State, AgentBudgetEntity.Event, AgentBudgetEntity>
      budget(int retryLimit) {
    var testKit = EventSourcedTestKit.of("crew1:writer", AgentBudgetEntity::new);
    testKit.method(AgentBudgetEntity::declareLimit).invoke(retryLimit);
    return testKit;
  }

  @Test
  void anEmptyBudgetCarriesTheSourcesOwnDefaultLimit() {
    var testKit = EventSourcedTestKit.of("crew1:writer", AgentBudgetEntity::new);

    assertThat(testKit.getState()).isEqualTo(new AgentBudgetEntity.State(2, 0));
  }

  @Test
  void chargingARetryableFailureWritesExactlyOneEvent() {
    var testKit = budget(2);

    var result = testKit.method(AgentBudgetEntity::recordFailure).invoke(FailureClass.RETRYABLE);

    assertThat(result.getReply()).isEqualTo(new AgentBudgetEntity.Decision(RetryVerdict.RETRY, 1));
    assertThat(result.getAllEvents()).containsExactly(new AgentBudgetEntity.Event.RetryCharged());
    assertThat(testKit.getState()).isEqualTo(new AgentBudgetEntity.State(2, 1));
  }

  @Test
  void aFailureClassThatCostsNothingWritesNothing() {
    for (FailureClass failureClass :
        List.of(FailureClass.PASSTHROUGH, FailureClass.PROVIDER, FailureClass.TIMEOUT,
            FailureClass.FATAL)) {
      var testKit = budget(2);

      var result = testKit.method(AgentBudgetEntity::recordFailure).invoke(failureClass);

      assertThat(result.getAllEvents()).describedAs("%s", failureClass).isEmpty();
      assertThat(result.getReply())
          .describedAs("%s", failureClass)
          .isEqualTo(new AgentBudgetEntity.Decision(RetryVerdict.GIVE_UP, 0));
    }
  }

  @Test
  void theVerdictTurnsOverAtTheLimitAndStaysThere() {
    var testKit = budget(2);

    List<RetryVerdict> verdicts =
        List.of(
            testKit.method(AgentBudgetEntity::recordFailure).invoke(FailureClass.RETRYABLE).getReply().verdict(),
            testKit.method(AgentBudgetEntity::recordFailure).invoke(FailureClass.RETRYABLE).getReply().verdict(),
            testKit.method(AgentBudgetEntity::recordFailure).invoke(FailureClass.RETRYABLE).getReply().verdict(),
            testKit.method(AgentBudgetEntity::recordFailure).invoke(FailureClass.RETRYABLE).getReply().verdict());

    assertThat(verdicts)
        .containsExactly(
            RetryVerdict.RETRY, RetryVerdict.RETRY, RetryVerdict.GIVE_UP, RetryVerdict.GIVE_UP);
    assertThat(testKit.getAllEvents()).hasSize(5);
  }

  @Test
  void declaringTheLimitAgainWritesAnEventAndLeavesTheCountAlone() {
    var testKit = budget(2);
    testKit.method(AgentBudgetEntity::recordFailure).invoke(FailureClass.RETRYABLE);

    var result = testKit.method(AgentBudgetEntity::declareLimit).invoke(5);

    assertThat(result.getAllEvents())
        .containsExactly(new AgentBudgetEntity.Event.LimitDeclared(5));
    assertThat(testKit.getState()).isEqualTo(new AgentBudgetEntity.State(5, 1));
  }
}
