package io.akka.crewai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** SPEC-001 R1, R2, R3. */
class CoworkerNameTest {

  // R1 -----------------------------------------------------------------------------

  @Test
  void theFirstNonEmptyCandidateWins() {
    assertThat(CoworkerName.chooseCandidate("Researcher", "Nobody", null)).isEqualTo("Researcher");
    assertThat(CoworkerName.chooseCandidate(null, "Researcher", null)).isEqualTo("Researcher");
    assertThat(CoworkerName.chooseCandidate(null, null, "Researcher")).isEqualTo("Researcher");
  }

  @Test
  void anEmptyCandidateFallsThroughRatherThanStoppingTheSearch() {
    assertThat(CoworkerName.chooseCandidate("", "Researcher", null)).isEqualTo("Researcher");
    assertThat(CoworkerName.chooseCandidate("", "", "Researcher")).isEqualTo("Researcher");
  }

  @Test
  void noCandidateAtAllIsTheEmptyName() {
    assertThat(CoworkerName.chooseCandidate(null, null, null)).isEmpty();
    assertThat(CoworkerName.chooseCandidate("", "", "")).isEmpty();
  }

  // R2 -----------------------------------------------------------------------------

  @Test
  void aBracketedListIsCutToItsFirstElement() {
    assertThat(CoworkerName.chooseCandidate("[Senior Research Analyst, Writer]", null, null))
        .isEqualTo("Senior Research Analyst");
    assertThat(CoworkerName.chooseCandidate("[Writer]", null, null)).isEqualTo("Writer");
    assertThat(CoworkerName.chooseCandidate("[ Writer , Editor ]", null, null))
        .isEqualTo(" Writer ");
  }

  @Test
  void anEmptyBracketedListIsTheEmptyName() {
    assertThat(CoworkerName.chooseCandidate("[]", null, null)).isEmpty();
  }

  @Test
  void bracketsAreOnlyCutWhenTheyEncloseTheWholeCandidate() {
    assertThat(CoworkerName.chooseCandidate("[Writer", null, null)).isEqualTo("[Writer");
    assertThat(CoworkerName.chooseCandidate("Writer]", null, null)).isEqualTo("Writer]");
    assertThat(CoworkerName.chooseCandidate("a[Writer]b", null, null)).isEqualTo("a[Writer]b");
  }

  // R3 -----------------------------------------------------------------------------

  @Test
  void namesAreComparedAfterWhitespaceQuotesAndCase() {
    assertThat(CoworkerName.sanitize("Senior Research Analyst")).isEqualTo("senior research analyst");
    assertThat(CoworkerName.sanitize("  Senior   Research\n\tAnalyst  "))
        .isEqualTo("senior research analyst");
    assertThat(CoworkerName.sanitize("\"Writer\"")).isEqualTo("writer");
    assertThat(CoworkerName.sanitize("Writer\"")).isEqualTo("writer");
  }

  @Test
  void aSingleQuoteIsNotRemoved() {
    assertThat(CoworkerName.sanitize("'Writer'")).isEqualTo("'writer'");
  }

  @Test
  void theEmptyAndNullNamesBothSanitiseToNothing() {
    assertThat(CoworkerName.sanitize(null)).isEmpty();
    assertThat(CoworkerName.sanitize("")).isEmpty();
    assertThat(CoworkerName.sanitize("   ")).isEmpty();
  }

  /**
   * The twenty spellings probe_01 ran against the source, with the source's own verdict on each.
   * Reading three of them and generalising is how the wrong rule gets written down confidently;
   * the class is enumerated because the claim is about the class.
   */
  @Test
  @Timeout(10)
  void everySpellingTheSourceWasRunOn() {
    Map<String, Boolean> sourceSaid = new LinkedHashMap<>();
    sourceSaid.put("Senior Research Analyst", true);
    sourceSaid.put("senior research analyst", true);
    sourceSaid.put("SENIOR RESEARCH ANALYST", true);
    sourceSaid.put("  Senior Research Analyst", true);
    sourceSaid.put("Senior Research Analyst   ", true);
    sourceSaid.put("Senior  Research   Analyst", true);
    sourceSaid.put("Senior\nResearch\nAnalyst", true);
    sourceSaid.put("Senior\tResearch\tAnalyst", true);
    sourceSaid.put("\"Senior Research Analyst\"", true);
    sourceSaid.put("'Senior Research Analyst'", false);
    sourceSaid.put("Senior Research Analyst\"", true);
    sourceSaid.put("[Senior Research Analyst, Writer]", true);
    sourceSaid.put("[Senior Research Analyst]", true);
    sourceSaid.put("[ Senior Research Analyst , Writer ]", true);
    sourceSaid.put("[]", false);
    sourceSaid.put("", false);
    sourceSaid.put(null, false);
    sourceSaid.put("Chef", false);
    sourceSaid.put("Senior Research", false);
    sourceSaid.put("Senior Research Analyst Jr", false);

    Roster roster =
        new Roster(List.of(new AgentSpec("Senior Research Analyst", 2), new AgentSpec("Writer", 2)));

    sourceSaid.forEach(
        (spelling, reaches) -> {
          String name = CoworkerName.sanitize(CoworkerName.chooseCandidate(spelling, null, null));
          assertThat(roster.find(name).isPresent())
              .describedAs("spelling [%s]", spelling)
              .isEqualTo(reaches);
        });
  }
}
