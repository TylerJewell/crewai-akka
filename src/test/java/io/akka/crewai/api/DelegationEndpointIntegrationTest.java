package io.akka.crewai.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.crewai.api.DelegationEndpoint.AgentRequest;
import io.akka.crewai.api.DelegationEndpoint.AttemptRequest;
import io.akka.crewai.api.DelegationEndpoint.CrewRequest;
import io.akka.crewai.api.DelegationEndpoint.CrewResponse;
import io.akka.crewai.api.DelegationEndpoint.DelegateRequest;
import io.akka.crewai.api.DelegationEndpoint.DelegateResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The capability reached the way something outside a test reaches it — SPEC-001 D1, D2, D3, D6.
 * Starts a runtime.
 */
class DelegationEndpointIntegrationTest extends TestKitSupport {

  private static final AttemptRequest FAILS =
      new AttemptRequest(null, "RETRYABLE", "boom");
  private static final AttemptRequest SUCCEEDS = new AttemptRequest("the summary", null, null);

  private String crew(AgentRequest... agents) {
    String crewId = "crew-" + UUID.randomUUID();
    httpClient
        .POST("/crews/" + crewId)
        .withRequestBody(new CrewRequest(List.of(agents)))
        .responseBodyAs(CrewResponse.class)
        .invoke();
    return crewId;
  }

  private DelegateResponse delegate(String crewId, String coworker, AttemptRequest... script) {
    return httpClient
        .POST("/crews/" + crewId + "/delegate")
        .withRequestBody(
            new DelegateRequest("Summarise the findings", "They are long.", coworker, null,
                List.of(script)))
        .responseBodyAs(DelegateResponse.class)
        .invoke()
        .body();
  }

  @Test
  void aDelegationThatSucceedsComesBackWithTheDelegatesOwnOutput() {
    String crewId = crew(new AgentRequest("Writer", 2, null));

    DelegateResponse response = delegate(crewId, "writer", SUCCEEDS);

    assertThat(response.output()).isEqualTo("the summary");
    assertThat(response.announcements()).containsExactly("STARTED", "COMPLETED");
    assertThat(response.attempts()).isEqualTo(1);
  }

  @Test
  void anUnknownCoworkerComesBackAsTextListingTheOnesThatExist() {
    String crewId =
        crew(new AgentRequest("Senior Research Analyst", 2, null), new AgentRequest("Writer", 2, null));

    DelegateResponse response = delegate(crewId, "Chef", SUCCEEDS);

    assertThat(response.output())
        .isEqualTo(
            "\nError executing tool. coworker mentioned not found, it must be one of the following"
                + " options:\n- senior research analyst\n- writer\n");
    assertThat(response.announcements()).isEmpty();
    assertThat(response.attempts()).isZero();
  }

  @Test
  void aSpentBudgetStaysSpentForTheNextDelegationThroughTheEndpoint() {
    String crewId = crew(new AgentRequest("Writer", 2, null));

    assertThat(delegate(crewId, "writer", FAILS).attempts()).isEqualTo(3);
    assertThat(delegate(crewId, "writer", FAILS).attempts()).isEqualTo(1);
    assertThat(delegate(crewId, "writer", FAILS).attempts()).isEqualTo(1);

    CrewResponse crew =
        httpClient.GET("/crews/" + crewId).responseBodyAs(CrewResponse.class).invoke().body();
    assertThat(crew.agents()).singleElement().satisfies(a -> {
      assertThat(a.role()).isEqualTo("Writer");
      assertThat(a.spent()).isEqualTo(5);
    });
  }

  @Test
  void aSuccessDoesNotHandTheBudgetBack() {
    String crewId = crew(new AgentRequest("Writer", 2, null));

    assertThat(delegate(crewId, "writer", FAILS, SUCCEEDS).attempts()).isEqualTo(2);
    assertThat(delegate(crewId, "writer", FAILS).attempts()).isEqualTo(2);
  }

