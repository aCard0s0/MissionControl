package io.hermes.missioncontrol.agents.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * The request body for creating or replacing an agent's MCP server.
 *
 * <p>Only ever a wire shape. It used to carry two extra constructors so internal callers —
 * the template applier, the catalog materializer — could build one to reach the domain, which
 * meant the bean-validation annotations here described a contract those paths never ran.
 * {@code McpServerDefinition} is what the domain takes now, and
 * {@code McpServerDefinition.from} is the one place this record is read.
 */
public record AddMcpServerRequest(
    @NotBlank String name,
    @NotBlank String transport,
    String url,
    String command,
    String args,
    Boolean enabled,
    /** null means "not edited", an empty map explicitly clears headers. */
    Map<String, String> headers,
    /** null means "not edited", an empty map explicitly clears stdio env. */
    Map<String, String> environment) {
}
