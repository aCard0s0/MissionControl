package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The exec seam's own rules — the ones every writer in this package inherits. */
class HermesContainerFilesTest {

  @Test
  void aBodyThatFitsOneArgumentTravelsAsOne_andALargerOneIsStreamed() {
    // the daemon refuses an argv word past 131071 bytes ("argument list too long", measured on
    // Docker 29); a 140KB skill used to save and then fail every deploy on exactly that
    FakeContainer container = new FakeContainer();
    HermesContainerFiles files = container.files();
    String fits = "a".repeat(HermesContainerFiles.MAX_ARG_BYTES);
    String streamed = fits + "a";

    files.writeFile(HOST, CONTAINER, "/opt/data/SOUL.md", fits);
    files.writeFileAtomically(HOST, CONTAINER, "/opt/data/config.yaml", streamed);

    List<String> plain = container.executed().get(0);
    assertEquals(fits, plain.getLast(), "the body is $2");
    assertTrue(plain.get(2).contains("printf '%s' \"$2\""), plain.get(2));
    assertNull(container.stdinOf(0));

    List<String> big = container.executed().get(1);
    assertEquals(String.valueOf(streamed.length()), big.getLast(), "$2 is the byte count");
    assertFalse(big.contains(streamed), "the body is not an argument");
    // exactly the byte count, because the stream is never closed and a `cat` would hang
    assertTrue(big.get(2).contains("head -c \"$2\""), big.get(2));
    assertArrayEquals(streamed.getBytes(StandardCharsets.UTF_8), container.stdinOf(1));
  }

  @Test
  void theThresholdIsBytesNotCharacters() {
    // two-byte letters cross the argv limit at half the length
    FakeContainer container = new FakeContainer();
    String multibyte = "\u00e9".repeat(HermesContainerFiles.MAX_ARG_BYTES / 2 + 1);

    container.files().writeFile(HOST, CONTAINER, "/opt/data/SOUL.md", multibyte);

    assertArrayEquals(multibyte.getBytes(StandardCharsets.UTF_8), container.stdinOf(0));
  }
}
