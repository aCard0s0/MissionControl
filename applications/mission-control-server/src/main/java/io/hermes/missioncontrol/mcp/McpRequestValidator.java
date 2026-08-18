package io.hermes.missioncontrol.mcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class McpRequestValidator {

  private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Pattern HEADER_KEY = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
  private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@:-]{0,499}");
  private static final Pattern PLATFORM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./-]{0,127}");
  private static final Pattern VOLUME = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,62}");
  private static final Pattern SUPPORT_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final Pattern DURATION = Pattern.compile("[1-9][0-9]*(?:\\.[0-9]+)?(?:ns|us|ms|s|m|h)");

  record Validated(
      String name,
      String description,
      String kind,
      String hostId,
      String transport,
      String url,
      String image,
      String platform,
      List<String> entrypoint,
      List<String> command,
      String stdioCommand,
      List<String> args,
      Integer internalPort,
      Integer publishedPort,
      String path,
      String crossHostUrl,
      List<ConfigValueInput> environment,
      List<ConfigValueInput> headers,
      List<VolumeSpec> volumes,
      HealthcheckSpec healthcheck,
      List<SupportServiceRequest> supportServices) {}

  private McpRequestValidator() {}

  static Validated validate(McpServerRequest request) {
    if (request == null) throw new IllegalArgumentException("request body is required");
    String name = required(request.name(), "name", 100);
    String description = optional(request.description(), "description", 2_000);
    String kind = lower(required(request.kind(), "kind", 20));
    if (!Set.of("managed", "external", "stdio").contains(kind)) {
      throw new IllegalArgumentException("kind must be managed, external, or stdio");
    }

    String transport = lower(optional(request.transport(), "transport", 20));
    String hostId = optional(request.hostId(), "hostId", 100);
    String url = optional(request.url(), "url", 2_048);
    String image = optional(request.image(), "image", 500);
    String platform = optional(request.platform(), "platform", 128);
    List<String> entrypoint = composeList(request.entrypoint(), "entrypoint", 100, 2_048);
    List<String> command = composeList(request.command(), "command", 100, 2_048);
    String stdioCommand = optional(request.stdioCommand(), "stdioCommand", 2_048);
    List<String> args = stringList(request.args(), "args", 100, 2_048);
    String path = optional(request.path(), "path", 512);
    String crossHostUrl = optional(request.crossHostUrl(), "crossHostUrl", 2_048);
    List<ConfigValueInput> environment = values(request.environment(), false);
    List<ConfigValueInput> headers = values(request.headers(), true);
    List<VolumeSpec> volumes = volumes(request.volumes());
    HealthcheckSpec healthcheck = healthcheck(request.healthcheck());
    List<SupportServiceRequest> supports = supports(request.supportServices());

    switch (kind) {
      case "managed" -> {
        if (hostId == null) throw new IllegalArgumentException("hostId is required for managed servers");
        if (image == null || !IMAGE.matcher(image).matches()) {
          throw new IllegalArgumentException("image is required and must be a valid image reference");
        }
        if (platform != null && !PLATFORM.matcher(platform).matches()) {
          throw new IllegalArgumentException("platform is invalid");
        }
        transport = requireTransport(transport);
        requirePort(request.internalPort(), "internalPort");
        if (request.publishedPort() != null) requirePort(request.publishedPort(), "publishedPort");
        path = normalizePath(path);
        if (crossHostUrl != null) validateHttpUrl(crossHostUrl, "crossHostUrl");
        if (url != null || stdioCommand != null || !args.isEmpty()) {
          throw new IllegalArgumentException("url and stdio fields do not apply to managed servers");
        }
      }
      case "external" -> {
        transport = requireTransport(transport);
        if (url == null) throw new IllegalArgumentException("url is required for external servers");
        validateHttpUrl(url, "url");
        rejectManagedFields(hostId, image, platform, entrypoint, command, stdioCommand, args,
            request.internalPort(), request.publishedPort(), path, crossHostUrl, volumes, healthcheck, supports);
        if (!environment.isEmpty()) {
          throw new IllegalArgumentException("environment does not apply to external servers");
        }
      }
      case "stdio" -> {
        transport = "stdio";
        if (stdioCommand == null) throw new IllegalArgumentException("stdioCommand is required for stdio servers");
        rejectManagedFields(hostId, image, platform, entrypoint, command, null, List.of(),
            request.internalPort(), request.publishedPort(), path, crossHostUrl, volumes, healthcheck, supports);
        if (url != null) throw new IllegalArgumentException("url does not apply to stdio servers");
        if (!headers.isEmpty()) throw new IllegalArgumentException("headers do not apply to stdio servers");
      }
      default -> throw new IllegalStateException("unreachable");
    }

    return new Validated(name, description, kind, hostId, transport, url, image, platform,
        entrypoint, command, stdioCommand, args, request.internalPort(), request.publishedPort(),
        path, crossHostUrl, environment, headers, volumes, healthcheck, supports);
  }

  private static void rejectManagedFields(
      String hostId, String image, String platform, List<String> entrypoint, List<String> command,
      String stdioCommand, List<String> args, Integer internalPort, Integer publishedPort, String path,
      String crossHostUrl, List<VolumeSpec> volumes, HealthcheckSpec healthcheck,
      List<SupportServiceRequest> supports) {
    if (hostId != null || image != null || platform != null || !entrypoint.isEmpty() || !command.isEmpty()
        || stdioCommand != null || !args.isEmpty() || internalPort != null || publishedPort != null
        || path != null || crossHostUrl != null || !volumes.isEmpty() || healthcheck != null
        || !supports.isEmpty()) {
      throw new IllegalArgumentException("managed deployment fields only apply to managed servers");
    }
  }

  private static String requireTransport(String transport) {
    // not Set.of(...).contains(transport): an omitted transport is null, and Set.of rejects a
    // null argument with an NPE — which leaves the handler no IllegalArgumentException to turn
    // into a 400 and reports a missing field as a 500
    if (!("http".equals(transport) || "sse".equals(transport))) {
      throw new IllegalArgumentException("transport must be http or sse");
    }
    return transport;
  }

  private static void requirePort(Integer port, String field) {
    if (port == null || port < 1 || port > 65_535) {
      throw new IllegalArgumentException(field + " must be between 1 and 65535");
    }
  }

  private static String normalizePath(String value) {
    if (value == null || value.isBlank()) return "/mcp";
    if (!value.startsWith("/") || value.startsWith("//")) {
      throw new IllegalArgumentException("path must start with one /");
    }
    URI path;
    try {
      path = URI.create(value);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("path is invalid");
    }
    // outside the try: inside it, this throw was caught by the catch below and reported as the
    // generic "path is invalid", so the reason a path was refused never reached the operator
    if (path.isAbsolute() || path.getHost() != null || path.getFragment() != null) {
      throw new IllegalArgumentException("path must be a relative HTTP path");
    }
    return value;
  }

  static void validateHttpUrl(String value, String field) {
    try {
      URI uri = URI.create(value);
      String scheme = lower(uri.getScheme());
      if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException(field + " must be an HTTP(S) URL without credentials or fragment");
      }
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith(field + " ")) throw e;
      throw new IllegalArgumentException(field + " must be a valid HTTP(S) URL");
    }
  }

  private static List<ConfigValueInput> values(List<ConfigValueInput> input, boolean headers) {
    if (input == null) return List.of();
    if (input.size() > 100) throw new IllegalArgumentException("too many configuration values");
    List<ConfigValueInput> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ConfigValueInput item : input) {
      if (item == null) throw new IllegalArgumentException("configuration entries cannot be null");
      String key = required(item.key(), headers ? "header key" : "environment key", 128);
      Pattern pattern = headers ? HEADER_KEY : ENV_KEY;
      if (!pattern.matcher(key).matches()) throw new IllegalArgumentException("invalid " + (headers ? "header" : "environment") + " key: " + key);
      String unique = headers ? lower(key) : key;
      if (!seen.add(unique)) throw new IllegalArgumentException("duplicate configuration key: " + key);
      String value = item.value();
      if (value != null) scalar(value, "configuration value", 8_192);
      out.add(new ConfigValueInput(key, value, item.secret(), item.clear()));
    }
    return List.copyOf(out);
  }

  private static List<VolumeSpec> volumes(List<VolumeSpec> input) {
    if (input == null) return List.of();
    if (input.size() > 20) throw new IllegalArgumentException("too many volumes");
    List<VolumeSpec> out = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (VolumeSpec item : input) {
      if (item == null) throw new IllegalArgumentException("volume entries cannot be null");
      String name = lower(required(item.name(), "volume name", 63));
      String target = required(item.target(), "volume target", 512);
      if (!VOLUME.matcher(name).matches()) throw new IllegalArgumentException("invalid volume name: " + name);
      if (!names.add(name)) throw new IllegalArgumentException("duplicate volume name: " + name);
      if (!target.startsWith("/") || target.contains("/../") || target.endsWith("/..")
          || "/var/run/docker.sock".equals(target)) {
        throw new IllegalArgumentException("volume targets must be safe absolute container paths");
      }
      out.add(new VolumeSpec(name, target));
    }
    return List.copyOf(out);
  }

  private static HealthcheckSpec healthcheck(HealthcheckSpec input) {
    if (input == null) return null;
    List<String> test = composeList(input.test(), "healthcheck.test", 20, 2_048);
    if (test.isEmpty() || !Set.of("CMD", "CMD-SHELL", "NONE").contains(test.getFirst())) {
      throw new IllegalArgumentException("healthcheck.test must begin with CMD, CMD-SHELL, or NONE");
    }
    if ("NONE".equals(test.getFirst()) && test.size() != 1) {
      throw new IllegalArgumentException("NONE healthcheck cannot have arguments");
    }
    String interval = duration(input.interval(), "healthcheck.interval");
    String timeout = duration(input.timeout(), "healthcheck.timeout");
    String startPeriod = duration(input.startPeriod(), "healthcheck.startPeriod");
    Integer retries = input.retries();
    if (retries != null && (retries < 1 || retries > 100)) {
      throw new IllegalArgumentException("healthcheck.retries must be between 1 and 100");
    }
    return new HealthcheckSpec(test, interval, timeout, retries, startPeriod);
  }

  private static List<SupportServiceRequest> supports(List<SupportServiceRequest> input) {
    if (input == null) return List.of();
    if (input.size() > 10) throw new IllegalArgumentException("too many support services");
    List<SupportServiceRequest> out = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (SupportServiceRequest item : input) {
      if (item == null) throw new IllegalArgumentException("support service entries cannot be null");
      String name = lower(required(item.name(), "support service name", 63));
      if (!SUPPORT_NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid support service name: " + name);
      if (!names.add(name)) throw new IllegalArgumentException("duplicate support service name: " + name);
      String image = required(item.image(), "support service image", 500);
      if (!IMAGE.matcher(image).matches()) throw new IllegalArgumentException("invalid support service image");
      String platform = optional(item.platform(), "support service platform", 128);
      if (platform != null && !PLATFORM.matcher(platform).matches()) throw new IllegalArgumentException("invalid support service platform");
      out.add(new SupportServiceRequest(name, image, platform,
          composeList(item.entrypoint(), "support entrypoint", 100, 2_048),
          composeList(item.command(), "support command", 100, 2_048),
          values(item.environment(), false), volumes(item.volumes()), healthcheck(item.healthcheck())));
    }
    return List.copyOf(out);
  }

  private static String duration(String value, String field) {
    value = optional(value, field, 30);
    if (value != null && !DURATION.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a positive Compose duration such as 5s");
    }
    return value;
  }

  private static List<String> stringList(List<String> input, String field, int maxItems, int maxLen) {
    if (input == null) return List.of();
    if (input.size() > maxItems) throw new IllegalArgumentException(field + " has too many entries");
    List<String> out = new ArrayList<>();
    for (String item : input) out.add(scalar(item, field, maxLen));
    return List.copyOf(out);
  }

  /**
   * Like {@link #stringList}, for the fields written verbatim into the generated Compose
   * file: {@code entrypoint}, {@code command}, and {@code healthcheck.test}, on the main
   * service and on every support service.
   */
  private static List<String> composeList(List<String> input, String field, int maxItems, int maxLen) {
    if (input == null) return List.of();
    if (input.size() > maxItems) throw new IllegalArgumentException(field + " has too many entries");
    List<String> out = new ArrayList<>();
    for (String item : input) out.add(composeLiteral(item, field, maxLen));
    return List.copyOf(out);
  }

  /**
   * A scalar that will appear literally in the Compose file, so it must not contain '$'.
   *
   * <p>Compose interpolates {@code ${VAR}} in values it parses, and YAML single quotes do
   * not prevent it. The Compose process environment carries the decrypted secrets of
   * <em>every</em> managed server in the stack, under names derived from values the API
   * hands back — so a '$' here lets one server's command read another server's
   * credentials and print them to its own log.
   *
   * <p>Deliberately not folded into {@link #scalar}: that also guards configuration
   * <em>values</em>, where a '$' in a password is legitimate and safe, because those
   * reach the file only as a {@code ${MC_MCP_…:-}} reference, never as literal text.
   */
  private static String composeLiteral(String value, String field, int maxLen) {
    String result = scalar(value, field, maxLen);
    if (result.indexOf('$') >= 0) {
      throw new IllegalArgumentException(field + " cannot contain '$'");
    }
    return result;
  }

  private static String required(String value, String field, int maxLen) {
    String result = optional(value, field, maxLen);
    if (result == null) throw new IllegalArgumentException(field + " is required");
    return result;
  }

  private static String optional(String value, String field, int maxLen) {
    if (value == null) return null;
    String result = value.trim();
    if (result.isEmpty()) return null;
    return scalar(result, field, maxLen);
  }

  private static String scalar(String value, String field, int maxLen) {
    if (value == null) throw new IllegalArgumentException(field + " cannot be null");
    if (value.length() > maxLen) throw new IllegalArgumentException(field + " is too long");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\n' || c == '\r' || c == '\0' || (c < 0x20 && c != '\t')) {
        throw new IllegalArgumentException(field + " cannot contain control characters");
      }
    }
    return value;
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
