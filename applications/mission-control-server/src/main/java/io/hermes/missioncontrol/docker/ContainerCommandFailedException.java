package io.hermes.missioncontrol.docker;

/**
 * A command run inside a container exited non-zero.
 *
 * <p>Translated here for the same reason as {@link ContainerNotRunningException}: so callers can
 * recognise the case without importing docker-java, and so the HTTP layer can answer something
 * other than 500. Before this type the exit was a bare {@code RuntimeException}, which no advice
 * matched, so {@code ApiExceptionHandler}'s catch-all reported every hermes CLI rejection — a
 * schedule expression it cannot parse, an unknown skill id, a webhook route already taken — as a
 * Mission Control defect: 500, with a stack trace at ERROR, tripping any alerting keyed on 5xx.
 *
 * <p>Maps to 400, because the dominant case is the CLI refusing what the request asked for, and
 * the message already carries its reason ({@code stderr}, or {@code stdout} for the subcommands
 * that report failures there) trimmed to one bounded line. A {@code sensitive} operation carries
 * only the operation name and the exit code, never argv or output.
 *
 * <p>A caller for which a non-zero exit means something else translates at its own seam —
 * {@link DeploymentReadiness} answers 503, because a gateway that is slow to come up is not a bad
 * request. That translation used to be a {@code catch (RuntimeException)}, which also swallowed
 * genuine defects into a 503; catching this type instead leaves them alone.
 */
public class ContainerCommandFailedException extends RuntimeException {

  public ContainerCommandFailedException(String message) {
    super(message);
  }
}
