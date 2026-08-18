package io.hermes.missioncontrol;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application once. Unit tests construct their collaborators by hand, so
 * nothing else here notices a wiring mistake — an ambiguous constructor, a missing bean,
 * a property that no longer binds — until the app fails to start in front of a user.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ApiExceptionHandler exceptionHandler;

  @Test
  void theContextStarts() {
    assertNotNull(exceptionHandler);
  }

  @Test
  void theJdbcTemplateCarriesTheSqliteExceptionTranslator() {
    // repositories rely on constraint failures arriving as DataIntegrityViolationException;
    // Boot's default template would leave them uncategorised and answer 500 instead of 409
    SQLExceptionTranslator translator = jdbc.getExceptionTranslator();
    assertSame(SqliteExceptionTranslator.class, translator.getClass());
  }

  @Test
  void theSchemaIsApplied() {
    assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM docker_hosts", Integer.class));
    assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM profile_templates", Integer.class));
    assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM mcp_servers", Integer.class));
  }
}
