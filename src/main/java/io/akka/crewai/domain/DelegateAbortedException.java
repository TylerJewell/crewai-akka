package io.akka.crewai.domain;

import java.util.List;

/**
 * The one way out of a delegation that is not a string — SPEC-001 R9, R11.
 *
 * <p>A {@link FailureClass#FATAL} attempt is not a failure of the work: nothing in the source
 * catches it, so it passes the tool and reaches whoever asked for the delegation. The trace so
 * far travels with it, because a caller that never sees a result would otherwise have no way to
 * learn how far the delegation got.
 */
public final class DelegateAbortedException extends RuntimeException {

  private final transient List<Announcement> announcements;
  private final int attempts;

  public DelegateAbortedException(String message, List<Announcement> announcements, int attempts) {
    super(message);
    this.announcements = List.copyOf(announcements);
    this.attempts = attempts;
  }

  public List<Announcement> announcements() {
    return announcements;
  }

  public int attempts() {
    return attempts;
  }
}
