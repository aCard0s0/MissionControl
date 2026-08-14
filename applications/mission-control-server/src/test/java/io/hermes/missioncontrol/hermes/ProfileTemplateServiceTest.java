package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

  @Test
  void createFromTemplateRollsBackProfileWhenBlueprintFails() {
    HermesProfiles profiles = Mockito.mock(HermesProfiles.class);
    HermesSetup setup = Mockito.mock(HermesSetup.class);
    ProfileTemplateService ownedService =
        new ProfileTemplateService(repository, cipher, profiles, setup);
    ProfileTemplate template = new ProfileTemplate(
        "pt-1", "ops", "", "anthropic", "model", "", "", "soul", "",
        List.of(), List.of(), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(template));
    CreateAgentRequest create = new CreateAgentRequest(
        "dh-local", "cid", "ops", "anthropic", "model", null, null, null, "pt-1", null);
    doThrow(new RuntimeException("soul write failed"))
        .when(profiles).updateSoul("unix:///sock", "cid", "ops", "soul");

    assertThrows(RuntimeException.class,
        () -> ownedService.createFromTemplate("pt-1", "unix:///sock", create));

    verify(profiles).createProfileBare("unix:///sock", create);
    verify(profiles).delete("unix:///sock", "cid", "ops");
  }

  @Test
  void catalogInputBecomesDetachedEncryptedSnapshot() {
    McpRegistryService registry = Mockito.mock(McpRegistryService.class);
    McpServerDto catalog = Mockito.mock(McpServerDto.class);
    when(catalog.id()).thenReturn("mcp-1");
    when(catalog.name()).thenReturn("Remote tools");
    when(catalog.kind()).thenReturn("external");
    when(catalog.transport()).thenReturn("http");
    when(catalog.connectionUrl()).thenReturn("https://tools.example.test/mcp");
    when(registry.require("mcp-1")).thenReturn(catalog);
    when(registry.materializedHeaders("mcp-1"))
        .thenReturn(Map.of("Authorization", "Bearer raw-catalog-secret"));
    when(repository.existsByName("ops")).thenReturn(false);
    ProfileTemplateService catalogService =
        new ProfileTemplateService(repository, cipher, null, null, registry);
    UpsertProfileTemplateRequest input = new UpsertProfileTemplateRequest(
        "ops", "", "nous", "model", "", "/opt/data", "", "", List.of(),
        List.of(new McpServerSpec(
            "tools", null, null, null, null, true, "mcp-1", null, null)),
        List.of());

    ProfileTemplateDto response = catalogService.create(input);

    ArgumentCaptor<ProfileTemplate> stored = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).insert(stored.capture());
    McpServerSpec snapshot = stored.getValue().mcpServers().getFirst();
    assertEquals("tools", snapshot.name());
    assertEquals("http", snapshot.transport());
    assertEquals("https://tools.example.test/mcp", snapshot.url());
    assertEquals(null, snapshot.sourceServerId(), "the catalog link is input-only");
    assertFalse(snapshot.headers().getFirst().encryptedValue().contains("raw-catalog-secret"));
    assertEquals(null, response.mcpServers().getFirst().headers().getFirst().encryptedValue());
    assertFalse(response.toString().contains("raw-catalog-secret"));
  }

  @Test
  void laterTemplateUpdatePreservesSnapshotWithoutConsultingCatalog() {
    McpRegistryService registry = Mockito.mock(McpRegistryService.class);
    String encrypted = cipher.encrypt("Bearer retained-secret");
    McpServerSpec snapshot = new McpServerSpec(
        "tools", "http", "https://tools.example.test/mcp", null, null, true, null,
        List.of(), List.of(new TemplateMcpConfigValue("Authorization", encrypted)));
    ProfileTemplate existing = new ProfileTemplate(
        "pt-1", "ops", "", "nous", "model", "", "/opt/data", "", "",
        List.of(), List.of(snapshot), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));
    when(repository.existsByNameExcept("ops", "pt-1")).thenReturn(false);
    ProfileTemplateService catalogService =
        new ProfileTemplateService(repository, cipher, null, null, registry);
    UpsertProfileTemplateRequest input = new UpsertProfileTemplateRequest(
        "ops", "updated", "nous", "model", "", "/opt/data", "", "", List.of(),
        List.of(new McpServerSpec(
            "tools", "http", "https://tools.example.test/mcp", null, null, true)),
        List.of());

    catalogService.update("pt-1", input);

    ArgumentCaptor<ProfileTemplate> updated = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).update(updated.capture());
    String rotated = updated.getValue().mcpServers().getFirst().headers().getFirst().encryptedValue();
    assertNotEquals(encrypted, rotated);
    assertEquals("Bearer retained-secret", cipher.decrypt(rotated));
    verifyNoInteractions(registry);
  }

  @Test
  void applyingSnapshotDecryptsHeadersAndStdioEnvironmentOnlyAtRuntime() {
    HermesProfiles profiles = Mockito.mock(HermesProfiles.class);
    HermesSetup setup = Mockito.mock(HermesSetup.class);
    McpServerSpec network = new McpServerSpec(
        "remote", "http", "https://tools.example.test/mcp", null, null, true, null,
        List.of(), List.of(new TemplateMcpConfigValue(
            "Authorization", cipher.encrypt("Bearer runtime-token"))));
    McpServerSpec stdio = new McpServerSpec(
        "local", "stdio", null, "npx", "-y @acme/server", true, null,
        List.of(new TemplateMcpConfigValue("npm_config_token", cipher.encrypt("stdio-token"))),
        List.of());
    ProfileTemplate template = new ProfileTemplate(
        "pt-1", "ops", "", "nous", "model", "", "/opt/data", "", "",
        List.of(), List.of(network, stdio), List.of(), 1L, 1L);
    when(repository.findById("pt-1")).thenReturn(Optional.of(template));
    ProfileTemplateService runtimeService =
        new ProfileTemplateService(repository, cipher, profiles, setup);

    runtimeService.applyExisting("pt-1", "unix:///sock", "cid", "ops");

    ArgumentCaptor<AddMcpServerRequest> requests =
        ArgumentCaptor.forClass(AddMcpServerRequest.class);
    verify(profiles, Mockito.times(2))
        .addMcpServer(Mockito.eq("unix:///sock"), Mockito.eq("cid"), Mockito.eq("ops"), requests.capture());
    assertEquals(Map.of("Authorization", "Bearer runtime-token"),
        requests.getAllValues().getFirst().headers());
    assertEquals(Map.of("npm_config_token", "stdio-token"),
        requests.getAllValues().get(1).environment());
    verify(setup, never()).putEnv(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyList());
  }
}
