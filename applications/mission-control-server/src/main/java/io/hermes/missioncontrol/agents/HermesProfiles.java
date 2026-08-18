package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.ConflictException;
import io.hermes.missioncontrol.agents.ModelProviderRegistry;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.secrets.Secrets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class HermesProfiles {

  private static final Logger log = LoggerFactory.getLogger(HermesProfiles.class);

  private static final String HERMES_HOME = "/opt/data";
  private static final String PROFILES_DIR = "/opt/data/profiles";
  private static final String PLATFORM_CLI = "cli";
  /** The auxiliary side-task slots hermes seeds into {@code config.yaml}. Each one
   *  ships as {@code provider: auto}, and hermes' auto chain resolves that to the
   *  profile's own {@code model.provider} / {@code model.default} before it tries
   *  OpenRouter, Nous or a custom endpoint — so on a profile whose model map is
   *  empty, every task falls through to "no provider available" and compression,
   *  summarization and memory flush stop working. Pinning them to the same
   *  provider/model the profile already uses keeps them resolvable on their own.
   *
   *  <p>{@code vision} is deliberately excluded: its chain skips a main model that
   *  is known to be text-only and falls back to OpenRouter/Nous, so pinning would
   *  aim image payloads at a model that may reject them. */
  private static final List<String> AUXILIARY_TASKS = List.of(
      "approval", "compression", "curator", "kanban_decomposer", "mcp", "monitor",
      "profile_describer", "skills_hub", "title_generation", "triage_specifier",
      "tts_audio_tags", "web_extract");
  private static final Pattern PROFILE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");
  private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
  private static final Pattern TOOL_COUNT = Pattern.compile("(?i)\\b(\\d+)\\s+tools?\\b");
  private static final Pattern DISCOVERED_TOOL_COUNT = Pattern.compile("(?i)tools discovered:\\s*(\\d+)");
  private static final Pattern MCP_CONNECTED = Pattern.compile("(?m)^\\s*[✓✔]\\s+Connected \\(");
  private static final Pattern GATEWAY_LOG_LINE = Pattern.compile(
      "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{1,9})\\s{2}(.*)$");
  private static final DateTimeFormatter GATEWAY_LOG_TIME = new DateTimeFormatterBuilder()
      .appendPattern("yyyy-MM-dd HH:mm:ss")
      .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
      .toFormatter(Locale.ROOT);

  private final DockerExecService dockerExec;
  private final Yaml yaml = new Yaml();
  private final ObjectMapper objectMapper;
  private final HermesConfigEditor config;
  private final ConcurrentMap<McpCacheKey, CachedMcpProbe> mcpProbeCache = new ConcurrentHashMap<>();

  public HermesProfiles(
      DockerExecService dockerExec, ObjectMapper objectMapper, HermesConfigEditor config) {
    this.dockerExec = dockerExec;
    this.objectMapper = objectMapper;
    this.config = config;
  }

  public List<AgentProfileDto> list(String url, String containerId) {
    try {
      List<String> names = listProfileNames(url, containerId);
      List<AgentProfileDto> profiles = new ArrayList<>();
      for (String name : names) {
        profiles.add(readProfile(url, containerId, name));
      }
      return profiles;
    } catch (ConflictException stopped) {
      // Docker returns 409 when a stale dashboard client asks to exec inside a
      // stopped container. Inventory is simply unavailable until it restarts.
      return List.of();
    }
  }

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
    if (profileName == null || !PROFILE_NAME.matcher(profileName).matches()) {
      throw new IllegalArgumentException("invalid profile name");
    }
    List<String> command = new ArrayList<>(List.of("hermes", "profile", "create", profileName));
    String cloneFrom = request.cloneFrom();
    if (cloneFrom != null && !cloneFrom.isBlank()) {
      command.addAll(List.of("--clone", "--clone-from", cloneFrom));
    }
    boolean created = false;
    try {
      exec(url, request.containerId(), command);
      created = true;
      ModelTarget auxiliary = auxiliaryTarget(
          request.provider(), request.model(), request.baseUrl(), request.auxiliary());
      writeModelConfig(url, request.containerId(), profileName,
          request.provider(), request.model(), request.baseUrl(), auxiliary);
      assertModelConfigured(url, request.containerId(), profileName);
      seedEnvIfMissing(url, request.containerId(), profileName);
      String envKey = apiKeyVar(normalizeProvider(request.provider()));
      if (envKey != null && request.apiKey() != null && !request.apiKey().isBlank()) {
        writeEnvVar(url, request.containerId(), profileName, envKey, request.apiKey());
      }
      writeAuxiliaryApiKey(url, request.containerId(), profileName, auxiliary, request.auxiliary());
      return profileName;
    } catch (RuntimeException failure) {
      if (created) {
        try {
          delete(url, request.containerId(), profileName);
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
  }

  public void delete(String url, String containerId, String name) {
    exec(url, containerId, List.of("hermes", "profile", "delete", name, "--yes"));
  }

  public void updateSoul(String url, String containerId, String name, String soul) {
    String path = requireProfileDir(url, containerId, name) + "/SOUL.md";
    writeFile(url, containerId, path, soul == null ? "" : soul);
  }

  public void updateMemory(String url, String containerId, String name, String memory) {
    String path = requireProfileDir(url, containerId, name) + "/MEMORY.md";
    writeFile(url, containerId, path, memory == null ? "" : memory);
  }

  /**
   * The profile's directory, or a 404 when there is no such profile.
   *
   * <p>{@link #writeFile} runs {@code mkdir -p} on the parent, which a skill subdirectory
   * needs. Without this guard that also means a PUT against a mistyped profile name mints
   * the directory, and the phantom profile then appears in {@link #list} — indistinguishable
   * from a real one, and holding only whatever the typo'd request wrote.
   */
  private String requireProfileDir(String url, String containerId, String name) {
    String dir = profileDir(name);
    if (!dirExists(url, containerId, dir)) {
      throw new NoSuchElementException("unknown agent profile: " + name);
    }
    return dir;
  }

  /** Reads a single profile's current state (config, soul, memory, skills, mcp). */
  public AgentProfileDto get(String url, String containerId, String name) {
    return readProfile(url, containerId, name);
  }

  /** Reads the profile-specific s6 gateway log, including rotated files, rather
   * than reusing Docker's container-wide stdout/stderr stream. */
  public List<LogLineDto> logs(
      String url, String containerId, String profileName, int tail) {
    profileDir(profileName); // validates the URL-sourced profile name
    int limit = Math.min(Math.max(tail, 1), 500);
    String logDir = HERMES_HOME + "/logs/gateways/" + profileName;
    String script = """
        dir="$1"; limit="$2"
        { for file in "$dir"/@*.u "$dir/current"; do
            [ -f "$file" ] && cat "$file"
          done
        } | tail -n "$limit"
        """;
    ExecResult result = exec(
        url, containerId, List.of("sh", "-c", script, "_", logDir, String.valueOf(limit)));
    return parseGatewayLogs(profileName, result.stdout());
  }

  static List<LogLineDto> parseGatewayLogs(String profileName, String output) {
    List<LogLineDto> lines = new ArrayList<>();
    for (String raw : (output == null ? "" : output).split("\\R")) {
      Matcher matcher = GATEWAY_LOG_LINE.matcher(raw);
      if (!matcher.matches()) continue;
      String message = ANSI.matcher(matcher.group(2)).replaceAll("").stripTrailing();
      if (message.isBlank()) continue;
      try {
        long timestamp = LocalDateTime.parse(matcher.group(1), GATEWAY_LOG_TIME)
            .toInstant(ZoneOffset.UTC).toEpochMilli();
        lines.add(new LogLineDto(timestamp, gatewayLogLevel(message), profileName, message));
      } catch (RuntimeException ignored) {
        // A malformed line must not poison the rest of the tail.
      }
    }
    return lines;
  }

  private static String gatewayLogLevel(String message) {
    String lower = message.stripLeading().toLowerCase(Locale.ROOT);
    if (lower.startsWith("warning") || lower.startsWith("warn") || lower.startsWith("[warn")) return "warn";
    if (lower.startsWith("debug") || lower.startsWith("[debug")) return "debug";
    if (lower.startsWith("error") || lower.startsWith("fatal") || lower.startsWith("traceback")
        || lower.contains("permissionerror:") || lower.contains("exception:")) return "error";
    return "info";
  }

  public AgentProfileDto updateConfig(String url, String containerId, String name, String configYaml) {
    if (configYaml == null || configYaml.isBlank()) {
      throw new IllegalArgumentException("config.yaml must be a YAML mapping");
    }
    try {
      Object loaded = yaml.load(configYaml);
      if (!(loaded instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("config.yaml must be a YAML mapping");
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("config.yaml must be a YAML mapping", e);
    }
    writeFile(url, containerId, requireProfileDir(url, containerId, name) + "/config.yaml", configYaml);
    return readProfile(url, containerId, name);
  }

  public AgentProfileDto setSkillEnabled(String url, String containerId, String profileName, String skillName, boolean enabled) {
    if (skillName == null || skillName.isBlank()) {
      throw new IllegalArgumentException("missing skill name");
    }
    String configPath = requireProfileDir(url, containerId, profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    Map<Object, Object> root = config.parseForEdit(configYaml, configPath);
    Map<Object, Object> skills = config.asMutableMap(root.get("skills"));
    root.put("skills", skills);
    Map<Object, Object> platformDisabled = config.asMutableMap(skills.get("platform_disabled"));
    skills.put("platform_disabled", platformDisabled);
    List<Object> cliDisabled = asMutableList(platformDisabled.get(PLATFORM_CLI));
    platformDisabled.put(PLATFORM_CLI, cliDisabled);

    if (enabled) {
      cliDisabled.removeIf(x -> skillName.equals(stringValue(x)));
    } else {
      boolean present = cliDisabled.stream().anyMatch(x -> skillName.equals(stringValue(x)));
      if (!present) cliDisabled.add(skillName);
    }

    writeFile(url, containerId, configPath, yaml.dump(root));
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto installSkill(String url, String containerId, String profileName, String skillId) {
    if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("missing skill name");
    // skill ids flow in from reusable templates (user-authored) — validate the
    // same way uninstall does so a stray value can't be parsed as a CLI flag
    if (!PROFILE_NAME.matcher(skillId).matches()) throw new IllegalArgumentException("invalid skill id: " + skillId);
    exec(url, containerId, List.of("hermes", "-p", profileName, "skills", "install", skillId, "--force"));
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto uninstallSkill(String url, String containerId, String profileName, String skillName) {
    if (skillName == null || skillName.isBlank()) throw new IllegalArgumentException("missing skill name");
    if (!PROFILE_NAME.matcher(skillName).matches()) throw new IllegalArgumentException("invalid skill name");
    // `hermes skills uninstall` prompts "Confirm [y/N]" (no --yes flag) and
    // reports failures on stdout with exit code 0, so it cannot be driven
    // reliably through a non-tty exec — remove the skill directory instead.
    String skillDir = findSkillDir(url, containerId, profileName, skillName);
    if (skillDir == null) throw new IllegalArgumentException("skill not found: " + skillName);
    exec(url, containerId, List.of("sh", "-lc", "rm -rf \"$1\"", "_", skillDir));
    return readProfile(url, containerId, profileName);
  }

  /** Resolves the directory backing a skill: the dir name usually matches the
   *  skill name, but SKILL.md frontmatter may override the display name. Searches
   *  flat and category-nested layouts alike. */
  private String findSkillDir(String url, String containerId, String profileName, String skillName) {
    String skillsDir = profileDir(profileName) + "/skills";
    String direct = skillsDir + "/" + skillName;
    if (dirExists(url, containerId, direct)) return direct;
    for (String skillMdPath : findSkillMdPaths(url, containerId, skillsDir)) {
      int fileSlash = skillMdPath.lastIndexOf('/');
      String dir = fileSlash >= 0 ? skillMdPath.substring(0, fileSlash) : skillMdPath;
      String dirName = skillDirName(skillMdPath);
      if (skillName.equals(dirName)) return dir;
      String skillMd = readFile(url, containerId, skillMdPath);
      if (!skillMd.isBlank() && skillName.equals(parseSkillMeta(skillMd, dirName).name())) {
        return dir;
      }
    }
    return null;
  }

  /** Reads a skill's SKILL.md body plus its file list, for inspection/editing. */
  public SkillContentDto readSkillContent(String url, String containerId, String profileName, String skillName) {
    if (skillName == null || !PROFILE_NAME.matcher(skillName).matches()) {
      throw new IllegalArgumentException("invalid skill name");
    }
    String skillDir = findSkillDir(url, containerId, profileName, skillName);
    if (skillDir == null) throw new IllegalArgumentException("skill not found: " + skillName);
    String body = readFile(url, containerId, skillDir + "/SKILL.md");
    return new SkillContentDto(skillName, skillDir, body, listSkillFiles(url, containerId, skillDir));
  }

  /** Overwrites a skill's SKILL.md, then re-reads the profile so the refreshed
   *  name/version/description/source flow back to the caller. */
  public AgentProfileDto updateSkillContent(
      String url, String containerId, String profileName, String skillName, String body) {
    if (skillName == null || !PROFILE_NAME.matcher(skillName).matches()) {
      throw new IllegalArgumentException("invalid skill name");
    }
    if (body == null) throw new IllegalArgumentException("missing skill body");
    String skillDir = findSkillDir(url, containerId, profileName, skillName);
    if (skillDir == null) throw new IllegalArgumentException("skill not found: " + skillName);
    writeFile(url, containerId, skillDir + "/SKILL.md", body);
    return readProfile(url, containerId, profileName);
  }

  /** Relative file paths inside a skill dir (skipping dot-files), for the UI. */
  private List<String> listSkillFiles(String url, String containerId, String skillDir) {
    String script = "d=\"$1\"; cd \"$d\" 2>/dev/null || exit 0; "
        + "find . -maxdepth 3 -type f -not -path '*/.*' 2>/dev/null | sed 's|^\\./||' | sort";
    ExecResult ls = exec(url, containerId, List.of("sh", "-lc", script, "_", skillDir));
    List<String> files = new ArrayList<>();
    for (String line : ls.stdout().split("\\R")) {
      String f = line.trim();
      if (!f.isEmpty()) files.add(f);
    }
    return files;
  }

  public AgentProfileDto addMcpServer(String url, String containerId, String profileName, AddMcpServerRequest request) {
    String configPath = requireProfileDir(url, containerId, profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    String name = config.serverName(request.name());
    String updatedConfig = config.addMcpServer(configYaml, configPath, request);
    mcpProbeCache.remove(new McpCacheKey(url, containerId, profileName, name));
    writeFileAtomically(url, containerId, configPath, updatedConfig);
    return readProfile(url, containerId, profileName);
  }

  /** Updates (and optionally renames) an existing MCP entry with a single
   * atomic config-file replacement. A rename collision is rejected before any
   * container write, so the original definition is never lost. */
  public AgentProfileDto updateMcpServer(
      String url,
      String containerId,
      String profileName,
      String serverName,
      AddMcpServerRequest request) {
    String currentName = config.serverName(serverName);
    String newName = config.serverName(request.name());
    String configPath = requireProfileDir(url, containerId, profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    String updatedConfig = config.updateMcpServer(configYaml, configPath, currentName, request);
    mcpProbeCache.remove(new McpCacheKey(url, containerId, profileName, currentName));
    mcpProbeCache.remove(new McpCacheKey(url, containerId, profileName, newName));
    writeFileAtomically(url, containerId, configPath, updatedConfig);
    return readProfile(url, containerId, profileName);
  }

  /** Toggles only {@code enabled}; URL, command, args, headers, tool filters,
   * and any keys unknown to Mission Control are retained in the YAML data
   * model. */
  public AgentProfileDto setMcpServerEnabled(
      String url,
      String containerId,
      String profileName,
      String serverName,
      boolean enabled) {
    String name = config.serverName(serverName);
    String configPath = requireProfileDir(url, containerId, profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    String updatedConfig = config.setMcpServerEnabled(configYaml, configPath, name, enabled);
    mcpProbeCache.remove(new McpCacheKey(url, containerId, profileName, name));
    writeFileAtomically(url, containerId, configPath, updatedConfig);
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto removeMcpServer(String url, String containerId, String profileName, String serverName) {
    serverName = config.serverName(serverName);
    String configPath = requireProfileDir(url, containerId, profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    String updatedConfig = config.removeMcpServer(configYaml, configPath, serverName);
    mcpProbeCache.remove(new McpCacheKey(url, containerId, profileName, serverName));
    writeFileAtomically(url, containerId, configPath, updatedConfig);
    return readProfile(url, containerId, profileName);
  }

  public List<IntegrationDto> integrations(String url, String containerId, String profileName) {
    return listIntegrations(url, containerId, profileName);
  }

  // ── sessions ────────────────────────────────────────────────────────────
  // Hermes records conversations in a per-profile SQLite DB (state.db): the
  // `sessions` table holds one row per conversation, `messages` holds the turns
  // keyed by session_id. The container image has python3 but no sqlite3 CLI, so
  // we shell out to python3 to query the DB and emit JSON. session ids/values
  // are passed as argv + bound query params — never interpolated into SQL/shell.

  private String stateDb(String profileName) {
    return profileDir(profileName) + "/state.db";
  }

  public List<SessionDto> listSessions(String url, String containerId, String profileName) {
    String db = stateDb(profileName);
    if (!fileExists(url, containerId, db)) return List.of();
    String py = """
        import sqlite3, json, sys
        db = sys.argv[1]
        try:
            con = sqlite3.connect('file:%s?mode=ro' % db, uri=True)
            con.row_factory = sqlite3.Row
            rows = con.execute(
                "SELECT id, source, title, started_at, ended_at, message_count "
                "FROM sessions WHERE archived=0 ORDER BY started_at DESC LIMIT 200").fetchall()
            print(json.dumps([dict(r) for r in rows]))
        except Exception:
            print('[]')
        """;
    ExecResult r = exec(url, containerId, List.of("python3", "-c", py, db), false);
    return parseSessionRows(r.stdout());
  }

  /** Returns the chat history (messages) for a session as a JSON array string. */
  public String readSessionMessages(String url, String containerId, String profileName, String sessionId) {
    if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("invalid session id");
    String db = stateDb(profileName);
    if (!fileExists(url, containerId, db)) return "[]";
    // A genuinely empty session yields '[]' (exit 0). Real availability errors
    // (locked, corrupt) still raise -> non-zero exit -> exec(check=true) throws ->
    // the caller surfaces them. But a schema mismatch (an older/newer hermes whose
    // messages table is missing an optional column) is degraded to '[]' rather than
    // 500-ing the whole chat view — only OperationalErrors that aren't "locked"/
    // "corrupt" are swallowed.
    String py = """
        import sqlite3, json, sys
        db, sid = sys.argv[1], sys.argv[2]
        con = sqlite3.connect('file:%s?mode=ro' % db, uri=True)
        con.row_factory = sqlite3.Row
        try:
            rows = con.execute(
                "SELECT role, content, tool_name, tool_calls, reasoning_content, timestamp "
                "FROM messages WHERE session_id=? AND active=1 ORDER BY timestamp, id LIMIT 4000",
                (sid,)).fetchall()
        except sqlite3.OperationalError as e:
            msg = str(e).lower()
            if 'locked' in msg or 'malformed' in msg or 'corrupt' in msg:
                raise
            print('[]'); sys.exit(0)
        out = [{'role': r['role'], 'content': r['content'] or '',
                'toolName': r['tool_name'], 'toolCalls': r['tool_calls'],
                'reasoning': r['reasoning_content'],
                'ts': int((r['timestamp'] or 0) * 1000)} for r in rows]
        print(json.dumps(out))
        """;
    ExecResult r = exec(url, containerId, List.of("python3", "-c", py, db, sessionId));
    String out = r.stdout().trim();
    return out.isEmpty() ? "[]" : out;
  }

  public void deleteSession(String url, String containerId, String profileName, String sessionId) {
    if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("invalid session id");
    String db = stateDb(profileName);
    if (!fileExists(url, containerId, db)) throw new IllegalArgumentException("no session store for this profile");
    String py = """
        import sqlite3, sys
        db, sid = sys.argv[1], sys.argv[2]
        con = sqlite3.connect(db, timeout=10)
        con.execute('PRAGMA busy_timeout=10000')
        con.execute('DELETE FROM messages WHERE session_id=?', (sid,))
        con.execute('DELETE FROM sessions WHERE id=?', (sid,))
        con.commit(); con.close()
        """;
    exec(url, containerId, List.of("python3", "-c", py, db, sessionId));   // check=true surfaces errors
  }

  private List<SessionDto> parseSessionRows(String json) {
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(json, new TypeReference<>() {});
      List<SessionDto> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        String id = stringValue(row.get("id"));
        if (id.isBlank()) continue;
        String title = stringValue(row.get("title"));
        if (title.isBlank()) title = "(untitled session)";
        String platform = stringValue(row.get("source"));
        if (platform.isBlank()) platform = PLATFORM_CLI;
        long startedAt = (long) (toDouble(row.get("started_at")) * 1000);
        int messages = (int) toDouble(row.get("message_count"));
        String status = row.get("ended_at") == null ? "open" : "closed";
        out.add(new SessionDto(id, title, platform, startedAt, messages, status));
      }
      return out;
    } catch (Exception e) {
      // an empty session list and an unreadable one look identical to the operator
      log.warn("could not parse the session rows returned by the profile state db: {}", e.toString());
      return List.of();
    }
  }

  private double toDouble(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    try {
      return value == null ? 0 : Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private AgentProfileDto readProfile(String url, String containerId, String name) {
    String dir = profileDir(name);
    String configYaml = readFile(url, containerId, dir + "/config.yaml");
    String soul = readFile(url, containerId, dir + "/SOUL.md");
    String memoryMd = readFile(url, containerId, dir + "/MEMORY.md");
    String env = readFile(url, containerId, dir + "/.env");
    Map<?, ?> configMap = parseYamlMap(configYaml);
    ConfigInfo config = parseConfig(configMap);
    String provider = config.provider();
    String model = config.model();
    String apiKeyMasked = maskApiKey(env, provider);
    String cwd = config.cwd().isBlank() ? "/opt/data" : config.cwd();
    String role = "default".equals(name) ? "Default profile" : "Profile";
    String state = "idle";
    long lastActive = System.currentTimeMillis();
    List<SkillDto> skills = listSkills(url, containerId, name, configMap);
    List<AgentMcpServerDto> mcp = listMcpServers(url, containerId, name, configMap);
    List<IntegrationDto> integrations = listIntegrations(url, containerId, name);
    return new AgentProfileDto(
        profileId(containerId, name),
        containerId,
        name,
        role,
        state,
        provider,
        model,
        apiKeyMasked,
        cwd,
        soul,
        memoryMd,
        configYaml,
        skills,
        mcp,
        integrations,
        lastActive);
  }

  String profileDir(String name) {
    if ("default".equals(name)) return HERMES_HOME;
    // names reach us from URL path segments — reject anything that could
    // escape the profiles dir before it is concatenated into a container path
    if (name == null || !PROFILE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("invalid profile name");
    }
    return PROFILES_DIR + "/" + name;
  }

  private String profileId(String containerId, String name) {
    return containerId + "--" + name;
  }

  private List<String> listProfileNames(String url, String containerId) {
    List<String> names = new ArrayList<>();
    if (dirExists(url, containerId, HERMES_HOME)) {
      names.add("default");
    }
    ExecResult ls = exec(url, containerId, List.of("sh", "-lc", "ls -1 " + PROFILES_DIR + " 2>/dev/null || true"));
    for (String line : ls.stdout().split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || "default".equals(trimmed)) continue;
      if (PROFILE_NAME.matcher(trimmed).matches()) {
        names.add(trimmed);
      }
    }
    return names;
  }

  private boolean dirExists(String url, String containerId, String path) {
    ExecResult result = exec(url, containerId, List.of("sh", "-lc", "test -d \"$1\"", "_", path), false);
    return result.exitCode() == 0;
  }

  boolean fileExists(String url, String containerId, String path) {
    ExecResult result = exec(url, containerId, List.of("sh", "-lc", "test -f \"$1\"", "_", path), false);
    return result.exitCode() == 0;
  }

  /** Sets the profile's model through hermes' own config writer
   *  ({@code hermes -p <name> config set model.<key> <value>}), which produces
   *  the documented {@code model: { provider, default, base_url }} mapping and
   *  keeps hermes' validation/migration in the loop. Only the model.* keys are
   *  touched — sibling config keys are preserved.
   *
   *  <p>The model id is passed verbatim to {@code model.default} — never
   *  concatenated as {@code provider/model} — so OpenRouter ids that already
   *  contain a slash (e.g. {@code anthropic/claude-sonnet-4}) are stored intact.
   *  Custom/local endpoints (ollama, vLLM, …) set {@code model.base_url} and own
   *  their routing, so they carry no provider; standard providers (nous,
   *  openrouter, anthropic, openai, …) set {@code model.provider}.
   *
   *  <p>The map is first wiped to an empty scalar ({@code model: ""}) so a
   *  {@code --clone}'d profile cannot leak ANY stale key — provider, base_url, or
   *  even a hand-set api_mode — from the source profile; the dotted sets then
   *  rebuild model from scratch with only the keys the chosen provider needs.
   *  (`hermes config set` mutates one key and preserves the rest of the map, so a
   *  full reset is the only way to guarantee no leak.) */
  private void writeModelConfig(
      String url, String containerId, String name,
      String provider, String model, String baseUrl, ModelTarget auxiliary) {
    for (String[] kv : modelConfigEntries(provider, model, baseUrl)) {
      setConfig(url, containerId, name, kv[0], kv[1]);
    }
    for (String[] kv : auxiliaryConfigEntries(
        auxiliary.provider(), auxiliary.model(), auxiliary.baseUrl())) {
      setConfig(url, containerId, name, kv[0], kv[1]);
    }
  }

  /** A provider/model/endpoint triple — what {@code model.*} and each
   *  {@code auxiliary.<task>.*} block each resolve to. */
  record ModelTarget(String provider, String model, String baseUrl) {}

  /** Resolves which backend the auxiliary tasks get pinned to.
   *
   *  <p>The default is the profile's own main model: side tasks that silently ran
   *  on a different model than the conversation would be a surprise, and hermes'
   *  own {@code auto} means exactly this. An override is honoured only when it
   *  names a model — a spec carrying just a provider has nothing to pin to.
   *
   *  <p>The endpoint follows the provider: an override that names its own provider
   *  brings its own {@code base_url} (or none), while one that only swaps the model
   *  inherits the main endpoint, so "same local ollama, smaller model" needs no
   *  repeated URL. */
  static ModelTarget auxiliaryTarget(
      String provider, String model, String baseUrl, AuxiliaryModelSpec spec) {
    if (spec == null || isBlank(spec.model())) {
      return new ModelTarget(provider, model, baseUrl);
    }
    boolean ownProvider = !isBlank(spec.provider());
    String auxProvider = ownProvider ? spec.provider() : provider;
    String auxBaseUrl = !isBlank(spec.baseUrl()) ? spec.baseUrl() : (ownProvider ? null : baseUrl);
    return new ModelTarget(auxProvider, spec.model().trim(), auxBaseUrl);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Writes the override provider's API key into the profile's .env. Only runs when
   *  the override actually introduces a provider — a same-provider model swap is
   *  already covered by the main key, and re-writing it would let a blank field in
   *  the create form clobber a working one. */
  private void writeAuxiliaryApiKey(
      String url, String containerId, String name, ModelTarget auxiliary, AuxiliaryModelSpec spec) {
    if (spec == null || isBlank(spec.apiKey()) || isBlank(spec.provider())) return;
    String envKey = apiKeyVar(normalizeProvider(auxiliary.provider()));
    if (envKey == null) return;
    writeEnvVar(url, containerId, name, envKey, spec.apiKey());
  }

  /** Pure planner for the {@code auxiliary.*} half of {@link #writeModelConfig}: the
   *  {@code (key, value)} sets that pin every task in {@link #AUXILIARY_TASKS} to the
   *  same backend the profile's main model uses. Written from the same seam as
   *  {@code model.*} so the two can never drift — a model change rewrites both.
   *
   *  <p>Returns an empty plan when there is nothing concrete to pin to (blank model,
   *  or a blank/auto provider with no custom endpoint); leaving those tasks on
   *  {@code auto} is strictly better than pinning them at a provider that does not
   *  resolve. A custom endpoint pins {@code provider: custom} plus its
   *  {@code base_url}, matching how hermes rewrites aliased providers to custom when
   *  a task carries an endpoint of its own. */
  static List<String[]> auxiliaryConfigEntries(String provider, String model, String baseUrl) {
    List<String[]> entries = new ArrayList<>();
    String pinnedModel = model == null ? "" : model.trim();
    if (pinnedModel.isBlank()) return entries;
    boolean custom = baseUrl != null && !baseUrl.isBlank();
    String pinnedProvider = custom ? "custom" : normalizeProvider(provider);
    if (!custom && (pinnedProvider.isBlank() || "auto".equals(pinnedProvider))) return entries;
    for (String task : AUXILIARY_TASKS) {
      String prefix = "auxiliary." + task + ".";
      entries.add(new String[] {prefix + "provider", pinnedProvider});
      entries.add(new String[] {prefix + "model", pinnedModel});
      if (custom) entries.add(new String[] {prefix + "base_url", baseUrl});
    }
    return entries;
  }

  /** Fails the create when the profile ended up without a usable model.
   *
   *  <p>{@code hermes profile create} never seeds {@code config.yaml} — it bootstraps
   *  dirs, {@code .env} and {@code SOUL.md}, and only copies a config under
   *  {@code --clone}. The file exists at all because the {@code config set} calls above
   *  bring it into existence, so a write that silently no-ops leaves a profile whose
   *  auxiliary chain has nothing to resolve to and whose gateway logs
   *  "no provider available ... compression, summarization, and memory flush will not
   *  work". Throwing here lets the caller's rollback drop the half-built profile
   *  instead of handing back an agent that degrades on first long session. */
  private void assertModelConfigured(String url, String containerId, String name) {
    String configPath = profileDir(name) + "/config.yaml";
    ConfigInfo info = parseConfig(parseYamlMap(readFile(url, containerId, configPath)));
    if (info.model().isBlank()) {
      throw new IllegalStateException(
          "profile '" + name + "' has no model in " + configPath
              + " — auxiliary tasks (compression, summarization, memory flush)"
              + " would have no provider to resolve to");
    }
  }

  /** Pure planner for {@link #writeModelConfig}: the ordered {@code (key, value)}
   *  config sets. The first entry wipes {@code model} to an empty scalar (kills
   *  any inherited map); {@code model.default} then promotes it back to a map;
   *  finally the one applicable routing key is set — {@code model.provider} for a
   *  standard provider, {@code model.base_url} for a custom/local endpoint, and
   *  neither when the provider is blank/auto. Extracted (package-private, no I/O)
   *  so the clone-reset contract is unit-testable. */
  static List<String[]> modelConfigEntries(String provider, String model, String baseUrl) {
    List<String[]> entries = new ArrayList<>();
    entries.add(new String[] {"model", ""});                                   // wipe any clone leftovers
    entries.add(new String[] {"model.default", model == null ? "" : model});   // promote back to a map
    boolean custom = baseUrl != null && !baseUrl.isBlank();
    if (custom) {
      entries.add(new String[] {"model.base_url", baseUrl});   // custom endpoint owns routing
    } else {
      String normalizedProvider = normalizeProvider(provider);
      if (!normalizedProvider.isBlank() && !"auto".equals(normalizedProvider)) {
        entries.add(new String[] {"model.provider", normalizedProvider});
      }
    }
    return entries;
  }

  private void setConfig(String url, String containerId, String name, String key, String value) {
    exec(url, containerId, List.of("hermes", "-p", name, "config", "set", key, value));
  }

  static String normalizeProvider(String provider) {
    if (provider == null) return "";
    String trimmed = provider.trim().toLowerCase(Locale.ROOT);
    if (trimmed.startsWith("nous")) return "nous";
    return trimmed;
  }

  /** The provider's API-key env var (or null for OAuth/keyless/unknown), from the
   *  shared {@link ModelProviderRegistry} so the key written into .env always
   *  matches the providers the UI offers. */
  private String apiKeyVar(String provider) {
    return ModelProviderRegistry.envVar(provider);
  }

  void writeEnvVar(String url, String containerId, String name, String key, String value) {
    String path = requireProfileDir(url, containerId, name) + "/.env";
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\"; value=\"$3\";",
        "touch \"$path\";",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "printf '%s=%s\\n' \"$key\" \"$value\" >> \"$path.tmp\";",
        "mv \"$path.tmp\" \"$path\";");
    execSensitive(url, containerId, List.of("sh", "-lc", script, "_", path, key, value), "write profile environment");
  }

  void removeEnvVar(String url, String containerId, String name, String key) {
    String path = requireProfileDir(url, containerId, name) + "/.env";
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\";",
        "[ -f \"$path\" ] || exit 0;",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "mv \"$path.tmp\" \"$path\";");
    exec(url, containerId, List.of("sh", "-lc", script, "_", path, key));
  }

  /** Writes the documented commented-out .env template; no-op when .env exists. */
  void seedEnvIfMissing(String url, String containerId, String name) {
    String path = requireProfileDir(url, containerId, name) + "/.env";
    if (fileExists(url, containerId, path)) return;
    writeFile(url, containerId, path, HermesSetup.envTemplate());
  }

  private void writeFile(String url, String containerId, String path, String content) {
    String script = String.join(" ",
        "path=\"$1\"; content=\"$2\";",
        "mkdir -p \"$(dirname \"$path\")\";",
        "printf '%s' \"$content\" > \"$path\";");
    exec(url, containerId, List.of("sh", "-lc", script, "_", path, content));
  }

  /** Writes a complete config through a sibling temp file and atomic rename, so
   * readers can observe either the old definition or the new one, never the
   * delete half of a rename or a partially-written YAML document. */
  private void writeFileAtomically(
      String url, String containerId, String path, String content) {
    String script = String.join(" ",
        "path=\"$1\"; content=\"$2\";",
        "mkdir -p \"$(dirname \"$path\")\";",
        "tmp=\"${path}.mission-control.$$\";",
        "trap 'rm -f \"$tmp\"' 0 1 2 15;",
        "printf '%s' \"$content\" > \"$tmp\";",
        "mv -f \"$tmp\" \"$path\";",
        "trap - 0 1 2 15;");
    // The complete YAML may carry authentication headers, so do not include
    // argv in Docker execution errors/logs.
    execSensitive(
        url,
        containerId,
        List.of("sh", "-lc", script, "_", path, content),
        "write MCP configuration");
  }

  /**
   * KNOWN GAP: {@code cat … || true} cannot tell an empty file from an absent one, so a
   * read of a profile that does not exist yields an empty profile rather than a 404. Most
   * callers rely on that tolerance (a profile legitimately has no MEMORY.md, no .env, no
   * gateway_state.json), so separating the two needs a per-call decision rather than a
   * change here. The write paths are guarded by {@link #requireProfileDir} instead, which
   * is what stopped a mistyped name from creating a profile.
   */
  String readFile(String url, String containerId, String path) {
    ExecResult result = exec(url, containerId, List.of("sh", "-lc", "cat \"$1\" 2>/dev/null || true", "_", path));
    return result.stdout();
  }

  private Map<?, ?> parseYamlMap(String yamlText) {
    if (yamlText == null || yamlText.isBlank()) return Map.of();
    try {
      Object loaded = yaml.load(yamlText);
      return loaded instanceof Map<?, ?> map ? map : Map.of();
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  ConfigInfo parseConfig(Map<?, ?> map) {
    if (map == null || map.isEmpty()) return new ConfigInfo("auto", "", "");
    String provider = "auto";
    String model = "";
    Object modelNode = map.get("model");
    if (modelNode instanceof String modelString) {
      ModelInfo info = parseModelString(modelString);
      provider = info.provider();
      model = info.model();
    } else if (modelNode instanceof Map<?, ?> modelMap) {
      String providerValue = stringValue(modelMap.get("provider"));
      String defaultValue = stringValue(modelMap.get("default"));
      if (defaultValue.isBlank()) {
        defaultValue = stringValue(modelMap.get("model"));
      }
      if (!providerValue.isBlank()) {
        // a structured map already separates provider from the model id, so the
        // id is taken verbatim — splitting it would drop the namespace of an
        // OpenRouter id (anthropic/claude-sonnet-4 -> claude-sonnet-4), which the
        // provider can no longer resolve and which breaks template re-capture.
        provider = providerValue;
        model = defaultValue;
      } else {
        // no explicit provider (custom/ollama, or legacy): fall back to the
        // scalar "provider/model" convention.
        ModelInfo info = parseModelString(defaultValue);
        provider = info.provider();
        model = info.model().isBlank() ? defaultValue : info.model();
      }
    }
    String cwd = "";
    Object terminal = map.get("terminal");
    if (terminal instanceof Map<?, ?> terminalMap) {
      cwd = stringValue(terminalMap.get("cwd"));
    }
    return new ConfigInfo(provider, model, cwd);
  }

  private List<SkillDto> listSkills(String url, String containerId, String profileName, Map<?, ?> configMap) {
    String skillsDir = profileDir(profileName) + "/skills";
    Set<String> disabled = disabledSkills(configMap, PLATFORM_CLI);
    Set<String> bundled = bundledSkillNames(url, containerId, skillsDir);
    List<SkillDto> skills = new ArrayList<>();
    for (String skillMdPath : findSkillMdPaths(url, containerId, skillsDir)) {
      String dirName = skillDirName(skillMdPath);
      String skillMd = readFile(url, containerId, skillMdPath);
      if (skillMd == null || skillMd.isBlank()) continue;
      SkillMeta meta = parseSkillMeta(skillMd, dirName);
      String source = resolveSkillSource(meta, bundled);
      boolean enabled = !disabled.contains(meta.name());
      skills.add(new SkillDto(
          meta.name(),
          meta.name(),
          source,
          meta.version(),
          meta.description(),
          enabled));
    }
    return skills;
  }

  /** All SKILL.md paths under a profile's skills dir — flat (skills/<x>/SKILL.md)
   *  AND category-nested (skills/<category>/<x>/SKILL.md), skipping curator
   *  backups and other dot-dirs. The old flat-only `ls` missed nested skills. */
  private List<String> findSkillMdPaths(String url, String containerId, String skillsDir) {
    ExecResult find = exec(url, containerId, List.of("sh", "-lc",
        "find \"$1\" -mindepth 1 -maxdepth 3 -name SKILL.md -not -path '*/.*' 2>/dev/null || true", "_", skillsDir));
    List<String> paths = new ArrayList<>();
    for (String line : find.stdout().split("\\R")) {
      String p = line.trim();
      if (!p.isEmpty()) paths.add(p);
    }
    return paths;
  }

  /** Skill directory name from a `.../<dir>/SKILL.md` path. */
  private String skillDirName(String skillMdPath) {
    int fileSlash = skillMdPath.lastIndexOf('/');
    String dir = fileSlash >= 0 ? skillMdPath.substring(0, fileSlash) : skillMdPath;
    int dirSlash = dir.lastIndexOf('/');
    return dirSlash >= 0 ? dir.substring(dirSlash + 1) : dir;
  }

  /** Names listed in skills/.bundled_manifest ("name:hash" per line) ship with
   *  Hermes. Anything present on disk but absent here was created locally — by
   *  the agent itself or the curator (which authors umbrella skills). */
  private Set<String> bundledSkillNames(String url, String containerId, String skillsDir) {
    Set<String> names = new HashSet<>();
    String manifest = readFile(url, containerId, skillsDir + "/.bundled_manifest");
    if (manifest == null) return names;
    for (String line : manifest.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      int colon = trimmed.indexOf(':');
      String name = colon >= 0 ? trimmed.substring(0, colon).trim() : trimmed;
      if (!name.isEmpty()) names.add(name);
    }
    return names;
  }

  /** Frontmatter `source` wins when an author declares it; otherwise a skill in
   *  the bundled manifest is "bundled" and everything else is agent-authored "user". */
  private String resolveSkillSource(SkillMeta meta, Set<String> bundled) {
    if (meta.source() != null && !meta.source().isBlank()) return meta.source();
    return bundled.contains(meta.name()) ? "bundled" : "user";
  }

  private Set<String> disabledSkills(Map<?, ?> configMap, String platform) {
    Set<String> disabled = new HashSet<>();
    if (configMap == null) return disabled;
    Object skills = configMap.get("skills");
    if (!(skills instanceof Map<?, ?> skillsMap)) return disabled;
    addStringList(disabled, skillsMap.get("disabled"));
    Object platformDisabled = skillsMap.get("platform_disabled");
    if (platformDisabled instanceof Map<?, ?> platformMap) {
      addStringList(disabled, platformMap.get(platform));
    }
    return disabled;
  }

  private void addStringList(Set<String> out, Object node) {
    if (node instanceof List<?> list) {
      for (Object v : list) {
        String s = stringValue(v);
        if (!s.isBlank()) out.add(s);
      }
    }
  }

  private List<Object> asMutableList(Object node) {
    if (node instanceof List<?> l) {
      return new java.util.ArrayList<>(l);
    }
    return new java.util.ArrayList<>();
  }

  private SkillMeta parseSkillMeta(String skillMd, String fallbackName) {
    String text = skillMd == null ? "" : skillMd;
    if (text.startsWith("---")) {
      int end = text.indexOf("\n---", 3);
      if (end > 0) {
        String fm = text.substring(3, end);
        try {
          Object loaded = yaml.load(fm);
          if (loaded instanceof Map<?, ?> meta) {
            String name = stringValue(meta.get("name"));
            String description = stringValue(meta.get("description"));
            String version = stringValue(meta.get("version"));
            // frontmatter may declare its origin; blank means "infer from manifest"
            String source = stringValue(meta.get("source"));
            return new SkillMeta(name.isBlank() ? fallbackName : name, source, version, description);
          }
        } catch (Exception ignored) { }
      }
    }
    return new SkillMeta(fallbackName, "", "", "");
  }

  List<AgentMcpServerDto> listMcpServers(
      String hostUrl, String containerId, String profileName, Map<?, ?> configMap) {
    Object mcpServers = configMap == null ? null : configMap.get("mcp_servers");
    if (!(mcpServers instanceof Map<?, ?> serversMap)) return List.of();
    List<AgentMcpServerDto> result = new ArrayList<>();
    for (Map.Entry<?, ?> e : serversMap.entrySet()) {
      String name = stringValue(e.getKey());
      if (name.isBlank()) continue;
      Object cfg = e.getValue();
      if (!(cfg instanceof Map<?, ?> server)) continue;
      boolean enabled = !"false".equalsIgnoreCase(stringValue(server.get("enabled")));
      String configuredTransport = stringValue(server.get("transport")).toLowerCase(Locale.ROOT);
      String transport = server.containsKey("command") ? "stdio"
          : ("sse".equals(configuredTransport) ? "sse" : "http");
      int tools = 0;
      Object toolsNode = server.get("tools");
      if (toolsNode instanceof Map<?, ?> toolsMap) {
        Object include = toolsMap.get("include");
        if (include instanceof List<?> list) {
          tools = (int) list.stream().filter(x -> !stringValue(x).isBlank()).count();
        }
      }
      String endpoint = stringValue(server.get("url"));
      String command = stringValue(server.get("command"));
      String args = joinArgs(server.get("args"));
      String fingerprint = mcpFingerprint(transport, endpoint, command, args, enabled);
      McpCacheKey cacheKey = new McpCacheKey(hostUrl, containerId, profileName, name);
      CachedMcpProbe cached = mcpProbeCache.get(cacheKey);
      if (cached != null && !cached.fingerprint().equals(fingerprint)) {
        mcpProbeCache.remove(cacheKey, cached);
        cached = null;
      }
      String status = enabled ? (cached == null ? "unknown" : cached.result().status()) : "disabled";
      int effectiveTools = cached == null ? tools : cached.result().tools();
      Long latencyMs = cached == null ? null : cached.result().latencyMs();
      String error = cached == null ? null : cached.result().error();
      Long checkedAt = cached == null ? null : cached.result().checkedAt();
      result.add(new AgentMcpServerDto(name, name, transport, enabled, status, effectiveTools, latencyMs, error, checkedAt,
          endpoint.isBlank() ? null : endpoint, command.isBlank() ? null : command, args.isBlank() ? null : args));
    }
    return result;
  }

  /** Joins a YAML args list back into a space-separated string for the edit form. */
  private String joinArgs(Object node) {
    if (node instanceof List<?> list) {
      List<String> parts = new ArrayList<>();
      for (Object v : list) {
        String s = stringValue(v);
        if (!s.isBlank()) parts.add(s);
      }
      return String.join(" ", parts);
    }
    return stringValue(node);
  }

  /** Probes a single MCP server with Hermes' own MCP initialize handshake. */
  public McpTestResult testMcpServer(String url, String containerId, String profileName, String serverName) {
    if (serverName == null || serverName.isBlank()) throw new IllegalArgumentException("missing server name");
    long checkedAt = System.currentTimeMillis();
    String configPath = profileDir(profileName) + "/config.yaml";
    Map<?, ?> configMap = parseYamlMap(readFile(url, containerId, configPath));
    Object serversNode = configMap.get("mcp_servers");
    if (!(serversNode instanceof Map<?, ?> servers) || !(servers.get(serverName) instanceof Map<?, ?> server)) {
      return new McpTestResult(serverName, "error", 0, null, "server not found in config.yaml", checkedAt);
    }
    boolean enabled = !"false".equalsIgnoreCase(stringValue(server.get("enabled")));
    int tools = 0;
    Object toolsNode = server.get("tools");
    if (toolsNode instanceof Map<?, ?> toolsMap && toolsMap.get("include") instanceof List<?> list) {
      tools = (int) list.stream().filter(x -> !stringValue(x).isBlank()).count();
    }
    String configuredTransport = stringValue(server.get("transport")).toLowerCase(Locale.ROOT);
    String transport = server.containsKey("command") ? "stdio"
        : ("sse".equals(configuredTransport) ? "sse" : "http");
    String endpoint = stringValue(server.get("url"));
    String command = stringValue(server.get("command"));
    String args = joinArgs(server.get("args"));
    String fingerprint = mcpFingerprint(transport, endpoint, command, args, enabled);
    McpCacheKey cacheKey = new McpCacheKey(url, containerId, profileName, serverName);
    if (!enabled) {
      McpTestResult disabled = new McpTestResult(serverName, "disabled", tools, null, null, checkedAt);
      mcpProbeCache.put(cacheKey, new CachedMcpProbe(fingerprint, disabled));
      return disabled;
    }

    long start = System.nanoTime();
    List<String> probeCommand = "default".equals(profileName)
        ? List.of("hermes", "mcp", "test", serverName)
        : List.of("hermes", "-p", profileName, "mcp", "test", serverName);
    ExecResult probe = exec(url, containerId, probeCommand, false);
    long latencyMs = (System.nanoTime() - start) / 1_000_000L;
    String probeOutput = probe.stdout() + "\n" + probe.stderr();
    int discoveredTools = Math.max(tools, parseToolCount(probeOutput));
    McpTestResult result;
    if (probe.exitCode() == 0 && mcpProbeSucceeded(probeOutput)) {
      result = new McpTestResult(serverName, "connected", discoveredTools, latencyMs, null, checkedAt);
    } else {
      result = new McpTestResult(
          serverName, "error", discoveredTools, null, probeError(probe.stdout(), probe.stderr()), checkedAt);
    }
    mcpProbeCache.put(cacheKey, new CachedMcpProbe(fingerprint, result));
    return result;
  }

  static int parseToolCount(String output) {
    String text = output == null ? "" : output;
    Matcher matcher = TOOL_COUNT.matcher(text);
    int count = 0;
    while (matcher.find()) count = Math.max(count, Integer.parseInt(matcher.group(1)));
    matcher = DISCOVERED_TOOL_COUNT.matcher(text);
    while (matcher.find()) count = Math.max(count, Integer.parseInt(matcher.group(1)));
    return count;
  }

  static boolean mcpProbeSucceeded(String output) {
    String clean = ANSI.matcher(output == null ? "" : output).replaceAll("");
    return MCP_CONNECTED.matcher(clean).find();
  }

  private static String probeError(String stdout, String stderr) {
    String text = stderr == null || stderr.isBlank() ? stdout : stderr;
    String clean = ANSI.matcher(text == null ? "" : text).replaceAll("").trim();
    if (clean.isBlank()) return "MCP handshake failed";
    String[] lines = clean.split("\\R");
    String detail = lines[lines.length - 1].trim();
    if (detail.length() > 300) detail = detail.substring(0, 300);
    return detail.isBlank() ? "MCP handshake failed" : detail;
  }

  private static String mcpFingerprint(
      String transport, String endpoint, String command, String args, boolean enabled) {
    return Integer.toHexString(Objects.hash(transport, endpoint, command, args, enabled));
  }

  private List<IntegrationDto> listIntegrations(String url, String containerId, String profileName) {
    String json = readFile(url, containerId, profileDir(profileName) + "/gateway_state.json");
    if (json == null || json.isBlank()) return List.of();
    try {
      Map<?, ?> root = objectMapper.readValue(json, Map.class);
      Object platforms = root.get("platforms");
      if (!(platforms instanceof Map<?, ?> platformsMap)) return List.of();
      List<IntegrationDto> result = new ArrayList<>();
      for (Map.Entry<?, ?> e : platformsMap.entrySet()) {
        String kind = stringValue(e.getKey());
        if (kind.isBlank()) continue;
        if (!isKnownIntegrationKind(kind)) continue;
        String state = "";
        if (e.getValue() instanceof Map<?, ?> p) {
          state = stringValue(p.get("state"));
          if (state.isBlank()) state = stringValue(p.get("status"));
        }
        String status = mapIntegrationStatus(state);
        String detail = state.isBlank() ? "gateway state unknown" : ("gateway " + state);
        result.add(new IntegrationDto(kind, status, detail));
      }
      return result;
    } catch (Exception e) {
      log.warn("could not read gateway integrations: {}", e.toString());
      return List.of();
    }
  }

  private boolean isKnownIntegrationKind(String kind) {
    return switch (kind) {
      case "slack", "whatsapp", "discord", "telegram", "signal", "email",
           "github", "filesystem", "browser", "database" -> true;
      default -> false;
    };
  }

  private String mapIntegrationStatus(String state) {
    String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
    return switch (s) {
      case "connected", "up", "ok" -> "up";
      case "degraded", "warning", "warn" -> "degraded";
      case "off", "disabled", "paused" -> "off";
      case "down", "disconnected", "error", "fail" -> "down";
      default -> "down";
    };
  }

  ModelInfo parseModelString(String value) {
    if (value == null || value.isBlank()) {
      return new ModelInfo("auto", "");
    }
    String trimmed = value.trim();
    int idx = trimmed.indexOf('/');
    if (idx > 0) {
      return new ModelInfo(trimmed.substring(0, idx), trimmed.substring(idx + 1));
    }
    return new ModelInfo("auto", trimmed);
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String maskApiKey(String env, String provider) {
    if (env == null || env.isBlank()) return "";
    String key = apiKeyVar(normalizeProvider(provider));
    if (key == null) return "";
    for (String line : env.split("\\R")) {
      if (line.startsWith(key + "=")) {
        return Secrets.mask(line.substring(key.length() + 1));
      }
    }
    return "";
  }

  ExecResult exec(String url, String containerId, List<String> command) {
    return exec(url, containerId, command, true);
  }

  /**
   * check=false callers (e.g. dirExists) interpret the exit code themselves — and an exit
   * code of {@link DockerExecService#EXIT_STATUS_UNAVAILABLE} means the daemon never
   * reported one, so "did the command succeed" has no answer.
   */
  private ExecResult exec(String url, String containerId, List<String> command, boolean check) {
    return dockerExec.runAsUser(
        url, containerId, "hermes", command, "Hermes command", check, false, Duration.ofSeconds(30));
  }

  private ExecResult execSensitive(
      String url, String containerId, List<String> command, String operation) {
    return dockerExec.runAsUser(
        url, containerId, "hermes", command, operation, true, true, Duration.ofSeconds(30));
  }

  private record McpCacheKey(String url, String containerId, String profileName, String serverName) {}

  private record CachedMcpProbe(String fingerprint, McpTestResult result) {}

  record ConfigInfo(String provider, String model, String cwd) {}

  record ModelInfo(String provider, String model) {}

  private record SkillMeta(String name, String source, String version, String description) {}
}
