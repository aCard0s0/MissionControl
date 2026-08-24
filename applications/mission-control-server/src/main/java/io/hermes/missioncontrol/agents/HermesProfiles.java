package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.docker.ContainerNotRunningException;
import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.HermesModelConfig.ModelTarget;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * A Hermes container's agent profiles, as the dashboard sees them.
 *
 * <p>This class owns the profile lifecycle (create, clone, delete) and the read-back that
 * assembles an {@link AgentProfileDto}. Everything else a profile is made of belongs to a
 * collaborator, each of which owns one file or one concern inside the container:
 *
 * <ul>
 *   <li>{@link HermesContainerFiles} — the exec seam and every container read/write
 *   <li>{@link HermesEnvFile} — {@code .env}, where API keys live
 *   <li>{@link HermesModelConfig} — the {@code model.*} / {@code auxiliary.*} round trip
 *   <li>{@link HermesSkills} — SKILL.md files and the enable/disable list
 *   <li>{@link HermesProfileMcp} — the {@code mcp_servers} block and its probe cache
 *   <li>{@link HermesSessions} — the conversation store in {@code state.db}
 *   <li>{@link HermesGatewayLogs} — the per-profile s6 gateway log
 *   <li>{@link HermesIntegrations} — the platforms in {@code gateway_state.json}
 *   <li>{@link ProfileInventory} — which profiles the container has at all
 * </ul>
 *
 * <p>The mutating endpoints all return the whole profile, so each one reads as
 * "delegate the edit, then re-read" — that re-read is why this facade exists rather
 * than the controller wiring the collaborators up itself.
 */
@Service
public class HermesProfiles {

  private final HermesContainerFiles files;
  private final HermesEnvFile env;
  private final HermesModelConfig modelConfig;
  private final HermesSkills skills;
  private final HermesProfileMcp mcp;
  private final HermesSessions sessions;
  private final HermesGatewayLogs gatewayLogs;
  private final HermesIntegrations integrations;
  private final ProfileInventory inventory;

  public HermesProfiles(
      HermesContainerFiles files,
      HermesEnvFile env,
      HermesModelConfig modelConfig,
      HermesSkills skills,
      HermesProfileMcp mcp,
      HermesSessions sessions,
      HermesGatewayLogs gatewayLogs,
      HermesIntegrations integrations,
      ProfileInventory inventory) {
    this.files = files;
    this.env = env;
    this.modelConfig = modelConfig;
    this.skills = skills;
    this.mcp = mcp;
    this.sessions = sessions;
    this.gatewayLogs = gatewayLogs;
    this.integrations = integrations;
    this.inventory = inventory;
  }

  // ── inventory ──────────────────────────────────────────────────────────────

  public List<AgentProfileDto> list(DockerHostRef host, String containerId) {
    try {
      List<AgentProfileDto> profiles = new ArrayList<>();
      for (String name : inventory.names(host, containerId)) {
        profiles.add(readProfile(host, containerId, name));
      }
      return profiles;
    } catch (ContainerNotRunningException stopped) {
      // A stale dashboard client asking to exec inside a stopped container. Inventory is
      // simply unavailable until it restarts.
      return List.of();
    }
  }

  /** Reads a single profile's current state (config, soul, memory, skills, mcp). */
  public AgentProfileDto get(DockerHostRef host, String containerId, String name) {
    return readProfile(host, containerId, name);
  }

  private AgentProfileDto readProfile(DockerHostRef host, String containerId, String name) {
    String dir = ProfilePaths.profileDir(name);
    String configYaml = files.readFile(host, containerId, dir + "/config.yaml");
    String soul = files.readFile(host, containerId, dir + "/SOUL.md");
    String memoryMd = files.readFile(host, containerId, dir + "/MEMORY.md");
    String envFile = files.readFile(host, containerId, dir + "/.env");
    Map<?, ?> configMap = YamlValues.parseMap(configYaml);
    ConfigInfo config = modelConfig.parseConfig(configMap);
    List<SkillDto> skillList = skills.list(host, containerId, name, configMap);
    List<AgentMcpServerDto> mcpList = mcp.list(host, containerId, name, configMap);
    List<IntegrationDto> integrationList = integrations.list(host, containerId, name);
    return new AgentProfileDto(
        ProfilePaths.profileId(containerId, name),
        containerId,
        name,
        "default".equals(name) ? "Default profile" : "Profile",
        "idle",
        config.provider(),
        config.model(),
        HermesEnvFile.maskApiKey(envFile, config.provider()),
        config.cwd().isBlank() ? ProfilePaths.HERMES_HOME : config.cwd(),
        soul,
        memoryMd,
        configYaml,
        skillList,
        mcpList,
        integrationList,
        System.currentTimeMillis());
  }

  // ── lifecycle ──────────────────────────────────────────────────────────────

  public AgentProfileDto create(DockerHostRef host, ProfileSpec spec) {
    String profileName = createProfileBare(host, spec);
    return readProfile(host, spec.containerId(), profileName);
  }

