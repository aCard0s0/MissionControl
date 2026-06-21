package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.hermes.HermesProfiles.ConfigInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the model-config write planner and the read-back parser —
 * the two seams that carry the OpenRouter-namespace and clone-clearing
 * invariants (both were live-only bugs once, so they get unit coverage here).
 */
class HermesProfilesTest {

  // construction touches no Docker client, so null is safe for the pure methods
  private final HermesProfiles profiles = new HermesProfiles(null, new ObjectMapper());

  private static Map<String, String> entries(String provider, String model, String baseUrl) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String[] kv : HermesProfiles.modelConfigEntries(provider, model, baseUrl)) {
      out.put(kv[0], kv[1]);
    }
    return out;
  }

  // ── write planner: modelConfigEntries ──────────────────────────────────────

  @Test
  void standardProviderSetsProviderAndNoBaseUrl() {
    Map<String, String> e = entries("nous", "Hermes-4-405B", null);
    assertEquals("Hermes-4-405B", e.get("model.default"));
    assertEquals("nous", e.get("model.provider"));
    assertEquals(false, e.containsKey("model.base_url"), "standard provider writes no base_url");
  }

  @Test
  void openrouterModelIdKeptVerbatimNotConcatenated() {
    Map<String, String> e = entries("openrouter", "anthropic/claude-sonnet-4", null);
    // the slashed id is written as-is to model.default — never provider/model
    assertEquals("anthropic/claude-sonnet-4", e.get("model.default"));
    assertEquals("openrouter", e.get("model.provider"));
  }

  @Test
  void customEndpointSetsBaseUrlAndNoProvider() {
    Map<String, String> e = entries("ollama", "qwen3:8b", "http://host.docker.internal:11434/v1");
    assertEquals("qwen3:8b", e.get("model.default"));
    assertEquals("http://host.docker.internal:11434/v1", e.get("model.base_url"));
    assertEquals(false, e.containsKey("model.provider"), "custom endpoint writes no provider");
  }

  @Test
  void blankOrAutoProviderWritesNeitherRoutingKey() {
    for (String p : new String[] {null, "", "auto"}) {
      Map<String, String> e = entries(p, "some-model", null);
      assertEquals(false, e.containsKey("model.provider"), "provider=" + p);
      assertEquals(false, e.containsKey("model.base_url"), "provider=" + p);
    }
  }

  @Test
  void everyWriteWipesModelFirstSoCloneCannotLeak() {
    // the clone guard: the FIRST set always resets model to an empty scalar,
    // so no stale key (provider, base_url, api_mode, …) can survive a --clone
    for (String[] c : new String[][] {
        {"nous", "Hermes-4-405B", null},
        {"openrouter", "anthropic/claude-sonnet-4", null},
        {"ollama", "qwen3:8b", "http://x/v1"},
        {"auto", "m", null}}) {
      List<String[]> plan = HermesProfiles.modelConfigEntries(c[0], c[1], c[2]);
      assertEquals("model", plan.get(0)[0], "first set must wipe the model map");
      assertEquals("", plan.get(0)[1]);
      assertEquals("model.default", plan.get(1)[0], "default written right after the wipe");
    }
  }

  // ── read-back parser: parseConfig ──────────────────────────────────────────

  @Test
  void structuredMapWithProviderKeepsNamespacedModel() {
    ConfigInfo info = profiles.parseConfig(Map.of(
        "model", Map.of("provider", "openrouter", "default", "anthropic/claude-sonnet-4")));
    assertEquals("openrouter", info.provider());
    // must NOT be truncated to "claude-sonnet-4"
    assertEquals("anthropic/claude-sonnet-4", info.model());
  }

  @Test
  void structuredNousRoundTrips() {
    ConfigInfo info = profiles.parseConfig(Map.of(
        "model", Map.of("provider", "nous", "default", "Hermes-4-405B", "base_url", "")));
    assertEquals("nous", info.provider());
    assertEquals("Hermes-4-405B", info.model());
  }

  @Test
  void customEndpointMapReadsBlankProviderAsAuto() {
    ConfigInfo info = profiles.parseConfig(Map.of(
        "model", Map.of("provider", "", "default", "qwen3:8b", "base_url", "http://x/v1")));
    assertEquals("auto", info.provider());
    assertEquals("qwen3:8b", info.model());
  }

  @Test
  void legacyScalarModelStringStillSplits() {
    ConfigInfo info = profiles.parseConfig(Map.of("model", "anthropic/claude-opus-4-8"));
    assertEquals("anthropic", info.provider());
    assertEquals("claude-opus-4-8", info.model());
  }

  @Test
  void emptyConfigDefaults() {
    ConfigInfo info = profiles.parseConfig(Map.of());
    assertEquals("auto", info.provider());
    assertEquals("", info.model());
  }

  @Test
  void terminalCwdIsRead() {
    ConfigInfo info = profiles.parseConfig(new LinkedHashMap<>(Map.of(
        "model", "nous/Hermes-4-405B",
        "terminal", Map.of("cwd", "/work"))));
    assertEquals("/work", info.cwd());
  }

  @Test
  void roundTripWritePlanThenParseIsStableForOpenrouter() {
    // simulate: write plan -> resulting model map -> parse back
    Map<String, String> plan = entries("openrouter", "anthropic/claude-sonnet-4", null);
    Map<String, Object> modelMap = new LinkedHashMap<>();
    modelMap.put("provider", plan.get("model.provider"));
    modelMap.put("default", plan.get("model.default"));
    modelMap.put("base_url", plan.get("model.base_url"));
    ConfigInfo info = profiles.parseConfig(Map.of("model", modelMap));
    assertEquals("openrouter", info.provider());
    assertEquals("anthropic/claude-sonnet-4", info.model());
  }

  @Test
  void planWipesThenSetsDefault() {
    List<String[]> plan = HermesProfiles.modelConfigEntries("nous", "Hermes-4-405B", null);
    assertEquals("model", plan.get(0)[0], "wipe first");
    assertEquals("", plan.get(0)[1]);
    assertEquals("model.default", plan.get(1)[0], "then promote back to a map with the default");
  }
}
