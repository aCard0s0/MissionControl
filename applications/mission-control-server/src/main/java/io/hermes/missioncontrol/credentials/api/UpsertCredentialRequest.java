package io.hermes.missioncontrol.credentials.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A credential as the editor submits it. */
public record UpsertCredentialRequest(
    @NotBlank @Size(max = 80) String name,
    @Size(max = 2_000) String description,
    @Size(max = 32, message = "a credential may hold at most 32 entries")
    List<@Valid CredentialEntryInput> entries) {
}
