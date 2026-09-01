package io.hermes.missioncontrol.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A list of ids on the way in, and in a TEXT column.
 *
 * <p>Five columns hold a JSON array of ids that are deliberately not foreign keys — two on a
 * guide, one each on a skill, prompt and MCP group — and each had its own copy of this. The
 * tables stay separate and every repository still writes its own SQL; only the codec is shared.
 */
public final class IdList {

  private static final Logger log = LoggerFactory.getLogger(IdList.class);
  private static final TypeReference<List<String>> IDS = new TypeReference<>() {};

  private IdList() {}

  /** Blanks and duplicates dropped, order kept — the order is the operator's, so not a sort. */
  public static List<String> normalize(List<String> raw) {
    if (raw == null) {
      return List.of();
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (String id : raw) {
      if (id != null && !id.isBlank()) {
        ids.add(id.trim());
      }
    }
    return List.copyOf(ids);
  }

  /** An absent or unparseable column degrades to empty and says so: one bad column must not
   *  cost the row it is on. */
  public static List<String> read(ObjectMapper mapper, String json, String column) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(json, IDS);
    } catch (Exception e) {
      log.warn("dropping unparseable {} column: {}", column, e.getMessage());
      return List.of();
    }
  }

  /** A null list writes an empty array rather than a null column. */
  public static String write(ObjectMapper mapper, List<String> ids) {
    try {
      return mapper.writeValueAsString(ids == null ? List.of() : ids);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize an id list", e);
    }
  }
}
