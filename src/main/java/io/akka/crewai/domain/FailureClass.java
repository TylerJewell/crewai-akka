package io.akka.crewai.domain;

/**
 * Why an attempt failed, in the terms the delegation rule cares about — SPEC-001 §2, R11, R15.
 *
 * <p>Three separate questions have the same five answers, which is why they are one type: is the
 * attempt retried, does the failure cost budget, and does the delegation announce it. The four
 * classes that do not retry reach that outcome by four different routes in the source — a
 * declared passthrough type, a test on the failing class's module name, a wall-clock arm that
 * never reaches the retry handler, and simply not being an {@code Exception} — and they do
 * <em>not</em> agree on the other two questions, so collapsing them into "not retried" would
 * lose the distinction the announcement trace turns on.
 */
public enum FailureClass {
  /** Anything with no other classification. Retried, costs budget, announced. */
  RETRYABLE(true),
  /**
   * A tool that deliberately stopped, so trying again would repeat a decision, not a mishap. The
   * source refuses it before the announcement is made, so the delegation says only that it
   * started.
   */
  PASSTHROUGH(false),
  /** The model provider refused. Announced, then refused. */
  PROVIDER(true),
  /** The attempt outlived the agent's wall-clock limit. Announced by the arm that catches it. */
  TIMEOUT(true),
  /**
   * Not a failure of the work: the process is being torn down. Nothing catches it, so nothing
   * announces it and nothing turns it into text either — it leaves through the caller.
   */
  FATAL(false);

  private final boolean announced;

  FailureClass(boolean announced) {
    this.announced = announced;
  }

  /** Whether a delegation says anything when an attempt fails this way. */
  public boolean isAnnounced() {
    return announced;
  }

  /** Whether the failure leaves through the caller instead of becoming the delegation's text. */
  public boolean escapes() {
    return this == FATAL;
  }
}
