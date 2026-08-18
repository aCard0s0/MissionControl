package io.hermes.missioncontrol.errors;

/**
 * The request is well-formed but the state it would act on refuses it: a name already taken, an
 * operation already running, a stored file or column Mission Control will not rewrite blind, a
 * secret that must be re-entered before it can be used.
 *
 * <p>Maps to 409. Distinct from an unmapped {@link RuntimeException}, which means Mission Control
 * itself is broken and answers 500 with a stack trace at ERROR — a distinction that matters
 * because alerting keyed on 5xx should not fire because someone hand-edited a config file.
 */
public class ResourceConflictException extends RuntimeException {
  public ResourceConflictException(String message) {
    super(message);
  }

  public ResourceConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
