# crewai-akka

One agent hands a piece of work to another by name, and this decides who that name means,
how many times a failed hand-off is tried again, and what the asking agent is told when it
runs out of tries.

A port of [crewAIInc/crewAI](https://github.com/crewAIInc/crewAI) onto **Akka**, built with
**Akka Specify**.

---

## Where it came from

crewAI is a framework for building teams of language-model agents that work on a task
together. It was ported to derive a specification format precise enough to regenerate a
system on a different stack — the port is the vehicle, the specification is the
deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `crewai-port/`.

---

## crewAIInc/crewAI → this port

📉 316 Python lines → **335 Java lines**<br>
📁 6 files → **18 files**<br>
🎯 24 of 24 shared answers matching → **24 of 24**<br>
⚡ 537,588 → **783** nanoseconds, one hand-off retried to its limit<br>
⚡ 1,041,908 → **1,892** nanoseconds, three hand-offs to one agent in a row<br>
🧪 not measured → **66 tests**<br>
💾 a count that is lost when the program stops → **a count that survives a restart**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/crewai-port/bench/REPORT.md).

---

## What it took to build

⏱️ **2.1 hours** from the first command to the published repository, **1.6** of them active<br>
💬 **414** exchanges with the model<br>
✍️ **387,706** tokens written by the model, **106,861,470** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **66** tests

```bash
python toolkit/tokens.py --port crewai    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

- **A name is matched exactly, after tidying.** Runs of spaces, tabs and newlines become
  one space, the ends are trimmed, double quotes are dropped and capitals are folded away.
  Nothing else. `SENIOR  RESEARCH\nANALYST` reaches the Senior Research Analyst;
  `Senior Research` reaches nobody.
- **A name in square brackets is cut down to its first item.** Models sometimes answer
  with a list where one name was asked for, and the first item is taken.
- **Two agents may share a name, and the first one on the list gets the work.** It is not
  an error and nothing warns.
- **Nobody by that name is an answer, not a failure.** The asking agent is handed a
  sentence listing every name that would have worked, and carries on.
- **A hand-off that fails is also an answer, not a failure.** The asking agent is handed a
  sentence naming the agent and what went wrong.
- **How many times a failure is tried again is a property of the agent, not of the
  hand-off.** An agent allowed two retries is tried three times. That allowance is spent
  for the rest of its life: an agent that used all three tries on one piece of work gets a
  single try on every piece of work after that, and succeeding in between gives nothing
  back.
- **Four kinds of failure are not tried again and cost nothing.** A tool that stopped on
  purpose, a refusal from the model's provider, running past a time limit, and the program
  being shut down.
- **Being shut down is the one thing that is not turned into a sentence.** It reaches
  whoever asked for the hand-off.
- **A time limit changes the wording as well as the waiting.** The same failure is
  reported differently depending on whether the agent has a limit set at all.
- **A time limit that is zero or negative fails every try without ever asking the agent**,
  and spends the whole allowance doing it.

---

## Design decisions

**The tally lives in a journal.** Each agent's used-up tries are written down as they
happen rather than held in memory, so restarting the program does not quietly hand
everybody a fresh allowance. Anyone asking about an agent gets the same answer, on any
machine.

**One name for every kind of failure.** The original decides not to try again in four
different places, in three different files, and whether it announces the failure falls out
of where each of those checks happens to sit. Here it is one list of five names with two
questions asked of each, so the whole rule can be read at once.

**The tally is asked, not told.** The thing holding an agent's used-up tries applies the
rule itself and answers "try again" or "stop", instead of handing out a number for the
caller to compare. Two hand-offs to the same agent at the same moment therefore share one
allowance rather than each seeing it untouched.

**A hand-off waiting on a time limit gets its own lightweight thread.** A limit that
expires leaves work running that nobody will collect, so it must be something cheap to
abandon. This made a hand-off under a limit fourteen times quicker.

**Anything can be on the other end of a hand-off.** What the receiving agent actually does
is behind a single method, and what it produces is supplied with the request. That is what
lets the same answers be compared against the original without either side calling a
language model.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/crewai-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9051.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

No key for a model provider is needed. Nothing here calls one.

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9051**.

### Try it

Put a crew on the record, then hand work to one of them. Each request says what the
receiving agent produces on each try, because that is the part of the original this port
deliberately leaves out.

```bash
curl -X POST localhost:9051/crews/demo -H 'Content-Type: application/json' -d '{
  "agents": [
    {"role": "Senior Research Analyst", "retryLimit": 2},
    {"role": "Writer", "retryLimit": 2}
  ]
}'

