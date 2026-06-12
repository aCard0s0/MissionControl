package io.hermes.missioncontrol.hermes;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.DockerClients;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class HermesProfiles {

  private static final String HERMES_HOME = "/opt/data";
  private static final String PROFILES_DIR = "/opt/data/profiles";
  private static final String PLATFORM_CLI = "cli";
  private static final Pattern PROFILE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

  private final DockerClients clients;
  private final Yaml yaml = new Yaml();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public HermesProfiles(DockerClients clients) {
    this.clients = clients;
  }

  public List<AgentProfileDto> list(String url, String containerId) {
    List<String> names = listProfileNames(url, containerId);
    List<AgentProfileDto> profiles = new ArrayList<>();
    for (String name : names) {
      profiles.add(readProfile(url, containerId, name));
    }
    return profiles;
  }

  public AgentProfileDto create(String url, CreateAgentRequest request) {
    String profileName = request.name();
    if (profileName == null || !PROFILE_NAME.matcher(profileName).matches()) {
      throw new IllegalArgumentException("invalid profile name");
    }
    List<String> command = new ArrayList<>(List.of("hermes", "profile", "create", profileName));
    String cloneFrom = request.cloneFrom();
    if (cloneFrom != null && !cloneFrom.isBlank()) {
      command.addAll(List.of("--clone", "--clone-from", cloneFrom));
    }
    exec(url, request.containerId(), command);
    String baseUrl = request.baseUrl();
    if (baseUrl != null && !baseUrl.isBlank()) {
      configureModelWithBaseUrl(url, request.containerId(), profileName, request.model(), baseUrl);
    } else {
      configureModel(url, request.containerId(), profileName, request.provider(), request.model());
    }
    seedEnvIfMissing(url, request.containerId(), profileName);
    String envKey = apiKeyVar(normalizeProvider(request.provider()));
    if (envKey != null && request.apiKey() != null && !request.apiKey().isBlank()) {
      writeEnvVar(url, request.containerId(), profileName, envKey, request.apiKey());
    }
    return readProfile(url, request.containerId(), profileName);
  }

  public void delete(String url, String containerId, String name) {
    exec(url, containerId, List.of("hermes", "profile", "delete", name, "--yes"));
  }

  public void updateSoul(String url, String containerId, String name, String soul) {
    String path = profileDir(name) + "/SOUL.md";
    writeFile(url, containerId, path, soul == null ? "" : soul);
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
    writeFile(url, containerId, profileDir(name) + "/config.yaml", configYaml);
    return readProfile(url, containerId, name);
  }

  public AgentProfileDto setSkillEnabled(String url, String containerId, String profileName, String skillName, boolean enabled) {
    if (skillName == null || skillName.isBlank()) {
      throw new IllegalArgumentException("missing skill name");
    }
    String configPath = profileDir(profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    Map<Object, Object> root = parseConfigForEdit(configYaml, configPath);
    Map<Object, Object> skills = asMutableMap(root.get("skills"));
    root.put("skills", skills);
    Map<Object, Object> platformDisabled = asMutableMap(skills.get("platform_disabled"));
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
   *  skill name, but SKILL.md frontmatter may override the display name. */
  private String findSkillDir(String url, String containerId, String profileName, String skillName) {
    String skillsDir = profileDir(profileName) + "/skills";
    String direct = skillsDir + "/" + skillName;
    if (dirExists(url, containerId, direct)) return direct;
    ExecResult ls = exec(url, containerId, List.of("sh", "-lc", "ls -1 \"$1\" 2>/dev/null || true", "_", skillsDir));
    for (String line : ls.stdout().split("\\R")) {
      String dirName = line.trim();
      if (dirName.isEmpty()) continue;
      String skillMd = readFile(url, containerId, skillsDir + "/" + dirName + "/SKILL.md");
      if (skillMd.isBlank()) continue;
      if (skillName.equals(parseSkillMeta(skillMd, dirName).name())) {
        return skillsDir + "/" + dirName;
      }
    }
    return null;
  }

  public AgentProfileDto addMcpServer(String url, String containerId, String profileName, AddMcpServerRequest request) {
    String name = request.name();
    if (name == null || name.isBlank()) throw new IllegalArgumentException("missing server name");
    String configPath = profileDir(profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    Map<Object, Object> root = parseConfigForEdit(configYaml, configPath);
    Map<Object, Object> servers = asMutableMap(root.get("mcp_servers"));
    root.put("mcp_servers", servers);
    Map<Object, Object> server = asMutableMap(servers.get(name));

    String transport = request.transport() == null ? "" : request.transport().trim().toLowerCase(Locale.ROOT);
    if ("stdio".equals(transport)) {
      String command = request.command();
      if (command == null || command.isBlank()) throw new IllegalArgumentException("missing command");
      server.put("command", command.trim());
      String args = request.args();
      if (args != null && !args.isBlank()) {
        server.put("args", splitArgs(args.trim()));
      }
      server.remove("url");
      server.remove("headers");
    } else if ("http".equals(transport) || "sse".equals(transport)) {
      String urlValue = request.url();
      if (urlValue == null || urlValue.isBlank()) throw new IllegalArgumentException("missing url");
      server.put("url", urlValue.trim());
      server.remove("command");
      server.remove("args");
    } else {
      throw new IllegalArgumentException("invalid transport");
    }

    Boolean enabled = request.enabled();
    if (enabled != null) {
      server.put("enabled", enabled);
    } else if (!server.containsKey("enabled")) {
      server.put("enabled", true);
    }

    servers.put(name, server);
    writeFile(url, containerId, configPath, yaml.dump(root));
    return readProfile(url, containerId, profileName);
  }

  public AgentProfileDto removeMcpServer(String url, String containerId, String profileName, String serverName) {
    if (serverName == null || serverName.isBlank()) throw new IllegalArgumentException("missing server name");
    String configPath = profileDir(profileName) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    Map<Object, Object> root = parseConfigForEdit(configYaml, configPath);
    Map<Object, Object> servers = asMutableMap(root.get("mcp_servers"));
    servers.remove(serverName);
    root.put("mcp_servers", servers);
    writeFile(url, containerId, configPath, yaml.dump(root));
    return readProfile(url, containerId, profileName);
  }

  public List<IntegrationDto> integrations(String url, String containerId, String profileName) {
    return listIntegrations(url, containerId, profileName);
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
    List<McpServerDto> mcp = listMcpServers(configMap);
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

  private void configureModel(String url, String containerId, String name, String provider, String model) {
    String normalizedProvider = normalizeProvider(provider);
    String modelValue = normalizedProvider.isBlank() ? model : normalizedProvider + "/" + model;
    exec(url, containerId, List.of("hermes", "-p", name, "config", "set", "model", modelValue));
  }

  /** Custom-endpoint providers (e.g. ollama) have no `hermes config set model`
   *  provider prefix — write model.default and model.base_url into config.yaml
   *  directly, leaving model.provider and every other key untouched. */
  private void configureModelWithBaseUrl(String url, String containerId, String name, String model, String baseUrl) {
    String configPath = profileDir(name) + "/config.yaml";
    String configYaml = readFile(url, containerId, configPath);
    Map<Object, Object> root = parseConfigForEdit(configYaml, configPath);
    Map<Object, Object> modelNode = asMutableMap(root.get("model"));
    modelNode.put("default", model);
    modelNode.put("base_url", baseUrl);
    root.put("model", modelNode);
    writeFile(url, containerId, configPath, yaml.dump(root));
  }

  private String normalizeProvider(String provider) {
    if (provider == null) return "";
    String trimmed = provider.trim().toLowerCase(Locale.ROOT);
    if (trimmed.startsWith("nous")) return "nous";
    return trimmed;
  }

  private String apiKeyVar(String provider) {
    return switch (provider) {
      case "anthropic" -> "ANTHROPIC_API_KEY";
      case "openai" -> "OPENAI_API_KEY";
      case "openrouter" -> "OPENROUTER_API_KEY";
      default -> null;
    };
  }

  void writeEnvVar(String url, String containerId, String name, String key, String value) {
    String path = profileDir(name) + "/.env";
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\"; value=\"$3\";",
        "touch \"$path\";",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "printf '%s=%s\\n' \"$key\" \"$value\" >> \"$path.tmp\";",
        "mv \"$path.tmp\" \"$path\";");
    exec(url, containerId, List.of("sh", "-lc", script, "_", path, key, value));
  }

  void removeEnvVar(String url, String containerId, String name, String key) {
    String path = profileDir(name) + "/.env";
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\";",
        "[ -f \"$path\" ] || exit 0;",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "mv \"$path.tmp\" \"$path\";");
    exec(url, containerId, List.of("sh", "-lc", script, "_", path, key));
  }

  /** Writes the documented commented-out .env template; no-op when .env exists. */
  void seedEnvIfMissing(String url, String containerId, String name) {
    String path = profileDir(name) + "/.env";
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

  /** Read-for-edit variant: a config we cannot parse must abort the edit —
   *  falling back to an empty map would rewrite the file and wipe it. */
  private Map<Object, Object> parseConfigForEdit(String yamlText, String configPath) {
    if (yamlText == null || yamlText.isBlank()) return new java.util.LinkedHashMap<>();
    try {
      Object loaded = yaml.load(yamlText);
      if (loaded instanceof Map<?, ?> map) return new java.util.LinkedHashMap<>(map);
    } catch (Exception e) {
      throw new IllegalStateException("refusing to rewrite unparseable " + configPath, e);
    }
    throw new IllegalStateException("refusing to rewrite unparseable " + configPath);
  }

  private ConfigInfo parseConfig(Map<?, ?> map) {
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
      ModelInfo info = parseModelString(defaultValue);
      provider = providerValue.isBlank() ? info.provider() : providerValue;
      model = info.model().isBlank() ? defaultValue : info.model();
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
    ExecResult ls = exec(url, containerId, List.of("sh", "-lc", "ls -1 \"$1\" 2>/dev/null || true", "_", skillsDir));
    Set<String> disabled = disabledSkills(configMap, PLATFORM_CLI);
    List<SkillDto> skills = new ArrayList<>();
    for (String line : ls.stdout().split("\\R")) {
      String dirName = line.trim();
      if (dirName.isEmpty()) continue;
      String skillMd = readFile(url, containerId, skillsDir + "/" + dirName + "/SKILL.md");
      if (skillMd == null || skillMd.isBlank()) continue;
      SkillMeta meta = parseSkillMeta(skillMd, dirName);
      boolean enabled = !disabled.contains(meta.name());
      skills.add(new SkillDto(
          meta.name(),
          meta.name(),
          meta.source(),
          meta.version(),
          meta.description(),
          enabled));
    }
    return skills;
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

  private Map<Object, Object> asMutableMap(Object node) {
    if (node instanceof Map<?, ?> m) {
      return new java.util.LinkedHashMap<>(m);
    }
    return new java.util.LinkedHashMap<>();
  }

  /** Shell-style tokenizer so quoted MCP args keep their internal spaces. */
  private List<String> splitArgs(String args) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0; else cur.append(c);
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (Character.isWhitespace(c)) {
        if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
      } else {
        cur.append(c);
      }
    }
    if (cur.length() > 0) out.add(cur.toString());
    return out;
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
            return new SkillMeta(name.isBlank() ? fallbackName : name, "bundled", version, description);
          }
        } catch (Exception ignored) { }
      }
    }
    return new SkillMeta(fallbackName, "bundled", "", "");
  }

  private List<McpServerDto> listMcpServers(Map<?, ?> configMap) {
    Object mcpServers = configMap == null ? null : configMap.get("mcp_servers");
    if (!(mcpServers instanceof Map<?, ?> serversMap)) return List.of();
    List<McpServerDto> result = new ArrayList<>();
    for (Map.Entry<?, ?> e : serversMap.entrySet()) {
      String name = stringValue(e.getKey());
      if (name.isBlank()) continue;
      Object cfg = e.getValue();
      if (!(cfg instanceof Map<?, ?> server)) continue;
      boolean enabled = !"false".equalsIgnoreCase(stringValue(server.get("enabled")));
      String transport = server.containsKey("command") ? "stdio" : "http";
      int tools = 0;
      Object toolsNode = server.get("tools");
      if (toolsNode instanceof Map<?, ?> toolsMap) {
        Object include = toolsMap.get("include");
        if (include instanceof List<?> list) {
          tools = (int) list.stream().filter(x -> !stringValue(x).isBlank()).count();
        }
      }
      result.add(new McpServerDto(name, name, transport, enabled ? "connected" : "disabled", tools, null));
    }
    return result;
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
    } catch (Exception ignored) {
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

  private ModelInfo parseModelString(String value) {
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
        String value = line.substring(key.length() + 1).trim();
        if (value.length() <= 4) return "..." + value;
        return "..." + value.substring(value.length() - 4);
      }
    }
    return "";
  }

  ExecResult exec(String url, String containerId, List<String> command) {
    return exec(url, containerId, command, true);
  }

  /** check=false callers (e.g. dirExists) interpret the exit code themselves. */
  private ExecResult exec(String url, String containerId, List<String> command, boolean check) {
    DockerClient client = clients.forUrl(url);
    ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .withCmd(command.toArray(new String[0]))
        .exec();

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        if (frame.getPayload() == null) return;
        if (frame.getStreamType() == StreamType.STDERR) {
          stderr.writeBytes(frame.getPayload());
        } else {
          stdout.writeBytes(frame.getPayload());
        }
      }
    };
    boolean finished;
    try {
      finished = client.execStartCmd(exec.getId()).exec(callback).awaitCompletion(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("command interrupted: " + String.join(" ", command), e);
    } finally {
      try {
        callback.close();
      } catch (Exception ignored) { }
    }
    if (!finished) {
      throw new RuntimeException("command timed out: " + String.join(" ", command));
    }
    Integer exit = client.inspectExecCmd(exec.getId()).exec().getExitCode();
    int exitCode = exit == null ? 0 : exit;
    if (check && exitCode != 0) {
      String message = stderr.toString(StandardCharsets.UTF_8).trim();
      if (message.isEmpty()) message = stdout.toString(StandardCharsets.UTF_8).trim();
      if (message.isEmpty()) message = "command failed with exit code " + exitCode;
      throw new RuntimeException(message);
    }
    return new ExecResult(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  record ExecResult(int exitCode, String stdout, String stderr) {}

  private record ConfigInfo(String provider, String model, String cwd) {}

  private record ModelInfo(String provider, String model) {}

  private record SkillMeta(String name, String source, String version, String description) {}
}
