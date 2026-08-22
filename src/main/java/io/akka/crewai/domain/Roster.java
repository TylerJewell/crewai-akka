package io.akka.crewai.domain;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The agents a delegation may name — SPEC-001 R4, R5.
 *
 * <p>The lookup walks the roster in order and stops at the first match, which is R4. A roster is
 * a handful of agents, so an index would cost more to build than the walk costs to run.
 *
 * @param agents in the order the crew declared them
 */
public record Roster(List<AgentSpec> agents) {

  public Roster {
    agents = List.copyOf(agents);
  }

  /** The first agent whose role matches an already-sanitised name. */
  public Optional<AgentSpec> find(String sanitizedName) {
    if (sanitizedName == null || sanitizedName.isEmpty()) {
      return Optional.empty();
    }
    for (AgentSpec agent : agents) {
      if (agent.sanitizedRole().equals(sanitizedName)) {
        return Optional.of(agent);
      }
    }
    return Optional.empty();
  }

  /**
   * What a delegation naming nobody comes back with. The roles are listed in their sanitised
   * spelling, which is what the caller must send back to hit one.
   */
  public String notFoundText() {
    String options =
        agents.stream().map(a -> "- " + a.sanitizedRole()).collect(Collectors.joining("\n"));
    return "\nError executing tool. coworker mentioned not found, it must be one of the following"
        + " options:\n"
        + options
        + "\n";
  }
}
