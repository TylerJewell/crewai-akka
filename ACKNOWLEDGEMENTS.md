# Acknowledgements

This project is a port of **[crewAIInc/crewAI](https://github.com/crewAIInc/crewAI)**,
version 1.15.17.

## Licence and copyright

- crewAIInc/crewAI is licensed under the **MIT License**. Copyright (c) 2025 crewAI, Inc.
  (`LICENSE-crewai:1`, copied verbatim from the source repository's `LICENSE`).
- **Text was copied verbatim, and it had to be.** The point of this port is that a caller
  cannot tell the two apart, so the strings a delegation hands back are the source's own,
  character for character. They come from
  `crewai/translations/en.json` and `crewai/agent/core.py`, and they are:
  - `\nError executing tool. coworker mentioned not found, it must be one of the following
    options:\n{...}\n` (`en.json:48`) — in `Roster.notFoundText()`.
  - `Error executing task with agent '{...}'. Error: {...}` (`en.json:54`) — in
    `Delegation.failureText`.
  - `Your best answer to your coworker asking you this, accounting for the context
    shared.` (`en.json:28`) — in `DelegatedTask.EXPECTED_OUTPUT`.
  - `Task '{...}' execution timed out after {...} seconds. Consider increasing
    max_execution_time or optimizing the task.` (`agent/core.py:917`) — in
    `Delegation.withinLimit`.
  - `Task execution failed: {...}` (`agent/core.py:926`) — in `Delegation.withinLimit`.
  - `Max Execution time must be a positive integer greater than zero`
    (`agent/utils.py:316`) — in `Delegation.attemptOnce`.

  That is six message strings and no code. No Python was transcribed; every Java file
  under `src` was written fresh against behaviour read out of, and run against, the
  installed `crewai` package. Where a comment or the spec cites a source file and line
  range, that is citation rather than copying.
- **Behaviour is derived throughout**, plainly: the coworker-name sanitising and lookup,
  the not-found text, the per-agent retry budget and its five failure classes, the
  wall-clock wording, and the sequence of announcements a delegation makes are a port of
  the decision procedure in `crewai/tools/agent_tools/base_agent_tools.py` and
  `crewai/agent/core.py`. This is the nature of a port and is not something to obscure.
- The MIT licence asks that its notice travel with copies or substantial portions of the
  software. Six message strings are not a substantial portion, but `LICENSE-crewai`
  carries the notice regardless, because attribution costs nothing and guessing at the
  threshold does.

## Also used

- [Akka](https://akka.io) — the SDK and runtime this port is built on.
