package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.EnableWebhookPlatformRequest;
import io.hermes.missioncontrol.agents.api.SubscribeWebhookRequest;
import io.hermes.missioncontrol.agents.api.WebhookSubscriptionDto;
import io.hermes.missioncontrol.agents.api.WebhooksDto;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A profile's webhook routes: what hermes' own {@code webhook_subscriptions.json} means, and
 * what argv each mutation sends.
 *
 * <p>The listing fixture is captured from {@code nousresearch/hermes-agent} v0.16.0 — the
 * file is keyed by route name rather than being an array, and it holds each route's HMAC
 * secret in plaintext, which is the reason a listing never carries one.
 */
class HermesWebhooksTest {

  private static final String URL = "unix:///var/run/docker.sock";
  private static final String CONTAINER = "c1";

  private static final String ENABLED_CONFIG = """
      platforms:
        webhook:
          enabled: true
          extra:
            host: "0.0.0.0"
            port: 8644
      """;

  /** Records every argv and answers reads from canned files. */
  private static final class Exec extends HermesContainerFiles {
    private final List<List<String>> commands = new ArrayList<>();
    private String subscriptions = "";
    private String config = "";

    Exec() {
      super(null);
    }

    @Override
    String readFile(String url, String containerId, String path) {
      commands.add(List.of("readFile", path));
      return path.endsWith("config.yaml") ? config : subscriptions;
    }

    @Override
    ExecResult exec(String url, String containerId, List<String> command, boolean check) {
      commands.add(command);
      return new ExecResult(0, "delivered", "");
    }

    List<List<String>> hermesCommands() {
      return commands.stream()
          .filter(c -> !c.isEmpty() && "hermes".equals(c.getFirst()))
          .toList();
    }
  }

  private final Exec exec = new Exec();
  private final HermesWebhooks webhooks = new HermesWebhooks(exec, new ObjectMapper());

  private static String fixture() throws IOException {
    return Files.readString(
        Path.of("src/test/resources/fixtures/hermes-0.16.0/webhook-subscriptions.json"));
  }

  private WebhooksDto listed() throws IOException {
    exec.subscriptions = fixture();
    exec.config = ENABLED_CONFIG;
    return webhooks.list(URL, CONTAINER, "default");
  }

  // ── the secret must not travel with a listing ──────────────────────────────

  @Test
  void aListingCarriesOnlyAMaskedSecret() throws IOException {
    for (WebhookSubscriptionDto route : listed().subscriptions()) {
      assertTrue(route.secretMasked().startsWith("..."), route.name());
      // the real secret is 43 characters of base64url; a mask cannot be that long
      assertTrue(route.secretMasked().length() < 10, route.name());
    }
  }

  @Test
  void theSecretIsReadableOnItsOwn_becauseTheProviderSigningRequestsNeedsIt() throws IOException {
    exec.subscriptions = fixture();

    String secret = webhooks.secret(URL, CONTAINER, "default", "grafana");

    assertEquals("FfrKrfUrccoJwEDfeIW_t2brVGBacVyfK4IcNsFWjd0", secret);
  }

  @Test
  void anUnknownRouteHasNoSecretToReveal() throws IOException {
    exec.subscriptions = fixture();

    assertThrows(IllegalArgumentException.class,
        () -> webhooks.secret(URL, CONTAINER, "default", "nope"));
  }

  // ── reading what hermes stored ─────────────────────────────────────────────

  @Test
  void everyRouteInTheFileIsListed() throws IOException {
    List<String> names = listed().subscriptions().stream().map(WebhookSubscriptionDto::name).toList();

    // the file is an object keyed by route name, not an array
    assertEquals(List.of("deploys", "grafana"), names);
  }