  @Test
  void onlyTheDelegatesBudgetMoves() {
    String crewId =
        crew(new AgentRequest("Senior Research Analyst", 2, null), new AgentRequest("Writer", 2, null));

    delegate(crewId, "writer", FAILS);

    CrewResponse crew =
        httpClient.GET("/crews/" + crewId).responseBodyAs(CrewResponse.class).invoke().body();
    assertThat(crew.agents())
        .extracting(DelegationEndpoint.AgentBudgetResponse::role, DelegationEndpoint.AgentBudgetResponse::spent)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Senior Research Analyst", 0),
            org.assertj.core.groups.Tuple.tuple("Writer", 3));
  }

  @Test
  void aCoworkerNamedTheWayALanguageModelNamesOneStillLands() {
    String crewId = crew(new AgentRequest("Senior Research Analyst", 2, null));

    for (String spelling :
        List.of(
            "Senior Research Analyst",
            "senior  research\nanalyst",
            "\"Senior Research Analyst\"",
            "[Senior Research Analyst, Writer]")) {
      assertThat(delegate(crewId, spelling, SUCCEEDS).output())
          .describedAs("spelling [%s]", spelling)
          .isEqualTo("the summary");
    }
  }

  @Test
  void aTornDownDelegateIsReportedOnItsOwnChannel() {
    String crewId = crew(new AgentRequest("Writer", 2, null));

    DelegateResponse response =
        delegate(crewId, "writer", new AttemptRequest(null, "FATAL", "interrupted"));

    assertThat(response.aborted()).isTrue();
    assertThat(response.output()).isEqualTo("interrupted");
    assertThat(response.announcements()).containsExactly("STARTED");
    assertThat(response.attempts()).isEqualTo(1);

    CrewResponse crew =
        httpClient.GET("/crews/" + crewId).responseBodyAs(CrewResponse.class).invoke().body();
    assertThat(crew.agents()).singleElement().satisfies(a -> assertThat(a.spent()).isZero());
  }

  @Test
  void aPassthroughFailureIsNotAnnouncedTheWayAProviderRefusalIs() {
    String crewId =
        crew(new AgentRequest("Writer", 2, null), new AgentRequest("Editor", 2, null));

    assertThat(
            delegate(crewId, "writer", new AttemptRequest(null, "PASSTHROUGH", "tool stopped"))
                .announcements())
        .containsExactly("STARTED");
    assertThat(
            delegate(crewId, "editor", new AttemptRequest(null, "PROVIDER", "rate limited"))
                .announcements())
        .containsExactly("STARTED", "ERROR");
  }

  @Test
  void aQuestionTakesTheSameRouteAsATask() {
    String crewId = crew(new AgentRequest("Writer", 2, null));

    DelegateResponse response =
        httpClient
            .POST("/crews/" + crewId + "/ask")
            .withRequestBody(
                new DelegateRequest("What did you find?", "Nothing yet.", "writer", null,
                    List.of(SUCCEEDS)))
            .responseBodyAs(DelegateResponse.class)
            .invoke()
            .body();

    assertThat(response.output()).isEqualTo("the summary");
  }

  @Test
  void aCrewIdCarryingTheRuntimesReservedCharacterIsRefused() {
    var response =
        httpClient
            .POST("/crews/bad%7Cid")
            .withRequestBody(new CrewRequest(List.of(new AgentRequest("Writer", 2, null))))
            .invoke();

    assertThat(response.httpResponse().status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }

  @Test
  void aDelegationToACrewThatWasNeverDeclaredIsNotFound() {
    var response =
        httpClient
            .POST("/crews/never-declared-" + UUID.randomUUID() + "/delegate")
            .withRequestBody(
                new DelegateRequest("t", "c", "writer", null, List.of(SUCCEEDS)))
            .invoke();

    assertThat(response.httpResponse().status()).isEqualTo(StatusCodes.NOT_FOUND);
  }

  @Test
  void aDelegationWithNothingScriptedIsRefused() {
    String crewId = crew(new AgentRequest("Writer", 2, null));

    var response =
        httpClient
            .POST("/crews/" + crewId + "/delegate")
            .withRequestBody(new DelegateRequest("t", "c", "writer", null, List.of()))
            .invoke();

    assertThat(response.httpResponse().status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }

  @Test
  void aWallClockLimitChangesTheWordingOfAnOrdinaryFailure() {
    String crewId = crew(new AgentRequest("Writer", 2, 30));

    DelegateResponse response = delegate(crewId, "writer", FAILS);

    assertThat(response.output())
        .isEqualTo(
            "Error executing task with agent 'writer'. Error: Task execution failed: boom");
    assertThat(response.attempts()).isEqualTo(3);
  }

  @Test
  void aNonPositiveWallClockLimitBurnsTheBudgetWithoutAskingTheDelegate() {
    String crewId = crew(new AgentRequest("Writer", 2, 0));

    DelegateResponse response = delegate(crewId, "writer", SUCCEEDS);

    assertThat(response.output())
        .isEqualTo(
            "Error executing task with agent 'writer'. Error: Max Execution time must be a"
                + " positive integer greater than zero");
    assertThat(response.attempts()).isEqualTo(3);
  }
}
