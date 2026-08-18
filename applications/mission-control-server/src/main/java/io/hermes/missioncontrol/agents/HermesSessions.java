package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Conversations recorded in a profile's SQLite store.
 *
 * <p>Hermes keeps them in {@code state.db}: the {@code sessions} table holds one row per
 * conversation, {@code messages} the turns keyed by {@code session_id}. The container image
 * has python3 but no sqlite3 CLI, so every query shells out to python3 and emits JSON.
 * Session ids and values are passed as argv plus bound query parameters — never
 * interpolated into SQL or shell.
 *
 * <p>Split out of {@link HermesProfiles} to keep those embedded scripts, and the rules about
 * which sqlite errors are degraded rather than surfaced, in one readable place.
 */
@Component
class HermesSessions {

  private static final Logger log = LoggerFactory.getLogger(HermesSessions.class);

  private final HermesContainerFiles files;
  private final ObjectMapper objectMapper;

  HermesSessions(HermesContainerFiles files, ObjectMapper objectMapper) {
    this.files = files;
    this.objectMapper = objectMapper;
  }

  List<SessionDto> list(String url, String containerId, String profileName) {
    String db = ProfilePaths.stateDb(profileName);
    if (!files.fileExists(url, containerId, db)) return List.of();
    String py = """
        import sqlite3, json, sys
        db = sys.argv[1]
        try:
            con = sqlite3.connect('file:%s?mode=ro' % db, uri=True)
            con.row_factory = sqlite3.Row
            rows = con.execute(
                "SELECT id, source, title, started_at, ended_at, message_count "
                "FROM sessions WHERE archived=0 ORDER BY started_at DESC LIMIT 200").fetchall()
            print(json.dumps([dict(r) for r in rows]))
        except Exception:
            print('[]')
        """;
    ExecResult r = files.exec(url, containerId, List.of("python3", "-c", py, db), false);
    return parseSessionRows(r.stdout());
  }

  /** Returns the chat history (messages) for a session as a JSON array string. */
  String readMessages(String url, String containerId, String profileName, String sessionId) {
    requireSessionId(sessionId);
    String db = ProfilePaths.stateDb(profileName);
    if (!files.fileExists(url, containerId, db)) return "[]";
    // A genuinely empty session yields '[]' (exit 0). Real availability errors
    // (locked, corrupt) still raise -> non-zero exit -> exec(check=true) throws ->
    // the caller surfaces them. But a schema mismatch (an older/newer hermes whose
    // messages table is missing an optional column) is degraded to '[]' rather than
    // 500-ing the whole chat view — only OperationalErrors that aren't "locked"/
    // "corrupt" are swallowed.
    String py = """
        import sqlite3, json, sys
        db, sid = sys.argv[1], sys.argv[2]
        con = sqlite3.connect('file:%s?mode=ro' % db, uri=True)
        con.row_factory = sqlite3.Row
        try:
            rows = con.execute(
                "SELECT role, content, tool_name, tool_calls, reasoning_content, timestamp "
                "FROM messages WHERE session_id=? AND active=1 ORDER BY timestamp, id LIMIT 4000",
                (sid,)).fetchall()
        except sqlite3.OperationalError as e:
            msg = str(e).lower()
            if 'locked' in msg or 'malformed' in msg or 'corrupt' in msg:
                raise
            print('[]'); sys.exit(0)
        out = [{'role': r['role'], 'content': r['content'] or '',
                'toolName': r['tool_name'], 'toolCalls': r['tool_calls'],
                'reasoning': r['reasoning_content'],
                'ts': int((r['timestamp'] or 0) * 1000)} for r in rows]
        print(json.dumps(out))
        """;
    ExecResult r = files.exec(url, containerId, List.of("python3", "-c", py, db, sessionId));
    String out = r.stdout().trim();
    return out.isEmpty() ? "[]" : out;
  }

  void delete(String url, String containerId, String profileName, String sessionId) {
    requireSessionId(sessionId);
    String db = ProfilePaths.stateDb(profileName);
    if (!files.fileExists(url, containerId, db)) {
      throw new IllegalArgumentException("no session store for this profile");
    }
    String py = """
        import sqlite3, sys
        db, sid = sys.argv[1], sys.argv[2]
        con = sqlite3.connect(db, timeout=10)
        con.execute('PRAGMA busy_timeout=10000')
        con.execute('DELETE FROM messages WHERE session_id=?', (sid,))
        con.execute('DELETE FROM sessions WHERE id=?', (sid,))
        con.commit(); con.close()
        """;
    files.exec(url, containerId, List.of("python3", "-c", py, db, sessionId));   // check=true surfaces errors
  }

  private static void requireSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("invalid session id");
    }
  }

  private List<SessionDto> parseSessionRows(String json) {
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(json, new TypeReference<>() {});
      List<SessionDto> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        String id = YamlValues.stringValue(row.get("id"));
        if (id.isBlank()) continue;
        String title = YamlValues.stringValue(row.get("title"));
        if (title.isBlank()) title = "(untitled session)";
        String platform = YamlValues.stringValue(row.get("source"));
        if (platform.isBlank()) platform = ProfilePaths.PLATFORM_CLI;
        long startedAt = (long) (YamlValues.toDouble(row.get("started_at")) * 1000);
        int messages = (int) YamlValues.toDouble(row.get("message_count"));
        String status = row.get("ended_at") == null ? "open" : "closed";
        out.add(new SessionDto(id, title, platform, startedAt, messages, status));
      }
      return out;
    } catch (Exception e) {
      // an empty session list and an unreadable one look identical to the operator
      log.warn("could not parse the session rows returned by the profile state db: {}", e.toString());
      return List.of();
    }
  }
}
