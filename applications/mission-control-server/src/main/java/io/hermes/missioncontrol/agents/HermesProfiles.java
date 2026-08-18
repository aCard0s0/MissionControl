package io.hermes.missioncontrol.agents;

import com.github.dockerjava.api.exception.ConflictException;
import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.HermesModelConfig.ModelTarget;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
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

  public HermesProfiles(
      HermesContainerFiles files,
      HermesEnvFile env,
      HermesModelConfig modelConfig,
      HermesSkills skills,
      HermesProfileMcp mcp,
      HermesSessions sessions,
      HermesGatewayLogs gatewayLogs,
      HermesIntegrations integrations) {
    this.files = files;
    this.env = env;
    this.modelConfig = modelConfig;
    this.skills = skills;
    this.mcp = mcp;
    this.sessions = sessions;
    this.gatewayLogs = gatewayLogs;
    this.integrations = integrations;
  }

  // ── inventory ──────────────────────────────────────────────────────────────

  public List<AgentProfileDto> list(String url, String containerId) {
    try {
      List<AgentProfileDto> profiles = new ArrayList<>();
      for (String name : listProfileNames(url, containerId)) {
        profiles.add(readProfile(url, containerId, name));
      }
      return profiles;
    } catch (ConflictException stopped) {
      // Docker returns 409 when a stale dashboard client asks to exec inside a
      // stopped container. Inventory is simply unavailable until it restarts.
      return List.of();
    }
  }

  /** Reads a single profile's current state (config, soul, memory, skills, mcp). */
  public AgentProfileDto get(String url, String containerId, String name) {
    return readProfile(url, containerId, name);
  }

  private List<String> listProfileNames(String url, String containerId) {
    List<String> names = new ArrayList<>();
    if (files.dirExists(url, containerId, ProfilePaths.HERMES_HOME)) {
      names.add("default");
    }
    var ls = files.exec(url, containerId, List.of(
        "sh", "-lc", "ls -1 " + ProfilePaths.PROFILES_DIR + " 2>/dev/null || true"));
    for (String name : HermesContainerFiles.lines(ls.stdout())) {
      if ("default".equals(name)) continue;
      if (ProfilePaths.isValidName(name)) names.add(name);
    }
    return names;
  }

  private AgentProfileDto readProfile(String url, String containerId, String name) {
    String dir = ProfilePaths.profileDir(name);
    String configYaml = files.readFile(url, containerId, dir + "/config.yaml");
    String soul = files.readFile(url, containerId, dir + "/SOUL.md");
    String memoryMd = files.readFile(url, containerId, dir + "/MEMORY.md");
    String envFile = files.readFile(url, containerId, dir + "/.env");
    Map<?, ?> configMap = YamlValues.parseMap(configYaml);
    ConfigInfo config = modelConfig.parseConfig(configMap);
    List<SkillDto> skillList = skills.list(url, containerId, name, configMap);
    List<AgentMcpServerDto> mcpList = mcp.list(url, containerId, name, configMap);
    List<IntegrationDto> integrationList = integrations.list(url, containerId, name);
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

  public AgentProfileDto create(String url, CreateAgentRequest request) {
    String profileName = createProfileBare(url, request);
    return readProfile(url, request.containerId(), profileName);
  }

  /** Creates and configures the profile but skips the read-back. The template
   *  create/deploy flow re-reads the profile after layering its blueprint, so the
   *  read here would be thrown away — callers that need the DTO use {@link #create}.
   *  Returns the created profile name. */
  public String createProfileBare(String url, CreateAgentRequest request) {
    String profileName = request.name();
    if (!ProfilePaths.isValidName(profileName)) {
      throw new IllegalArgumentException("invalid profile name");
    }
    String containerId = request.containerId();
    List<String> command = new ArrayList<>(List.of("hermes", "profile", "create", profileName));
    String cloneFrom = request.cloneFrom();
    if (cloneFrom != null && !cloneFrom.isBlank()) {
      command.addAll(List.of("--clone", "--clone-from", cloneFrom));
    }
    boolean created = false;
    try {
      files.exec(url, containerId, command);
      created = true;
      ModelTarget auxiliary = HermesModelConfig.auxiliaryTarget(
          request.provider(), request.model(), request.baseUrl(), request.auxiliary());
      modelConfig.write(url, containerId, profileName,
          request.provider(), request.model(), request.baseUrl(), auxiliary);
      modelConfig.assertConfigured(url, containerId, profileName);
      env.seedIfMissing(url, containerId, profileName);
      modelConfig.writeApiKey(url, containerId, profileName, request.provider(), request.apiKey());
      modelConfig.writeAuxiliaryApiKey(url, containerId, profileName, auxiliary, request.auxiliary());
      return profileName;
    } catch (RuntimeException failure) {
      if (created) {
        try {
          delete(url, containerId, profileName);
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
  }

  public void delete(String url, String containerId, String name) {
    files.exec(url, containerId, List.of("hermes", "profile", "delete", name, "--yes"));
  }

  // ── documents ──────────────────────────────────────────────────────────────

  public void updateSoul(String url, String containerId, String name, String soul) {
    writeProfileFile(url, containerId, name, "SOUL.md", soul);
  }

  public void updateMemory(String url, String containerId, String name, String memory) {
    writeProfileFile(url, containerId, name, "MEMORY.md", memory);
  }

  private void writeProfileFile(
      String url, String containerId, String name, String fileName, String content) {
    String path = files.requireProfileDir(url, containerId, name) + "/" + fileName;
    files.writeFile(url, containerId, path, content == null ? "" : content);
  }

  public AgentProfileDto updateConfig(String url, String containerId, String name, String configYaml) {
    YamlValues.requireMapping(configYaml, "config.yaml must be a YAML mapping");
    writeProfileFile(url, containerId, name, "config.yaml", configYaml);
    return readProfile(url, containerId, name);
  }

  // ── skills ─────────────────────────────────────────────────────────────────

  public AgentProfileDto setSkillEnabled(
      String url, String containerId, String profileName, String skillName, boolean enabled) {
    skills.setEnabled(url, containerId, profileName, skillName, enabled);
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto installSkill(
      String url, String containerId, String profileName, String skillId) {
    skills.install(url, containerId, profileName, skillId);
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto uninstallSkill(
      String url, String containerId, String profileName, String skillName) {
    skills.uninstall(url, containerId, profileName, skillName);
    return readProfile(url, containerId, profileName);
  }

  public SkillContentDto readSkillContent(
      String url, String containerId, String profileName, String skillName) {
    return skills.readContent(url, containerId, profileName, skillName);
  }

  /** Overwrites a skill's SKILL.md, then re-reads the profile so the refreshed
   *  name/version/description/source flow back to the caller. */
  public AgentProfileDto updateSkillContent(
      String url, String containerId, String profileName, String skillName, String body) {
    skills.updateContent(url, containerId, profileName, skillName, body);
    return readProfile(url, containerId, profileName);
  }

  // ── MCP servers ────────────────────────────────────────────────────────────

  public AgentProfileDto addMcpServer(
      String url, String containerId, String profileName, AddMcpServerRequest request) {
    mcp.add(url, containerId, profileName, request);
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto updateMcpServer(
      String url, String containerId, String profileName, String serverName,
      AddMcpServerRequest request) {
    mcp.update(url, containerId, profileName, serverName, request);
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto setMcpServerEnabled(
      String url, String containerId, String profileName, String serverName, boolean enabled) {
    mcp.setEnabled(url, containerId, profileName, serverName, enabled);
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto removeMcpServer(
      String url, String containerId, String profileName, String serverName) {
    mcp.remove(url, containerId, profileName, serverName);
    return readProfile(url, containerId, profileName);
  }

  /** Probes a single MCP server with Hermes' own MCP initialize handshake. */
  public McpTestResult testMcpServer(
      String url, String containerId, String profileName, String serverName) {
    return mcp.test(url, containerId, profileName, serverName);
  }

  // ── observability ──────────────────────────────────────────────────────────

  public List<IntegrationDto> integrations(String url, String containerId, String profileName) {
    return integrations.list(url, containerId, profileName);
  }

  /** Reads the profile-specific s6 gateway log, including rotated files, rather
   * than reusing Docker's container-wide stdout/stderr stream. */
  public List<LogLineDto> logs(String url, String containerId, String profileName, int tail) {
    return gatewayLogs.read(url, containerId, profileName, tail);
  }

  // ── sessions ───────────────────────────────────────────────────────────────

  public List<SessionDto> listSessions(String url, String containerId, String profileName) {
    return sessions.list(url, containerId, profileName);
  }

  /** Returns the chat history (messages) for a session as a JSON array string. */
  public String readSessionMessages(
      String url, String containerId, String profileName, String sessionId) {
    return sessions.readMessages(url, containerId, profileName, sessionId);
  }

  public void deleteSession(
      String url, String containerId, String profileName, String sessionId) {
    sessions.delete(url, containerId, profileName, sessionId);
  }
}
