package io.hermes.missioncontrol;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MissionControlApplication {

  public static void main(String[] args) throws Exception {
    ensureDbDirectory();
    SpringApplication.run(MissionControlApplication.class, args);
  }

  /** sqlite-jdbc creates the database file but not its parent directory. */
  private static void ensureDbDirectory() throws Exception {
    String dbPath = System.getenv().getOrDefault("MC_DB_PATH", "./data/mission-control.db");
    Path parent = Path.of(dbPath).toAbsolutePath().getParent();
    if (parent != null) Files.createDirectories(parent);
  }
}
