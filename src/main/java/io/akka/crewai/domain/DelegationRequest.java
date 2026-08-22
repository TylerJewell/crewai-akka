package io.akka.crewai.domain;

/**
 * One delegation as it arrives — SPEC-001 §2.
 *
 * <p>Three name fields rather than one because the source takes the name from whichever of three
 * keys a model happened to use, and which one it used is part of the request rather than
 * something to normalise away before the rule sees it (R1).
 */
public record DelegationRequest(
    ToolKind toolKind,
    String work,
    String context,
    String coworker,
    String coWorker,
    String coworkerKwarg) {

  /** The name this request means, after R1 and R2 but before R3. */
  public String candidateName() {
    return CoworkerName.chooseCandidate(coworker, coWorker, coworkerKwarg);
  }
}
