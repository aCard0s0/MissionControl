package io.hermes.missioncontrol.docker;

public record LogLineDto(long ts, String level, String source, String msg) {}
