package io.akka.crewai.domain;

/**
 * Which of the two coworker tools a request came through. The only thing it changes is what the
 * work is called on the way in; everything after that is identical (SPEC-001 §1, R7).
 */
public enum ToolKind {
  DELEGATE,
  ASK
}
