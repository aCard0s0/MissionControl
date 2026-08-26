package io.hermes.missioncontrol.docker;

/**
 * How a container id is written into a log line.
 *
 * <p>The 12-character prefix the Docker CLI and the dashboard both show. A full 64-character id
 * is unreadable and matches nothing an operator has in front of them. Five call sites across
 * {@code docker} and {@code terminal} carried their own copy of this one expression.
 *
 * <p>Not the same as {@link ContainerDto#shortId()}, which is 7 characters because that is what
 * the fleet table has room for. The two lengths are a display decision each, which is exactly
 * why the log one needs somewhere to live rather than being re-typed per logger.
 */
public final class ContainerIds {

  private ContainerIds() {}

  public static String shortId(String containerId) {
    return containerId == null ? "?" : containerId.substring(0, Math.min(12, containerId.length()));
  }
}
