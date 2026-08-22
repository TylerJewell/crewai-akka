package io.akka.crewai.domain;

/** What one attempt at the delegate's work came back with. */
public sealed interface AttemptOutcome {

  record Succeeded(String output) implements AttemptOutcome {}

  record Failed(FailureClass failureClass, String message) implements AttemptOutcome {}

  static AttemptOutcome succeeded(String output) {
    return new Succeeded(output);
  }

  static AttemptOutcome failed(FailureClass failureClass, String message) {
    return new Failed(failureClass, message);
  }
}
