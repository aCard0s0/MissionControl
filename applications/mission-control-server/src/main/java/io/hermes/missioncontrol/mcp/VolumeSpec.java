package io.hermes.missioncontrol.mcp;

/** A managed named volume. Bind mounts are intentionally not representable. */
public record VolumeSpec(String name, String target) {}
