package io.hermes.missioncontrol.docker;

import io.hermes.missioncontrol.config.AppProperties;

/**
 * Builds the Docker collaborator graph the way Spring does, for tests that drive a whole
 * operation through {@link DockerGateway}.
 *
 * <p>Tests aimed at one collaborator construct it directly — its own factory below keeps
 * that a one-liner without pulling in the rest of the graph.
 */
final class DockerWiring {

  private DockerWiring() {}

  static ImageStore images(DockerClients clients, AppProperties props) {
    return new ImageStore(clients, props);
  }

  static ContainerInventory inventory(DockerClients clients, AppProperties props) {
    return new ContainerInventory(clients, props, images(clients, props));
  }

  static HermesDeployer deployer(
      DockerClients clients, AppProperties props, DockerExecService dockerExec) {
    return new HermesDeployer(
        clients, images(clients, props), new DeploymentReadiness(dockerExec));
  }

  static DockerGateway gateway(
      DockerClients clients, AppProperties props, DockerExecService dockerExec) {
    ImageStore images = images(clients, props);
    DeploymentReadiness readiness = new DeploymentReadiness(dockerExec);
    DockerNetworks networks = new DockerNetworks(clients);
    return new DockerGateway(
        new ContainerInventory(clients, props, images),
        new ContainerStatsReader(clients),
        new ContainerLogReader(clients),
        networks,
        images,
        new HermesDeployer(clients, images, readiness),
        new ContainerUpgrader(clients, props, images, readiness, networks),
        new ContainerLifecycle(clients));
  }
}
