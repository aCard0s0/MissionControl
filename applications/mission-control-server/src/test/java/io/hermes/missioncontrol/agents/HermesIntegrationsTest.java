package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reading the gateway's own view of a profile's platforms.
 *
 * <p>{@code gateway_state.json} is written by hermes, not by Mission Control, so it is the
 * one input here that can change shape without this code changing. Every failure mode
 * therefore has to degrade to "no integrations" rather than throw — and the status mapping
 * has to collapse the gateway's vocabulary onto the four the dashboard renders, defaulting
 * to {@code down} so an unrecognised state never reads as healthy.
 */
class HermesIntegrationsTest {

  private static final String STATE = "/opt/data/profiles/ops/gateway_state.json";

  private HermesIntegrations integrations(FakeContainer container) {
    return new HermesIntegrations(container.files(), new ObjectMapper());
  }

  private Map<String, String> statuses(String json) {
    FakeContainer container = new FakeContainer().file(STATE, json);
    return integrations(container).list(HOST, CONTAINER, "ops").stream()
        .collect(Collectors.toMap(IntegrationDto::kind, IntegrationDto::status));
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

    List<IntegrationDto> listed = integrations(container).list(HOST, CONTAINER, "ops");

    assertEquals("gateway reticulating", listed.getFirst().detail());
    assertEquals("gateway state unknown", listed.get(1).detail());
  }

  // ── filtering ──────────────────────────────────────────────────────────────

  @Test
  void onlyPlatformsTheDashboardHasACardForAreReported() {
    assertEquals(Map.of("slack", "up", "github", "up"), statuses("""
        {"platforms":{"slack":{"state":"connected"},"github":{"state":"connected"},
                      "some-future-platform":{"state":"connected"}}}
        """));
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
  void aProfileWithNoGatewayStateHasNoIntegrations() {
    assertEquals(List.of(), integrations(new FakeContainer()).list(HOST, CONTAINER, "ops"));
  }

  @Test
  void aPlatformEntryThatIsNotAMapIsStillReportedAsDown() {
    // the kind is known, so hiding it would look like the platform is not configured
    assertEquals(Map.of("slack", "down"), statuses("{\"platforms\":{\"slack\":\"connected\"}}"));
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirectoryIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> integrations(new FakeContainer()).list(HOST, CONTAINER, "../../etc"));
  }
}
