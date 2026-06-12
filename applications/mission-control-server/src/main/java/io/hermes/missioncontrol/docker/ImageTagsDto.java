package io.hermes.missioncontrol.docker;

import java.util.List;

public record ImageTagsDto(String repository, List<String> tags) {}
