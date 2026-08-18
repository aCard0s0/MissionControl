package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rule deciding which mountinfo line identifies the container owning this process'
 * network namespace. Attaching the MCP network to the wrong container leaves managed MCP
 * servers unreachable, and the shared-namespace case is the one that is easy to get wrong.
 */
class ContainerIdFromMountinfoTest {

  private static final String MC_ID = "a".repeat(64);
  private static final String TAILSCALE_ID = "b".repeat(64);

  @Test
  void picksTheContainerIdOutOfAnOrdinaryMountinfo() {
    List<String> lines = List.of(
        "1234 1200 0:100 / / rw,relatime - overlay overlay rw,lowerdir=/var/lib/docker/overlay2",
        "1240 1234 8:1 /var/lib/docker/containers/" + MC_ID + "/hostname /etc/hostname rw,relatime - ext4 /dev/sda1 rw");

    assertEquals(MC_ID, McpHealthProbe.containerIdFrom(lines));
  }

  @Test
  void takesTheFirstMatchWhenTheNamespaceIsSharedWithAnotherContainer() {
    // network_mode: service:tailscale — the network files are bind-mounted out of the
    // namespace owner, and that is the container the MCP network must be attached to
    List<String> lines = List.of(
        "1240 1234 8:1 /var/lib/docker/containers/" + TAILSCALE_ID + "/resolv.conf /etc/resolv.conf rw - ext4 /dev/sda1 rw",
        "1241 1234 8:1 /var/lib/docker/containers/" + MC_ID + "/hostname /etc/hostname rw - ext4 /dev/sda1 rw");

    assertEquals(TAILSCALE_ID, McpHealthProbe.containerIdFrom(lines));
  }

  @Test
  void returnsNullWhenNotRunningInAContainer() {
    List<String> lines = List.of(
        "22 28 0:21 / /sys rw,nosuid,nodev,noexec,relatime shared:7 - sysfs sysfs rw",
        "23 28 0:22 / /proc rw,nosuid,nodev,noexec,relatime shared:14 - proc proc rw");

    assertNull(McpHealthProbe.containerIdFrom(lines));
  }

  @Test
  void returnsNullForAnEmptyMountinfo() {
    assertNull(McpHealthProbe.containerIdFrom(List.of()));
  }

  @Test
  void ignoresAPathThatOnlyLooksLikeAContainerId() {
    // too short to be a container id — a partial match here would attach the wrong network
    List<String> lines = List.of(
        "1240 1234 8:1 /var/lib/docker/containers/abc123/hostname /etc/hostname rw - ext4 /dev/sda1 rw");

    assertNull(McpHealthProbe.containerIdFrom(lines));
  }
}
