package io.hermes.missioncontrol.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Supplies the {@link JdbcTemplate} every repository injects, with the sqlite-aware
 * exception translator attached. Defining it here backs off Boot's auto-configured
 * template, which would otherwise leave constraint failures uncategorised.
 */
@Configuration
class JdbcConfig {

  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.setExceptionTranslator(new SqliteExceptionTranslator());
    return jdbc;
  }
}
