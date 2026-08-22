package io.akka.crewai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R4, R5. */
class RosterTest {

  private static final AgentSpec ANALYST = new AgentSpec("Senior Research Analyst", 2);
  private static final AgentSpec WRITER = new AgentSpec("Writer", 2);

  @Test
  void theFirstMatchingRoleWins() {
    AgentSpec first = new AgentSpec("Analyst", 1);
    AgentSpec second = new AgentSpec("analyst", 9);
    Roster roster = new Roster(List.of(first, second));

    assertThat(roster.find("analyst")).contains(first);
  }

  @Test
  void duplicateRolesAreNotAnError() {
    Roster roster = new Roster(List.of(new AgentSpec("Analyst", 1), new AgentSpec("Analyst", 1)));

    assertThat(roster.find("analyst")).isPresent();
  }

  @Test
  void aRoleIsMatchedThroughTheSameSanitisingAsTheRequest() {
    Roster roster = new Roster(List.of(new AgentSpec("  Senior   RESEARCH Analyst ", 2)));

    assertThat(roster.find(CoworkerName.sanitize("senior research analyst"))).isPresent();
  }

  @Test
  void theNotFoundTextListsSanitisedRolesOnePerLine() {
    Roster roster = new Roster(List.of(ANALYST, WRITER));

    assertThat(roster.notFoundText())
        .isEqualTo(
            "\nError executing tool. coworker mentioned not found, it must be one of the"
                + " following options:\n- senior research analyst\n- writer\n");
  }

  @Test
  void anEmptyRosterStillProducesTheText() {
    assertThat(new Roster(List.of()).notFoundText())
        .isEqualTo(
            "\nError executing tool. coworker mentioned not found, it must be one of the"
                + " following options:\n\n");
  }

  @Test
  void anUnknownNameFindsNobody() {
    Roster roster = new Roster(List.of(ANALYST, WRITER));

    assertThat(roster.find("chef")).isEmpty();
    assertThat(roster.find("")).isEmpty();
  }
}
