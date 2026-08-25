package io.hermes.missioncontrol.models;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The last model list each provider's own API answered with.
 *
 * <p>Stored rather than cached in memory so a restart does not drop the fleet
 * back to the curated list this app shipped with — the refresh runs twice a day,
 * and a picker that went stale on every deploy would spend most of its life
 * showing yesterday's answer.
 */
@Repository
public class ModelCatalogRepository {

  private final JdbcTemplate jdbc;

  public ModelCatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Model ids for one provider, in the order that provider listed them.
   *
   * <p>{@code fetched_at} is written but not read back here: it is there for an
   * operator reading the database directly, to tell a list refreshed this morning
   * from one left behind by a provider that has been unreachable for a week.
   */
  public List<String> models(String provider) {
    return jdbc.queryForList(
        "SELECT model_id FROM model_catalog WHERE provider = ? ORDER BY position",
        String.class, provider);
  }

  /**
   * Replaces one provider's list wholesale.
   *
   * <p>Transactional and delete-then-insert, because a model the provider has
   * withdrawn has to leave: merging would accumulate every id the provider ever
   * served, and the picker would go on offering models that now 404.
   *
   * <p>An empty list is refused by the caller, not here — see
   * {@link ModelCatalogService#refresh}. A provider answering 200 with nothing
   * is far more likely to be a bad read than a vendor with no models.
   */
  @Transactional
  public void replace(String provider, List<String> models, long fetchedAt) {
    jdbc.update("DELETE FROM model_catalog WHERE provider = ?", provider);
    for (int i = 0; i < models.size(); i++) {
      jdbc.update(
          "INSERT INTO model_catalog (provider, model_id, position, fetched_at) VALUES (?, ?, ?, ?)",
          provider, models.get(i), i, fetchedAt);
    }
  }
}
