package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The MCP catalog table against real sqlite. Until now this repository was only ever
 * exercised as a collaborator of {@link McpRegistryService}, which meant its own rules —
 * the case-insensitive name index, the nullable check columns, the operation-state
 * transitions — were only implied by tests aimed at something else.
 */
class McpServerRepositoryTest {

  private SqliteTestDatabase database;
  private McpServerRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new McpServerRepository(database.jdbc());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static ServerRow row(String id, String name) {
    return new ServerRow(id, name, "desc", null, "managed", "dh-local", "svc-" + id, "{}",
        "running", "stopped", "idle", null, 1L, 0L, null, null, null, null, null, 100L, 100L);
  }

  @Test
  void anInsertedRowRoundTripsIncludingTheNullableCheckColumns() {
    repository.insert(row("mcp-1", "files"));

    ServerRow stored = repository.findById("mcp-1").orElseThrow();
    assertEquals("files", stored.name());
    assertEquals("managed", stored.kind());
    assertEquals("dh-local", stored.hostId());
    assertEquals("svc-mcp-1", stored.serviceKey());
    assertEquals(1L, stored.revision());
    // bare rs.getLong returns 0 for SQL NULL, which would render in the UI as "checked
    // just now, 0 ms" for a server that has never been probed
    assertNull(stored.checkedAt());
    assertNull(stored.latencyMs());
    assertNull(stored.checkStatus());
  }

  @Test
  void findAllOrdersByNameCaseInsensitively() {
    repository.insert(row("mcp-1", "beta"));
    repository.insert(row("mcp-2", "Alpha"));
    repository.insert(row("mcp-3", "gamma"));

    // a plain ORDER BY name would sort every capitalised name ahead of every lowercase
    // one, so the list would look arbitrary to the user
    assertEquals(
        List.of("Alpha", "beta", "gamma"),
        repository.findAll().stream().map(ServerRow::name).toList());
  }

  @Test
  void nameExistsIsCaseInsensitiveAndMatchesTheUniqueIndex() {
    repository.insert(row("mcp-1", "files"));

    assertTrue(repository.nameExists("files", null));
    assertTrue(repository.nameExists("FILES", null));
    assertFalse(repository.nameExists("documents", null));
  }

  @Test
  void nameExistsCanExcludeOneRowSoRenamingToItsOwnNameIsNotAConflict() {
    repository.insert(row("mcp-1", "files"));
    repository.insert(row("mcp-2", "documents"));

    // saving a server without changing its name must not collide with itself
    assertFalse(repository.nameExists("files", "mcp-1"));
    // but taking a name another row already holds still must
    assertTrue(repository.nameExists("documents", "mcp-1"));
  }

  @Test
  void aDuplicateNameOrIdSurfacesAsADataIntegrityViolation() {
    repository.insert(row("mcp-1", "files"));

    // the pre-check is racy; the UNIQUE index plus the translator is what actually makes
    // the concurrent case a 409 rather than a 500
    assertThrows(DataIntegrityViolationException.class, () -> repository.insert(row("mcp-1", "other")));
    assertThrows(DataIntegrityViolationException.class, () -> repository.insert(row("mcp-2", "files")));
  }

  @Test
  void findByHostIsScopedAndFindBySeedKeyOnlyMatchesSeededRows() {
    repository.insert(row("mcp-1", "files"));
    ServerRow onAnotherHost = new ServerRow("mcp-2", "remote-files", null, null, "managed", "dh-remote",
        "svc-2", "{}", "running", "stopped", "idle", null, 1L, 0L, "postgres-seed",
        null, null, null, null, 100L, 100L);
    repository.insert(onAnotherHost);

    assertEquals(List.of("mcp-1"), repository.findByHost("dh-local").stream().map(ServerRow::id).toList());
    assertEquals("mcp-2", repository.findBySeedKey("postgres-seed").orElseThrow().id());
    // a row with no seed key must never be mistaken for a seeded one
    assertTrue(repository.findBySeedKey("absent-seed").isEmpty());
  }

  @Test
  void beginOperationClearsAPreviousErrorAndFinishOperationAdvancesTheAppliedRevision() {
    repository.insert(row("mcp-1", "files"));
    repository.failOperation("mcp-1", "compose up failed");
    assertEquals("error", repository.findById("mcp-1").orElseThrow().operationState());

    repository.beginOperation("mcp-1", "running", "starting");
    ServerRow started = repository.findById("mcp-1").orElseThrow();
    assertEquals("starting", started.operationState());
    // a stale error next to a running operation is what makes the UI show a red badge on
    // a server that is currently coming up fine
    assertNull(started.operationError());

    repository.finishOperation("mcp-1", "running", 1L);
    ServerRow finished = repository.findById("mcp-1").orElseThrow();
    assertEquals("idle", finished.operationState());
    assertEquals("running", finished.runtimeState());
    // applied == revision is what clears `pendingChanges` in the DTO
    assertEquals(1L, finished.appliedRevision());
  }

  @Test
  void aClaimIsTakenOnlyBySomethingThatFindsTheRecordSettled() {
    repository.insert(row("mcp-1", "files"));

    // idle and error both mean nothing is in flight, so both admit a claim
    assertTrue(repository.claimOperation("mcp-1", "running", "starting"));
    repository.failOperation("mcp-1", "compose up failed");
    assertTrue(repository.claimOperation("mcp-1", "running", "starting"));
    assertNull(repository.findById("mcp-1").orElseThrow().operationError());

    // the second of two requests that both read an idle record is refused by the write itself,
    // which is the only thing that can tell them apart
    assertFalse(repository.claimOperation("mcp-1", "stopped", "stopping"));
    ServerRow held = repository.findById("mcp-1").orElseThrow();
    assertEquals("starting", held.operationState());
    assertEquals("running", held.desiredState(), "a refused claim must not move the desired state");

    repository.releaseOperation("mcp-1");
    assertEquals("idle", repository.findById("mcp-1").orElseThrow().operationState());
    assertTrue(repository.claimOperation("mcp-1", "stopped", "stopping"));

    assertFalse(repository.claimOperation("mcp-nope", "running", "starting"));
  }

