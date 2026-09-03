package io.hermes.missioncontrol.credentials.api;

import java.util.List;

/** A saved credential as the API exposes it — secret values redacted. */
public record CredentialDto(
    String id,
    String name,
    String description,
    List<CredentialEntryDto> entries,
    long createdAt,
    long updatedAt) {
}
