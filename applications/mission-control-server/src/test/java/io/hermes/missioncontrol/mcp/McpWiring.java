package io.hermes.missioncontrol.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.secrets.SecretCipher;
import java.util.concurrent.ExecutorService;

/**
 * Builds the catalog collaborator graph the way Spring does, for tests that drive a whole flow
 * through {@link McpRegistryService} rather than one collaborator.
 *
 * <p>The same role {@code AgentsWiring} and {@code DockerWiring} play in their packages, and the
 * reason {@link McpRegistryService} no longer needs to assemble its own collaborators: the one
 * dependency a test substitutes is the operations executor, which this takes as a parameter.
 *
 * <p>Tests that only need one collaborator construct it directly — that is the point of the
 * split, and going through this helper hides which one is under test.
 */
final class McpWiring {

  private McpWiring() {}

  static SecretCipher cipher() {
    return new SecretCipher("test-secret", "", false);
  }

  /**
   * The whole graph, with the executor the caller wants to observe operations through. A
   * same-thread or queued executor makes desired state, {@code operation_state},
   * {@code applied_revision} and a recorded failure assertable, which an async task does not.
   */
  static McpRegistryService registry(
      McpServerRepository repository,
      RetainedResourceRepository retained,
      AgentMcpLinkRepository links,
      HostService hosts,
      DockerGateway docker,
      ComposeStackManager compose,
      AppProperties props,
      ExecutorService operations) {
    McpConfigStore configs = new McpConfigStore(cipher(), new ObjectMapper());
    return new McpRegistryService(
        repository,
        retained,
        links,
        hosts,
        configs,
        new McpServerDtoMapper(configs),
        new McpComposeLifecycle(repository, retained, hosts, docker, compose,
            new ComposeStackRenderer(), configs, operations),
        new McpHealthProbe(repository, configs, hosts, docker),
        new McpCatalogSeeder(repository, configs),
        new McpLogReader(hosts, docker, compose, configs),
        props);
  }
}
