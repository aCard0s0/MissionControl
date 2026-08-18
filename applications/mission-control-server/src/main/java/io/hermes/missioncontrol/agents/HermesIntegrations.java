package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The messaging/tool platforms a profile's gateway reports in {@code gateway_state.json}.
 *
 * <p>Split out of {@link HermesProfiles} because it reads a file no other concern touches
 * and translates the gateway's own vocabulary of states into the four the dashboard shows.
 */
@Component
class HermesIntegrations {

  private static final Logger log = LoggerFactory.getLogger(HermesIntegrations.class);

  /** Kinds the dashboard has a card for; anything else in the gateway state is ignored. */
  private static final Set<String> KNOWN_KINDS = Set.of(
      "slack", "whatsapp", "discord", "telegram", "signal", "email",
      "github", "filesystem", "browser", "database");

  private final HermesContainerFiles files;
  private final ObjectMapper objectMapper;

  HermesIntegrations(HermesContainerFiles files, ObjectMapper objectMapper) {
    this.files = files;
    this.objectMapper = objectMapper;
  }

  List<IntegrationDto> list(String url, String containerId, String profileName) {
    String json = files.readFile(
        url, containerId, ProfilePaths.profileDir(profileName) + "/gateway_state.json");
    if (json == null || json.isBlank()) return List.of();
    try {
      Map<?, ?> root = objectMapper.readValue(json, Map.class);
      if (!(root.get("platforms") instanceof Map<?, ?> platformsMap)) return List.of();
      List<IntegrationDto> result = new ArrayList<>();
      for (Map.Entry<?, ?> e : platformsMap.entrySet()) {
        String kind = YamlValues.stringValue(e.getKey());
        if (kind.isBlank() || !KNOWN_KINDS.contains(kind)) continue;
        String state = "";
        if (e.getValue() instanceof Map<?, ?> p) {
          state = YamlValues.stringValue(p.get("state"));
          if (state.isBlank()) state = YamlValues.stringValue(p.get("status"));
        }
        String detail = state.isBlank() ? "gateway state unknown" : ("gateway " + state);
        result.add(new IntegrationDto(kind, mapStatus(state), detail));
      }
      return result;
    } catch (Exception e) {
      log.warn("could not read gateway integrations: {}", e.toString());
      return List.of();
    }
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
