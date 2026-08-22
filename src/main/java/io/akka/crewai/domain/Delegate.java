package io.akka.crewai.domain;

/**
 * Whatever actually does a delegated piece of work.
 *
 * <p>SPEC-001 §1 puts this outside the capability: in crewAI it is a language model driving a
 * tool loop, and what it does with the task is not what delegation is about. What delegation is
 * about is what happens to the attempt afterwards, which is why this interface reports an
 * outcome rather than throwing.
 */
@FunctionalInterface
public interface Delegate {

  /**
   * @param attemptNumber 1 for the first attempt, so a delegate may behave differently on a retry
   */
  AttemptOutcome attempt(DelegatedTask task, int attemptNumber);
}
