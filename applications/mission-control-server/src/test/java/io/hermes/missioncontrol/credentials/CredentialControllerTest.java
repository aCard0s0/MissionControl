package io.hermes.missioncontrol.credentials;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The credentials' HTTP surface.
 *
 * <p>What is worth pinning here is the redaction: no response may carry key material, and no
 * route here resolves a value at all.
 */
class CredentialControllerTest {

  private static final SecretCipher CIPHER = new SecretCipher("test-secret", "", false);

  private SqliteTestDatabase database;
  private CredentialService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    service = new CredentialService(
        new CredentialRepository(database.jdbc(), new ObjectMapper()), new SecretsAtRest(CIPHER));
    mvc = MockMvcBuilders
        .standaloneSetup(new CredentialController(service))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private String create(String body) throws Exception {
    String json = mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return new ObjectMapper().readTree(json).get("id").asText();
  }

  // ── CRUD ───────────────────────────────────────────────────────────────────

  @Test
  void createsACredentialAndRedactsItsSecretInTheAnswer() throws Exception {
    mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"anthropic prod","description":"the live key","entries":[
                  {"key":"ANTHROPIC_API_KEY","value":"sk-live-1234","secret":true}]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("anthropic prod"))
        .andExpect(jsonPath("$.entries[0].key").value("ANTHROPIC_API_KEY"))
        .andExpect(jsonPath("$.entries[0].secret").value(true))
        .andExpect(jsonPath("$.entries[0].set").value(true))
        .andExpect(jsonPath("$.entries[0].value").value((Object) null))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("sk-live"))));
  }

  @Test
  void aPlainEntryKeepsItsValueSoThePickerCanShowIt() throws Exception {
    mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"telegram ops","entries":[
                  {"key":"TELEGRAM_BOT_TOKEN","value":"bot-1","secret":true},
                  {"key":"TELEGRAM_HOME_CHANNEL","value":"#ops","secret":false}]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[1].value").value("#ops"));
  }

  @Test
  void theListingIsRedactedToo() throws Exception {
    create("""
        {"name":"anthropic","entries":[{"key":"ANTHROPIC_API_KEY","value":"sk-live-1234","secret":true}]}""");

    mvc.perform(get("/api/credentials"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].entries[0].value").value((Object) null))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("sk-live"))));
  }

  @Test
  void updatesACredentialInPlace() throws Exception {
    String id = create("""
        {"name":"anthropic","entries":[{"key":"ANTHROPIC_API_KEY","value":"sk-1","secret":true}]}""");

    mvc.perform(put("/api/credentials/" + id).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"anthropic prod","entries":[
                  {"key":"ANTHROPIC_API_KEY","value":"","secret":true}]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("anthropic prod"))
        .andExpect(jsonPath("$.entries[0].set").value(true));
  }

  @Test
  void anUnknownCredentialIsNotFound() throws Exception {
    mvc.perform(put("/api/credentials/cr-nope").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"x","entries":[{"key":"A_KEY","value":"v","secret":true}]}"""))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletesACredential() throws Exception {
    String id = create("""
        {"name":"anthropic","entries":[{"key":"ANTHROPIC_API_KEY","value":"sk-1","secret":true}]}""");

    mvc.perform(delete("/api/credentials/" + id)).andExpect(status().isOk());

    mvc.perform(get("/api/credentials")).andExpect(jsonPath("$.length()").value(0));
  }

  // ── validation ─────────────────────────────────────────────────────────────

  @Test
  void aLowerCaseKeyIsRefusedBecauseNoProfileEnvCanHoldIt() throws Exception {
    // the same rule EnvEntry declares — the .env is the only place these land, so the looser
    // MCP env-key pattern would let a credential be saved that can never be applied
    mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"x","entries":[{"key":"lower_case","value":"v","secret":true}]}"""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aCredentialNeedsAName() throws Exception {
    mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"  ","entries":[{"key":"A_KEY","value":"v","secret":true}]}"""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aBlankSecretOnACreateIsABadRequestRatherThanAnEmptySave() throws Exception {
    mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"x","entries":[{"key":"A_KEY","value":"","secret":true}]}"""))
        .andExpect(status().isBadRequest());
  }
}
