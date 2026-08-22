package io.akka.crewai.domain;

import java.util.List;

/**
 * A delegate whose outcomes are decided in advance — SPEC-001 D5.
 *
 * <p>The last scripted outcome repeats once the script runs out, so "always fails" is a
 * one-element script rather than a guess at how many attempts the rule will make.
 */
public final class ScriptedDelegate implements Delegate {

  private final List<AttemptOutcome> script;

  private ScriptedDelegate(List<AttemptOutcome> script) {
    if (script.isEmpty()) {
      throw new IllegalArgumentException("a scripted delegate needs at least one outcome");
    }
    this.script = List.copyOf(script);
  }

  public static ScriptedDelegate of(AttemptOutcome... outcomes) {
    return new ScriptedDelegate(List.of(outcomes));
  }

  public static ScriptedDelegate of(List<AttemptOutcome> outcomes) {
    return new ScriptedDelegate(outcomes);
  }

  public static ScriptedDelegate alwaysSucceeding(String output) {
    return of(AttemptOutcome.succeeded(output));
  }

  public static ScriptedDelegate alwaysFailing(FailureClass failureClass, String message) {
    return of(AttemptOutcome.failed(failureClass, message));
  }

  @Override
  public AttemptOutcome attempt(DelegatedTask task, int attemptNumber) {
    return script.get(Math.min(attemptNumber, script.size()) - 1);
  }
}
