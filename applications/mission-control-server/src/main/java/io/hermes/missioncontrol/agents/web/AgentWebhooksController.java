package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesWebhooks;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import io.hermes.missioncontrol.agents.api.EnableWebhookPlatformRequest;
import io.hermes.missioncontrol.agents.api.OutboundWebhookRequest;
import io.hermes.missioncontrol.agents.api.SubscribeWebhookRequest;
import io.hermes.missioncontrol.agents.api.WebhooksDto;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A profile's inbound webhook routes. Mission Control manages the listener and the routes;
 * it never receives webhook traffic itself, so there is no endpoint here that a provider
 * would ever call.
 */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}/{name}/webhooks")
class AgentWebhooksController {

  private final HermesWebhooks webhooks;
  private final HostService hosts;

  AgentWebhooksController(HermesWebhooks webhooks, HostService hosts) {
    this.webhooks = webhooks;
    this.hosts = hosts;
  }

  @GetMapping
  public WebhooksDto list(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return webhooks.list(hosts.requireConnected(hostId), containerId, name);
  }

  /** Turns the profile's webhook listener on or off. */
  @PutMapping("/platform")
  public WebhooksDto setPlatform(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody EnableWebhookPlatformRequest request) {
    return webhooks.setPlatformEnabled(hosts.requireConnected(hostId), containerId, name, request);
  }

  @PostMapping
  public WebhooksDto subscribe(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody SubscribeWebhookRequest request) {
    return webhooks.subscribe(hosts.requireConnected(hostId), containerId, name, request);
  }

  /**
   * The route's HMAC secret, which the sending provider needs. Its own endpoint on purpose:
   * a secret must not ride along in the listing the dashboard polls.
   */
  /**
   * The outbound half: targets hermes POSTs signed lifecycle events to. Hermes registers the
   * list at startup, so an edit here takes effect when the gateway next restarts.
   *
   * <p>Addressed by position, because that is the only handle hermes gives a target —
   * {@code name} is optional and not unique. A stale index is a 404, not a rewrite of
   * whatever moved into that slot.
   */
  @PostMapping("/outbound")
  public WebhooksDto addOutbound(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody OutboundWebhookRequest request) {
    return webhooks.addOutbound(hosts.requireConnected(hostId), containerId, name, request);
  }

  @PutMapping("/outbound/{index}")
  public WebhooksDto updateOutbound(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable int index,
      @Valid @RequestBody OutboundWebhookRequest request) {
    return webhooks.updateOutbound(
        hosts.requireConnected(hostId), containerId, name, index, request);
  }

  @DeleteMapping("/outbound/{index}")
  public WebhooksDto removeOutbound(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable int index) {
    return webhooks.removeOutbound(hosts.requireConnected(hostId), containerId, name, index);
  }

  @GetMapping("/{route}/secret")
  public Map<String, String> secret(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String route) {
    return Map.of(
        "secret", webhooks.secret(hosts.requireConnected(hostId), containerId, name, route));
  }

  /** Fires hermes' own test POST at the route. */
  @PostMapping("/{route}/test")
  public Map<String, String> test(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String route) {
    return Map.of(
        "output", webhooks.test(hosts.requireConnected(hostId), containerId, name, route));
  }

  @DeleteMapping("/{route}")
  public WebhooksDto remove(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String route) {
    return webhooks.remove(hosts.requireConnected(hostId), containerId, name, route);
  }
}
