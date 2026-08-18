package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.EnableWebhookPlatformRequest;
import io.hermes.missioncontrol.agents.api.SubscribeWebhookRequest;
import io.hermes.missioncontrol.agents.api.WebhookPlatformDto;
import io.hermes.missioncontrol.agents.api.WebhookSubscriptionDto;
import io.hermes.missioncontrol.agents.api.WebhooksDto;
import io.hermes.missioncontrol.secrets.Secrets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
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
 * never carries webhook traffic itself. An agent container publishes no port, so a route is
 * configured but unreachable from outside the docker network until an operator exposes it
 * deliberately; {@link WebhookPlatformDto#published()} is how the page says so.
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
  private static final int DEFAULT_PORT = 8644;

  private final HermesContainerFiles files;
  private final ObjectMapper objectMapper;

  HermesWebhooks(HermesContainerFiles files, ObjectMapper objectMapper) {
    this.files = files;
    this.objectMapper = objectMapper;
  }

  public WebhooksDto list(String url, String containerId, String profileName) {
    return new WebhooksDto(
        read(url, containerId, profileName), platform(url, containerId, profileName));
  }

  /**
   * Turns the listener on or off. Enabling mints a global HMAC secret only when the profile
   * has none, so toggling the listener twice does not invalidate secrets an operator has
   * already given to a provider.
   */
  public WebhooksDto setPlatformEnabled(
      String url, String containerId, String profileName, EnableWebhookPlatformRequest request) {
    setConfig(url, containerId, profileName, PLATFORM_KEY + ".enabled",
        String.valueOf(request.enabled()));
    if (request.enabled()) {
      String host = notBlank(request.host()) ? request.host().trim() : "0.0.0.0";
      int port = request.port() != null ? request.port() : DEFAULT_PORT;
      requirePort(port);
      setConfig(url, containerId, profileName, PLATFORM_KEY + ".extra.host", host);
      setConfig(url, containerId, profileName, PLATFORM_KEY + ".extra.port", String.valueOf(port));
    }
    return list(url, containerId, profileName);
  }

  public WebhooksDto subscribe(
      String url, String containerId, String profileName, SubscribeWebhookRequest request) {
    List<String> command = new ArrayList<>(
        List.of("webhook", "subscribe", requireRoute(request.name())));
    addOption(command, "--prompt", request.prompt());
    addOption(command, "--description", request.description());
    addOption(command, "--events", joined(request.events()));
    addOption(command, "--skills", joined(request.skills()));
    addOption(command, "--deliver", request.deliver());
    addOption(command, "--deliver-chat-id", request.deliverChatId());
    if (request.deliverOnly()) command.add("--deliver-only");
    // no --secret: hermes generates one, so a secret never travels through the dashboard
    run(url, containerId, profileName, command);
    return list(url, containerId, profileName);
  }

  public WebhooksDto remove(String url, String containerId, String profileName, String route) {
    run(url, containerId, profileName, List.of("webhook", "remove", requireRoute(route)));
    return list(url, containerId, profileName);
  }

  /** Fires hermes' own test POST at the route, so an operator can prove it is wired. */
  public String test(String url, String containerId, String profileName, String route) {
    return run(url, containerId, profileName, List.of("webhook", "test", requireRoute(route)));
  }

  /**
   * The route's HMAC secret in full, read on request rather than carried by every listing.
   * The provider that will sign requests needs it, and hermes has no command that prints it,
   * so it comes from the file.
   */
  public String secret(String url, String containerId, String profileName, String route) {
    requireRoute(route);
    JsonNode node = subscriptions(url, containerId, profileName).path(route);
    if (node.isMissingNode()) throw new IllegalArgumentException("no such webhook route");
    return node.path("secret").asText("");
  }

  // ── reading ────────────────────────────────────────────────────────────────

  private List<WebhookSubscriptionDto> read(String url, String containerId, String profileName) {
    JsonNode root = subscriptions(url, containerId, profileName);
    WebhookPlatformDto platform = platform(url, containerId, profileName);
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
  private JsonNode subscriptions(String url, String containerId, String profileName) {
    String json = files.readFile(
        url, containerId, ProfilePaths.webhookSubscriptionsFile(profileName));
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
        text(node, "description"),
        routeUrl(platform, name),
        events,
        text(node, "prompt"),
        skills,
        text(node, "deliver"),
        node.path("deliver_only").asBoolean(false),
        Secrets.mask(text(node, "secret")),
        epochMillis(text(node, "created_at")));
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

  private WebhookPlatformDto platform(String url, String containerId, String profileName) {
    Map<?, ?> config =
        YamlValues.parseMap(files.readFile(url, containerId, ProfilePaths.configFile(profileName)));
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

  private String run(
      String url, String containerId, String profileName, List<String> hermesArgs) {
    List<String> command = new ArrayList<>(ProfilePaths.hermesCli(profileName));
    command.addAll(hermesArgs);
    return files.exec(url, containerId, command, true).stdout();
  }

  private void setConfig(
      String url, String containerId, String profileName, String key, String value) {
    List<String> command = new ArrayList<>(ProfilePaths.hermesCli(profileName));
    command.addAll(List.of("config", "set", key, value));
    files.exec(url, containerId, command, true);
  }

  /** One level down a parsed config, or an empty map when the key is absent or not a map. */
  private static Map<?, ?> childMap(Map<?, ?> parent, String key) {
    return parent.get(key) instanceof Map<?, ?> child ? child : Map.of();
  }

  private static String joined(List<String> values) {
    if (values == null) return null;
    List<String> kept = values.stream().filter(HermesWebhooks::notBlank).map(String::trim).toList();
    return kept.isEmpty() ? null : String.join(",", kept);
  }

  private static void addOption(List<String> command, String flag, String value) {
    if (notBlank(value)) {
      command.add(flag);
      command.add(value.trim());
    }
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
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

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }

  private static Long epochMillis(String isoTimestamp) {
    if (!notBlank(isoTimestamp)) return null;
    try {
      return OffsetDateTime.parse(isoTimestamp).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(isoTimestamp).toEpochMilli();
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
  }
}
