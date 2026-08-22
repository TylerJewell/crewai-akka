package io.akka.crewai.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.akka.crewai.application.AgentBudgetEntity;
import io.akka.crewai.application.CrewEntity;
import io.akka.crewai.application.DurableBudgetLedger;
import io.akka.crewai.domain.AgentSpec;
import io.akka.crewai.domain.Announcement;
import io.akka.crewai.domain.AttemptOutcome;
import io.akka.crewai.domain.DelegateAbortedException;
import io.akka.crewai.domain.Delegation;
import io.akka.crewai.domain.DelegationRequest;
import io.akka.crewai.domain.DelegationResult;
import io.akka.crewai.domain.FailureClass;
import io.akka.crewai.domain.Roster;
import io.akka.crewai.domain.ScriptedDelegate;
import io.akka.crewai.domain.ToolKind;
import java.util.ArrayList;
import java.util.List;

/**
 * The delegation capability's own surface: declare a crew, then delegate work to one of its
 * agents and read back the one string the delegation produced.
 *
 * <p>What the delegate does with the work is out of scope (SPEC-001 §1, D5), so a request carries
 * the outcome of each attempt. That is the same substitution the probes made against the source
 * and for the same reason — the model is not what this capability is about.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/crews")
public class DelegationEndpoint {

  private final ComponentClient componentClient;

  public DelegationEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record AgentRequest(String role, Integer retryLimit, Integer maxExecutionTimeSeconds) {}

  public record CrewRequest(List<AgentRequest> agents) {}

  public record AgentBudgetResponse(String role, int retryLimit, int spent) {}

  public record CrewResponse(List<AgentBudgetResponse> agents) {}

  /**
   * One scripted attempt outcome. {@code output} is what a succeeding attempt returns;
   * {@code failureClass} and {@code message} are what a failing one reports.
   */
  public record AttemptRequest(String output, String failureClass, String message) {}

  public record DelegateRequest(
      String work, String context, String coworker, String coWorker, List<AttemptRequest> script) {}

  /**
   * @param aborted set when the delegate was torn down rather than failing: SPEC-001 R11 keeps a
   *     fatal attempt out of the text channel, so the caller is told which channel it came from
   */
  public record DelegateResponse(
      String output, List<String> announcements, int attempts, boolean aborted) {}

  // ------------------------------------------------------------------------- the crew

  @Post("/{crewId}")
  public HttpResponse declare(String crewId, CrewRequest request) {
    List<AgentSpec> agents = new ArrayList<>();
    for (AgentRequest agent : request.agents()) {
      agents.add(
          new AgentSpec(
              agent.role(),
              agent.retryLimit() == null ? 2 : agent.retryLimit(),
              agent.maxExecutionTimeSeconds()));
    }
    Roster roster = new Roster(agents);

    for (AgentSpec agent : agents) {
      componentClient
          .forEventSourcedEntity(budgetId(crewId, agent.sanitizedRole()))
          .method(AgentBudgetEntity::declareLimit)
          .invoke(agent.retryLimit());
    }
    componentClient.forKeyValueEntity(crewId).method(CrewEntity::declare).invoke(roster);
    return HttpResponses.created(read(crewId, roster));
  }

  @Get("/{crewId}")
  public CrewResponse get(String crewId) {
    return read(crewId, roster(crewId));
  }

  // -------------------------------------------------------------------- one delegation

  @Post("/{crewId}/delegate")
  public DelegateResponse delegate(String crewId, DelegateRequest request) {
    return run(crewId, ToolKind.DELEGATE, request);
  }

  @Post("/{crewId}/ask")
  public DelegateResponse ask(String crewId, DelegateRequest request) {
    return run(crewId, ToolKind.ASK, request);
  }

  private DelegateResponse run(String crewId, ToolKind toolKind, DelegateRequest request) {
    Roster roster = roster(crewId);
    if (roster.agents().isEmpty()) {
      throw HttpException.error(
          akka.http.javadsl.model.StatusCodes.NOT_FOUND, "No such crew has been declared");
    }

    Delegation delegation =
        new Delegation(
            roster,
            ScriptedDelegate.of(script(request)),
            new DurableBudgetLedger(componentClient, crewId));
    DelegationRequest delegationRequest =
        new DelegationRequest(
            toolKind,
            request.work(),
            request.context(),
            request.coworker(),
            request.coWorker(),
            null);

    try {
      DelegationResult result = delegation.run(delegationRequest);
      return new DelegateResponse(
          result.output(),
          result.announcements().stream().map(Announcement::name).toList(),
          result.attempts(),
          false);
    } catch (DelegateAbortedException e) {
      return new DelegateResponse(
          e.getMessage(),
          e.announcements().stream().map(Announcement::name).toList(),
          e.attempts(),
          true);
    }
  }

  private static List<AttemptOutcome> script(DelegateRequest request) {
    if (request.script() == null || request.script().isEmpty()) {
      throw HttpException.badRequest("A delegation must say what each attempt at it produces");
    }
    List<AttemptOutcome> outcomes = new ArrayList<>();
    for (AttemptRequest attempt : request.script()) {
      if (attempt.failureClass() == null) {
        outcomes.add(AttemptOutcome.succeeded(attempt.output()));
      } else {
        outcomes.add(
            AttemptOutcome.failed(
                failureClass(attempt.failureClass()),
                attempt.message() == null ? "" : attempt.message()));
      }
    }
    return outcomes;
  }

  private static FailureClass failureClass(String name) {
    try {
      return FailureClass.valueOf(name.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw HttpException.badRequest(
          "The failure class must be one of " + List.of(FailureClass.values()));
    }
  }

  private Roster roster(String crewId) {
    return componentClient.forKeyValueEntity(crewId).method(CrewEntity::get).invoke();
  }

  private CrewResponse read(String crewId, Roster roster) {
    List<AgentBudgetResponse> agents = new ArrayList<>();
    for (AgentSpec agent : roster.agents()) {
      AgentBudgetEntity.State state =
          componentClient
              .forEventSourcedEntity(budgetId(crewId, agent.sanitizedRole()))
              .method(AgentBudgetEntity::get)
              .invoke();
      agents.add(new AgentBudgetResponse(agent.role(), state.retryLimit(), state.spent()));
    }
    return new CrewResponse(agents);
  }

  /** SPEC-001 D6: an id the runtime cannot route is refused here rather than left to hang. */
  private static String budgetId(String crewId, String sanitizedRole) {
    try {
      return AgentBudgetEntity.idFor(crewId, sanitizedRole);
    } catch (IllegalArgumentException e) {
      throw HttpException.badRequest(e.getMessage());
    }
  }
}
