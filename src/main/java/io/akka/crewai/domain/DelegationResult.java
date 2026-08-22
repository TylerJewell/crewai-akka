package io.akka.crewai.domain;

import java.util.List;

/**
 * What a delegation leaves behind — SPEC-001 §2, R15, D7.
 *
 * @param output the one string every path ends at, whether the delegation succeeded, failed or
 *     found nobody
 * @param announcements in emit order. The source publishes these to a bus that hands subscribers
 *     to a thread pool, so what a subscriber reads is not necessarily this order; carrying them
 *     on the result is what makes the order a caller reads the order that happened
 * @param attempts how many times the delegate was asked, 0 when nobody was found
 */
public record DelegationResult(String output, List<Announcement> announcements, int attempts) {

  public DelegationResult {
    announcements = List.copyOf(announcements);
  }
}
