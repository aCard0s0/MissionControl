package io.hermes.missioncontrol.hermes;

import java.util.List;

public record SetEnvRequest(List<EnvEntry> entries) {
}
