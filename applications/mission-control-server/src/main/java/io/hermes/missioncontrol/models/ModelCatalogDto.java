package io.hermes.missioncontrol.models;

import java.util.List;

/** Matches the frontend model catalog. */
public record ModelCatalogDto(
    String provider,
    List<String> models,
    String source) {      // config | live
}
