package io.akka.crewai.domain;

/**
 * Turning what a caller said into the name a roster is searched by — SPEC-001 R1, R2, R3.
 *
 * <p>The two halves are separate on purpose and run in this order: a bracketed list is cut down
 * before anything else touches it, and only the survivor is sanitised. Doing it the other way
 * round would make {@code [ Writer , Editor ]} unmatchable, because the spaces the sanitiser
 * removes are inside the brackets.
 */
public final class CoworkerName {

  private CoworkerName() {}

  /**
   * The first candidate with any text in it, cut to the first element if it is a bracketed list.
   * An empty candidate falls through to the next rather than ending the search, so a caller that
   * supplies {@code coworker=""} alongside a usable {@code co_worker} is answered.
   */
  public static String chooseCandidate(String coworker, String coWorker, String coworkerKwarg) {
    String chosen = firstWithText(coworker, coWorker, coworkerKwarg);
    if (chosen == null) {
      return "";
    }
    return unbracket(chosen);
  }

  private static String firstWithText(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isEmpty()) {
        return candidate;
      }
    }
    return null;
  }

  /** {@code [a, b]} becomes {@code a}; anything not enclosed in brackets is returned unchanged. */
  public static String unbracket(String candidate) {
    if (candidate.length() < 2 || !candidate.startsWith("[") || !candidate.endsWith("]")) {
      return candidate;
    }
    String inside = candidate.substring(1, candidate.length() - 1);
    int comma = inside.indexOf(',');
    return comma < 0 ? inside : inside.substring(0, comma);
  }

  /**
   * The comparison form: every run of whitespace collapsed to one space, the ends trimmed, every
   * double quote dropped, then case-folded. A single quote survives — the rule was written for a
   * truncated JSON string, which is only ever cut on a double quote.
   */
  public static String sanitize(String name) {
    if (name == null || name.isEmpty()) {
      return "";
    }
    String normalized = String.join(" ", name.trim().split("\\s+"));
    return normalized.replace("\"", "").toLowerCase(java.util.Locale.ROOT);
  }
}
