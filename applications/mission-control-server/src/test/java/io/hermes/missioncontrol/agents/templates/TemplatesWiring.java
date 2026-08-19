package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.secrets.SecretCipher;

/**
 * Builds the template collaborator graph the way Spring does, for tests that drive a whole flow
 * through {@link ProfileTemplateService}.
 *
 * <p>The same role {@code AgentsWiring} and {@code McpWiring} play in their packages. Its
 * existence is why {@link ProfileTemplateService} no longer needs a second constructor that
 * passed {@code null} for the MCP registry: a test that does not reach the registry passes null
 * here instead, where it stays a statement about that test rather than a shape production code
 * has to support — and {@link TemplateMcpSnapshots} no longer needs the null check that reported
 * it to an operator as a 503.
 *
 * <p>A null collaborator is deliberate where a test asserts a path never reaches it: a mock
 * would silently no-op, while null fails loudly.
 */
final class TemplatesWiring {

  private TemplatesWiring() {}

  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup,
      McpRegistryService registry) {
    TemplateSecrets secrets = new TemplateSecrets(cipher);
    return new ProfileTemplateService(
        repository,
        secrets,
        new TemplateApplier(profiles, setup, secrets),
        new TemplateMcpSnapshots(registry, secrets),
        profiles,
        setup);
  }

  /** For the flows that never reach the catalog. */
  static ProfileTemplateService service(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup) {
    return service(repository, cipher, profiles, setup, null);
  }
}
