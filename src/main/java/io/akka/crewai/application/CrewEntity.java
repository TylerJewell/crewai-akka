package io.akka.crewai.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.crewai.domain.Roster;
import java.util.List;

/**
 * The roster a delegation is resolved against — SPEC-001 R4, R5.
 *
 * <p>Key-value rather than event-sourced: a roster is a statement of who is on the crew now, and
 * nothing in the capability depends on how it came to be that. What does have a history is each
 * agent's retry budget, which lives in {@link AgentBudgetEntity} and is deliberately not reset by
 * a roster being declared again.
 */
@Component(id = "crew")
public class CrewEntity extends KeyValueEntity<Roster> {

  public CrewEntity(KeyValueEntityContext context) {}

  @Override
  public Roster emptyState() {
    return new Roster(List.of());
  }

  public Effect<Roster> declare(Roster roster) {
    return effects().updateState(roster).thenReply(roster);
  }

  public ReadOnlyEffect<Roster> get() {
    return effects().reply(currentState());
  }
}
