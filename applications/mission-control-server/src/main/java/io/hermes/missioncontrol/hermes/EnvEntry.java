package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One variable to write into a profile's {@code .env}.
 *
 * <p>{@link HermesSetup#putEnv} re-checks both halves — it is also reached from the
 * template deploy path, which does not go through bean validation.
 */
public record EnvEntry(
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "invalid env key") String key,
    @Size(max = 8192) String value) {
}
