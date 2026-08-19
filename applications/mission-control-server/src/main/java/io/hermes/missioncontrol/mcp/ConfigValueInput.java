package io.hermes.missioncontrol.mcp;

/** A structured environment variable or HTTP header supplied by the client. */
public record ConfigValueInput(String key, String value, boolean secret, Boolean clear) {

  /**
   * What an MCP server's environment variable may be named.
   *
   * <p>Shared with {@code agents/McpServerDefinition}, which validates the same keys on their
   * way into a profile's {@code mcp_servers} block. The two are the same variable seen twice: a
   * catalog entry's environment is copied onto an Agent when the two are connected, so a rule
   * only one of them enforces means the catalog accepts a key that then fails the connect with
   * a 400 the operator cannot act on.
   */
  public static final String ENV_KEY_PATTERN = "[A-Za-z_][A-Za-z0-9_]{0,127}";

  public boolean shouldClear() {
    return Boolean.TRUE.equals(clear);
  }
}
