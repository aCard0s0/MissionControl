package io.hermes.missioncontrol.credentials;

import java.util.List;

/**
 * A credential saved once, to be offered wherever a key is typed: an agent's {@code .env}, the
 * create-agent dialog, a blueprint's keys tab.
 *
 * <p>A bundle of {@link CredentialEntry} rather than a single key/value pair, because that is
 * the shape of the things being saved. {@code HermesEnvCatalog.MESSAGING} pairs a bot token
 * with a home channel, and a self-hosted provider takes a base URL alongside its key — one row
 * per key would make an operator save and pick those halves separately.
 *
 * <p>Autofill only. Nothing records that a credential filled something, so this row has no
 * dependents and deleting it breaks nothing already written. That is not a simplification
 * waiting to be undone: a profile's {@code .env} is a file inside a container, so a rotation
 * here could not reach it without a re-push whichever way the association pointed.
 *
 * <p>{@link #name} is a label, unlike an entry's key. It is what the dropdown shows.
 */
public record Credential(
    String id,
    String name,
    String description,
    List<CredentialEntry> entries,
    long createdAt,
    long updatedAt) {
}
