package io.hermes.missioncontrol;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;

/**
 * Turns sqlite constraint failures into {@link DataIntegrityViolationException}.
 *
 * <p>sqlite-jdbc reports no SQLState (it is {@code null}) and sqlite is absent from
 * Spring's error-code registry, so the default translator cannot classify anything and
 * every failure arrives as {@code UncategorizedSQLException} — which is a plain
 * {@code RuntimeException} as far as the web layer is concerned. Without this, a UNIQUE
 * or CHECK violation reaches {@code ApiExceptionHandler}'s catch-all and the client gets
 * 503 instead of 409.
 *
 * <p>sqlite reports every constraint failure as primary result code 19
 * ({@code SQLITE_CONSTRAINT}); the extended codes that distinguish UNIQUE from CHECK from
 * NOT NULL are all refinements of it, and all of them are integrity violations.
 */
public class SqliteExceptionTranslator extends AbstractFallbackSQLExceptionTranslator {

  private static final int SQLITE_CONSTRAINT = 19;

  @Override
  protected DataAccessException doTranslate(String task, String sql, SQLException ex) {
    if (ex.getErrorCode() == SQLITE_CONSTRAINT) {
      return new DataIntegrityViolationException(buildMessage(task, sql, ex), ex);
    }
    // anything else keeps the default behaviour: fall through to UncategorizedSQLException
    return null;
  }
}
