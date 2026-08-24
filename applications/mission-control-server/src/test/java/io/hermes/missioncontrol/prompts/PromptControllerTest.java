package io.hermes.missioncontrol.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * The prompt endpoints over a real database: HTTP request, bean validation, controller,
 * real SQL, HTTP response, with nothing mocked in between.
 *
 * <p>{@link PromptRepositoryTest} covers the SQL on its own. What is only reachable from
 * here is the normalization the controller owns — the category default and folding, the
 * tag cleanup — plus that a rejected body writes nothing and that a PUT at an unknown id
 * becomes a 404 through {@link ApiExceptionHandler} instead of creating a row.
 */
class PromptControllerTest {

  private SqliteTestDatabase database;
  private PromptRepository repository;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new PromptRepository(database.jdbc(), new ObjectMapper());
    mvc = MockMvcBuilders
        .standaloneSetup(new PromptController(repository))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static Prompt stored(String id, String category) {
    return new Prompt(id, "Triage", "look at the logs", category, "read only",
        List.of("ops"), 1_000L, 1_000L);
  }

  @Test
  void aCreatedPromptIsPersistedAndComesBackFromTheListEndpoint() throws Exception {
    mvc.perform(post("/api/prompts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"title":"Triage a container","body":"read the first error","category":"ops",
                 "notes":"read only","tags":["ops","triage"]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("p-")))
        .andExpect(jsonPath("$.category").value("ops"));

    mvc.perform(get("/api/prompts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("Triage a container"))
        .andExpect(jsonPath("$[0].body").value("read the first error"))
        .andExpect(jsonPath("$[0].notes").value("read only"))
        // tags are stored as a JSON column, so this is the round trip that matters
        .andExpect(jsonPath("$[0].tags[0]").value("ops"))
        .andExpect(jsonPath("$[0].tags[1]").value("triage"));
  }

  @Test
  void aPromptSavedWithNoCategoryLandsInGeneral() throws Exception {
    mvc.perform(post("/api/prompts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Scratch\",\"body\":\"b\",\"category\":\"  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("general"))
        .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.tags.length()").value(0));

    // the column is NOT NULL, so a category left empty would have failed the insert
    assertEquals(1, repository.findAll().size());
  }

  @Test
  void categoriesAndTagsAreFoldedSoTheFilterChipsCannotSplitInTwo() throws Exception {
    mvc.perform(post("/api/prompts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"title":" Triage ","body":"b","category":" OPS ","tags":["Ops"," ops ","","triage"]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Triage"))
        .andExpect(jsonPath("$.category").value("ops"))
        // "Ops", " ops " and "" collapse to one tag; blanks are dropped
        .andExpect(jsonPath("$.tags.length()").value(2))
        .andExpect(jsonPath("$.tags[0]").value("ops"))
        .andExpect(jsonPath("$.tags[1]").value("triage"));
  }

  @Test
  void aBlankTitleOrBodyIsRejectedAndWritesNothing() throws Exception {
    mvc.perform(post("/api/prompts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"   \",\"body\":\"b\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    mvc.perform(post("/api/prompts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"t\"}"))
        .andExpect(status().isBadRequest());

    assertTrue(repository.findAll().isEmpty(), "a rejected body reached the database");
  }

  @Test
  void listFiltersByCategoryAndAcceptsItInAnyCase() throws Exception {
    repository.insert(stored("p-1", "ops"));
    repository.insert(stored("p-2", "review"));

    mvc.perform(get("/api/prompts").param("category", "OPS"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("p-1"));

    // a blank filter is not a filter — it must not answer an empty library
    mvc.perform(get("/api/prompts").param("category", " "))
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void oneUnknownPromptIsANotFoundRatherThanAnEmptyBody() throws Exception {
    mvc.perform(get("/api/prompts/p-nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown prompt: p-nope"));
  }

  @Test
  void editingAPromptKeepsWhenItWasFirstSavedAndMovesItsUpdatedStamp() throws Exception {
    repository.insert(stored("p-1", "ops"));

    mvc.perform(put("/api/prompts/p-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"title":"Triage v2","body":"quote the first error","category":"Review","tags":[]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Triage v2"))
        .andExpect(jsonPath("$.category").value("review"))
        .andExpect(jsonPath("$.createdAt").value(1_000));

    Prompt saved = repository.find("p-1").orElseThrow();
    assertEquals("quote the first error", saved.body());
    assertEquals(1_000L, saved.createdAt());
    assertTrue(saved.updatedAt() > 1_000L, "the edit did not move updatedAt");
    assertTrue(saved.tags().isEmpty());
  }

  @Test
  void editingAPromptThatIsGoneIsANotFoundAndCreatesNothing() throws Exception {
    // without the read-back this would either write nothing and answer 200, or
    // resurrect a deleted prompt under an id the operator never asked for
    mvc.perform(put("/api/prompts/p-nope")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"t\",\"body\":\"b\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown prompt: p-nope"));

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void anInvalidEditLeavesTheStoredPromptAsItWas() throws Exception {
    repository.insert(stored("p-1", "ops"));

    mvc.perform(put("/api/prompts/p-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"\",\"body\":\"b\"}"))
        .andExpect(status().isBadRequest());

    assertEquals("Triage", repository.find("p-1").orElseThrow().title());
  }

  @Test
  void deletingAPromptRemovesItAndDeletingAnUnknownOneIsNotAnError() throws Exception {
    repository.insert(stored("p-1", "ops"));

    mvc.perform(delete("/api/prompts/p-1")).andExpect(status().isOk());
    assertTrue(repository.findAll().isEmpty());

    // delete is deliberately idempotent — a double-click from the UI is not a 404
    mvc.perform(delete("/api/prompts/p-1")).andExpect(status().isOk());
  }
}
