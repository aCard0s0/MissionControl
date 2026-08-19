package io.hermes.missioncontrol.mcp;

/**
 * The names that make a Compose resource part of the managed MCP stack: the project, the
 * network Agents reach it over, and the labels that mark it as ours.
 *
 * <p>Each of these is written in one place and read in another, and every pair has to agree.
 * The network name is the one that had escaped this package: {@code agents} held its own copy
 * of the literal to attach an Agent container to, so renaming it here would have left every
 * Agent attaching to a network no managed server is on — and the health probe, which reads the
 * renderer's constant, would still have passed. The owner label was written by
 * {@link ComposeStackRenderer} and read back by {@link ComposeStackManager} from two separate
 * literals, where a rename turns every ownership guard into "exists but is not owned by
 * Mission Control MCP".
 *
 * <p>Same shape and the same reason as {@code docker/ManagedContainer}, which centralises the
 * labels and volume prefix that make a container one Mission Control deployed.
 */
public final class ManagedMcpStack {

  /** Compose project every managed MCP service belongs to. */
  public static final String PROJECT = "mission-control-mcp";

  /**
   * The user-defined network the stack runs on. Agents are attached to it on demand so a
   * managed server resolves by its Compose service name; Mission Control attaches itself for
   * the same reason before probing one.
   */
  public static final String NETWORK = "mission-control-mcp-net";

  /** Marks a container, network or volume as belonging to {@link #PROJECT}. */
  public static final String OWNER_LABEL = "io.hermes.mission-control.owner";

  /** Ties a rendered service or volume back to the catalog row it came from. */
  public static final String SERVER_ID_LABEL = "io.hermes.mission-control.mcp-server-id";

  private ManagedMcpStack() {}

  /** Every managed volume is named for its Compose key under the project's own prefix. */
  static String volumeName(String volumeKey) {
    return PROJECT + "-" + volumeKey;
  }
}
