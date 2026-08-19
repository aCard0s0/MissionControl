package io.hermes.missioncontrol.docker;

/**
 * An exec was asked for inside a container that is not running.
 *
 * <p>The daemon reports this as a 409, which docker-java raises as its own
 * {@code ConflictException}. Translating it here is what keeps that type inside this
 * package: {@code HermesProfiles} treats a stopped container as "inventory is simply
 * unavailable until it restarts" and needs to recognise the case, which previously meant the
 * agents package importing docker-java's exception hierarchy to catch it.
 *
 * <p>Maps to 409, the same status the untranslated exception produced.
 */
public class ContainerNotRunningException extends RuntimeException {

  public ContainerNotRunningException(String message, Throwable cause) {
    super(message, cause);
  }
}
