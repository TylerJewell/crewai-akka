package io.akka.crewai.domain;

import java.util.Optional;

/**
 * One entry on a roster: what it is called, how many retries it is allowed, and the wall-clock
 * limit it runs under if it has one.
 *
 * @param role the declared spelling, kept as declared — {@link CoworkerName#sanitize} is applied
 *     where the role is compared or reported, never stored in its place
 * @param retryLimit retries, not attempts: a limit of 2 means three tries
 * @param maxExecutionTimeSeconds empty when the agent runs unbounded. A present but non-positive
 *     value is a configuration the source accepts and fails on per attempt (SPEC-001 R17), so it
 *     is representable rather than rejected here
 */
public record AgentSpec(String role, int retryLimit, Optional<Integer> maxExecutionTimeSeconds) {

  public AgentSpec {
    maxExecutionTimeSeconds =
        maxExecutionTimeSeconds == null ? Optional.empty() : maxExecutionTimeSeconds;
  }

  public AgentSpec(String role, int retryLimit) {
    this(role, retryLimit, Optional.empty());
  }

  public AgentSpec(String role, int retryLimit, Integer maxExecutionTimeSeconds) {
    this(role, retryLimit, Optional.ofNullable(maxExecutionTimeSeconds));
  }

  public String sanitizedRole() {
    return CoworkerName.sanitize(role);
  }
}
