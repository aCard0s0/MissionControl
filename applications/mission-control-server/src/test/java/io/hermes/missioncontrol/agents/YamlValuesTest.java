package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lenient readers every profile view goes through.
 *
 * <p>These exist so a {@code config.yaml} written by a newer hermes, or hand-edited into an
 * unexpected shape, renders a degraded profile instead of 500-ing the whole agents page. Each
 * accessor therefore has a "give up quietly" path, and those are the paths worth pinning —
 * {@link YamlValues#requireMapping} is the single deliberate exception.
 */
class YamlValuesTest {

  @Test
  void aDocumentThatIsNotAMappingReadsAsEmptyRatherThanFailing() {
    assertEquals(Map.of(), YamlValues.parseMap(null));
    assertEquals(Map.of(), YamlValues.parseMap("   "));
    // a bare scalar, a list, and unparseable text are all 'no settings I understand'
    assertEquals(Map.of(), YamlValues.parseMap("just a string"));
    assertEquals(Map.of(), YamlValues.parseMap("- one\n- two\n"));
    assertEquals(Map.of(), YamlValues.parseMap("model: [unclosed\n"));
  }

  @Test
  void aMappingIsReturnedAsItWasParsed() {
    Map<?, ?> parsed = YamlValues.parseMap("model: opus\ntemperature: 0.4\n");

    assertEquals("opus", parsed.get("model"));
    assertEquals(0.4, parsed.get("temperature"));
  }

  @Test
  void requireMappingIsTheOneReaderThatRefuses() {
    // it guards a config write: storing a scalar where hermes expects a mapping would leave the
    // agent with a config file it cannot start from
    assertEquals("config must be a mapping",
        assertThrows(IllegalArgumentException.class,
            () -> YamlValues.requireMapping(null, "config must be a mapping")).getMessage());
    assertEquals("config must be a mapping",
        assertThrows(IllegalArgumentException.class,
            () -> YamlValues.requireMapping("  ", "config must be a mapping")).getMessage());
    assertEquals("config must be a mapping",
        assertThrows(IllegalArgumentException.class,
            () -> YamlValues.requireMapping("- a list\n", "config must be a mapping")).getMessage());

    // a parse failure carries the snakeyaml error as the cause, not as the message
    IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
        () -> YamlValues.requireMapping("model: [unclosed\n", "config must be a mapping"));
    assertEquals("config must be a mapping", malformed.getMessage());
    assertTrue(malformed.getCause() != null, "the parser's own reason is kept on the cause");

    YamlValues.requireMapping("model: opus\n", "config must be a mapping");
  }

  @Test
  void scalarsAreTrimmedAndAbsenceReadsAsEmpty() {
    assertEquals("", YamlValues.stringValue(null));
    assertEquals("opus", YamlValues.stringValue("  opus  "));
    assertEquals("42", YamlValues.stringValue(42));
    assertEquals("true", YamlValues.stringValue(true));
  }

  @Test
  void aListIsCopiedSoTheEditedTreeNeverAliasesTheParsedOne() {
    List<?> parsed = List.of("one", "two");

    List<Object> mutable = YamlValues.asMutableList(parsed);

    assertEquals(List.of("one", "two"), mutable);
    assertNotSame(parsed, mutable);
    mutable.add("three");
    // anything that is not a list yields a fresh empty list, never null
    assertEquals(new ArrayList<>(), YamlValues.asMutableList("not a list"));
    assertEquals(new ArrayList<>(), YamlValues.asMutableList(null));
  }

  @Test
  void argsJoinBackIntoTheStringTheEditFormShows() {
    assertEquals("-y @example/mcp", YamlValues.joinArgs(List.of("-y", "@example/mcp")));
    // blank entries would render as double spaces in the form
    assertEquals("-y @example/mcp", YamlValues.joinArgs(List.of("-y", "   ", "@example/mcp", "")));
    assertEquals("42 true", YamlValues.joinArgs(List.of(42, true)));
    // a config that stores args as one string rather than a list still reads back
    assertEquals("-y @example/mcp", YamlValues.joinArgs("-y @example/mcp"));
    assertEquals("", YamlValues.joinArgs(null));
  }

  @Test
  void aNumberThatIsNotANumberReadsAsZeroRatherThanBreakingTheProfile() {
    assertEquals(0.4, YamlValues.toDouble(0.4));
    assertEquals(2.0, YamlValues.toDouble(2));
    assertEquals(0.7, YamlValues.toDouble(" 0.7 "));
    assertEquals(0, YamlValues.toDouble("warm"));
    assertEquals(0, YamlValues.toDouble(null));
  }

  @Test
  void anEditedTreeDumpsBackToYaml() {
    String dumped = YamlValues.dump(Map.of("model", "opus"));

    assertEquals(Map.of("model", "opus"), YamlValues.parseMap(dumped));
  }
}
