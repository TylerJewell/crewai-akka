package io.akka.crewai.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.crewai.domain.AgentSpec;
import io.akka.crewai.domain.AttemptOutcome;
import io.akka.crewai.domain.Delegate;
import io.akka.crewai.domain.DelegateAbortedException;
import io.akka.crewai.domain.Delegation;
import io.akka.crewai.domain.DelegationRequest;
import io.akka.crewai.domain.DelegationResult;
import io.akka.crewai.domain.FailureClass;
import io.akka.crewai.domain.InMemoryBudgetLedger;
import io.akka.crewai.domain.Roster;
import io.akka.crewai.domain.ToolKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The port's side of the benchmark: this rebuild answering every workload in
 * {@code crewai-port/bench/workloads.json}, in the shape the source side prints.
 *
 * <pre>
 *   java -cp target/classes:&lt;jackson&gt; io.akka.crewai.bench.BenchRunner &lt;workloads.json&gt; answers
 *   java -cp ... io.akka.crewai.bench.BenchRunner &lt;workloads.json&gt; timings
 * </pre>
 *
 * <p>The ledger here is the in-memory one, which is what crewAI has. The durable ledger is a
 * property of where the count lives rather than of the rule, so timing it would time the runtime;
 * that the two ledgers give the same answers is checked by {@code
 * AgentBudgetEntityIntegrationTest} and {@code DelegationEndpointIntegrationTest} instead.
 */
public final class BenchRunner {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long WINDOW_TARGET_NANOS = 50_000_000L;

  private BenchRunner() {}

  public static void main(String[] args) throws IOException {
    ArrayNode workloads = (ArrayNode) JSON.readTree(Files.readString(Path.of(args[0])));
    String mode = args.length > 1 ? args[1] : "answers";
    ObjectNode result = mode.equals("timings") ? timings(workloads) : answers(workloads);
    Path out = Path.of(args[0]).resolveSibling("port_" + mode + ".json");
    Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
    System.out.println(workloads.size() + " workloads -> " + out);
  }

  // ------------------------------------------------------------------------- answers

  private static ObjectNode answers(ArrayNode workloads) {
    ObjectNode out = JSON.createObjectNode();
    for (JsonNode workload : workloads) {
      out.set(workload.get("name").asText(), answer(workload));
    }
    return out;
  }

  private static ObjectNode answer(JsonNode workload) {
    Roster roster = roster(workload.get("roster"));

    if ("arrival-orders".equals(text(workload.get("sequence")))) {
      List<JsonNode> rows = list(workload.get("rows"));
      ObjectNode orders = JSON.createObjectNode();
      for (int[] permutation : permutations(rows.size())) {
        List<JsonNode> delivered = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < permutation.length; i++) {
          delivered.add(rows.get(permutation[i]));
          key.append(i == 0 ? "" : "-").append(permutation[i]);
        }
        orders.set(key.toString(), runDelegations(delivered, roster));
      }
      ObjectNode out = JSON.createObjectNode();
      out.set("orders", orders);
      return out;
    }

    if (workload.hasNonNull("batches")) {
      List<JsonNode> flat = new ArrayList<>();
      ArrayNode sizes = JSON.createArrayNode();
      for (JsonNode batch : workload.get("batches")) {
        sizes.add(batch.size());
        flat.addAll(list(batch));
      }
      ObjectNode out = JSON.createObjectNode();
      out.set("batched", runDelegations(flat, roster));
      out.set("batchSizes", sizes);
      return out;
    }

