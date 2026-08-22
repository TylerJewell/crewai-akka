package io.akka.crewai.domain;

/**
 * The whole of what a failed attempt costs and what happens next — SPEC-001 R10, R11.
 *
 * <p>Two things at once, and they are separable: the budget moves only for {@link
 * FailureClass#RETRYABLE}, and the verdict depends on where the budget landed. The count is
 * passed in and the new count returned rather than held, because the count belongs to the agent
 * and outlives any one delegation.
 */
public final class RetryRule {

  private RetryRule() {}

  /**
   * @param spentBefore the delegate's count when this attempt failed
   * @param retryLimit retries allowed, so the comparison is {@code <=} against the new count
   */
  public static Decision decide(FailureClass failureClass, int spentBefore, int retryLimit) {
    if (failureClass != FailureClass.RETRYABLE) {
      return new Decision(RetryVerdict.GIVE_UP, spentBefore);
    }
    int spentAfter = spentBefore + 1;
    return new Decision(
        spentAfter <= retryLimit ? RetryVerdict.RETRY : RetryVerdict.GIVE_UP, spentAfter);
  }

  public record Decision(RetryVerdict verdict, int spentAfter) {}
}
