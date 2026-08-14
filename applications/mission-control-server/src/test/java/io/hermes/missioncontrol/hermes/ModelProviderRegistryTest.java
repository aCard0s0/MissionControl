package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.hermes.ModelProviderRegistry.Provider;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The provider table's own invariants. It is a static list, so the risk is not that a
 * method breaks but that a row is added inconsistently with the other places that key off
 * it — the API-key env slot, and the catalog service's provider switch.
 */
class ModelProviderRegistryTest {

  /** Mirrors ModelCatalogService's provider switch — the set it can serve a catalog for. */
  private static final Set<String> PROVIDERS_WITH_A_CURATED_CATALOG =
      Set.of("anthropic", "openai", "nous", "openrouter");

  @Test
  void everyProviderKeyIsUniqueAndLowercase() {
    Set<String> seen = new HashSet<>();
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      assertTrue(seen.add(p.key()), "duplicate provider key: " + p.key());
      assertEquals(p.key().toLowerCase(Locale.ROOT), p.key(), "provider key must be lowercase");
      assertFalse(p.label() == null || p.label().isBlank(), "provider " + p.key() + " has no label");
    }
  }

  @Test
  void everyEnvVarIsUniqueSoNoTwoProvidersFightOverOneEnvSlot() {
    Set<String> seen = new HashSet<>();
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      if (p.envVar() == null) continue;
      // two providers sharing an env var would silently overwrite each other's key in
      // the agent's .env
      assertTrue(seen.add(p.envVar()), "duplicate env var: " + p.envVar());
    }
  }

  @Test
  void needsKeyIsTrueForExactlyTheKeyBasedNonOauthProviders() {
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      boolean expected = p.envVar() != null && !p.oauth();
      assertEquals(expected, p.needsKey(), "needsKey wrong for " + p.key());
      if (p.oauth()) {
        // OAuth providers authenticate out-of-band, so the UI must not ask for a key
        assertNull(p.envVar(), "oauth provider " + p.key() + " also declares an env var");
        assertFalse(p.needsKey());
      }
    }
  }

  @Test
  void byKeyIsCaseAndWhitespaceInsensitiveAndNullSafe() {
    assertEquals("anthropic", ModelProviderRegistry.byKey("  ANTHROPIC  ").key());
    assertEquals("ANTHROPIC_API_KEY", ModelProviderRegistry.envVar(" Anthropic "));

    assertNull(ModelProviderRegistry.byKey(null));
    assertNull(ModelProviderRegistry.byKey("mystery"));
    assertNull(ModelProviderRegistry.envVar("mystery"));
    // an OAuth provider resolves but has no env var
    assertNotNull(ModelProviderRegistry.byKey("nous"));
    assertNull(ModelProviderRegistry.envVar("nous"));
  }

  @Test
  void nousIsFirstBecauseThePickerDefaultsToTheTopEntry() {
    assertEquals("nous", ModelProviderRegistry.PROVIDERS.getFirst().key());
  }

  @Test
  void everyProviderClaimingACatalogIsOneTheCatalogServiceCanActuallyServe() {
    // hasCatalog drives the UI into GET /api/models/{provider}. A provider marked with a
    // catalog that the service has no curated list for answers 404 and breaks the picker.
    for (Provider p : ModelProviderRegistry.PROVIDERS) {
      assertEquals(
          PROVIDERS_WITH_A_CURATED_CATALOG.contains(p.key()),
          p.hasCatalog(),
          "hasCatalog for " + p.key() + " disagrees with ModelCatalogService's provider switch");
    }
  }
}
