package io.hermes.missioncontrol.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
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
   * The beans a whole-flow test drives, holding the ones it has to reach directly.
   *
   * <p>{@link #close()} exists because the executor outlives the test otherwise: in production
   * Spring calls {@code @PreDestroy} on the lifecycle, and a test has no container to do it.
   *
   * @param service   the catalog's rules
   * @param startup   the boot sequence — seeding, repair, reconciling every record
   * @param seeder    the default entries themselves, for the tests that assert on their shape
   * @param lifecycle the Compose operations, and the executor they run on
   */
  record Graph(
      McpRegistryService service,
      McpStartupReconciler startup,
      McpCatalogSeeder seeder,
      McpComposeLifecycle lifecycle) {

    void close() {
      lifecycle.shutdown();
    }
  }

  /**
   * The whole graph, with the executor the caller wants to observe operations through. A
   * same-thread or queued executor makes desired state, {@code operation_state},
   * {@code applied_revision} and a recorded failure assertable, which an async task does not.
   */
  static Graph graph(
      McpServerRepository repository,
      RetainedResourceRepository retained,
      AgentMcpLinkRepository links,
      HostService hosts,
      DockerGateway docker,
      ComposeStackManager compose,
      AppProperties props,
      ExecutorService operations) {
    McpConfigStore configs = new McpConfigStore(new SecretsAtRest(cipher()), new ObjectMapper());
    McpComposeLifecycle lifecycle = new McpComposeLifecycle(repository, retained, hosts, docker,
        compose, new ComposeStackRenderer(), configs, operations,
        org.mockito.Mockito.mock(io.hermes.missioncontrol.docker.RegistryTagService.class));
    McpCatalogSeeder seeder = new McpCatalogSeeder(repository, configs);
    return new Graph(
        new McpRegistryService(
            repository,
            retained,
            links,
            hosts,
            configs,
            new McpServerDtoMapper(configs, links, lifecycle),
            lifecycle,
            new McpHealthProbe(repository, configs, hosts, docker),
            new McpLogReader(hosts, docker, compose, configs)),
        new McpStartupReconciler(repository, seeder, lifecycle, props),
        seeder,
        lifecycle);
  }
}
