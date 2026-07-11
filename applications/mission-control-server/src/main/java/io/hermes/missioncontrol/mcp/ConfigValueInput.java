package io.hermes.missioncontrol.mcp;

/** A structured environment variable or HTTP header supplied by the client. */
public record ConfigValueInput(String key, String value, boolean secret, Boolean clear) {
  public boolean shouldClear() {
    return Boolean.TRUE.equals(clear);
  }
}
