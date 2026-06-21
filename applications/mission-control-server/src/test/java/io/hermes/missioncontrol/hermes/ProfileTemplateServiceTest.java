package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** CRUD-edge behaviour: rename-collision guard and the no-secret-leak DTO mapping. */
class ProfileTemplateServiceTest {

  private final ProfileTemplateRepository repository = Mockito.mock(ProfileTemplateRepository.class);
  // a real cipher (dev key) — encryption/decryption is exercised end to end
  private final SecretCipher cipher = new SecretCipher("unit-test-key", "", true);
  // create/update never touch the docker-backed collaborators, so null is safe
  private final ProfileTemplateService service =
      new ProfileTemplateService(repository, cipher, null, null);

  private static UpsertProfileTemplateRequest request(String name, List<SecretInput> secrets) {
    return new UpsertProfileTemplateRequest(
        name, "desc", "anthropic", "claude-opus-4-8", "", "/opt/data",
        "soul", "memory", List.of(), List.of(), secrets);
  }

  @Test
  void updateRejectsRenameOntoAnExistingName() {
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "old", "", "anthropic", "m", "", "", "", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("taken", "pt-1")).thenReturn(true);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> service.update("pt-1", request("taken", List.of())));
    assertTrue(e.getMessage().contains("already exists"));
    verify(repository, never()).update(any());
  }

  @Test
  void updateKeepingItsOwnNameProceeds() {
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "ops", "", "anthropic", "m", "", "", "", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("ops", "pt-1")).thenReturn(false);

    service.update("pt-1", request("ops", List.of()));
    verify(repository).update(any());
  }

  @Test
  void storedSecretIsEncryptedAndNeverEchoedToTheClient() {
    when(repository.existsByName("ops")).thenReturn(false);

    ProfileTemplateDto dto = service.create(
        request("ops", List.of(new SecretInput("ANTHROPIC_API_KEY", "sk-ant-raw-secret"))));

    SecretRef ref = dto.secrets().get(0);
    assertEquals("ANTHROPIC_API_KEY", ref.key());
    assertTrue(ref.set(), "a value was supplied");
    assertTrue(ref.recoverable(), "value decrypts under the current key");
    // SecretRef exposes only key/set/recoverable — there is no field that could
    // carry the raw value or a suffix of it back to the client.
    assertFalse(ref.toString().contains("sk-ant"), "no secret material in the DTO");
  }
}
