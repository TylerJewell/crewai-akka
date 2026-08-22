package io.akka.crewai.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.crewai.domain.AgentSpec;
import io.akka.crewai.domain.Roster;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The roster survives being written down and read back, including the field that may be absent.
 * A wall-clock limit that does not round-trip would turn every limited agent into an unlimited
 * one, and the delegation would still return a plausible string.
 */
class CrewEntityTest {

  @Test
  void aRosterRoundTripsIncludingTheLimitThatMayBeAbsent() {
    var testKit = KeyValueEntityTestKit.of("crew1", CrewEntity::new);
    Roster roster =
        new Roster(
            List.of(
                new AgentSpec("Senior Research Analyst", 2),
                new AgentSpec("Writer", 0, 30),
                new AgentSpec("Editor", 5, 0)));

    testKit.method(CrewEntity::declare).invoke(roster);
    Roster read = testKit.method(CrewEntity::get).invoke().getReply();

    assertThat(read).isEqualTo(roster);
    assertThat(read.agents().get(0).maxExecutionTimeSeconds()).isEmpty();
    assertThat(read.agents().get(1).maxExecutionTimeSeconds()).contains(30);
    assertThat(read.agents().get(2).maxExecutionTimeSeconds()).contains(0);
    assertThat(testKit.getState()).isEqualTo(roster);
  }

  @Test
  void anUndeclaredCrewHasNobodyOnIt() {
    var testKit = KeyValueEntityTestKit.of("crew1", CrewEntity::new);

    assertThat(testKit.method(CrewEntity::get).invoke().getReply().agents()).isEmpty();
  }

  @Test
  void declaringAgainReplacesTheRosterRatherThanAddingToIt() {
    var testKit = KeyValueEntityTestKit.of("crew1", CrewEntity::new);
    testKit.method(CrewEntity::declare).invoke(new Roster(List.of(new AgentSpec("Writer", 2))));

    testKit
        .method(CrewEntity::declare)
        .invoke(new Roster(List.of(new AgentSpec("Editor", 2, Optional.of(10)))));

    assertThat(testKit.getState().agents()).extracting(AgentSpec::role).containsExactly("Editor");
  }
}
