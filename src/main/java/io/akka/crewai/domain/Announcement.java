package io.akka.crewai.domain;

/** What a delegation says about itself, one per event — SPEC-001 R6, R8, R15. */
public enum Announcement {
  STARTED,
  ERROR,
  COMPLETED
}
