package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reading the gateway's own view of a profile — its platforms, how it is running, and
 * whether it is paused.
 *
 * <p>{@code gateway_state.json} and the {@code ESTOP} sentinel are both written by hermes,
 * not by Mission Control, so they are the inputs here that can change shape without this
 * code changing. Every failure mode therefore has to degrade to "nothing known" rather than
 * throw — and the status mapping has to collapse the gateway's vocabulary onto the four the
 * dashboard renders, defaulting to {@code down} so an unrecognised state never reads as
 * healthy.
 */
class HermesGatewayStateTest {

  private static final String STATE = "/opt/data/profiles/ops/gateway_state.json";
  private static final String ESTOP = "/opt/data/profiles/ops/ESTOP";

  private HermesGatewayState gatewayState(FakeContainer container) {
    return new HermesGatewayState(container.files(), new ObjectMapper());
  }

  private Map<String, String> statuses(String json) {
    FakeContainer container = new FakeContainer().file(STATE, json);
    return gatewayState(container).integrations(HOST, CONTAINER, "ops").stream()
        .collect(Collectors.toMap(IntegrationDto::kind, IntegrationDto::status));
  }

  private GatewayDto gateway(FakeContainer container) {
    return gatewayState(container).read(HOST, CONTAINER, "ops").gateway();
  }

  // ── status mapping ─────────────────────────────────────────────────────────

  @Test
  void everyGatewaySpellingOfHealthyIsUp() {
    assertEquals(Map.of("slack", "up", "discord", "up", "telegram", "up"), statuses("""
        {"platforms":{"slack":{"state":"connected"},"discord":{"state":"up"},
                      "telegram":{"state":"ok"}}}
        """));
  }

  @Test
  void degradedAndOffAreDistinctFromDown() {
    assertEquals(Map.of("slack", "degraded", "discord", "degraded", "telegram", "off",
            "signal", "off", "email", "down"), statuses("""
        {"platforms":{"slack":{"state":"degraded"},"discord":{"state":"warning"},
                      "telegram":{"state":"disabled"},"signal":{"state":"paused"},
                      "email":{"state":"disconnected"}}}
        """));
  }

  @Test
  void anUnrecognisedStateReadsAsDownRatherThanHealthy() {
    assertEquals(Map.of("slack", "down", "discord", "down"), statuses("""
        {"platforms":{"slack":{"state":"reticulating"},"discord":{}}}
        """));
  }

  @Test
  void statusIsAcceptedWhereStateIsAbsent() {
    assertEquals(Map.of("slack", "up"), statuses("""
        {"platforms":{"slack":{"status":"connected"}}}
        """));
    // and state wins when both are present
    assertEquals(Map.of("slack", "down"), statuses("""
        {"platforms":{"slack":{"state":"error","status":"connected"}}}
        """));
  }

  @Test
  void theStateIsCarriedIntoTheDetailSoAnOperatorSeesWhatTheGatewaySaid() {
    FakeContainer container = new FakeContainer().file(STATE, """
        {"platforms":{"slack":{"state":"reticulating"},"discord":{}}}
        """);

    List<IntegrationDto> listed = gatewayState(container).integrations(HOST, CONTAINER, "ops");

    assertEquals("gateway reticulating", listed.getFirst().detail());
    assertEquals("gateway state unknown", listed.get(1).detail());
  }

  // ── every platform, not a chosen ten ───────────────────────────────────────

  @Test
  void aPlatformTheDashboardHasNoCardForIsStillReported() {
    // this filtered against a hardcoded set of kinds, which meant a profile talking over
    // anything newer than that set rendered as "no integrations" while the gateway was
    // connected — including api_server, the one entry a plain container reports
    assertEquals(
        Map.of("slack", "up", "api_server", "up", "some-future-platform", "up"), statuses("""
        {"platforms":{"slack":{"state":"connected"},"api_server":{"state":"connected"},
                      "some-future-platform":{"state":"connected"}}}
        """));
  }

  // ── how the gateway is running ─────────────────────────────────────────────

