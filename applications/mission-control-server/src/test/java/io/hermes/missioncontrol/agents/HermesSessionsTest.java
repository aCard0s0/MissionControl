package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.SessionDto;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading a profile's conversation store.
 *
 * <p>The container image has python3 but no sqlite3 CLI, so every query is a python script
 * driven over exec with the session id as argv — never interpolated into SQL or shell. Two
 * behaviours matter beyond that: a profile with no {@code state.db} yet is empty rather than
 * an error, and an unparseable answer degrades to "no sessions" rather than 500-ing the
 * agents view, which is exactly why a silently wrong parse would be invisible.
 */
class HermesSessionsTest {

  private static final String DB = "/opt/data/profiles/ops/state.db";

  private HermesSessions sessions(FakeContainer container) {
    return new HermesSessions(container.files(), new ObjectMapper());
  }

  // ── listing ────────────────────────────────────────────────────────────────

  @Test
  void rowsAreMappedWithSecondsPromotedToMillis() {
    FakeContainer container = new FakeContainer()
        .file(DB, "")
        .onCommand("FROM sessions", """
            [{"id":"s1","source":"slack","title":"Deploy review",
              "started_at":1783765479.656,"ended_at":null,"message_count":12}]
            """);

    SessionDto session = sessions(container).list(HOST, CONTAINER, "ops").getFirst();

    assertEquals("s1", session.id());
    assertEquals("Deploy review", session.title());
    assertEquals("slack", session.platform());
    assertEquals(1783765479656L, session.startedAt());
    assertEquals(12, session.messages());
    assertEquals("open", session.status(), "a session with no ended_at is still open");
  }

  @Test
  void anEndedSessionIsClosed() {
    FakeContainer container = new FakeContainer()
        .file(DB, "")
        .onCommand("FROM sessions", """
            [{"id":"s1","source":"cli","title":"t","started_at":1,
              "ended_at":2,"message_count":1}]
            """);

    assertEquals("closed", sessions(container).list(HOST, CONTAINER, "ops").getFirst().status());
  }

  @Test
  void missingTitleAndSourceGetDisplayableDefaults() {
    FakeContainer container = new FakeContainer()
        .file(DB, "")
        .onCommand("FROM sessions", """
            [{"id":"s1","source":null,"title":null,"started_at":null,
              "ended_at":null,"message_count":null}]
            """);

    SessionDto session = sessions(container).list(HOST, CONTAINER, "ops").getFirst();
    assertEquals("(untitled session)", session.title());
    assertEquals("cli", session.platform());
    assertEquals(0, session.startedAt());
    assertEquals(0, session.messages());
  }

  @Test
  void aRowWithNoIdIsDroppedRatherThanListedUnopenable() {
    FakeContainer container = new FakeContainer()
        .file(DB, "")
        .onCommand("FROM sessions", """
            [{"id":"","source":"cli","title":"ghost","started_at":1,"ended_at":null,
              "message_count":1},
             {"id":"s2","source":"cli","title":"real","started_at":1,"ended_at":null,
              "message_count":1}]
            """);

    assertEquals(List.of("s2"),
        sessions(container).list(HOST, CONTAINER, "ops").stream().map(SessionDto::id).toList());
  }

  @Test
  void aProfileWithNoStoreYetHasNoSessionsAndIsNotQueried() {
    FakeContainer container = new FakeContainer();   // no state.db

    assertEquals(List.of(), sessions(container).list(HOST, CONTAINER, "ops"));
    assertTrue(container.executed().stream()
        .noneMatch(argv -> argv.contains("python3")), "the store was queried anyway");
  }

  @Test
  void anUnparseableAnswerDegradesToNoSessions() {
    // the python script prints '[]' on its own failures, but a truncated or
    // non-JSON answer must not surface as a 500 on the agents view
    FakeContainer container = new FakeContainer()
        .file(DB, "")
        .onCommand("FROM sessions", "Traceback (most recent call last): …");

    assertEquals(List.of(), sessions(container).list(HOST, CONTAINER, "ops"));
  }

  // ── messages ───────────────────────────────────────────────────────────────

