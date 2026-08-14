package io.hermes.missioncontrol.hosts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.hosts.HostRepository.HostRow;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class HostRepositoryTest {

  private SqliteTestDatabase database;
  private HostRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new HostRepository(database.jdbc());
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void insertedRowComesBackWithEveryFieldIntact() {
    repository.insert(new HostRow("dh-1", "workshop", "tcp://10.0.0.4:2375", "remote"));

    HostRow found = repository.findById("dh-1").orElseThrow();
    assertEquals("dh-1", found.id());
    assertEquals("workshop", found.name());
    assertEquals("tcp://10.0.0.4:2375", found.url());
    assertEquals("remote", found.kind());
  }

  @Test
  void findByIdIsEmptyForAnUnknownHost() {
    assertTrue(repository.findById("nope").isEmpty());
  }

  @Test
  void findAllOrdersByInsertionTime() {
    repository.insert(new HostRow("dh-1", "first", "unix:///var/run/docker.sock", "local"));
    repository.insert(new HostRow("dh-2", "second", "tcp://10.0.0.4:2375", "remote"));

    assertEquals(List.of("dh-1", "dh-2"), repository.findAll().stream().map(HostRow::id).toList());
  }

  @Test
  void urlExistsTracksInsertsAndDeletes() {
    assertFalse(repository.urlExists("tcp://10.0.0.4:2375"));

    repository.insert(new HostRow("dh-1", "workshop", "tcp://10.0.0.4:2375", "remote"));
    assertTrue(repository.urlExists("tcp://10.0.0.4:2375"));

    repository.delete("dh-1");
    assertFalse(repository.urlExists("tcp://10.0.0.4:2375"));
    assertTrue(repository.findById("dh-1").isEmpty());
  }

  @Test
  void theSchemaRefusesADuplicateUrl() {
    repository.insert(new HostRow("dh-1", "workshop", "tcp://10.0.0.4:2375", "remote"));

    // the UNIQUE constraint is the backstop behind HostService's explicit pre-check;
    // ApiExceptionHandler turns this into a 409 rather than an opaque 503
    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(new HostRow("dh-2", "copy", "tcp://10.0.0.4:2375", "remote")));
  }

  @Test
  void theSchemaRefusesAnUnknownKind() {
    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insert(new HostRow("dh-1", "workshop", "tcp://10.0.0.4:2375", "podman")));
  }
}
