package io.hermes.missioncontrol.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The board endpoints over a real database: HTTP request, bean validation, controller,
 * real SQL, HTTP response, with nothing mocked in between.
 *
 * <p>{@link BoardRepositoryTest} already covers the SQL on its own. What is only reachable
 * from here is the wiring — that a rejected body inserts nothing, that a miss on the update
 * becomes a 404 through {@link ApiExceptionHandler} rather than a silent success, and that
 * the JSON tags column survives a full round trip.
 */
class BoardControllerTest {

  private SqliteTestDatabase database;
  private BoardRepository repository;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new BoardRepository(database.jdbc(), new ObjectMapper());
    mvc = MockMvcBuilders
        .standaloneSetup(new BoardController(repository))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void aCreatedTaskIsPersistedAndComesBackFromTheListEndpoint() throws Exception {
    mvc.perform(post("/api/board/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"containerId":"c1","agentId":"scout","title":"ship the thing",
                 "column":"running","priority":"high","tags":["infra","urgent"]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("t-")))
        .andExpect(jsonPath("$.column").value("running"));

    mvc.perform(get("/api/board/tasks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("ship the thing"))
        // tags are stored as a JSON column, so this is the round trip that matters
        .andExpect(jsonPath("$[0].tags[0]").value("infra"))
        .andExpect(jsonPath("$[0].tags[1]").value("urgent"));
  }

  @Test
  void createDefaultsTheColumnToQueuedAndThePriorityToMed() throws Exception {
    mvc.perform(post("/api/board/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"containerId\":\"c1\",\"title\":\"triage\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.column").value("queued"))
        .andExpect(jsonPath("$.priority").value("med"))
        .andExpect(jsonPath("$.tags.length()").value(0));

    // the CHECK constraints on col/priority mean a wrong default would fail the insert,
    // so this also proves the defaults agree with the schema
    assertEquals(1, repository.findAll().size());
  }

  @Test
  void anInvalidColumnIsRejectedByValidationAndInsertsNothing() throws Exception {
    mvc.perform(post("/api/board/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"containerId\":\"c1\",\"title\":\"t\",\"column\":\"archived\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    assertTrue(repository.findAll().isEmpty(), "a rejected body reached the database");
  }

  @Test
  void aBlankTitleOrMissingContainerIdIsRejected() throws Exception {
    mvc.perform(post("/api/board/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"containerId\":\"c1\",\"title\":\"   \"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/board/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"t\"}"))
        .andExpect(status().isBadRequest());

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void movingATaskChangesItsColumnInTheDatabase() throws Exception {
    repository.insert(new BoardTask("t-1", "c1", null, "t", "queued", "med", List.of(), 1L));

    mvc.perform(patch("/api/board/tasks/t-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"column\":\"done\"}"))
        .andExpect(status().isOk());

    assertEquals("done", repository.findAll().getFirst().column());
  }

  @Test
  void movingAnUnknownTaskIsANotFound() throws Exception {
    // updateColumn returns 0 rows -> NoSuchElementException -> the advice maps it to 404.
    // Without the row-count check this would answer 200 for a task that does not exist.
    mvc.perform(patch("/api/board/tasks/t-nope")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"column\":\"done\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown task: t-nope"));
  }

  @Test
  void anInvalidMoveColumnLeavesTheTaskWhereItWas() throws Exception {
    repository.insert(new BoardTask("t-1", "c1", null, "t", "queued", "med", List.of(), 1L));

    mvc.perform(patch("/api/board/tasks/t-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"column\":\"archived\"}"))
        .andExpect(status().isBadRequest());

    assertEquals("queued", repository.findAll().getFirst().column());
  }

  @Test
  void listFiltersByContainerId() throws Exception {
    repository.insert(new BoardTask("t-1", "c1", null, "mine", "queued", "med", List.of(), 1L));
    repository.insert(new BoardTask("t-2", "c2", null, "theirs", "queued", "med", List.of(), 2L));

    mvc.perform(get("/api/board/tasks").param("containerId", "c1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("mine"));
  }

  @Test
  void deletingATaskRemovesItAndDeletingAnUnknownOneIsNotAnError() throws Exception {
    repository.insert(new BoardTask("t-1", "c1", null, "t", "queued", "med", List.of(), 1L));

    mvc.perform(delete("/api/board/tasks/t-1")).andExpect(status().isOk());
    assertTrue(repository.findAll().isEmpty());

    // delete is deliberately idempotent — a double-click from the UI is not a 404
    mvc.perform(delete("/api/board/tasks/t-1")).andExpect(status().isOk());
  }
}
