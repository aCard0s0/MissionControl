package io.hermes.missioncontrol.docker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record DeployRequest(
    @NotBlank String hostId,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*", message = "invalid container name") String name,
    String version,
    List<String> profiles) {
}
