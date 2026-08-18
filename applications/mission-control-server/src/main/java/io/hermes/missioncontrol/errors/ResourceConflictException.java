package io.hermes.missioncontrol.errors;

/** A requested resource name conflicts with existing external/runtime state. */
public class ResourceConflictException extends RuntimeException {
  public ResourceConflictException(String message) {
    super(message);
  }
}
