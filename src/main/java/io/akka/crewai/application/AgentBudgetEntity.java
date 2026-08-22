package io.akka.crewai.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.crewai.domain.FailureClass;
import io.akka.crewai.domain.RetryRule;
import io.akka.crewai.domain.RetryVerdict;

/**
 * One agent's retry budget — SPEC-001 R12, D2, D3.
 *
 * <p>crewAI keeps this on a live Python object, so it is unshared and gone when the process is.
 * Here it is a journal, which is what makes R12 — a spent budget stays spent — hold across a
 * restart and across callers as well as across delegations.
 *
 * <p>The entity id is {@code <crewId>:<sanitised role>}. It cannot contain {@code |}: the runtime
 * reserves it and reports an id that does by timing the command out after ninety seconds rather
 * than refusing it, so {@link #idFor} refuses instead.
 */
@Component(id = "agent-budget")
public class AgentBudgetEntity
    extends EventSourcedEntity<AgentBudgetEntity.State, AgentBudgetEntity.Event> {

  /**
   * @param retryLimit retries allowed, as the roster declared them
   * @param spent how many retryable failures this agent has already been charged for, over its
   *     whole life rather than this delegation's
   */
  public record State(int retryLimit, int spent) {
    State withLimit(int newLimit) {
      return new State(newLimit, spent);
    }

    State charged() {
      return new State(retryLimit, spent + 1);
    }
  }

  public sealed interface Event {
    @TypeName("limit-declared")
    record LimitDeclared(int retryLimit) implements Event {}

    @TypeName("retry-charged")
    record RetryCharged() implements Event {}
  }

  public record Decision(RetryVerdict verdict, int spent) {}

  public AgentBudgetEntity(EventSourcedEntityContext context) {}

  /** The id an agent's budget lives under, or a refusal a caller can act on. */
  public static String idFor(String crewId, String sanitizedRole) {
    if (crewId.contains("|") || sanitizedRole.contains("|")) {
      throw new IllegalArgumentException(
          "'|' is reserved in an entity id and an id containing it does not fail, it hangs");
    }
    return crewId + ":" + sanitizedRole;
  }

  @Override
  public State emptyState() {
    return new State(2, 0);
  }

  @Override
  public State applyEvent(Event event) {
    return switch (event) {
      case Event.LimitDeclared e -> currentState().withLimit(e.retryLimit());
      case Event.RetryCharged ignored -> currentState().charged();
    };
  }

  /**
   * Declares the limit without touching what has already been spent — re-registering a crew does
   * not hand its agents their retries back, which is R12 seen from the other side.
   */
  public Effect<State> declareLimit(int retryLimit) {
    return effects().persist(new Event.LimitDeclared(retryLimit)).thenReply(state -> state);
  }

  /**
   * Charges one failure and says what it leaves.
   *
   * <p>A refusal is returned as data rather than thrown: an entity that persists and then throws
   * is stopped by the runtime, its message never reaches the caller, and the next command to it
   * times out.
   */
  public Effect<Decision> recordFailure(FailureClass failureClass) {
    RetryRule.Decision decision =
        RetryRule.decide(failureClass, currentState().spent(), currentState().retryLimit());
    if (decision.spentAfter() == currentState().spent()) {
      return effects().reply(new Decision(decision.verdict(), decision.spentAfter()));
    }
    return effects()
        .persist(new Event.RetryCharged())
        .thenReply(state -> new Decision(decision.verdict(), state.spent()));
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}
