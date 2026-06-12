package io.hermes.missioncontrol.hermes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Agent setup status: merges the `hermes status` report with the profile .env. */
@Service
public class HermesSetup {

  /** Mirrors the provider tables in /opt/hermes/hermes_cli/status.py inside the
   *  hermes image — the .env is the source of truth for set/masked, the status
   *  output only fills in providers configured outside the .env. */
  static final List<ApiKeySpec> API_KEYS = List.of(
      new ApiKeySpec("OpenRouter", "OPENROUTER_API_KEY", List.of(), false),
      new ApiKeySpec("OpenAI", "OPENAI_API_KEY", List.of(), false),
      new ApiKeySpec("Anthropic", "ANTHROPIC_API_KEY", List.of("ANTHROPIC_TOKEN"), false),
      new ApiKeySpec("Google / Gemini", "GOOGLE_API_KEY", List.of("GEMINI_API_KEY"), false),
      new ApiKeySpec("DeepSeek", "DEEPSEEK_API_KEY", List.of(), false),
      new ApiKeySpec("xAI / Grok", "XAI_API_KEY", List.of(), false),
      new ApiKeySpec("NVIDIA NIM", "NVIDIA_API_KEY", List.of(), false),
      new ApiKeySpec("Z.AI / GLM", "GLM_API_KEY", List.of(), false),
      new ApiKeySpec("Kimi", "KIMI_API_KEY", List.of(), false),
      new ApiKeySpec("StepFun Step Plan", "STEPFUN_API_KEY", List.of(), false),
      new ApiKeySpec("MiniMax", "MINIMAX_API_KEY", List.of(), false),
      new ApiKeySpec("MiniMax-CN", "MINIMAX_CN_API_KEY", List.of(), false),
      new ApiKeySpec("Firecrawl", "FIRECRAWL_API_KEY", List.of(), false),
      new ApiKeySpec("Tavily", "TAVILY_API_KEY", List.of(), false),
      new ApiKeySpec("Browser Use", "BROWSER_USE_API_KEY", List.of(), true),
      new ApiKeySpec("Browserbase", "BROWSERBASE_API_KEY", List.of(), true),
      new ApiKeySpec("FAL", "FAL_KEY", List.of(), false),
      new ApiKeySpec("ElevenLabs", "ELEVENLABS_API_KEY", List.of(), false),
      new ApiKeySpec("GitHub", "GITHUB_TOKEN", List.of(), false));

  static final List<MessagingSpec> MESSAGING = List.of(
      new MessagingSpec("Telegram", "TELEGRAM_BOT_TOKEN", "TELEGRAM_HOME_CHANNEL"),
      new MessagingSpec("Discord", "DISCORD_BOT_TOKEN", "DISCORD_HOME_CHANNEL"),
      new MessagingSpec("WhatsApp", "WHATSAPP_ENABLED", null),
      new MessagingSpec("Signal", "SIGNAL_HTTP_URL", "SIGNAL_HOME_CHANNEL"),
      new MessagingSpec("Slack", "SLACK_BOT_TOKEN", null),
      new MessagingSpec("Email", "EMAIL_ADDRESS", "EMAIL_HOME_ADDRESS"),
      new MessagingSpec("SMS", "TWILIO_ACCOUNT_SID", "SMS_HOME_CHANNEL"),
      new MessagingSpec("DingTalk", "DINGTALK_CLIENT_ID", null),
      new MessagingSpec("Feishu", "FEISHU_APP_ID", "FEISHU_HOME_CHANNEL"),
      new MessagingSpec("WeCom", "WECOM_BOT_ID", "WECOM_HOME_CHANNEL"),
      new MessagingSpec("WeCom Callback", "WECOM_CALLBACK_CORP_ID", null),
      new MessagingSpec("Weixin", "WEIXIN_ACCOUNT_ID", "WEIXIN_HOME_CHANNEL"),
      new MessagingSpec("BlueBubbles", "BLUEBUBBLES_SERVER_URL", "BLUEBUBBLES_HOME_CHANNEL"),
      new MessagingSpec("QQBot", "QQ_APP_ID", "QQ_HOME_CHANNEL"),
      new MessagingSpec("Yuanbao", "YUANBAO_APP_ID", "YUANBAO_HOME_CHANNEL"));