  @Test
  void aRouteCarriesTheFieldsThePageRenders() throws IOException {
    WebhookSubscriptionDto grafana = listed().subscriptions().stream()
        .filter(r -> "grafana".equals(r.name())).findFirst().orElseThrow();

    assertEquals("Grafana alerting", grafana.description());
    assertEquals(List.of("alert.firing", "alert.resolved"), grafana.events());
    assertEquals("Alert {alert.name} is {status}", grafana.prompt());
    assertEquals("log", grafana.deliver());
    assertFalse(grafana.deliverOnly());
    assertTrue(grafana.createdAt() > 0);
  }

  @Test
  void anEmptyEventListMeansEveryEvent() throws IOException {
    WebhookSubscriptionDto deploys = listed().subscriptions().stream()
        .filter(r -> "deploys".equals(r.name())).findFirst().orElseThrow();

    assertEquals(List.of(), deploys.events());
    assertTrue(deploys.deliverOnly());
    assertEquals(List.of("web-research"), deploys.skills());
  }

  @Test
  void theRouteUrlNamesThePortTheListenerBoundRatherThanLocalhost() throws IOException {
    // hermes renders these as localhost, which is true inside the container and
    // misleading anywhere an operator would paste it
    WebhookSubscriptionDto route = listed().subscriptions().getFirst();

    assertTrue(route.url().endsWith(":8644/webhooks/" + route.name()), route.url());
    assertFalse(route.url().contains("localhost"));
  }

  @Test
  void anEmptyOrUnreadableFileReadsAsNoRoutes() {
    exec.subscriptions = "";
    assertTrue(webhooks.list(URL, CONTAINER, "default").subscriptions().isEmpty());

    exec.subscriptions = "{\"grafana\": {";
    assertTrue(webhooks.list(URL, CONTAINER, "default").subscriptions().isEmpty());

    // hermes writes an object; an array would mean a shape this cannot read
    exec.subscriptions = "[]";
    assertTrue(webhooks.list(URL, CONTAINER, "default").subscriptions().isEmpty());
  }

  @Test
  void readsTheProfilesOwnSubscriptionFile() throws IOException {
    exec.subscriptions = fixture();

    webhooks.list(URL, CONTAINER, "ops");

    assertTrue(exec.commands.contains(
        List.of("readFile", "/opt/data/profiles/ops/webhook_subscriptions.json")));
  }

  // ── the listener ──────────────────────────────────────────────────────────

  @Test
  void reportsTheListenerAsOffUntilTheProfileEnablesIt() {
    exec.config = "platforms: {}\n";

    assertFalse(webhooks.list(URL, CONTAINER, "default").platform().enabled());
  }

  @Test
  void reportsWhereTheListenerBoundOnceItIsOn() throws IOException {
    var platform = listed().platform();

    assertTrue(platform.enabled());
    assertEquals("0.0.0.0", platform.host());
    assertEquals(8644, platform.port());
  }

  @Test
  void neverClaimsARouteIsReachable_becauseNoAgentPortIsPublished() throws IOException {
    // Mission Control publishes no port for an agent container, so a configured route is
    // unreachable from outside the docker network until an operator exposes it
    assertFalse(listed().platform().published());
  }

  @Test
  void enablingTheListenerSetsItsBindAddressAndPort() {
    webhooks.setPlatformEnabled(URL, CONTAINER, "default",
        new EnableWebhookPlatformRequest(true, "127.0.0.1", 9000));

    assertEquals(List.of(
        List.of("hermes", "config", "set", "platforms.webhook.enabled", "true"),
        List.of("hermes", "config", "set", "platforms.webhook.extra.host", "127.0.0.1"),
        List.of("hermes", "config", "set", "platforms.webhook.extra.port", "9000")),
        exec.hermesCommands());
  }

  @Test
  void enablingWithoutABindAddressUsesHermesOwnDefaults() {
    webhooks.setPlatformEnabled(URL, CONTAINER, "default",
        new EnableWebhookPlatformRequest(true, null, null));

    assertTrue(exec.hermesCommands().contains(
        List.of("hermes", "config", "set", "platforms.webhook.extra.host", "0.0.0.0")));
    assertTrue(exec.hermesCommands().contains(
        List.of("hermes", "config", "set", "platforms.webhook.extra.port", "8644")));
  }

