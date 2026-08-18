package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretInput;
import io.hermes.missioncontrol.secrets.SecretRef;
import io.hermes.missioncontrol.secrets.StoredSecret;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + apply logic for reusable {@link ProfileTemplate}s. Secrets are encrypted
 * on the way in (kept on blank input), never returned to the client (only a
 * set/recoverable flag), and decrypted only when applied to a live agent.
 */
@Service
public class ProfileTemplateService {

  private static final Logger log = LoggerFactory.getLogger(ProfileTemplateService.class);
  /** Matches the env-key rule HermesSetup enforces at write time. */
  private static final Pattern ENV_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  /** Generous ceiling for a single secret value (API keys/tokens are short). */
  private static final int MAX_SECRET_LEN = 65_536;

  private final ProfileTemplateRepository repository;
  private final SecretCipher cipher;
  private final HermesProfiles profiles;
  private final HermesSetup setup;
  private final McpRegistryService mcpRegistry;

  @Autowired
  public ProfileTemplateService(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup,
      McpRegistryService mcpRegistry) {
    this.repository = repository;
    this.cipher = cipher;
    this.profiles = profiles;
    this.setup = setup;
    this.mcpRegistry = mcpRegistry;
  }

  /** Narrow constructor retained for CRUD-focused unit tests. */
  ProfileTemplateService(
      ProfileTemplateRepository repository,
      SecretCipher cipher,
      HermesProfiles profiles,
      HermesSetup setup) {
    this(repository, cipher, profiles, setup, null);
  }

  // ── CRUD ─────────────────────────────────────────────────────────────────
  public List<ProfileTemplateDto> list() {
    return repository.findAll().stream().map(this::toDto).toList();
  }

  public ProfileTemplateDto get(String id) {
    return toDto(require(id));
  }

  @Transactional
  public ProfileTemplateDto create(UpsertProfileTemplateRequest request) {
    if (repository.existsByName(request.name())) {
      throw new IllegalArgumentException("a template named '" + request.name() + "' already exists");
    }
    long now = System.currentTimeMillis();
    ProfileTemplate template = build(newId(), request, null, now, now);
    repository.insert(template);
    return toDto(template);
  }

  @Transactional
  public ProfileTemplateDto update(String id, UpsertProfileTemplateRequest request) {
    ProfileTemplate existing = require(id);
    // mirror create()'s friendly 400 instead of letting the UNIQUE(name)
    // constraint surface as an opaque DB error when renaming onto another template
    if (repository.existsByNameExcept(request.name(), id)) {
      throw new IllegalArgumentException("a template named '" + request.name() + "' already exists");
    }
    ProfileTemplate template = build(id, request, existing, existing.createdAt(), System.currentTimeMillis());
    repository.update(template);
    return toDto(template);
  }

  public void delete(String id) {
    repository.delete(id);
  }

  // ── apply / deploy ─────────────────────────────────────────────────────────
  /** Create a new agent in {@code containerId} from a template and apply it. */
  public AgentProfileDto deploy(String id, String url, String containerId, String name) {
    return applyTo(require(id), url, containerId, name, true);
  }

  /** Layer a template onto an agent that already exists (used by the create flow). */
  public AgentProfileDto applyExisting(String id, String url, String containerId, String name) {
    return applyTo(require(id), url, containerId, name, false);
  }

  /** Create the caller-configured base profile and apply a template as one owned operation. */
  public AgentProfileDto createFromTemplate(String id, String url, CreateAgentRequest request) {
    ProfileTemplate template = require(id);
    profiles.createProfileBare(url, request);
    try {
      return applyTo(template, url, request.containerId(), request.name(), false);
    } catch (RuntimeException failure) {
      try {
        profiles.delete(url, request.containerId(), request.name());
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
        log.warn("rollback of partially-created profile '{}' failed: {}",
            request.name(), cleanup.getMessage());
      }
      throw failure;
    }
  }

