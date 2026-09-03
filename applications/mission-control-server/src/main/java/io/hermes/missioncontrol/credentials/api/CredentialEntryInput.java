package io.hermes.missioncontrol.credentials.api;

import io.hermes.missioncontrol.agents.api.EnvEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One entry of a credential on the way in. A blank {@code value} on a secret entry keeps the
 * stored one, which is how the editor says "unchanged" — it never receives ciphertext to send
 * back.
 *
 * <p>There is no {@code clear} flag. The editor submits the whole entry list, so an entry it
 * left out is one it removed. {@code mcp/ConfigValueInput} needs the flag because its form
 * keeps emptied rows on screen; this one does not.
 */
public record CredentialEntryInput(
    @NotBlank @Pattern(regexp = EnvEntry.KEY_PATTERN, message = "invalid env key") String key,
    @Size(max = 8192) String value,
    boolean secret) {
}
