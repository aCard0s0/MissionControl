package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gateway's own view of a profile: which platforms it has connected, and how it is
 * running.
 *
 * <p>Both come out of {@code gateway_state.json}, which the gateway rewrites as it goes.
 * This used to read one key out of it — {@code platforms} — and drop the rest on the floor,
 * including the two fields an operator most needs before touching a container:
 * {@code active_agents}, the count of turns in flight, and the {@code gateway_state} /
 * {@code desired_state} pair that differs while it drains. The version the agent is actually
 * running is in there too, which is not the same thing as the image tag it was deployed from.
 *
 * <p>The pause flag comes from a second file, the {@code ESTOP} sentinel that
 * {@code hermes pause} writes into the profile home. Presence alone means paused — hermes
 * honours a bare {@code touch} — so an unparseable body still reads as a pause, just without
 * a reason to show.
 *
 * <p>Hermes owns the format of both, so every failure mode here degrades to "nothing known"
 * rather than throwing: a shape change upstream must not take the agents view down with it.
 */
@Component
class HermesGatewayState {

  private static final Logger log = LoggerFactory.getLogger(HermesGatewayState.class);

  /** One read of the two files, for the caller that wants both halves. */
  record Reading(GatewayDto gateway, List<IntegrationDto> integrations) {}

  private final HermesContainerFiles files;
  private final ObjectMapper objectMapper;

  HermesGatewayState(HermesContainerFiles files, ObjectMapper objectMapper) {
    this.files = files;
    this.objectMapper = objectMapper;
  }

  Reading read(DockerHostRef host, String containerId, String profileName) {
    String dir = ProfilePaths.profileDir(profileName);
    JsonNode root = parse(files.readFile(host, containerId, dir + "/gateway_state.json"));
    String estopPath = ProfilePaths.estopFile(profileName);
    // presence is the pause; the body is only ever a reason to show, so it is not read
    // on the path where there is no pause to explain
    boolean paused = files.fileExists(host, containerId, estopPath);
    JsonNode estop = paused ? parse(files.readFile(host, containerId, estopPath)) : null;
    return new Reading(gateway(root, estop, paused), integrations(root));
  }

  List<IntegrationDto> integrations(DockerHostRef host, String containerId, String profileName) {
    return read(host, containerId, profileName).integrations();
  }

  private JsonNode parse(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      log.warn("could not read gateway state: {}", e.toString());
      return null;
    }
  }

  private static GatewayDto gateway(JsonNode root, JsonNode estop, boolean paused) {
    if (root == null) {
      return paused
          ? new GatewayDto("", "", 0, "", "", true, reason(estop), pausedAt(estop))
          : GatewayDto.unknown();
    }
    return new GatewayDto(
        root.path("gateway_state").asText(""),
        root.path("desired_state").asText(""),
        root.path("active_agents").asInt(0),
        root.path("code_version").asText(""),
        root.path("session_store").path("status").asText(""),
        paused,
        reason(estop),
        pausedAt(estop));
  }

  private static String reason(JsonNode estop) {
    return estop == null ? null : estop.path("reason").asText(null);
  }

  private static String pausedAt(JsonNode estop) {
    return estop == null ? null : estop.path("engaged_at").asText(null);
  }

  /**
   * Every platform the gateway lists, whatever it is called.
   *
   * <p>Deliberately not an allowlist. This filtered against a hardcoded set of ten kinds the
   * dashboard had a card for, which meant a profile talking over anything newer — iMessage,
   * Matrix, SimpleX, or the {@code api_server} entry a plain container reports — rendered as
   * "No integrations configured" while the gateway was connected and working. A kind with no
   * card is still worth a row: the operator can read the name.
   */
  private List<IntegrationDto> integrations(JsonNode root) {
    if (root == null || !root.path("platforms").isObject()) return List.of();
    List<IntegrationDto> result = new ArrayList<>();
    root.path("platforms").properties().forEach(entry -> {
      String kind = entry.getKey() == null ? "" : entry.getKey().trim();
      if (kind.isBlank()) return;
      JsonNode platform = entry.getValue();
      String state = platform.path("state").asText("");
      if (state.isBlank()) state = platform.path("status").asText("");
      String detail = state.isBlank() ? "gateway state unknown" : ("gateway " + state);
      result.add(new IntegrationDto(kind, mapStatus(state), detail));
    });
    return result;
  }

  private static String mapStatus(String state) {
    return switch (state == null ? "" : state.toLowerCase(Locale.ROOT)) {
      case "connected", "up", "ok" -> "up";
      case "degraded", "warning", "warn" -> "degraded";
      case "off", "disabled", "paused" -> "off";
      default -> "down";
    };
  }
}
