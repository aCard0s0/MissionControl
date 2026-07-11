package io.hermes.missioncontrol.hermes;

/**
 * A template-owned encrypted value attached to an MCP snapshot. The ciphertext
 * is persisted inside the template's MCP JSON, but is replaced with {@code null}
 * in every API response.
 */
public record TemplateMcpConfigValue(String key, String encryptedValue) {}