# succeeds first time
curl -X POST localhost:9051/crews/demo/delegate -H 'Content-Type: application/json' -d '{
  "work": "Summarise the findings", "context": "They are long.", "coworker": "writer",
  "script": [{"output": "Here is the summary."}]
}'

# fails every time: three tries, then a sentence
curl -X POST localhost:9051/crews/demo/delegate -H 'Content-Type: application/json' -d '{
  "work": "Summarise the findings", "context": "They are long.", "coworker": "writer",
  "script": [{"failureClass": "RETRYABLE", "message": "boom"}]
}'

# the same request again: one try, because the first one used the allowance up
curl -X POST localhost:9051/crews/demo/delegate -H 'Content-Type: application/json' -d '{
  "work": "Summarise the findings", "context": "They are long.", "coworker": "writer",
  "script": [{"failureClass": "RETRYABLE", "message": "boom"}]
}'

# what each agent has spent
curl localhost:9051/crews/demo
```

### Endpoints

| Method | Path | What it does |
|---|---|---|
| `POST` | `/crews/{crewId}` | puts a crew on the record; answers with each agent's allowance and what it has spent |
| `GET` | `/crews/{crewId}` | what each agent has spent so far |
| `POST` | `/crews/{crewId}/delegate` | hands a piece of work to one of them |
| `POST` | `/crews/{crewId}/ask` | asks one of them a question |

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| none | — | nothing here is configured by environment variable |

The one setting is the port the service listens on, in
`src/main/resources/application.conf`.

---

## Where it differs from crewAIInc/crewAI

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **How long you wait for an agent that ran past its time limit.** crewAI reports the
  limit as soon as it expires but does not return until the abandoned work has actually
  finished — a one-second limit over three seconds of work answers after three seconds.
  This port answers when the limit expires and leaves the abandoned work to finish on its
  own, because a limit that does not bound the wait is not a limit for whoever is waiting.
- **Where each agent's used-up tries are kept.** crewAI keeps the tally beside the agent
  in memory, so it is gone when the program stops and invisible to any other copy of it.
  This port writes it down as it happens, so a restart does not hand everybody a fresh
  allowance and every copy of the service sees the same tally. The rule itself is
  unchanged; how far it reaches is not.
- **Two hand-offs to the same agent at the same moment.** crewAI reads the tally, adds one
  and writes it back with nothing stopping the two from interleaving, so the total number
  of tries is somewhere between three and six and which one you get is a matter of timing.
  This port handles them one at a time, so it is always three. The original has no settled
  answer here; this is the one chosen.
- **The order the announcements come out in.** crewAI publishes them to a noticeboard that
  hands each reader to a pool of workers, so a reader may see them in a different order
  than they were announced, and a late one may appear to belong to the next hand-off. This
  port carries them on the result of the hand-off that made them, in the order they were
  made.
- **The ceiling on how many times a hand-off is tried.** Both stop after the allowance
  runs out. This port additionally refuses to try more than the allowance plus one, even
  if the tally says otherwise. crewAI has no such ceiling because its tally is a number in
  the same object doing the counting and cannot disagree with itself; this port's tally is
  read over a network, and something that never answers is not an answer.
- **Two entries on the list with the same name, after tidying.** Both send the work to the
  first of them, so what a hand-off does is the same. But the two entries have separate
  tallies in crewAI and share one here, which is visible only by asking what each has
  spent — a delegation cannot reach the second entry to move its tally either way.
- **An entry whose name cannot be read at all.** crewAI raises, because the sentence it
  builds to report the problem reads the same unreadable names again. This port cannot
  represent such an entry — a name is a piece of text on the way in — so the situation
  does not arise and no rule was invented for it.
- **What is on the other end of a hand-off.** crewAI calls a language model. Here that is a
  single method with one implementation, and what it produces is supplied with the request.
  Nothing model-backed is built.
- **Names containing a vertical bar.** The runtime this port is built on reserves that
  character in the key it stores each agent's tally under, and a key containing it does not
  fail — it hangs for ninety seconds. This port refuses such a name at the door. crewAI has
  no such restriction.
- **Everything else a crew does.** Planning, ordering the work, memory, knowledge,
  guardrails, asking a person, and the asynchronous route through the same rule are not
  rebuilt here and their behaviour is `not checked`.

---

## Licence

crewAIInc/crewAI is under the MIT License, © 2025 crewAI, Inc. This port reimplements the
behaviour without copied code; six message strings are copied verbatim so that a caller
cannot tell the two apart, and each is listed in `ACKNOWLEDGEMENTS.md`.
