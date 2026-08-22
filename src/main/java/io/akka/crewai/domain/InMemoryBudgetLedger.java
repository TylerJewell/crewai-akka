package io.akka.crewai.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * A ledger for one process, which is what crewAI has: the count lives beside the agent and dies
 * with it. Used by the unit checks and by the benchmark, where comparing against the source means
 * matching the source's own durability rather than exceeding it.
 */
public final class InMemoryBudgetLedger implements BudgetLedger {

  private final Map<String, Integer> spent = new HashMap<>();

  @Override
  public RetryRule.Decision recordFailure(
      String sanitizedRole, FailureClass failureClass, int retryLimit) {
    RetryRule.Decision decision =
        RetryRule.decide(failureClass, spent.getOrDefault(sanitizedRole, 0), retryLimit);
    spent.put(sanitizedRole, decision.spentAfter());
    return decision;
  }

  public int spent(String sanitizedRole) {
    return spent.getOrDefault(sanitizedRole, 0);
  }
}