  @Test
  void aDefinitionWriteOnlyLandsOverTheRevisionItsAuthorRead() {
    repository.insert(row("mcp-1", "files"));

    // two editors both open revision 1. The first save wins; the second is told to reload
    // rather than silently overwriting an edit it never saw.
    assertTrue(repository.updateDefinition(
        "mcp-1", "documents", "d", null, "{\"first\":1}", 2L, 1L, "idle", 1L));
    assertFalse(repository.updateDefinition(
        "mcp-1", "invoices", "d", null, "{\"second\":1}", 2L, 1L, "idle", 1L));

    ServerRow stored = repository.findById("mcp-1").orElseThrow();
    assertEquals("documents", stored.name());
    assertEquals("{\"first\":1}", stored.configJson());
  }

  @Test
  void theSettledDefinitionWriteIsRefusedWhileAnOperationIsInFlight() {
    // a claim moves no revision, so the revision guard alone cannot see it — this write-level
    // guard is what refuses an edit that read the row just before a start claimed it
    repository.insert(row("mcp-1", "files"));
    assertTrue(repository.claimOperation("mcp-1", "running", "starting"));

    assertFalse(repository.updateDefinitionIfSettled(
        "mcp-1", "documents", "d", null, "{\"a\":1}", 2L, 1L, "idle", 1L));
    ServerRow held = repository.findById("mcp-1").orElseThrow();
    assertEquals("starting", held.operationState(), "the claim must survive the refused edit");
    assertEquals("files", held.name());

    repository.releaseOperation("mcp-1");
    assertTrue(repository.updateDefinitionIfSettled(
        "mcp-1", "documents", "d", null, "{\"a\":1}", 2L, 1L, "idle", 1L));
    // `error` is settled too: an edit is how an operator fixes the definition a failure named
    repository.failOperation("mcp-1", "boom");
    assertTrue(repository.updateDefinitionIfSettled(
        "mcp-1", "invoices", "d", null, "{\"b\":1}", 3L, 1L, "idle", 2L));
  }

  @Test
  void failOperationKeepsOnlyTheFirstLineAndTruncatesAtFiveHundredCharacters() {
    repository.insert(row("mcp-1", "files"));

    repository.failOperation("mcp-1", "first line\nsecond line\nthird line");
    assertEquals("first line", repository.findById("mcp-1").orElseThrow().operationError());

    // a docker compose failure can carry a whole stack of output; the column is shown
    // verbatim in the UI, so it has to be bounded
    repository.failOperation("mcp-1", "x".repeat(900));
    assertEquals(500, repository.findById("mcp-1").orElseThrow().operationError().length());

    repository.failOperation("mcp-1", null);
    assertNull(repository.findById("mcp-1").orElseThrow().operationError());
    // the row is still marked failed even with no message to show
    assertEquals("error", repository.findById("mcp-1").orElseThrow().operationState());
  }

  @Test
  void updateDefinitionClearsTheOperationErrorAndUpdateCheckStoresNullsForAnIncompleteProbe() {
    repository.insert(row("mcp-1", "files"));
    repository.failOperation("mcp-1", "boom");

    assertTrue(repository.updateDefinition(
        "mcp-1", "documents", "new desc", null, "{\"a\":1}", 2L, 1L, "applying", 1L));
    ServerRow updated = repository.findById("mcp-1").orElseThrow();
    assertEquals("documents", updated.name());
    assertEquals("{\"a\":1}", updated.configJson());
    assertEquals(2L, updated.revision());
    assertNull(updated.operationError(), "an edit must not carry the previous failure forward");

    repository.updateCheck("mcp-1", "error", "connection refused\nat line 2", null, null);
    ServerRow checked = repository.findById("mcp-1").orElseThrow();
    assertEquals("error", checked.checkStatus());
    assertEquals("connection refused", checked.checkError());
    assertNull(checked.checkedAt());
    assertNull(checked.latencyMs());
  }

  @Test
  void metaPutIsAnUpsertSoTheSecondValueWins() {
    assertTrue(repository.meta("seed-version").isEmpty());

    repository.putMeta("seed-version", "1");
    repository.putMeta("seed-version", "2");

    // the seed repair path writes this on every boot; an insert-only statement would
    // fail the second start
    assertEquals("2", repository.meta("seed-version").orElseThrow());
  }

  @Test
  void deletingOneServerLeavesTheOthersAlone() {
    repository.insert(row("mcp-1", "files"));
    repository.insert(row("mcp-2", "documents"));

    repository.delete("mcp-1");

    assertTrue(repository.findById("mcp-1").isEmpty());
    assertEquals(1, repository.findAll().size());
    assertEquals("documents", repository.findAll().getFirst().name());
  }

  @Test
  void theRepositoryLinkRoundTripsAndIsNullableOnAnOlderRow() {
    // the column arrived through SchemaUpgrades, so a row written before it exists reads null
    ServerRow row = row("mcp-1", "Files");
    repository.insert(row);

    assertNull(repository.findById("mcp-1").orElseThrow().repoUrl());

    repository.updateDefinition("mcp-1", "Files", null, "https://github.com/o/r",
        row.configJson(), row.revision() + 1, row.appliedRevision(), "idle", row.revision());

    assertEquals("https://github.com/o/r", repository.findById("mcp-1").orElseThrow().repoUrl());
  }
}
