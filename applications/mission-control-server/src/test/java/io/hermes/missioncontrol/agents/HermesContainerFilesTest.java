package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The exec seam's own rules — the ones every writer in this package inherits. */
class HermesContainerFilesTest {

  @Test
  void aWriteLargerThanOneArgumentIsRefusedBeforeAnythingRuns() {
    // the daemon refuses the exec past 131071 bytes ("argument list too long", measured on
    // Docker 29), which a caller saw as a 400 naming /usr/bin/sh — or, on a sensitive write,
    // as a bare exit code 126. A skill that saved at 140KB deployed to exactly that.
    FakeContainer container = new FakeContainer();
    HermesContainerFiles files = container.files();
    String fits = "a".repeat(HermesContainerFiles.MAX_WRITE_BYTES);

    files.writeFile(HOST, CONTAINER, "/opt/data/SOUL.md", fits);
    files.writeFileAtomically(HOST, CONTAINER, "/opt/data/config.yaml", fits);
    assertEquals(2, container.executed().size());

    // bytes, not characters: two-byte letters cross the limit at half the length
    String multibyte = "\u00e9".repeat(HermesContainerFiles.MAX_WRITE_BYTES / 2 + 1);
    for (String oversize : List.of(fits + "a", multibyte)) {
      IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
          () -> files.writeFile(HOST, CONTAINER, "/opt/data/SOUL.md", oversize));
      assertTrue(refused.getMessage().startsWith("SOUL.md is "), refused.getMessage());
      assertThrows(IllegalArgumentException.class,
          () -> files.writeFileAtomically(HOST, CONTAINER, "/opt/data/config.yaml", oversize));
    }
    assertEquals(2, container.executed().size(), "nothing may run for a write the daemon would refuse");
  }
}
