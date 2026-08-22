package io.akka.crewai.application;

import akka.javasdk.client.ComponentClient;
import io.akka.crewai.domain.BudgetLedger;
import io.akka.crewai.domain.FailureClass;
import io.akka.crewai.domain.RetryRule;

/**
 * The ledger backed by {@link AgentBudgetEntity} — SPEC-001 D2, D3.
 *
 * <p>The retry limit is declared on the entity when the crew is, so the limit the decision uses is
 * the one on record rather than one the caller supplied with this delegation. The limit argument
 * the interface carries is therefore unused here: it exists for the in-memory ledger, which has
 * nowhere else to keep it.
 */
public final class DurableBudgetLedger implements BudgetLedger {

  private final ComponentClient componentClient;
  private final String crewId;

  public DurableBudgetLedger(ComponentClient componentClient, String crewId) {
    this.componentClient = componentClient;
    this.crewId = crewId;
  }

  @Override
  public RetryRule.Decision recordFailure(
      String sanitizedRole, FailureClass failureClass, int retryLimit) {
    AgentBudgetEntity.Decision decision =
        componentClient
            .forEventSourcedEntity(AgentBudgetEntity.idFor(crewId, sanitizedRole))
            .method(AgentBudgetEntity::recordFailure)
            .invoke(failureClass);
    return new RetryRule.Decision(decision.verdict(), decision.spent());
  }
}