  private static final String SECTION_API_KEYS = "API Keys";
  private static final String SECTION_AUTH_PROVIDERS = "Auth Providers";
  private static final String SECTION_API_KEY_PROVIDERS = "API-Key Providers";
  private static final String SECTION_MESSAGING = "Messaging Platforms";

  private static final char CHECK = '✓';
  private static final char CROSS = '✗';
  private static final String SECTION_MARK = "◆";

  private static final Pattern ENV_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final Pattern RUN_HINT = Pattern.compile("run:\\s*([^)]+)");
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

  private final HermesProfiles profiles;

  public HermesSetup(HermesProfiles profiles) {
    this.profiles = profiles;
  }

  public AgentSetupDto setup(String url, String containerId, String name) {
    String envPath = profiles.profileDir(name) + "/.env";
    boolean envExists = profiles.fileExists(url, containerId, envPath);
    Map<String, String> env = parseEnv(profiles.readFile(url, containerId, envPath));
    StatusReport report = runStatus(url, containerId, name);

    List<ApiKeyStatusDto> apiKeys = new ArrayList<>();
    for (ApiKeySpec spec : API_KEYS) {
      String value = envValue(env, spec);
      if (value != null) {
        apiKeys.add(new ApiKeyStatusDto(spec.label(), spec.envVar(), true, mask(value)));
      } else {
        StatusRow row = report == null ? null : report.row(SECTION_API_KEYS, spec.label());
        apiKeys.add(new ApiKeyStatusDto(spec.label(), spec.envVar(), row != null && row.ok(), null));
      }
    }

    List<AuthProviderDto> authProviders = new ArrayList<>();
    List<ApiKeyProviderDto> apiKeyProviders = new ArrayList<>();
    if (report != null) {
      for (StatusRow row : report.rows(SECTION_AUTH_PROVIDERS)) {
        authProviders.add(new AuthProviderDto(row.label(), row.ok(), row.status(), hint(row.status())));
      }
      for (StatusRow row : report.rows(SECTION_API_KEY_PROVIDERS)) {
        apiKeyProviders.add(new ApiKeyProviderDto(row.label(), row.ok(), row.status()));
      }
    }

    List<MessagingStatusDto> messaging = new ArrayList<>();
    for (MessagingSpec spec : MESSAGING) {
      StatusRow row = report == null ? null : report.row(SECTION_MESSAGING, spec.label());
      boolean tokenSet = isSet(env.get(spec.tokenVar()));
      boolean ok = row != null ? row.ok() : tokenSet;
      String status = row != null ? row.status() : (tokenSet ? "configured" : "not configured");
      String homeChannel = spec.homeVar() == null ? null : blankToNull(env.get(spec.homeVar()));
      messaging.add(new MessagingStatusDto(spec.label(), ok, status, spec.tokenVar(), spec.homeVar(), homeChannel));
    }

    return new AgentSetupDto(envPath, envExists, apiKeys, authProviders, apiKeyProviders, messaging);
  }

  public AgentSetupDto putEnv(String url, String containerId, String name, List<EnvEntry> entries) {
    List<EnvEntry> toApply = entries == null ? List.of() : entries;
    for (EnvEntry entry : toApply) {
      if (entry == null || entry.key() == null || !ENV_KEY.matcher(entry.key()).matches()) {
        throw new IllegalArgumentException("invalid env key: " + (entry == null ? null : entry.key()));
      }
    }
    for (EnvEntry entry : toApply) {
      if (entry.value() == null || entry.value().isBlank()) {
        profiles.removeEnvVar(url, containerId, name, entry.key());
      } else {
        profiles.writeEnvVar(url, containerId, name, entry.key(), entry.value());
      }
    }
    return setup(url, containerId, name);
  }

  public AgentSetupDto initEnv(String url, String containerId, String name) {
    profiles.seedEnvIfMissing(url, containerId, name);
    return setup(url, containerId, name);
  }