  /** Creates and configures the profile but skips the read-back. The template
   *  create/deploy flow re-reads the profile after layering its blueprint, so the
   *  read here would be thrown away — callers that need the DTO use {@link #create}.
   *  Returns the created profile name. */
  public String createProfileBare(DockerHostRef host, ProfileSpec spec) {
    String profileName = spec.name();
    String containerId = spec.containerId();
    List<String> command = new ArrayList<>(List.of("hermes", "profile", "create", profileName));
    String cloneFrom = spec.cloneFrom();
    if (cloneFrom != null) {
      command.addAll(List.of("--clone", "--clone-from", cloneFrom));
    }
    boolean created = false;
    try {
      files.exec(host, containerId, command);
      created = true;
      ModelTarget auxiliary = HermesModelConfig.auxiliaryTarget(
          spec.provider(), spec.model(), spec.baseUrl(), spec.auxiliary());
      modelConfig.write(host, containerId, profileName,
          spec.provider(), spec.model(), spec.baseUrl(), auxiliary);
      modelConfig.assertConfigured(host, containerId, profileName);
      env.seedIfMissing(host, containerId, profileName);
      modelConfig.writeApiKey(host, containerId, profileName, spec.provider(), spec.apiKey());
      modelConfig.writeAuxiliaryApiKey(host, containerId, profileName, auxiliary, spec.auxiliary());
      return profileName;
    } catch (RuntimeException failure) {
      if (created) {
        try {
          delete(host, containerId, profileName);
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
  }

  /**
   * Removes the profile from the container. Idempotent: a profile that is already gone is not
   * asked to be deleted again.
   *
   * <p>The guard is what makes retrying a delete useful. {@code hermes profile delete} exits
   * non-zero on a name it does not know, so without it a delete whose dashboard-side cleanup
   * failed could never be retried — the retry died here, before reaching the cleanup that was
   * the only thing left to do.
   */
  public void delete(DockerHostRef host, String containerId, String name) {
    if (files.dirExists(host, containerId, ProfilePaths.profileDir(name))) {
      files.exec(host, containerId, List.of("hermes", "profile", "delete", name, "--yes"));
    }
    mcp.evictProfile(host, containerId, name);
  }

  // ── documents ──────────────────────────────────────────────────────────────

  public void updateSoul(DockerHostRef host, String containerId, String name, String soul) {
    writeProfileFile(host, containerId, name, "SOUL.md", soul);
  }

  public void updateMemory(DockerHostRef host, String containerId, String name, String memory) {
    writeProfileFile(host, containerId, name, "MEMORY.md", memory);
  }

  private void writeProfileFile(
      DockerHostRef host, String containerId, String name, String fileName, String content) {
    String path = files.requireProfileDir(host, containerId, name) + "/" + fileName;
    files.writeFile(host, containerId, path, content == null ? "" : content);
  }

  public AgentProfileDto updateConfig(DockerHostRef host, String containerId, String name, String configYaml) {
    YamlValues.requireMapping(configYaml, "config.yaml must be a YAML mapping");
    writeProfileFile(host, containerId, name, "config.yaml", configYaml);
    return readProfile(host, containerId, name);
  }

  // ── skills ─────────────────────────────────────────────────────────────────

  public AgentProfileDto setSkillEnabled(
      DockerHostRef host, String containerId, String profileName, String skillName, boolean enabled) {
    skills.setEnabled(host, containerId, profileName, skillName, enabled);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto installSkill(
      DockerHostRef host, String containerId, String profileName, String skillId) {
    skills.install(host, containerId, profileName, skillId);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto uninstallSkill(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    skills.uninstall(host, containerId, profileName, skillName);
    return readProfile(host, containerId, profileName);
  }

  public SkillContentDto readSkillContent(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    return skills.readContent(host, containerId, profileName, skillName);
  }

  /** Overwrites a skill's SKILL.md, then re-reads the profile so the refreshed
   *  name/version/description/source flow back to the caller. */
  public AgentProfileDto updateSkillContent(
      DockerHostRef host, String containerId, String profileName, String skillName, String body) {
    skills.updateContent(host, containerId, profileName, skillName, body);
    return readProfile(host, containerId, profileName);
  }

  // ── MCP servers ────────────────────────────────────────────────────────────

  public AgentProfileDto addMcpServer(
      DockerHostRef host, String containerId, String profileName, McpServerDefinition definition) {
    mcp.add(host, containerId, profileName, definition);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto updateMcpServer(
      DockerHostRef host, String containerId, String profileName, String serverName,
      McpServerDefinition definition) {
    mcp.update(host, containerId, profileName, serverName, definition);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto setMcpServerEnabled(
      DockerHostRef host, String containerId, String profileName, String serverName, boolean enabled) {
    mcp.setEnabled(host, containerId, profileName, serverName, enabled);
    return readProfile(host, containerId, profileName);
  }

  public AgentProfileDto removeMcpServer(
      DockerHostRef host, String containerId, String profileName, String serverName) {
    mcp.remove(host, containerId, profileName, serverName);
    return readProfile(host, containerId, profileName);
  }

  /** Probes a single MCP server with Hermes' own MCP initialize handshake. */
  public McpTestResult testMcpServer(
      DockerHostRef host, String containerId, String profileName, String serverName) {
    return mcp.test(host, containerId, profileName, serverName);
  }

  // ── observability ──────────────────────────────────────────────────────────

  public List<IntegrationDto> integrations(DockerHostRef host, String containerId, String profileName) {
    return integrations.list(host, containerId, profileName);
  }

  /** Reads the profile-specific s6 gateway log, including rotated files, rather
   * than reusing Docker's container-wide stdout/stderr stream. */
  public List<LogLineDto> logs(DockerHostRef host, String containerId, String profileName, int tail) {
    return gatewayLogs.read(host, containerId, profileName, tail);
  }

  // ── sessions ───────────────────────────────────────────────────────────────

  public List<SessionDto> listSessions(DockerHostRef host, String containerId, String profileName) {
    return sessions.list(host, containerId, profileName);
  }

  /** Returns the chat history (messages) for a session as a JSON array string. */
  public String readSessionMessages(
      DockerHostRef host, String containerId, String profileName, String sessionId) {
    return sessions.readMessages(host, containerId, profileName, sessionId);
  }

  public void deleteSession(
      DockerHostRef host, String containerId, String profileName, String sessionId) {
    sessions.delete(host, containerId, profileName, sessionId);
  }
}
