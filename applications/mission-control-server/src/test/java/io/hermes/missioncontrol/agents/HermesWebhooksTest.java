package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.EnableWebhookPlatformRequest;
import io.hermes.missioncontrol.agents.api.SubscribeWebhookRequest;
import io.hermes.missioncontrol.agents.api.WebhookSubscriptionDto;
import io.hermes.missioncontrol.agents.api.OutboundWebhookRequest;
import io.hermes.missioncontrol.agents.api.WebhooksDto;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * A profile's webhook routes: what hermes' own {@code webhook_subscriptions.json} means, and
 * what argv each mutation sends.
 *
 * <p>The listing fixture is captured from {@code nousresearch/hermes-agent} v0.16.0 — the
 * file is keyed by route name rather than being an array, and it holds each route's HMAC
 * secret in plaintext, which is the reason a listing never carries one. The captured secrets
 * and prompts are scrubbed by {@code tools/capture-hermes-fixtures.sh}: a live signing key and
 * an operator's prompt are the two things in this file that must not reach a git repository,
 * and the placeholder keeps the shape a parser reads.
 */
class HermesWebhooksTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");
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
    /** Per-profile config.yaml, for the container-wide port checks; falls back to {@link #config}. */
    private final Map<String, String> configs = new HashMap<>();
    private List<String> namedProfiles = List.of();
    private String subscriptions = "";
    private String config = "";
    /** What a hermes webhook command prints — its only verdict, since it exits 0 either way. */
    private String verdict = "delivered";

    Exec() {
      super(null);
    }

    @Override
    String readFile(DockerHostRef host, String containerId, String path) {
      commands.add(List.of("readFile", path));
      if (!path.endsWith("config.yaml")) return subscriptions;
      String profile = path.startsWith(PROFILES_PREFIX)
          ? path.substring(PROFILES_PREFIX.length(), path.length() - "/config.yaml".length())
          : "default";
      return configs.getOrDefault(profile, config);
    }

    @Override
    ExecResult exec(DockerHostRef host, String containerId, List<String> command, boolean check) {
      commands.add(command);
      // the profile inventory's own two reads: is the hermes home there, and what is beside it
      if (command.size() > 2 && command.get(2).startsWith("ls -1")) {
        return new ExecResult(0, String.join("\n", namedProfiles), "");
      }
      return new ExecResult(0, verdict, "");
    }

    /** The last config.yaml written back, so an edit can be read rather than inferred. */
    private String written;

    @Override
    void writeFileAtomically(DockerHostRef host, String containerId, String path, String content) {
      commands.add(List.of("writeFileAtomically", path));
      written = content;
    }

    @Override
    String requireProfileDir(DockerHostRef host, String containerId, String profileName) {
      return "default".equals(profileName) ? "/opt/data" : PROFILES_PREFIX + profileName;
    }

    List<List<String>> hermesCommands() {
      return commands.stream()
          .filter(c -> !c.isEmpty() && "hermes".equals(c.getFirst()))
          .toList();
    }
  }

  private static final String PROFILES_PREFIX = "/opt/data/profiles/";

  private final Exec exec = new Exec();
  private final HermesWebhooks webhooks =
      new HermesWebhooks(exec, new HermesCli(exec), new ObjectMapper(), new ProfileInventory(exec), new HermesConfigEditor());

  private static String fixture() throws IOException {
    return Files.readString(
        Path.of("src/test/resources/fixtures/hermes-0.16.0/webhook-subscriptions.json"));
  }

  private WebhooksDto listed() throws IOException {
    exec.subscriptions = fixture();
    exec.config = ENABLED_CONFIG;
    return webhooks.list(HOST, CONTAINER, "default");
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

    String secret = webhooks.secret(HOST, CONTAINER, "default", "grafana");

    // the capture script replaces the real secret with a placeholder of the same shape — 43
    // base64url characters — so what is pinned is that this reads the field verbatim and
    // unmasked, which is the whole difference between it and a listing
    assertEquals("redacted-by-capture-hermes-fixtures-sh-0000", secret);
    assertEquals(43, secret.length());
  }

  @Test
  void anUnknownRouteHasNoSecretToReveal() throws IOException {
    exec.subscriptions = fixture();

    assertThrows(IllegalArgumentException.class,
        () -> webhooks.secret(HOST, CONTAINER, "default", "nope"));
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
    assertEquals("<redacted>", grafana.prompt(), "the capture script scrubs prompts");
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
    assertTrue(webhooks.list(HOST, CONTAINER, "default").subscriptions().isEmpty());

    exec.subscriptions = "{\"grafana\": {";
    assertTrue(webhooks.list(HOST, CONTAINER, "default").subscriptions().isEmpty());

    // hermes writes an object; an array would mean a shape this cannot read
    exec.subscriptions = "[]";
    assertTrue(webhooks.list(HOST, CONTAINER, "default").subscriptions().isEmpty());
  }

  @Test
  void readsTheProfilesOwnSubscriptionFile() throws IOException {
    exec.subscriptions = fixture();

    webhooks.list(HOST, CONTAINER, "ops");

    assertTrue(exec.commands.contains(
        List.of("readFile", "/opt/data/profiles/ops/webhook_subscriptions.json")));
  }

  // ── the listener ──────────────────────────────────────────────────────────

  @Test
  void reportsTheListenerAsOffUntilTheProfileEnablesIt() {
    exec.config = "platforms: {}\n";

    assertFalse(webhooks.list(HOST, CONTAINER, "default").platform().enabled());
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
    webhooks.setPlatformEnabled(HOST, CONTAINER, "default",
        new EnableWebhookPlatformRequest(true, "127.0.0.1", 9000));

    assertEquals(List.of(
        List.of("hermes", "config", "set", "platforms.webhook.enabled", "true"),
        List.of("hermes", "config", "set", "platforms.webhook.extra.host", "127.0.0.1"),
        List.of("hermes", "config", "set", "platforms.webhook.extra.port", "9000")),
        exec.hermesCommands());
  }

  @Test
  void enablingWithoutABindAddressUsesHermesOwnDefaults() {
    webhooks.setPlatformEnabled(HOST, CONTAINER, "default",
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
    webhooks.setPlatformEnabled(HOST, CONTAINER, "default",
        new EnableWebhookPlatformRequest(false, null, null));

    assertEquals(List.of(List.of("hermes", "config", "set", "platforms.webhook.enabled", "false")),
        exec.hermesCommands());
  }

  @Test
  void aPortOutsideTheLegalRangeIsRefusedBeforeTheListenerIsSwitchedOn() {
    for (int port : List.of(0, 65_536, -1)) {
      assertThrows(IllegalArgumentException.class, () -> webhooks.setPlatformEnabled(
          HOST, CONTAINER, "default", new EnableWebhookPlatformRequest(true, null, port)));
    }

    // writing `enabled: true` first would leave a listener an operator turned on and that
    // never binds, on whatever port the profile happened to carry before
    assertTrue(exec.hermesCommands().isEmpty());
  }

  // ── one listener port per container, not per profile ──────────────────────

  /** A container with {@code default} enabled on {@code port}, plus one other profile. */
  private void containerWhere(String otherProfile, String otherConfig) {
    exec.namedProfiles = List.of(otherProfile);
    exec.configs.put(otherProfile, otherConfig);
    exec.configs.put("default", "platforms: {}\n");
  }

  @Test
  void aPortAnotherProfileAlreadyListensOnIsRefused() {
    // profiles in one container share a network namespace: the second listener never binds,
    // and hermes says so only in the gateway log of a profile nobody has open
    containerWhere("ops", ENABLED_CONFIG);

    ResourceConflictException refused = assertThrows(ResourceConflictException.class,
        () -> webhooks.setPlatformEnabled(HOST, CONTAINER, "default",
            new EnableWebhookPlatformRequest(true, null, 8644)));

    assertTrue(refused.getMessage().contains("ops"), refused.getMessage());
    assertTrue(exec.hermesCommands().isEmpty(), "nothing may be written before the refusal");
  }

  @Test
  void aListenerWithNoPortChosenTakesTheFirstFreeOne() {
    // every profile defaults to 8644, so the second one enabled has to move rather than fail
    containerWhere("ops", ENABLED_CONFIG);

    webhooks.setPlatformEnabled(
        HOST, CONTAINER, "default", new EnableWebhookPlatformRequest(true, null, null));

    assertTrue(exec.hermesCommands().contains(
        List.of("hermes", "config", "set", "platforms.webhook.extra.port", "8645")));
  }

  @Test
  void anEnabledListenerWithNoPortRecordedStillHoldsTheDefault() {
    // a profile enabled through the hermes CLI rather than through here has no extra.port,
    // and hermes binds 8644 anyway
    containerWhere("ops", "platforms:\n  webhook:\n    enabled: true\n");

    webhooks.setPlatformEnabled(
        HOST, CONTAINER, "default", new EnableWebhookPlatformRequest(true, null, null));

    assertTrue(exec.hermesCommands().contains(
        List.of("hermes", "config", "set", "platforms.webhook.extra.port", "8645")));
  }

  @Test
  void aPortHeldByAProfileWhoseListenerIsOffIsFree() {
    containerWhere("ops", ENABLED_CONFIG.replace("enabled: true", "enabled: false"));

    webhooks.setPlatformEnabled(
        HOST, CONTAINER, "default", new EnableWebhookPlatformRequest(true, null, null));

    assertTrue(exec.hermesCommands().contains(
        List.of("hermes", "config", "set", "platforms.webhook.extra.port", "8644")));
  }

  @Test
  void aProfileDoesNotCollideWithItsOwnListener() {
    // re-enabling, or moving the bind address, must not read as a collision
    exec.namedProfiles = List.of("ops");
    exec.configs.put("ops", ENABLED_CONFIG);

    webhooks.setPlatformEnabled(
        HOST, CONTAINER, "ops", new EnableWebhookPlatformRequest(true, "127.0.0.1", 8644));

    assertTrue(exec.hermesCommands().contains(
        List.of("hermes", "-p", "ops", "config", "set", "platforms.webhook.extra.port", "8644")));
  }

  // ── what each mutation asks hermes to do ──────────────────────────────────

  @Test
  void subscribingPassesEveryFieldTheFormCollected() {
    exec.config = ENABLED_CONFIG;
    webhooks.subscribe(HOST, CONTAINER, "default", new SubscribeWebhookRequest(
        "grafana", "Alert {alert.name}", "Grafana alerting",
        List.of("alert.firing", "alert.resolved"), List.of("web-research"),
        "telegram", "12345", true));

    assertEquals(List.of("hermes", "webhook", "subscribe", "grafana",
        "--prompt=Alert {alert.name}", "--description=Grafana alerting",
        "--events=alert.firing,alert.resolved", "--skills=web-research",
        "--deliver=telegram", "--deliver-chat-id=12345", "--deliver-only"),
        exec.hermesCommands().getFirst());
  }

  @Test
  void neverSendsASecretOfItsOwn() {
    exec.config = ENABLED_CONFIG;
    webhooks.subscribe(HOST, CONTAINER, "default", new SubscribeWebhookRequest(
        "grafana", null, null, null, null, null, null, false));

    // hermes generates it, so a secret never travels through the dashboard to get here
    assertFalse(exec.hermesCommands().getFirst().contains("--secret"));
    assertEquals(List.of("hermes", "webhook", "subscribe", "grafana"),
        exec.hermesCommands().getFirst());
  }

  @Test
  void blankAndEmptyListFieldsAreLeftOffTheCommandLine() {
    exec.config = ENABLED_CONFIG;
    webhooks.subscribe(HOST, CONTAINER, "default", new SubscribeWebhookRequest(
        "grafana", "  ", "", List.of("", " "), List.of(), " ", null, false));

    assertEquals(List.of("hermes", "webhook", "subscribe", "grafana"),
        exec.hermesCommands().getFirst());
  }

  @Test
  void aRouteCannotBeAddedRemovedOrTestedWhileTheListenerIsOff() {
    // hermes v0.20.5 answers all three with a setup walkthrough and exit 0, so without the
    // guard the dashboard reported a route created that does not exist
    exec.config = "platforms: {}\n";
    SubscribeWebhookRequest request =
        new SubscribeWebhookRequest("grafana", null, null, null, null, null, null, false);

    assertThrows(ResourceConflictException.class,
        () -> webhooks.subscribe(HOST, CONTAINER, "default", request));
    assertThrows(ResourceConflictException.class,
        () -> webhooks.remove(HOST, CONTAINER, "default", "grafana"));
    assertThrows(ResourceConflictException.class,
        () -> webhooks.test(HOST, CONTAINER, "default", "grafana"));
    assertTrue(exec.hermesCommands().isEmpty(), "nothing may reach hermes while the listener is off");
  }

  @Test
  void aRouteHermesDoesNotHoldIsA404_becauseHermesSaysSoOnStdoutAndExitsZero() {
    // captured from v0.20.5: `webhook remove nope` and `webhook test nope` both print this
    // and exit 0, so without the check a stale row was "removed" with a 200 and stayed
    exec.config = ENABLED_CONFIG;
    exec.verdict = "  No subscription named 'grafana'.\n  Note: Static routes from config.yaml cannot be removed here.\n";

    assertThrows(NoSuchElementException.class, () -> webhooks.remove(HOST, CONTAINER, "default", "grafana"));
    assertThrows(NoSuchElementException.class, () -> webhooks.test(HOST, CONTAINER, "default", "grafana"));
  }

  @Test
  void removingAndTestingAddressTheRouteByName() {
    exec.config = ENABLED_CONFIG;
    webhooks.remove(HOST, CONTAINER, "default", "grafana");
    assertEquals(List.of("hermes", "webhook", "remove", "grafana"),
        exec.hermesCommands().getFirst());

    Exec other = new Exec();
    other.config = ENABLED_CONFIG;
    HermesWebhooks fresh = new HermesWebhooks(other, new HermesCli(other), new ObjectMapper(), new ProfileInventory(other), new HermesConfigEditor());
    assertEquals("delivered", fresh.test(HOST, CONTAINER, "default", "grafana"));
    assertEquals(List.of("hermes", "webhook", "test", "grafana"),
        other.hermesCommands().getFirst());
  }

  @Test
  void aRouteNameThatCouldCarryShellOrFlagMeaningIsRefused() {
    // route names become URL segments and argv elements
    for (String hostile : List.of("--help", "a b", "a;rm -rf /", "", "a/b", "a".repeat(65))) {
      assertThrows(IllegalArgumentException.class,
          () -> webhooks.remove(HOST, CONTAINER, "default", hostile), hostile);
      assertThrows(IllegalArgumentException.class,
          () -> webhooks.subscribe(HOST, CONTAINER, "default", new SubscribeWebhookRequest(
              hostile, null, null, null, null, null, null, false)), hostile);
    }
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> webhooks.list(HOST, CONTAINER, "../../etc"));
  }

  // ── outbound targets ───────────────────────────────────────────────────────

  private static final OutboundWebhookRequest CI_TARGET = new OutboundWebhookRequest(
      "ci-notify", "https://ci.example.test/hooks", List.of("on_session_end"), null, 10,
      "CI_SECRET");

  @Test
  void anOutboundTargetIsWrittenIntoTheProfileConfigRatherThanThroughTheCli() {
    // hermes ships no CLI command for hooks.outbound, so unlike every inbound mutation here
    // this one edits config.yaml — and must not have invented a hermes subcommand to do it
    exec.config = ENABLED_CONFIG;

    WebhooksDto after = webhooks.addOutbound(HOST, CONTAINER, "default", CI_TARGET);

    assertEquals(List.of(), exec.hermesCommands());
    assertTrue(exec.written.contains("https://ci.example.test/hooks"));
    assertTrue(exec.written.contains("CI_SECRET"));
    // the listener config it was added beside is still there
    assertTrue(exec.written.contains("webhook"));
    assertNotNull(after);
  }

  @Test
  void anOutboundEditRewritesTheTargetAtThatPositionAndRemovalDropsIt() {
    exec.config = """
        hooks:
          outbound:
            - url: https://old.example.test/hooks
              events: [on_session_end]
        """;

    webhooks.updateOutbound(HOST, CONTAINER, "default", 0, CI_TARGET);
    assertTrue(exec.written.contains("https://ci.example.test/hooks"));
    assertFalse(exec.written.contains("https://old.example.test/hooks"));

    webhooks.removeOutbound(HOST, CONTAINER, "default", 0);
    assertFalse(exec.written.contains("outbound"));
  }

  @Test
  void aListingCarriesTheOutboundTargetsBesideTheInboundRoutes() throws IOException {
    exec.subscriptions = fixture();
    exec.config = """
        platforms:
          webhook:
            enabled: true
        hooks:
          outbound:
            - url: https://ci.example.test/hooks
              events: [on_session_end]
              name: ci-notify
              secret_env: CI_SECRET
        """;

    WebhooksDto listed = webhooks.list(HOST, CONTAINER, "default");

    assertFalse(listed.subscriptions().isEmpty());
    assertEquals(1, listed.outbound().size());
    assertEquals("ci-notify", listed.outbound().getFirst().name());
    assertEquals("CI_SECRET", listed.outbound().getFirst().secretEnv());
  }

  @Test
  void anOutboundEditOnAProfileNameThatCouldEscapeTheProfilesDirIsRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> webhooks.addOutbound(HOST, CONTAINER, "../../etc", CI_TARGET));
  }
}
