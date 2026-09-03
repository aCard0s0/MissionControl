package io.hermes.missioncontrol.credentials;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the saved credentials. */
@Repository
public class CredentialRepository {

  private static final TypeReference<List<CredentialEntry>> ENTRIES = new TypeReference<>() {};

  private final RowMapper<Credential> mapper = (rs, n) -> new Credential(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("description"),
      entries(rs.getString("entries_json")),
      rs.getLong("created_at"),
      rs.getLong("updated_at"));

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public CredentialRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * By name, not by newest edit.
   *
   * <p>Same reason the skill groups read this way: this list is a dropdown, and an option that
   * jumps position because someone renamed an unrelated row makes the picker unreadable.
   */
  public List<Credential> findAll() {
    return jdbc.query("SELECT * FROM credentials ORDER BY name COLLATE NOCASE", mapper);
  }

  public Optional<Credential> find(String id) {
    return jdbc.query("SELECT * FROM credentials WHERE id = ?", mapper, id).stream().findFirst();
  }

  public void insert(Credential credential) {
    jdbc.update(
        "INSERT INTO credentials (id, name, description, entries_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        credential.id(), credential.name(), credential.description(),
        write(credential.entries()), credential.createdAt(), credential.updatedAt());
  }

  /** Everything an editor can change. {@code created_at} is deliberately not in the statement —
   *  an update must not rewrite when the credential was first saved. */
  public int update(Credential credential) {
    return jdbc.update(
        "UPDATE credentials SET name = ?, description = ?, entries_json = ?, updated_at = ? "
            + "WHERE id = ?",
        credential.name(), credential.description(), write(credential.entries()),
        credential.updatedAt(), credential.id());
  }

  public void delete(String id) {
    jdbc.update("DELETE FROM credentials WHERE id = ?", id);
  }

  /**
   * A row whose entries will not parse is a conflict, not an empty credential.
   *
   * <p>Answering with no entries would report a credential that holds nothing, which reads as
   * "never filled in" — and the editor's next save would then write that emptiness over the
   * only copy of the values.
   */
  private List<CredentialEntry> entries(String raw) {
    // never null — the column is NOT NULL. Blank is reachable by hand, and reading it as "holds
    // nothing" keeps one hand-edited row from 409-ing the whole listing.
    if (raw.isBlank()) return List.of();
    try {
      List<CredentialEntry> parsed = json.readValue(raw, ENTRIES);
      return parsed == null ? List.of() : List.copyOf(parsed);
    } catch (JsonProcessingException e) {
      throw new ResourceConflictException("stored credential is unreadable", e);
    }
  }

  private String write(List<CredentialEntry> entries) {
    try {
      return json.writeValueAsString(entries);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not store credential", e);
    }
  }
}
