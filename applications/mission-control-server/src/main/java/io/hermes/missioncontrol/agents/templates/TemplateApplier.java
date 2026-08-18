package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.secrets.StoredSecret;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writing a template's contents — soul, memory, skills, MCP entries, secrets — onto a live
 * agent profile.
 *
 * <p>Split out of {@link ProfileTemplateService} because this is the only part that reaches a
 * container, and because it carries a rule that is easy to get wrong: a half-applied template
 * leaves a misconfigured profile, so the profile is rolled back <em>only</em> when this code
 * created it. {@link #deployNew} owns the profile and is all-or-nothing;
 * {@link #layerOnto} writes into a profile someone else owns and only surfaces the error.
 */
class TemplateApplier {

  private static final Logger log = LoggerFactory.getLogger(TemplateApplier.class);

  private final HermesProfiles profiles;
  private final HermesSetup setup;
  private final TemplateSecrets secrets;

  TemplateApplier(HermesProfiles profiles, HermesSetup setup, TemplateSecrets secrets) {
    this.profiles = profiles;
    this.setup = setup;
    this.secrets = secrets;
  }

  /** Creates the profile from the template's own model settings, then applies it. All or
   *  nothing: a failure anywhere drops the profile this call created. */
  AgentProfileDto deployNew(ProfileTemplate template, String url, String containerId, String name) {
    CreateAgentRequest request = new CreateAgentRequest(
        null, containerId, name,
        blankTo(template.provider(), "nous"),
        blankTo(template.model(), "Hermes-4-405B"),
        null, null, blankToNull(template.baseUrl()), null, null);
    profiles.create(url, request);
    try {
      return apply(template, url, containerId, name);
    } catch (RuntimeException failure) {
      rollback(url, containerId, name, failure);
      throw failure;
    }
  }

  /** Applies the template onto a profile the caller already owns. The profile is left in
   *  place on failure — dropping someone else's agent is not this code's call. */
  AgentProfileDto layerOnto(
      ProfileTemplate template, String url, String containerId, String name) {
    return apply(template, url, containerId, name);
  }

  /** Best-effort cleanup of a profile the caller created and could not finish configuring. */
  void rollback(String url, String containerId, String name, RuntimeException failure) {
    try {
      profiles.delete(url, containerId, name);
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
      log.warn("rollback of partially-applied profile '{}' failed: {}", name, cleanup.getMessage());
    }
  }

  private AgentProfileDto apply(
      ProfileTemplate template, String url, String containerId, String name) {
    if (!template.soul().isBlank()) {
      profiles.updateSoul(url, containerId, name, template.soul());
    }
    if (!template.memory().isBlank()) {
      profiles.updateMemory(url, containerId, name, template.memory());
    }
    for (String skill : template.skills()) {
      if (skill != null && !skill.isBlank()) {
        profiles.installSkill(url, containerId, name, skill.trim());
      }
    }
    for (McpServerSpec server : template.mcpServers()) {
      if (server == null || server.name() == null || server.name().isBlank()) continue;
      profiles.addMcpServer(url, containerId, name, mcpRequest(server));
    }
    List<EnvEntry> env = environment(template);
    if (!env.isEmpty()) {
      setup.putEnv(url, containerId, name, env);
    }
    return profiles.get(url, containerId, name);
  }

  /** A snapshot's stdio environment applies only to a stdio server — sending it with an
   *  HTTP entry would write credentials the transport never reads. */
  private AddMcpServerRequest mcpRequest(McpServerSpec server) {
    Map<String, String> headers = secrets.decryptValues(server.headers());
    Map<String, String> environment = "stdio".equalsIgnoreCase(server.transport())
        ? secrets.decryptValues(server.environment()) : Map.of();
    return new AddMcpServerRequest(
        server.name(), server.transport(), server.url(), server.command(), server.args(),
        server.enabled(), headers, environment);
  }

  /** The template's secrets that still decrypt. A capture-only placeholder holds no value,
   *  and an unrecoverable one yields null — neither is written as an empty variable. */
  private List<EnvEntry> environment(ProfileTemplate template) {
    List<EnvEntry> env = new ArrayList<>();
    for (StoredSecret stored : template.secrets()) {
      String value = secrets.decryptOrNull(stored.enc());
      if (value != null && !value.isBlank()) {
        env.add(new EnvEntry(stored.key(), value));
      }
    }
    return env;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
