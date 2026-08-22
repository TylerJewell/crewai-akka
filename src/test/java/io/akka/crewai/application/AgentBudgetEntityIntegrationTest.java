package io.akka.crewai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.crewai.domain.FailureClass;
import io.akka.crewai.domain.RetryVerdict;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R12, R13, D2, D3 — the half of the retry rule that only shows up once the budget is
 * somewhere a second call can see it. Starts a runtime.
 */
class AgentBudgetEntityIntegrationTest extends TestKitSupport {

  private String budget(String role) {
    return AgentBudgetEntity.idFor("crew-" + UUID.randomUUID(), role);
  }

  private AgentBudgetEntity.Decision charge(String id, FailureClass failureClass) {
    return componentClient
        .forEventSourcedEntity(id)
        .method(AgentBudgetEntity::recordFailure)
        .invoke(failureClass);
  }

  private AgentBudgetEntity.State state(String id) {
    return componentClient.forEventSourcedEntity(id).method(AgentBudgetEntity::get).invoke();
  }

  @Test
  void aSpentBudgetStaysSpentAcrossDelegations() {
    String id = budget("writer");
    componentClient.forEventSourcedEntity(id).method(AgentBudgetEntity::declareLimit).invoke(2);

    List<String> verdicts = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      verdicts.add(charge(id, FailureClass.RETRYABLE).verdict().name());
    }

    assertThat(verdicts).containsExactly("RETRY", "RETRY", "GIVE_UP", "GIVE_UP", "GIVE_UP");
    assertThat(state(id).spent()).isEqualTo(5);
  }

  @Test
  void aClassThatDoesNotRetryLeavesTheBudgetWhereItWas() {
    String id = budget("writer");
    componentClient.forEventSourcedEntity(id).method(AgentBudgetEntity::declareLimit).invoke(2);

    for (FailureClass failureClass :
        List.of(FailureClass.PASSTHROUGH, FailureClass.PROVIDER, FailureClass.TIMEOUT, FailureClass.FATAL)) {
      AgentBudgetEntity.Decision decision = charge(id, failureClass);
      assertThat(decision.verdict()).describedAs("%s", failureClass).isEqualTo(RetryVerdict.GIVE_UP);
      assertThat(decision.spent()).describedAs("%s", failureClass).isZero();
    }
    assertThat(state(id).spent()).isZero();
  }

  @Test
  void onlyTheDelegatesBudgetMoves() {
    String crewId = "crew-" + UUID.randomUUID();
    String writer = AgentBudgetEntity.idFor(crewId, "writer");
    String analyst = AgentBudgetEntity.idFor(crewId, "senior research analyst");
    componentClient.forEventSourcedEntity(writer).method(AgentBudgetEntity::declareLimit).invoke(2);
    componentClient.forEventSourcedEntity(analyst).method(AgentBudgetEntity::declareLimit).invoke(2);

    charge(writer, FailureClass.RETRYABLE);
    charge(writer, FailureClass.RETRYABLE);

    assertThat(state(writer).spent()).isEqualTo(2);
    assertThat(state(analyst).spent()).isZero();
  }

  @Test
  void declaringTheLimitAgainDoesNotHandTheRetriesBack() {
    String id = budget("writer");
    componentClient.forEventSourcedEntity(id).method(AgentBudgetEntity::declareLimit).invoke(2);
    charge(id, FailureClass.RETRYABLE);
    charge(id, FailureClass.RETRYABLE);

    componentClient.forEventSourcedEntity(id).method(AgentBudgetEntity::declareLimit).invoke(2);

    assertThat(state(id).spent()).isEqualTo(2);
    assertThat(charge(id, FailureClass.RETRYABLE).verdict()).isEqualTo(RetryVerdict.GIVE_UP);
  }

  @Test
  void aRoleCarryingTheRuntimesReservedCharacterIsRefusedRatherThanLeftToHang() {
    assertThatThrownBy(() -> AgentBudgetEntity.idFor("crew1", "a|b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AgentBudgetEntity.idFor("crew|1", "writer"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * D3: crewAI's counter is an unguarded read-modify-write and its own async path shares it, so
   * two delegations at once have no defined total. Here the entity handles one command at a time,
   * so a limit-2 agent is charged exactly three times no matter how the charges arrive.
   */
  @Test
  void twoDelegationsAtOnceSpendOneBudgetBetweenThem() throws Exception {
    String id = budget("writer");
    componentClient.forEventSourcedEntity(id).method(AgentBudgetEntity::declareLimit).invoke(2);

    int chargesPerCaller = 4;
    List<Thread> callers = new ArrayList<>();
    List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
    for (int caller = 0; caller < 2; caller++) {
      Thread thread =
          new Thread(
              () -> {
                for (int i = 0; i < chargesPerCaller; i++) {
                  verdicts.add(charge(id, FailureClass.RETRYABLE).verdict().name());
                }
              });
      callers.add(thread);
      thread.start();
    }
    for (Thread thread : callers) {
      thread.join();
    }

    assertThat(verdicts).hasSize(2 * chargesPerCaller);
    assertThat(verdicts.stream().filter("RETRY"::equals).count()).isEqualTo(2);
    assertThat(state(id).spent()).isEqualTo(2 * chargesPerCaller);
  }
}
