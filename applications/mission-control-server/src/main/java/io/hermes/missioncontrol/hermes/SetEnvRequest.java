package io.hermes.missioncontrol.hermes;

import jakarta.validation.Valid;
import java.util.List;

/** A batch of {@code .env} writes. A blank value removes the variable. */
public record SetEnvRequest(@Valid List<@Valid EnvEntry> entries) {
}