  @Test
  void messagesArePassedThroughAsTheJsonTheScriptEmitted() {
    FakeContainer container = new FakeContainer()
        .file(DB, "")
        .onCommand("FROM messages", "[{\"role\":\"user\",\"content\":\"hi\"}]\n");

    assertEquals("[{\"role\":\"user\",\"content\":\"hi\"}]",
        sessions(container).readMessages(HOST, CONTAINER, "ops", "s1"));
  }

  @Test
  void anEmptyAnswerBecomesAnEmptyArrayRatherThanABlankBody() {
    FakeContainer container = new FakeContainer().file(DB, "").onCommand("FROM messages", "   ");

    assertEquals("[]", sessions(container).readMessages(HOST, CONTAINER, "ops", "s1"));
  }

  @Test
  void aProfileWithNoStoreHasNoHistory() {
    assertEquals("[]", sessions(new FakeContainer()).readMessages(HOST, CONTAINER, "ops", "s1"));
  }

  @Test
  void theHistoryReadIsCheckedWhileTheListingIsNot() {
    // the listing's script prints '[]' on its own errors, so its exit code carries no
    // information. The history script deliberately re-raises locked/corrupt failures, and
    // only the checked exec turns that non-zero exit into an error the operator sees.
    FakeContainer container = new FakeContainer().file(DB, "")
        .onCommand("FROM sessions", "[]")
        .onCommand("FROM messages", "[]");

    sessions(container).list(HOST, CONTAINER, "ops");
    verify(container.dockerExec()).runAsUser(any(), anyString(), anyString(),
        argThat(argv -> argv.size() > 2 && argv.get(2).contains("FROM sessions")),
        anyString(), eq(false), anyBoolean(), any(Duration.class));

    sessions(container).readMessages(HOST, CONTAINER, "ops", "s1");
    verify(container.dockerExec()).runAsUser(any(), anyString(), anyString(),
        argThat(argv -> argv.size() > 2 && argv.get(2).contains("FROM messages")),
        anyString(), eq(true), anyBoolean(), any(Duration.class));
  }

  // ── deletion ───────────────────────────────────────────────────────────────

  @Test
  void deletionRunsAgainstTheProfilesOwnStore() {
    FakeContainer container = new FakeContainer().file(DB, "");

    sessions(container).delete(HOST, CONTAINER, "ops", "s1");

    List<String> argv = container.executed().stream()
        .filter(a -> a.contains("python3")).findFirst().orElseThrow();
    assertEquals("python3", argv.getFirst());
    assertEquals("-c", argv.get(1));
    assertTrue(argv.get(2).contains("DELETE FROM sessions"));
    // db and id are argv, so neither can be read as SQL or shell
    assertEquals(List.of(DB, "s1"), argv.subList(3, argv.size()));
  }

  @Test
  void deletingFromAProfileWithNoStoreIsRejectedRatherThanSilentlyDoingNothing() {
    assertThrows(IllegalArgumentException.class,
        () -> sessions(new FakeContainer()).delete(HOST, CONTAINER, "ops", "s1"));
  }

  // ── input validation ───────────────────────────────────────────────────────

  @Test
  void aBlankSessionIdIsRejectedBeforeAnyQuery() {
    FakeContainer container = new FakeContainer().file(DB, "");
    HermesSessions sessions = sessions(container);

    for (String id : new String[] {null, "", "   "}) {
      assertThrows(IllegalArgumentException.class,
          () -> sessions.readMessages(HOST, CONTAINER, "ops", id), "id=" + id);
      assertThrows(IllegalArgumentException.class,
          () -> sessions.delete(HOST, CONTAINER, "ops", id), "id=" + id);
    }
    assertEquals(List.of(), container.executed());
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirectoryIsRejected() {
    HermesSessions sessions = sessions(new FakeContainer());

    assertThrows(IllegalArgumentException.class,
        () -> sessions.list(HOST, CONTAINER, "../../etc"));
    assertThrows(IllegalArgumentException.class,
        () -> sessions.readMessages(HOST, CONTAINER, "a/b", "s1"));
  }
}