  @Test
  void disablingTheListenerLeavesItsAddressAlone() {
    // the secrets an operator already handed to a provider stay valid, so turning the
    // listener off and on again must not rewrite the endpoint
    webhooks.setPlatformEnabled(URL, CONTAINER, "default",
        new EnableWebhookPlatformRequest(false, null, null));

    assertEquals(List.of(List.of("hermes", "config", "set", "platforms.webhook.enabled", "false")),
        exec.hermesCommands());
  }

  @Test
  void aPortOutsideTheLegalRangeIsRefused() {
    for (int port : List.of(0, 65_536, -1)) {
      assertThrows(IllegalArgumentException.class, () -> webhooks.setPlatformEnabled(
          URL, CONTAINER, "default", new EnableWebhookPlatformRequest(true, null, port)));
    }
  }

  // ── what each mutation asks hermes to do ──────────────────────────────────

  @Test
  void subscribingPassesEveryFieldTheFormCollected() {
    webhooks.subscribe(URL, CONTAINER, "default", new SubscribeWebhookRequest(
        "grafana", "Alert {alert.name}", "Grafana alerting",
        List.of("alert.firing", "alert.resolved"), List.of("web-research"),
        "telegram", "12345", true));

    assertEquals(List.of("hermes", "webhook", "subscribe", "grafana",
        "--prompt", "Alert {alert.name}", "--description", "Grafana alerting",
        "--events", "alert.firing,alert.resolved", "--skills", "web-research",
        "--deliver", "telegram", "--deliver-chat-id", "12345", "--deliver-only"),
        exec.hermesCommands().getFirst());
  }

  @Test
  void neverSendsASecretOfItsOwn() {
    webhooks.subscribe(URL, CONTAINER, "default", new SubscribeWebhookRequest(
        "grafana", null, null, null, null, null, null, false));

    // hermes generates it, so a secret never travels through the dashboard to get here
    assertFalse(exec.hermesCommands().getFirst().contains("--secret"));
    assertEquals(List.of("hermes", "webhook", "subscribe", "grafana"),
        exec.hermesCommands().getFirst());
  }

  @Test
  void blankAndEmptyListFieldsAreLeftOffTheCommandLine() {
    webhooks.subscribe(URL, CONTAINER, "default", new SubscribeWebhookRequest(
        "grafana", "  ", "", List.of("", " "), List.of(), " ", null, false));

    assertEquals(List.of("hermes", "webhook", "subscribe", "grafana"),
        exec.hermesCommands().getFirst());
  }

  @Test
  void removingAndTestingAddressTheRouteByName() {
    webhooks.remove(URL, CONTAINER, "default", "grafana");
    assertEquals(List.of("hermes", "webhook", "remove", "grafana"),
        exec.hermesCommands().getFirst());

    Exec other = new Exec();
    HermesWebhooks fresh = new HermesWebhooks(other, new ObjectMapper());
    assertEquals("delivered", fresh.test(URL, CONTAINER, "default", "grafana"));
    assertEquals(List.of("hermes", "webhook", "test", "grafana"),
        other.hermesCommands().getFirst());
  }

  @Test
  void aRouteNameThatCouldCarryShellOrFlagMeaningIsRefused() {
    // route names become URL segments and argv elements
    for (String hostile : List.of("--help", "a b", "a;rm -rf /", "", "a/b", "a".repeat(65))) {
      assertThrows(IllegalArgumentException.class,
          () -> webhooks.remove(URL, CONTAINER, "default", hostile), hostile);
      assertThrows(IllegalArgumentException.class,
          () -> webhooks.subscribe(URL, CONTAINER, "default", new SubscribeWebhookRequest(
              hostile, null, null, null, null, null, null, false)), hostile);
    }
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> webhooks.list(URL, CONTAINER, "../../etc"));
  }
}