  /** Commented-out .env template documenting every supported variable. */
  static String envTemplate() {
    StringBuilder sb = new StringBuilder();
    sb.append("# hermes profile environment\n");
    sb.append("# Uncomment a variable and fill in its value to enable it.\n");
    sb.append("# OAuth providers are not configured here — run 'hermes portal'\n");
    sb.append("# (auth) or 'hermes model' (model selection) from the web terminal.\n");
    sb.append("\n");
    sb.append("# ── model & tool API keys\n");
    for (ApiKeySpec spec : API_KEYS) {
      sb.append("# ").append(spec.envVar()).append("=  # ").append(spec.label());
      if (!spec.altVars().isEmpty()) {
        sb.append(" (alt: ").append(String.join(", ", spec.altVars())).append(")");
      }
      if (spec.optional()) {
        sb.append(" (optional)");
      }
      sb.append("\n");
    }
    sb.append("\n");
    sb.append("# ── messaging platforms\n");
    for (MessagingSpec spec : MESSAGING) {
      sb.append("# ").append(spec.tokenVar()).append("=  # ").append(spec.label()).append("\n");
      if (spec.homeVar() != null) {
        sb.append("# ").append(spec.homeVar()).append("=  # ").append(spec.label()).append(" home channel\n");
      }
    }
    return sb.toString();
  }

  /** Degrades to null when `hermes status` cannot run — callers then report
   *  from the .env alone. */
  private StatusReport runStatus(String url, String containerId, String name) {
    List<String> command = "default".equals(name)
        ? List.of("hermes", "status")
        : List.of("hermes", "-p", name, "status");
    try {
      return parseStatus(profiles.exec(url, containerId, command).stdout());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Sections headed by "◆ <name>"; rows are 2-space indented
   *  "<label>  <✓|✗> <status>"; deeper-indented detail lines are skipped. */
  private StatusReport parseStatus(String output) {
    Map<String, List<StatusRow>> sections = new LinkedHashMap<>();
    String section = null;
    for (String line : ANSI.matcher(output == null ? "" : output).replaceAll("").split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.startsWith(SECTION_MARK)) {
        section = trimmed.substring(1).trim();
        sections.putIfAbsent(section, new ArrayList<>());
        continue;
      }
      if (section == null || indentOf(line) > 2) continue;
      int mark = markIndex(trimmed);
      if (mark < 0) continue;
      String label = trimmed.substring(0, mark).trim();
      String status = trimmed.substring(mark + 1).trim();
      sections.get(section).add(new StatusRow(label, trimmed.charAt(mark) == CHECK, status));
    }
    return new StatusReport(sections);
  }

  private Map<String, String> parseEnv(String env) {
    Map<String, String> values = new HashMap<>();
    if (env == null || env.isBlank()) return values;
    for (String line : env.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      int eq = trimmed.indexOf('=');
      if (eq <= 0) continue;
      values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    return values;
  }

  private String envValue(Map<String, String> env, ApiKeySpec spec) {
    String value = env.get(spec.envVar());
    if (isSet(value)) return value;
    for (String alt : spec.altVars()) {
      value = env.get(alt);
      if (isSet(value)) return value;
    }
    return null;
  }

  private String mask(String value) {
    if (value.length() <= 4) return "..." + value;
    return "..." + value.substring(value.length() - 4);
  }

  private String hint(String status) {
    Matcher matcher = RUN_HINT.matcher(status);
    return matcher.find() ? matcher.group(1).trim() : null;
  }

  private boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private String blankToNull(String value) {
    return isSet(value) ? value : null;
  }

  private int indentOf(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') i++;
    return i;
  }

  private int markIndex(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == CHECK || c == CROSS) return i;
    }
    return -1;
  }

  record ApiKeySpec(String label, String envVar, List<String> altVars, boolean optional) {}

  record MessagingSpec(String label, String tokenVar, String homeVar) {}

  record StatusRow(String label, boolean ok, String status) {}

  private record StatusReport(Map<String, List<StatusRow>> sections) {
    List<StatusRow> rows(String section) {
      return sections.getOrDefault(section, List.of());
    }

    StatusRow row(String section, String label) {
      for (StatusRow row : rows(section)) {
        if (row.label().equals(label)) return row;
      }
      return null;
    }
  }
}