  @Test
  void theRuntimeFieldsBesidePlatformsAreRead() {
    GatewayDto gateway = gateway(new FakeContainer().file(STATE, """
        {"gateway_state":"running","desired_state":"running","active_agents":3,
         "code_version":"0.20.5","session_store":{"status":"ok"},
         "platforms":{"slack":{"state":"connected"}}}
        """));

    assertEquals("running", gateway.state());
    assertEquals("running", gateway.desiredState());
    assertEquals(3, gateway.activeAgents());
    assertEquals("0.20.5", gateway.agentVersion());
    assertEquals("ok", gateway.sessionStore());
    assertFalse(gateway.paused());
  }

  @Test
  void aDrainingGatewayReportsAStateItsDesiredStateDisagreesWith() {
    GatewayDto gateway = gateway(new FakeContainer().file(STATE, """
        {"gateway_state":"draining","desired_state":"stopped","active_agents":1}
        """));

    assertEquals("draining", gateway.state());
    assertEquals("stopped", gateway.desiredState());
    assertEquals(1, gateway.activeAgents());
  }

  // ── the pause ──────────────────────────────────────────────────────────────

  @Test
  void theSentinelBodyCarriesTheReasonForThePause() {
    GatewayDto gateway = gateway(new FakeContainer()
        .file(STATE, "{\"gateway_state\":\"running\"}")
        .file(ESTOP, """
            {"engaged_at":"2026-08-25T19:27:56+00:00","reason":"rotating credentials"}
            """));

    assertTrue(gateway.paused());
    assertEquals("rotating credentials", gateway.pauseReason());
  }

  @Test
  void presenceAloneIsThePauseEvenWithNothingToReadInIt() {
    // hermes honours a bare `touch` of the sentinel, so a body that is empty, not JSON, or
    // JSON without the fields must still read as paused rather than as running
    for (String body : List.of("", "  ", "{not json", "{}")) {
      GatewayDto gateway = gateway(new FakeContainer()
          .file(STATE, "{\"gateway_state\":\"running\"}")
          .file(ESTOP, body));
      assertTrue(gateway.paused(), "expected paused for sentinel body: " + body);
      assertNull(gateway.pauseReason());
    }
  }

  @Test
  void aPauseIsReportedEvenWhenTheGatewayHasWrittenNoStateAtAll() {
    GatewayDto gateway = gateway(new FakeContainer().file(ESTOP, """
        {"engaged_at":"2026-08-25T19:27:56+00:00","reason":"suspected loop"}
        """));

    assertTrue(gateway.paused());
    assertEquals("suspected loop", gateway.pauseReason());
    assertEquals("", gateway.state());
  }

  @Test
  void aProfileWithNoSentinelIsNotPaused() {
    assertFalse(gateway(new FakeContainer().file(STATE, "{\"gateway_state\":\"running\"}")).paused());
  }

  // ── degradation ────────────────────────────────────────────────────────────

  @Test
  void everyUnreadableShapeYieldsNoIntegrationsRatherThanAnError() {
    // hermes owns this file's format; a change in it must not break the agents view
    assertEquals(Map.of(), statuses("{not json"));
    assertEquals(Map.of(), statuses("{}"));
    assertEquals(Map.of(), statuses("{\"platforms\":[]}"));
    assertEquals(Map.of(), statuses("{\"platforms\":{}}"));
    assertEquals(Map.of(), statuses("{\"platforms\":{\"\":{\"state\":\"connected\"}}}"));
  }

  @Test
  void anUnreadableGatewayStateStillYieldsAReadingRatherThanAnError() {
    GatewayDto gateway = gateway(new FakeContainer().file(STATE, "{not json"));

    assertEquals("", gateway.state());
    assertEquals(0, gateway.activeAgents());
    assertFalse(gateway.paused());
  }

  @Test
  void aProfileWithNoGatewayStateHasNoIntegrations() {
    assertEquals(List.of(), gatewayState(new FakeContainer()).integrations(HOST, CONTAINER, "ops"));
  }

  @Test
  void aPlatformEntryThatIsNotAMapIsStillReportedAsDown() {
    // hiding it would look like the platform is not configured
    assertEquals(Map.of("slack", "down"), statuses("{\"platforms\":{\"slack\":\"connected\"}}"));
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirectoryIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> gatewayState(new FakeContainer()).integrations(HOST, CONTAINER, "../../etc"));
  }
}
