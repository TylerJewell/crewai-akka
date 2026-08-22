package io.akka.crewai.domain;

/**
 * Where a delegate's spent retries are kept — SPEC-001 R12, D2.
 *
 * <p>An interface because the count outliving one delegation is the rule, and where it outlives
 * it is the port's choice: in memory for a single process, in an event-sourced entity for a
 * durable one. The rule reads the same either way.
 */
public interface BudgetLedger {

  /**
   * Applies {@link RetryRule} to one failure and records the result.
   *
   * @param sanitizedRole the delegate, keyed the way it is matched
   */
  RetryRule.Decision recordFailure(String sanitizedRole, FailureClass failureClass, int retryLimit);
}
