package io.hermes.missioncontrol.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Prompt groups: filing, and deliberately nothing else.
 *
 * <p>What is worth pinning is what a group does not do. It never validates the ids it holds,
 * because the prompts behind them can be deleted afterwards. And deleting a group is not
 * deleting prompts.
 */
class PromptGroupControllerTest {

  private SqliteTestDatabase database;
  private PromptGroupRepository repository;
  private PromptRepository prompts;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new PromptGroupRepository(database.jdbc(), new ObjectMapper());
    prompts = new PromptRepository(database.jdbc(), new ObjectMapper());
    mvc = MockMvcBuilders
        .standaloneSetup(new PromptGroupController(repository))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void createsAGroup() throws Exception {
    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\",\"promptIds\":[\"p-1\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("triage"))
        .andExpect(jsonPath("$.promptIds[0]").value("p-1"));
  }

  @Test
  void takesIdsThatMatchNothing() throws Exception {
    // the prompts behind them can go at any moment, so validating here would only move the
    // lie earlier — the page resolves them on read and drops what is missing
    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\",\"promptIds\":[\"gone\"]}"))
        .andExpect(status().isOk());
  }

  @Test
  void dropsBlankDuplicateAndNullPromptIdsAndKeepsTheOrder() throws Exception {
    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\",\"promptIds\":[\"p-2\",\"\",null,\"p-1\",\"p-2\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.promptIds.length()").value(2))
        .andExpect(jsonPath("$.promptIds[0]").value("p-2"))
        .andExpect(jsonPath("$.promptIds[1]").value("p-1"));
  }

  @Test
  void treatsABlankDescriptionAsAbsent() throws Exception {
    // an editor that cleared the field sends "", not a missing key
    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\",\"description\":\"  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value((Object) null));
  }

  @Test
  void rejectsAGroupWithNoName() throws Exception {
    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answersConflictWhenTwoGroupsWouldReadTheSame() throws Exception {
    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\"}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/prompt-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"TRIAGE\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void anUpdateReplacesTheMembershipAndKeepsCreatedAt() throws Exception {
    repository.insert(new PromptGroup("pg-1", "triage", "old", List.of("p-1"), 1_000L, 1_000L));

    mvc.perform(put("/api/prompt-groups/pg-1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\",\"promptIds\":[\"p-9\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.promptIds[0]").value("p-9"));

    assertEquals(1_000L, repository.find("pg-1").orElseThrow().createdAt());
  }

  @Test
  void answersNotFoundRatherThanInsertingWhenTheGroupIsGone() throws Exception {
    mvc.perform(put("/api/prompt-groups/pg-nope").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"triage\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletingAGroupLeavesEveryPromptItNamedInTheLibrary() throws Exception {
    prompts.insert(new Prompt("p-1", "Triage a container", "check the logs", "ops", "",
        List.of("ops"), 1_000L, 1_000L));
    repository.insert(new PromptGroup("pg-1", "triage", null, List.of("p-1"), 1_000L, 1_000L));

    mvc.perform(delete("/api/prompt-groups/pg-1")).andExpect(status().isOk());

    assertEquals(1, prompts.findAll().size());
    // idempotent, like every other delete in this package
    mvc.perform(delete("/api/prompt-groups/pg-1")).andExpect(status().isOk());
  }

  @Test
  void listsGroupsByName() throws Exception {
    repository.insert(new PromptGroup("pg-1", "zebra", null, List.of(), 1_000L, 9_000L));
    repository.insert(new PromptGroup("pg-2", "alpha", null, List.of(), 1_000L, 1_000L));

    mvc.perform(get("/api/prompt-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("alpha"))
        .andExpect(jsonPath("$[1].name").value("zebra"));
  }
}