  private AgentProfileDto applyTo(
      ProfileTemplate t, String url, String containerId, String name, boolean create) {
    if (create) {
      CreateAgentRequest req = new CreateAgentRequest(
          null, containerId, name,
          blankTo(t.provider(), "nous"),
          blankTo(t.model(), "Hermes-4-405B"),
          null, null, blankToNull(t.baseUrl()), null, null);
      profiles.create(url, req);
    }
    try {
      if (!t.soul().isBlank()) {
        profiles.updateSoul(url, containerId, name, t.soul());
      }
      if (!t.memory().isBlank()) {
        profiles.updateMemory(url, containerId, name, t.memory());
      }
      for (String skill : t.skills()) {
        if (skill != null && !skill.isBlank()) {
          profiles.installSkill(url, containerId, name, skill.trim());
        }
      }
      for (McpServerSpec m : t.mcpServers()) {
        if (m == null || m.name() == null || m.name().isBlank()) {
          continue;
        }
        Map<String, String> headers = decryptValues(m.headers());
        Map<String, String> mcpEnvironment = "stdio".equalsIgnoreCase(m.transport())
            ? decryptValues(m.environment()) : Map.of();
        profiles.addMcpServer(url, containerId, name,
            new AddMcpServerRequest(
                m.name(), m.transport(), m.url(), m.command(), m.args(), m.enabled(),
                headers, mcpEnvironment));
      }
      List<EnvEntry> env = new ArrayList<>();
      for (StoredSecret s : t.secrets()) {
        String value = safeDecrypt(s.enc());
        if (value != null && !value.isBlank()) {
          env.add(new EnvEntry(s.key(), value));
        }
      }
      if (!env.isEmpty()) {
        setup.putEnv(url, containerId, name, env);
      }
      return profiles.get(url, containerId, name);
    } catch (RuntimeException e) {
      // a half-applied template leaves a misconfigured profile. We own the
      // profile only when we just created it — roll it back so deploy is all
      // or nothing. The fromTemplateId path (create=false) layers onto a
      // user-created profile, so leave that one in place and just surface the error.
      if (create) {
        try {
          profiles.delete(url, containerId, name);
        } catch (RuntimeException cleanup) {
          log.warn("rollback of partially-deployed profile '{}' failed: {}", name, cleanup.getMessage());
        }
      }
      throw e;
    }
  }

  // ── capture from a running agent ───────────────────────────────────────────
  // No @Transactional: this reads the agent over docker (slow) before its single
  // atomic insert, and the datasource pool is size 1 — holding the sole connection
  // across those execs would serialize the whole app.
  public ProfileTemplateDto captureFromAgent(
      String url, String containerId, String agentName, String templateName) {
    AgentProfileDto agent = profiles.get(url, containerId, agentName);
    AgentSetupDto agentSetup = setup.setup(url, containerId, agentName);

    List<String> skills = agent.skills().stream()
        .filter(SkillDto::enabled)
        .map(SkillDto::name)
        .toList();
    List<McpServerSpec> mcp = agent.mcp().stream()
        .map(m -> new McpServerSpec(
            m.name(), m.transport(), m.url(), m.command(), m.args(), !"disabled".equals(m.status())))
        .toList();
    // we cannot read raw .env values back — capture which keys are set, blank value
    List<StoredSecret> secrets = agentSetup.apiKeys().stream()
        .filter(ApiKeyStatusDto::set)
        .map(k -> new StoredSecret(k.envVar(), null))
        .toList();

    long now = System.currentTimeMillis();
    String name = uniqueName((templateName == null || templateName.isBlank())
        ? agentName + "-template" : templateName);
    ProfileTemplate template = new ProfileTemplate(
        newId(), name, "Captured from " + agentName,
        agent.provider(), agent.model(), "", agent.cwd(),
        agent.soul(), agent.memoryMd(), skills, mcp, secrets, now, now);
    repository.insert(template);
    return toDto(template);
  }

