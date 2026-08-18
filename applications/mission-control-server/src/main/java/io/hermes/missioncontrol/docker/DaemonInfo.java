package io.hermes.missioncontrol.docker;

/** What a daemon reports about itself, plus how long it took to answer a ping. */
public record DaemonInfo(String engine, String apiVersion, long latencyMs) {}
