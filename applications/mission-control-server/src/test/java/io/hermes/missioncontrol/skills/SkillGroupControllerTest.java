package io.hermes.missioncontrol.skills;

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
 * Skill groups: filing, and the optional guide link.
 *
 * <p>What is worth pinning here is what a group deliberately does <em>not</em> do. It never
 * validates the ids it holds, because the rows behind them can be deleted afterwards and the
 * page resolves them on read. And deleting a group is not deleting skills.
 */
class SkillGroupControllerTest {

  private SqliteTestDatabase database;
  private SkillGroupRepository repository;
  private SkillRepository skills;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new SkillGroupRepository(database.jdbc(), new ObjectMapper());
    skills = new SkillRepository(database.jdbc(), new ObjectMapper());
    mvc = MockMvcBuilders
        .standaloneSetup(new SkillGroupController(repository))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  void createsAGroupWithNoGuide() throws Exception {
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"skillIds\":[\"s-1\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("pdf"))
        .andExpect(jsonPath("$.guideId").value((Object) null))
        .andExpect(jsonPath("$.skillIds[0]").value("s-1"));
  }

  @Test
  void keepsTheGuideAssociationWhenOneIsGiven() throws Exception {
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"guideId\":\"g-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.guideId").value("g-1"));
  }

  @Test
  void takesIdsThatMatchNothing() throws Exception {
    // the rows behind them can go at any moment, so validating here would only move the lie
    // earlier — the page resolves them on read and marks what is missing
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"skillIds\":[\"gone\"],\"guideId\":\"also-gone\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void dropsBlankAndDuplicateSkillIdsAndKeepsTheOrder() throws Exception {
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"skillIds\":[\"s-2\",\"\",\"s-1\",\"s-2\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skillIds.length()").value(2))
        .andExpect(jsonPath("$.skillIds[0]").value("s-2"))
        .andExpect(jsonPath("$.skillIds[1]").value("s-1"));
  }

  @Test
  void dropsANullSkillIdInTheArrayRatherThanStoringIt() throws Exception {
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"skillIds\":[\"s-1\",null]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skillIds.length()").value(1))
        .andExpect(jsonPath("$.skillIds[0]").value("s-1"));
  }

  @Test
  void treatsABlankDescriptionAndGuideIdAsAbsent() throws Exception {
    // an editor that cleared a field sends "", not a missing key
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"description\":\"  \",\"guideId\":\"  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value((Object) null))
        .andExpect(jsonPath("$.guideId").value((Object) null));
  }

  @Test
  void rejectsAGroupWithNoName() throws Exception {
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answersConflictWhenTwoGroupsWouldReadTheSame() throws Exception {
    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\"}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/skill-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"PDF\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void anUpdateReplacesTheMembershipAndCanClearTheGuide() throws Exception {
    repository.insert(new SkillGroup("sg-1", "pdf", "old", List.of("s-1"), "g-1",
        1_000L, 1_000L));

    mvc.perform(put("/api/skill-groups/sg-1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\",\"skillIds\":[\"s-9\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skillIds[0]").value("s-9"))
        .andExpect(jsonPath("$.guideId").value((Object) null));

    assertEquals(1_000L, repository.find("sg-1").orElseThrow().createdAt());
  }

  @Test
  void answersNotFoundRatherThanInsertingWhenTheGroupIsGone() throws Exception {
    mvc.perform(put("/api/skill-groups/sg-nope").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"pdf\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletingAGroupLeavesEverySkillItNamedInTheLibrary() throws Exception {
    skills.insert(new Skill("s-1", Skill.LOCAL, "pdf-tools", "", "docs", "", "1.0",
        List.of(new SkillFile(Skill.SKILL_MD, "# pdf")), 1_000L, 1_000L));
    repository.insert(new SkillGroup("sg-1", "pdf", null, List.of("s-1"), null, 1_000L, 1_000L));

    mvc.perform(delete("/api/skill-groups/sg-1")).andExpect(status().isOk());

    assertEquals(1, skills.findAll().size());
    // idempotent, like every other delete in this package
    mvc.perform(delete("/api/skill-groups/sg-1")).andExpect(status().isOk());
  }

  @Test
  void listsGroupsByName() throws Exception {
    repository.insert(new SkillGroup("sg-1", "zebra", null, List.of(), null, 1_000L, 9_000L));
    repository.insert(new SkillGroup("sg-2", "alpha", null, List.of(), null, 1_000L, 1_000L));

    mvc.perform(get("/api/skill-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("alpha"))
        .andExpect(jsonPath("$[1].name").value("zebra"));
  }
}