  // ── helpers ────────────────────────────────────────────────────────────────
  private ProfileTemplate build(
      String id, UpsertProfileTemplateRequest r, ProfileTemplate existing, long created, long updated) {
    Map<String, String> prior = new HashMap<>();   // enc may be null for capture-only placeholder keys
    if (existing != null) {
      for (StoredSecret s : existing.secrets()) {
        prior.put(s.key(), s.enc());
      }
    }
    List<StoredSecret> secrets = new ArrayList<>();
    for (SecretInput s : nz(r.secrets())) {
      if (s == null || s.key() == null || s.key().isBlank()) {
        continue;
      }
      String key = s.key().trim();
      if (!ENV_KEY.matcher(key).matches()) {
        throw new IllegalArgumentException("invalid secret key: " + key);
      }
      String value = s.value();
      if (value == null || value.isBlank()) {
        // blank input keeps the stored secret (or its capture placeholder)
        if (prior.containsKey(key)) {
          secrets.add(new StoredSecret(key, prior.get(key)));
        }
      } else {
        if (value.length() > MAX_SECRET_LEN) {
          throw new IllegalArgumentException("secret value too large for " + key);
        }
        secrets.add(new StoredSecret(key, cipher.encrypt(value)));
      }
    }
    List<McpServerSpec> mcpServers = materializeMcpSnapshots(r.mcpServers(), existing);
    return new ProfileTemplate(
        id, r.name(), nz(r.description()), nz(r.provider()), nz(r.model()),
        nz(r.baseUrl()), nz(r.cwd()), nz(r.soul()), nz(r.memory()),
        nz(r.skills()), mcpServers, secrets, created, updated);
  }

  private ProfileTemplateDto toDto(ProfileTemplate t) {
    // never echo secret material (not even a suffix) to the client — surface only
    // whether a value is stored and whether it still decrypts with the current key
    List<SecretRef> refs = t.secrets().stream()
        .map(s -> {
          boolean set = s.enc() != null;
          boolean recoverable = set && safeDecrypt(s.enc()) != null;
          return new SecretRef(s.key(), set, recoverable);
        })
        .toList();
    List<McpServerSpec> mcp = t.mcpServers().stream().map(this::redactedMcp).toList();
    return new ProfileTemplateDto(
        t.id(), t.name(), t.description(), t.provider(), t.model(), t.baseUrl(), t.cwd(),
        t.soul(), t.memory(), t.skills(), mcp, refs, t.createdAt(), t.updatedAt());
  }

  /** Resolve input-only catalog ids and persist independent encrypted copies. */
  private List<McpServerSpec> materializeMcpSnapshots(
      List<McpServerSpec> input, ProfileTemplate existing) {
    Map<String, McpServerSpec> priorByName = new HashMap<>();
    if (existing != null) {
      for (McpServerSpec prior : nz(existing.mcpServers())) {
        if (prior != null && prior.name() != null) priorByName.put(prior.name(), prior);
      }
    }

    List<McpServerSpec> result = new ArrayList<>();
    for (McpServerSpec requested : nz(input)) {
      if (requested == null) continue;
      if (requested.sourceServerId() != null && !requested.sourceServerId().isBlank()) {
        result.add(snapshotFromCatalog(requested));
        continue;
      }

      // The ordinary template editor intentionally does not receive encrypted
      // values. Preserve a prior snapshot only while its connection definition
      // remains unchanged; replacing an entry with a different custom server
      // must not accidentally carry the old credentials forward.
      McpServerSpec prior = priorByName.get(requested.name());
      boolean unchanged = prior != null && sameConnection(prior, requested);
      result.add(new McpServerSpec(
          requested.name(), requested.transport(), requested.url(), requested.command(),
          requested.args(), requested.enabled(), null,
          unchanged ? reencryptValues(prior.environment()) : null,
          unchanged ? reencryptValues(prior.headers()) : null));
    }
    return List.copyOf(result);
  }