    return runDelegations(list(workload.get("delegations")), roster);
  }

  private static ObjectNode runDelegations(List<JsonNode> delegations, Roster roster) {
    return runDelegations(delegations, roster, new InMemoryBudgetLedger());
  }

  private static ObjectNode runDelegations(
      List<JsonNode> delegations, Roster roster, InMemoryBudgetLedger ledger) {
    ArrayNode results = JSON.createArrayNode();

    for (JsonNode spec : delegations) {
      AtomicInteger delegateCalls = new AtomicInteger();
      Delegate delegate = scripted(spec.get("script"), delegateCalls);
      DelegationRequest request =
          new DelegationRequest(
              "ask".equals(text(spec.get("tool"))) ? ToolKind.ASK : ToolKind.DELEGATE,
              text(spec.get("work")),
              text(spec.get("context")),
              text(spec.get("coworker")),
              text(spec.get("coWorker")),
              text(spec.get("coworkerKwarg")));

      ObjectNode row = JSON.createObjectNode();
      try {
        DelegationResult result = new Delegation(roster, delegate, ledger).run(request);
        row.put("output", result.output());
        row.putNull("escaped");
        row.put("attempts", result.attempts());
        ArrayNode trace = JSON.createArrayNode();
        result.announcements().forEach(a -> trace.add(a.name()));
        row.set("announcements", trace);
      } catch (DelegateAbortedException e) {
        row.putNull("output");
        row.put("escaped", e.getMessage());
        row.put("attempts", e.attempts());
        ArrayNode trace = JSON.createArrayNode();
        e.announcements().forEach(a -> trace.add(a.name()));
        row.set("announcements", trace);
      }
      row.put("delegateCalls", delegateCalls.get());
      results.add(row);
    }

    ObjectNode out = JSON.createObjectNode();
    out.set("delegations", results);
    out.set("spent", spent(roster, ledger));
    return out;
  }

  /**
   * What every reachable agent has spent. Keyed by sanitised role and by the position of the
   * first entry holding it: R4 makes any later duplicate unreachable, so its budget is not a
   * thing a delegation can move on either side.
   */
  private static ObjectNode spent(Roster roster, InMemoryBudgetLedger ledger) {
    ObjectNode out = JSON.createObjectNode();
    Set<String> seen = new LinkedHashSet<>();
    List<AgentSpec> agents = roster.agents();
    for (int i = 0; i < agents.size(); i++) {
      String role = agents.get(i).sanitizedRole();
      if (seen.add(role)) {
        out.put(i + ":" + agents.get(i).role(), ledger.spent(role));
      }
    }
    return out;
  }

  private static Delegate scripted(JsonNode script, AtomicInteger calls) {
    List<JsonNode> entries = list(script);
    return (task, attemptNumber) -> {
      JsonNode entry = entries.get(Math.min(calls.getAndIncrement(), entries.size() - 1));
      if (entry.hasNonNull("sleepSeconds")) {
        try {
          Thread.sleep(entry.get("sleepSeconds").asLong() * 1000L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return AttemptOutcome.failed(FailureClass.FATAL, "interrupted");
        }
      }
      if (entry.hasNonNull("failureClass")) {
        return AttemptOutcome.failed(
            FailureClass.valueOf(entry.get("failureClass").asText()), text(entry.get("message")));
      }
      return AttemptOutcome.succeeded(text(entry.get("output")));
    };
  }

  // -------------------------------------------------------------------------- timings

  private static ObjectNode timings(ArrayNode workloads) {
    ObjectNode out = JSON.createObjectNode();
    for (JsonNode workload : workloads) {
      String name = workload.get("name").asText();
      if (workload.hasNonNull("excludeFromTimings")) {
        ObjectNode skipped = JSON.createObjectNode();
        skipped.put("skipped", workload.get("excludeFromTimings").asText());
        out.set(name, skipped);
        continue;
      }

      Roster roster = roster(workload.get("roster"));
      List<JsonNode> delegations = delegationsOf(workload);

      ObjectNode row = JSON.createObjectNode();
      measure(() -> answer(workload), row, "");
      // Building the roster is setup, not delegation. Held outside the window and given a
      // fresh ledger each run, so what is timed is the rule -- the same split the source
      // side makes, where constructing an agent is most of the whole-workload figure.
      measure(
          () -> runDelegations(delegations, roster, new InMemoryBudgetLedger()),
          row,
          "DelegationOnly");
      out.set(name, row);
    }
    return out;
  }

  /**
   * A window aims for tens of milliseconds and the figure is its total over what was in it, taken
   * as the median of twelve. A pilot that measures nothing is a refusal rather than a
   * divide-by-a-tick.
   */
  private static void measure(Runnable once, ObjectNode row, String suffix) {
    for (int warmup = 0; warmup < 2000; warmup++) {
      once.run();
    }

    int pilotRuns = 20;
    long started = System.nanoTime();
    for (int i = 0; i < pilotRuns; i++) {
      once.run();
    }
    double per = (double) (System.nanoTime() - started) / pilotRuns;
    if (per < 100) {
      throw new IllegalStateException(
          "a pilot of " + pilotRuns + " runs measured " + per + " ns each, at or below the clock");
    }
    int perWindow = Math.max(pilotRuns, (int) Math.min(20_000, WINDOW_TARGET_NANOS / per));

    List<Long> perRun = new ArrayList<>();
    for (int window = 0; window < 12; window++) {
      long windowStarted = System.nanoTime();
      for (int i = 0; i < perWindow; i++) {
        once.run();
      }
      perRun.add((System.nanoTime() - windowStarted) / perWindow);
    }
    perRun.sort(Long::compare);

    row.put("nanosPerRun" + suffix, perRun.get(perRun.size() / 2));
    row.put("runsPerWindow" + suffix, perWindow);
    row.put("windows" + suffix, perRun.size());
  }

  /** The delegations a workload runs, whichever shape it declares them in. */
  private static List<JsonNode> delegationsOf(JsonNode workload) {
    if ("arrival-orders".equals(text(workload.get("sequence")))) {
      return list(workload.get("rows"));
    }
    if (workload.hasNonNull("batches")) {
      List<JsonNode> flat = new ArrayList<>();
      workload.get("batches").forEach(batch -> flat.addAll(list(batch)));
      return flat;
    }
    return list(workload.get("delegations"));
  }

  // --------------------------------------------------------------------------- reading

  private static Roster roster(JsonNode node) {
    List<AgentSpec> agents = new ArrayList<>();
    for (JsonNode agent : node) {
      agents.add(
          new AgentSpec(
              agent.get("role").asText(),
              agent.get("retryLimit").asInt(),
              agent.hasNonNull("maxExecutionTimeSeconds")
                  ? agent.get("maxExecutionTimeSeconds").asInt()
                  : null));
    }
    return new Roster(agents);
  }

  private static List<JsonNode> list(JsonNode node) {
    List<JsonNode> out = new ArrayList<>();
    if (node != null) {
      node.forEach(out::add);
    }
    return out;
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  private static List<int[]> permutations(int size) {
    List<int[]> out = new ArrayList<>();
    permute(new int[size], new boolean[size], 0, out);
    return out;
  }

  private static void permute(int[] current, boolean[] used, int depth, List<int[]> out) {
    if (depth == current.length) {
      out.add(current.clone());
      return;
    }
    for (int i = 0; i < current.length; i++) {
      if (!used[i]) {
        used[i] = true;
        current[depth] = i;
        permute(current, used, depth + 1, out);
        used[i] = false;
      }
    }
  }
}
