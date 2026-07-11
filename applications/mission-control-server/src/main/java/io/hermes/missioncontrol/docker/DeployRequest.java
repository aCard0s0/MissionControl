package io.hermes.missioncontrol.docker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DeployRequest(
    @NotBlank String hostId,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*", message = "invalid container name") String name,
    String version,
    @Size(max = 50) List<@Pattern(
        regexp = "default|[a-z0-9][a-z0-9_-]{0,63}",
        message = "invalid profile name") String> profiles) {
}