  private McpServerSpec snapshotFromCatalog(McpServerSpec requested) {
    if (mcpRegistry == null) {
      throw new UpstreamUnavailableException("MCP registry is unavailable");
    }
    String sourceId = requested.sourceServerId().trim();
    McpServerDto source = mcpRegistry.require(sourceId);
    String alias = requested.name() == null || requested.name().isBlank()
        ? source.name() : requested.name().trim();
    boolean enabled = requested.enabled() == null || requested.enabled();

    if ("stdio".equalsIgnoreCase(source.kind())) {
      if (source.stdioCommand() == null || source.stdioCommand().isBlank()) {
        throw new IllegalArgumentException("catalog stdio server has no command: " + source.name());
      }
      Map<String, String> environment = mcpRegistry.materializedEnvironment(sourceId);
      return new McpServerSpec(
          alias, "stdio", null, source.stdioCommand(), joinArgs(source.args()), enabled,
          null, encryptValues(environment), List.of());
    }

    String url = firstNonBlank(source.crossHostUrl(), source.connectionUrl(), source.url());
    if (url == null) {
      throw new IllegalArgumentException("catalog server has no usable connection URL: " + source.name());
    }
    return new McpServerSpec(
        alias, source.transport(), url, null, null, enabled, null, List.of(),
        encryptValues(mcpRegistry.materializedHeaders(sourceId)));
  }

  private List<TemplateMcpConfigValue> encryptValues(Map<String, String> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .map(entry -> new TemplateMcpConfigValue(entry.getKey(), cipher.encrypt(entry.getValue())))
        .toList();
  }

  private Map<String, String> decryptValues(List<TemplateMcpConfigValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TemplateMcpConfigValue value : nz(values)) {
      if (value == null || value.key() == null || value.key().isBlank()) continue;
      String clear = safeDecrypt(value.encryptedValue());
      if (clear != null) result.put(value.key(), clear);
    }
    return result;
  }

  /** A normal template save is also the key-rotation opportunity for MCP
   * snapshot values, matching the behavior of template-owned API keys. */
  private List<TemplateMcpConfigValue> reencryptValues(List<TemplateMcpConfigValue> values) {
    return nz(values).stream()
        .filter(Objects::nonNull)
        .map(value -> {
          String clear = safeDecrypt(value.encryptedValue());
          return clear == null
              ? value
              : new TemplateMcpConfigValue(value.key(), cipher.encrypt(clear));
        })
        .toList();
  }

  private McpServerSpec redactedMcp(McpServerSpec value) {
    return new McpServerSpec(
        value.name(), value.transport(), value.url(), value.command(), value.args(), value.enabled(),
        null, redactValues(value.environment()), redactValues(value.headers()));
  }

  private List<TemplateMcpConfigValue> redactValues(List<TemplateMcpConfigValue> values) {
    return nz(values).stream()
        .filter(Objects::nonNull)
        .map(value -> new TemplateMcpConfigValue(value.key(), null))
        .toList();
  }

  private static boolean sameConnection(McpServerSpec left, McpServerSpec right) {
    return Objects.equals(left.transport(), right.transport())
        && Objects.equals(left.url(), right.url())
        && Objects.equals(left.command(), right.command())
        && Objects.equals(left.args(), right.args());
  }

  private static String joinArgs(List<String> args) {
    if (args == null || args.isEmpty()) return null;
    return args.stream().map(ProfileTemplateService::quoteArg).reduce((a, b) -> a + " " + b).orElse(null);
  }

  private static String quoteArg(String value) {
    if (value == null) return "''";
    if (!value.isEmpty() && value.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '\'' || ch == '"')) {
      return value;
    }
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private ProfileTemplate require(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown template: " + id));
  }

  private String uniqueName(String base) {
    String name = base;
    int n = 2;
    while (repository.existsByName(name)) {
      name = base + "-" + n++;
    }
    return name;
  }

  private String safeDecrypt(String enc) {
    if (enc == null) {
      return null;
    }
    try {
      return cipher.decrypt(enc);
    } catch (RuntimeException e) {
      // wrong MC_SECRET_KEY or corrupt ciphertext — the secret is unrecoverable.
      // Don't fail the whole read/deploy, but make the loss visible.
      log.warn("failed to decrypt a stored template secret (check MC_SECRET_KEY): {}", e.getMessage());
      return null;
    }
  }

  private String newId() {
    return "pt-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> nz(List<T> value) {
    return value == null ? List.of() : value;
  }
}
