package io.hermes.missioncontrol.docker;

import java.util.List;

/**
 * Work Mission Control itself has in flight inside a container — things the daemon knows
 * nothing about, and a stop would cut short.
 *
 * <p>The one implementation today is the profile create: between {@code hermes profile create}
 * and its last configuration write the profile is on disk but unusable, and a stop makes the
 * next exec fail, which rolls the profile back. {@link ContainerLifecycle} asks before a stop
 * or an upgrade. An interface in this package rather than a reference to the agents package,
 * because the dependency already points the other way.
 */
@FunctionalInterface
public interface ContainerWork {

  /** Profile names still being created in this container; empty when nothing is. */
  List<String> creating(String containerId);
}
