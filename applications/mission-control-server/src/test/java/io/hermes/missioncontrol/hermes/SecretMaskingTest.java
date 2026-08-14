package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * How a credential is shown back to the client.
 *
 * <p>The reporting paths in {@link HermesSetup} and {@link HermesProfiles} both display
 * API keys in the same UI. They used to carry a copy of this rule each, and both copies
 * returned short values in full.
 */
class SecretMaskingTest {

  @Test
  void aLongKeyShowsOnlyItsLastFourCharacters() {
    assertEquals("...1234", Secrets.mask("sk-ant-secret-value-1234"));
    // enough to tell two configured keys apart, and nothing more
    assertFalse(Secrets.mask("sk-ant-secret-value-1234").contains("secret"));
  }

  @Test
  void aValueTooShortToHaveAHiddenPartRevealsNoneOfItsCharacters() {
    // 4 characters or fewer: the "last four" would be the entire secret
    assertEquals("...", Secrets.mask("abc"));
    assertEquals("...", Secrets.mask("abcd"));
    // one character past the boundary the suffix is genuinely a suffix
    assertEquals("...bcde", Secrets.mask("abcde"));
  }

  @Test
  void anAbsentValueMasksToNothingRatherThanToAPlaceholder() {
    // "" is what the DTO carries for "no key configured"; "..." would render as though
    // one were set
    assertEquals("", Secrets.mask(null));
    assertEquals("", Secrets.mask(""));
    assertEquals("", Secrets.mask("   "));
  }

  @Test
  void surroundingWhitespaceIsNotMistakenForSecretCharacters() {
    // values arrive from a .env line, so trailing whitespace is common — masking it
    // would show four spaces and hide the part that identifies the key
    assertEquals("...1234", Secrets.mask("  sk-ant-value-1234  "));
    assertEquals("...", Secrets.mask("  abc  "));
  }
}
