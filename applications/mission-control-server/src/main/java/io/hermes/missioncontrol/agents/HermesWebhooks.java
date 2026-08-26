package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.EnableWebhookPlatformRequest;
import io.hermes.missioncontrol.agents.api.OutboundWebhookRequest;
import io.hermes.missioncontrol.agents.api.SubscribeWebhookRequest;
import io.hermes.missioncontrol.agents.api.WebhookPlatformDto;
import io.hermes.missioncontrol.agents.api.WebhookSubscriptionDto;
import io.hermes.missioncontrol.agents.api.WebhooksDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.secrets.Secrets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A profile's inbound webhook routes, which hermes owns.
 *
 * <p>Two things have to be true before a route can fire: the profile's {@code webhook}
 * platform is enabled in its config (that is the listener, with its own bind address and
 * port), and a route exists under it. Mission Control manages both and nothing more — it
 * never carries webhook traffic itself, and never publishes a port for one. An agent
 * container therefore has no port mapped, so a route is configured but unreachable from
 * outside the docker network until an operator exposes it deliberately;
 * {@link WebhookPlatformDto#published()} is how the page says so, and
 * docs/architecture.md records why that is a decision rather than a gap.
 *
 * <p>Reads come from {@code webhook_subscriptions.json}, writes from {@code hermes webhook},
 * for the same reasons as the schedule: the file is the data, the CLI owns generating the
 * HMAC secret and validating a route name.
 *
 * <p><b>The secret never travels with a listing.</b> Hermes stores it in plaintext, and an
 * operator does need it to configure the sending provider — so the listing carries only a
 * masked tail and {@link #secret} is a separate, deliberate read.
 */
@Component
public class HermesWebhooks {

  private static final Logger log = LoggerFactory.getLogger(HermesWebhooks.class);

  /**
   * Route names become URL segments and argv elements. As with cron job ids, the first
   * character may not be a hyphen: a route called {@code --help} would be read by hermes as
   * an option rather than as the route to act on.
   */
  private static final Pattern ROUTE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}");

  private static final String PLATFORM_KEY = "platforms.webhook";
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 8644;

  /** How far above {@link #DEFAULT_PORT} a defaulted listener will look for a free port. */
  private static final int PORT_SEARCH_RANGE = 64;

  private final HermesContainerFiles files;
  private final HermesCli cli;
  private final ObjectMapper objectMapper;
  private final ProfileInventory inventory;
  private final HermesConfigEditor config;

  HermesWebhooks(
      HermesContainerFiles files,
      HermesCli cli, ObjectMapper objectMapper, ProfileInventory inventory,
      HermesConfigEditor config) {
    this.files = files;
    this.cli = cli;
    this.objectMapper = objectMapper;
    this.inventory = inventory;
    this.config = config;
  }

  public WebhooksDto list(DockerHostRef host, String containerId, String profileName) {
    return new WebhooksDto(
        read(host, containerId, profileName),
        platform(host, containerId, profileName),
        config.outboundWebhooks(
            files.readFile(host, containerId, ProfilePaths.configFile(profileName))));
  }

  // ── outbound targets ───────────────────────────────────────────────────────

  /**
   * Outbound targets live in {@code config.yaml}, not in hermes' webhook store, and hermes
   * ships no CLI command that edits them — so unlike every inbound mutation here, these
   * three rewrite the YAML directly, the same way the MCP block is edited.
   *
   * <p>Hermes registers the list at startup, so a change lands on the next gateway restart
   * rather than immediately. Saying so is the caller's job; silently implying it took effect
   * is what makes an operator think delivery is broken.
   */
  public WebhooksDto addOutbound(
      DockerHostRef host, String containerId, String profileName, OutboundWebhookRequest request) {
    rewriteConfig(host, containerId, profileName,
        (yaml, path) -> config.addOutboundWebhook(yaml, path, request));
    return list(host, containerId, profileName);
  }

  public WebhooksDto updateOutbound(
      DockerHostRef host, String containerId, String profileName, int index,
      OutboundWebhookRequest request) {
    rewriteConfig(host, containerId, profileName,
        (yaml, path) -> config.updateOutboundWebhook(yaml, path, index, request));
    return list(host, containerId, profileName);
  }

  public WebhooksDto removeOutbound(
      DockerHostRef host, String containerId, String profileName, int index) {
    rewriteConfig(host, containerId, profileName,
        (yaml, path) -> config.removeOutboundWebhook(yaml, path, index));
    return list(host, containerId, profileName);
  }

  /** Serialized for the reason given on {@code HermesProfileMcp.rewriteConfig}: the read and the
   *  write are separate execs, and this file has four other editors. */
  private void rewriteConfig(
      DockerHostRef host, String containerId, String profileName, ConfigRewrite rewrite) {
    files.serialized(containerId, profileName, () -> {
      String configPath = files.requireProfileDir(host, containerId, profileName) + "/config.yaml";
      String configYaml = files.readFile(host, containerId, configPath);
      files.writeFileAtomically(
          host, containerId, configPath, rewrite.apply(configYaml, configPath));
    });
  }

  @FunctionalInterface
  private interface ConfigRewrite {
    String apply(String configYaml, String configPath);
  }

  /**
   * Turns the listener on or off. Enabling mints a global HMAC secret only when the profile
   * has none, so toggling the listener twice does not invalidate secrets an operator has
   * already given to a provider.
   *
   * <p>The address is resolved <em>before</em> anything is written: a refused port used to
   * leave the listener switched on with whatever port it had before, which is a listener an
   * operator turned on and that never binds.
   */
  public WebhooksDto setPlatformEnabled(
      DockerHostRef host, String containerId, String profileName, EnableWebhookPlatformRequest request) {
    ProfilePaths.profileDir(profileName);   // before a URL-sourced name reaches a read below
    String bindHost = null;
    Integer port = null;
    if (request.enabled()) {
      bindHost = HermesCli.notBlank(request.host()) ? request.host().trim() : DEFAULT_HOST;
      port = resolvePort(host, containerId, profileName, request.port());
    }
    cli.setConfig(host, containerId, profileName, PLATFORM_KEY + ".enabled",
        String.valueOf(request.enabled()));
    if (request.enabled()) {
      cli.setConfig(host, containerId, profileName, PLATFORM_KEY + ".extra.host", bindHost);
      cli.setConfig(host, containerId, profileName, PLATFORM_KEY + ".extra.port", String.valueOf(port));
    }
    return list(host, containerId, profileName);
  }

  /**
   * The port this profile's listener will bind — never one another profile in the same
   * container already holds.
   *
   * <p>Every profile's listener defaults to {@value #DEFAULT_PORT} and every profile in a
   * container shares one network namespace, so two enabled profiles on the default collide.
   * The second listener simply fails to bind, and hermes says so only in the gateway log of a
   * profile nobody has open — the page would show the route as configured and the provider's
   * POSTs would go nowhere.
   *
   * <p>An explicitly chosen port that is taken is refused rather than moved: the operator
   * picked it because something outside is already pointed at it. A defaulted one walks up
   * from {@value #DEFAULT_PORT} to the first free port instead, because there is no choice
   * to respect.
   */
  private int resolvePort(DockerHostRef host, String containerId, String profileName, Integer requested) {
    if (requested != null) requirePort(requested);
    Map<Integer, String> taken = portsHeldByOtherProfiles(host, containerId, profileName);
    if (requested != null) {
      String holder = taken.get(requested);
      if (holder != null) {
        throw new ResourceConflictException("webhook port " + requested
            + " is already used by profile " + holder + " in this container");
      }
      return requested;
    }
    for (int port = DEFAULT_PORT; port <= DEFAULT_PORT + PORT_SEARCH_RANGE; port++) {
      if (!taken.containsKey(port)) return port;
    }
    throw new ResourceConflictException("no free webhook port between " + DEFAULT_PORT + " and "
        + (DEFAULT_PORT + PORT_SEARCH_RANGE) + " — choose one explicitly");
  }

  /**
   * Port to profile, for every <em>other</em> profile whose listener is on.
   *
   * <p>An enabled listener with no port recorded counts as holding {@value #DEFAULT_PORT}:
   * that is what hermes binds when its config says nothing, and a profile enabled through the
   * CLI rather than through here has no {@code extra.port} at all.
   */
  private Map<Integer, String> portsHeldByOtherProfiles(
      DockerHostRef host, String containerId, String profileName) {
    Map<Integer, String> taken = new LinkedHashMap<>();
    for (String other : inventory.names(host, containerId)) {
      if (other.equals(profileName)) continue;
      WebhookPlatformDto platform = platform(host, containerId, other);
      if (!platform.enabled()) continue;
      taken.putIfAbsent(platform.port() == null ? DEFAULT_PORT : platform.port(), other);
    }
    return taken;
  }

  public WebhooksDto subscribe(
      DockerHostRef host, String containerId, String profileName, SubscribeWebhookRequest request) {
    List<String> command = new ArrayList<>(
        List.of("webhook", "subscribe", requireRoute(request.name())));
    HermesCli.addOption(command, "--prompt", request.prompt());
    HermesCli.addOption(command, "--description", request.description());
    HermesCli.addOption(command, "--events", joined(request.events()));
    HermesCli.addOption(command, "--skills", joined(request.skills()));
    HermesCli.addOption(command, "--deliver", request.deliver());
    HermesCli.addOption(command, "--deliver-chat-id", request.deliverChatId());
    if (request.deliverOnly()) command.add("--deliver-only");
    // no --secret: hermes generates one, so a secret never travels through the dashboard
    cli.stdout(host, containerId, profileName, command);
    return list(host, containerId, profileName);
  }

  public WebhooksDto remove(DockerHostRef host, String containerId, String profileName, String route) {
    cli.stdout(host, containerId, profileName, List.of("webhook", "remove", requireRoute(route)));
    return list(host, containerId, profileName);
  }

  /** Fires hermes' own test POST at the route, so an operator can prove it is wired. */
  public String test(DockerHostRef host, String containerId, String profileName, String route) {
    return cli.stdout(host, containerId, profileName, List.of("webhook", "test", requireRoute(route)));
  }

  /**
   * The route's HMAC secret in full, read on request rather than carried by every listing.
   * The provider that will sign requests needs it, and hermes has no command that prints it,
   * so it comes from the file.
   */
  public String secret(DockerHostRef host, String containerId, String profileName, String route) {
    requireRoute(route);
    JsonNode node = subscriptions(host, containerId, profileName).path(route);
    if (node.isMissingNode()) throw new IllegalArgumentException("no such webhook route");
    return node.path("secret").asText("");
  }

  // ── reading ────────────────────────────────────────────────────────────────

  private List<WebhookSubscriptionDto> read(DockerHostRef host, String containerId, String profileName) {
    JsonNode root = subscriptions(host, containerId, profileName);
    WebhookPlatformDto platform = platform(host, containerId, profileName);
    List<WebhookSubscriptionDto> out = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> entries = root.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      out.add(toDto(entry.getKey(), entry.getValue(), platform));
    }
    out.sort(Comparator.comparing(WebhookSubscriptionDto::name));
    return out;
  }

  /** Hermes keys the file by route name, so this is an object rather than an array. */
  private JsonNode subscriptions(DockerHostRef host, String containerId, String profileName) {
    String json = files.readFile(
        host, containerId, ProfilePaths.webhookSubscriptionsFile(profileName));
    if (json == null || json.isBlank()) return objectMapper.createObjectNode();
    try {
      JsonNode root = objectMapper.readTree(json);
      return root.isObject() ? root : objectMapper.createObjectNode();
    } catch (Exception e) {
      log.warn("unreadable webhook_subscriptions.json for profile {}: {}",
          profileName, e.getMessage());
      return objectMapper.createObjectNode();
    }
  }

  private WebhookSubscriptionDto toDto(String name, JsonNode node, WebhookPlatformDto platform) {
    List<String> events = new ArrayList<>();
    for (JsonNode event : node.path("events")) events.add(event.asText());
    List<String> skills = new ArrayList<>();
    for (JsonNode skill : node.path("skills")) skills.add(skill.asText());
    return new WebhookSubscriptionDto(
        name,
        HermesCli.text(node, "description"),
        routeUrl(platform, name),
        events,
        HermesCli.text(node, "prompt"),
        skills,
        HermesCli.text(node, "deliver"),
        node.path("deliver_only").asBoolean(false),
        Secrets.mask(HermesCli.text(node, "secret")),
        HermesCli.epochMillis(HermesCli.text(node, "created_at")));
  }

  /**
   * Where a provider would POST. Built from the listener's own port rather than from
   * hermes' rendering, which says {@code localhost} — true inside the container and
   * misleading anywhere an operator would paste it.
   */
  private String routeUrl(WebhookPlatformDto platform, String route) {
    int port = platform.port() != null ? platform.port() : DEFAULT_PORT;
    return "http://<agent-host>:" + port + "/webhooks/" + route;
  }

  private WebhookPlatformDto platform(DockerHostRef host, String containerId, String profileName) {
    Map<?, ?> config =
        YamlValues.parseMap(files.readFile(host, containerId, ProfilePaths.configFile(profileName)));
    Map<?, ?> webhook = childMap(childMap(config, "platforms"), "webhook");
    Map<?, ?> extra = childMap(webhook, "extra");
    Object enabled = webhook.get("enabled");
    Object port = extra.get("port");
    return new WebhookPlatformDto(
        Boolean.TRUE.equals(enabled) || "true".equals(String.valueOf(enabled)),
        extra.get("host") == null ? null : String.valueOf(extra.get("host")),
        port instanceof Number number ? number.intValue() : null,
        // Mission Control publishes no port for an agent container, so a route is never
        // reachable from outside the docker network on its own
        false);
  }

  // ── writing ────────────────────────────────────────────────────────────────

  /** One level down a parsed config, or an empty map when the key is absent or not a map. */
  private static Map<?, ?> childMap(Map<?, ?> parent, String key) {
    return parent.get(key) instanceof Map<?, ?> child ? child : Map.of();
  }

  private static String joined(List<String> values) {
    if (values == null) return null;
    List<String> kept = values.stream().filter(HermesCli::notBlank).map(String::trim).toList();
    return kept.isEmpty() ? null : String.join(",", kept);
  }



  private static String requireRoute(String route) {
    if (route == null || !ROUTE.matcher(route).matches()) {
      throw new IllegalArgumentException("invalid webhook route name");
    }
    return route;
  }

  private static void requirePort(int port) {
    if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid webhook port");
  }


}
