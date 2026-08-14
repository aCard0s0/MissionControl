package io.hermes.missioncontrol.docker;

/**
 * Implemented by stores that key dashboard-owned rows on a Docker container id.
 *
 * <p>An image update recreates the container, which mints a new id — so those
 * rows have to follow it or they silently detach from the Agent they describe.
 * The seam exists so this package does not have to depend on the packages that
 * own those tables.
 *
 * @return how many rows were moved
 */
public interface ContainerIdListener {

  int onContainerReplaced(String hostId, String oldContainerId, String newContainerId);
}
