package io.hermes.missioncontrol.mcp;

/** Safe API view of a value. Secret values are always redacted. */
public record ConfigValueDto(
    String key,
    String value,
    boolean secret,
    boolean set,
    boolean recoverable) {}
