package io.hermes.missioncontrol.docker;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param version image tag to move the container onto — the one it already runs is allowed
 *     when host access comes with it, since the recreate is then the point
 * @param ports published ports to add or remap, over the ones the container already has
 * @param env variables to add or replace
 * @param mounts bind mounts to add
 */
public record UpdateContainerRequest(
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_][A-Za-z0-9._-]{0,127}", message = "invalid image tag")
    String version,
    @Valid @Size(max = 32) List<HostAccess.PortMapping> ports,
    @Valid @Size(max = 64) List<HostAccess.EnvVar> env,
    @Valid @Size(max = 16) List<HostAccess.Mount> mounts) {

  /** What the update adds to the container's host access — same rules as a deploy's, so a
   *  refused mount answers 400 with its reason. */
  public HostAccess hostAccess() {
    return new HostAccess(ports, env, mounts);
  }
}
