package io.akka.crewai.domain;

/**
 * What the delegate is handed — SPEC-001 R7. The expected output is fixed text, the same for a
 * delegated task and an asked question.
 */
public record DelegatedTask(String description, String expectedOutput, String role, String context) {

  public static final String EXPECTED_OUTPUT =
      "Your best answer to your coworker asking you this, accounting for the context shared.";

  public static DelegatedTask of(String work, AgentSpec agent, String context) {
    return new DelegatedTask(work, EXPECTED_OUTPUT, agent.role(), context);
  }
}
