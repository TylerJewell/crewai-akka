package io.akka.crewai.domain;

/** What to do after an attempt failed — SPEC-001 R10, R11. */
public enum RetryVerdict {
  RETRY,
  GIVE_UP
}
