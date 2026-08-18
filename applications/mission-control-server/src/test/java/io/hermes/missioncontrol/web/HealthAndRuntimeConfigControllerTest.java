package io.hermes.missioncontrol.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.HostService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code /health} and {@code /config.js} — the two endpoints the container healthcheck and
 * the browser's first request depend on.
 *
 * <p>{@code config.js} interpolates configuration into a single-quoted JS literal, so its
 * escaping is a script-injection boundary, not cosmetics.
 */
class HealthAndRuntimeConfigControllerTest {

  private static final String SOCKET = "unix:///var/run/docker.sock";

  private MockMvc mvcFor(AppProperties props, HostService hosts) {
    return MockMvcBuilders
        .standaloneSetup(new HealthController(props, hosts), new RuntimeConfigController(props))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static AppProperties props(String dataMode, String apiBaseUrl, String dockerSocket) {
    return new AppProperties(dataMode, apiBaseUrl, dockerSocket, "hermes/agent:latest", "hermes", "0.1.0");
  }

  @Test
  void healthReportsTheVersionAndWhetherTheLocalDaemonAnswered() throws Exception {
    HostService hosts = mock(HostService.class);
    when(hosts.isLocalDaemonConnected()).thenReturn(true);

    mvcFor(props("live", "", SOCKET), hosts).perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.version").value("0.1.0"))
        .andExpect(jsonPath("$.dockerConnected").value(true));
  }

  @Test
  void healthStillAnswersOkWhenTheLocalDaemonIsDown() throws Exception {
    HostService hosts = mock(HostService.class);
    when(hosts.isLocalDaemonConnected()).thenReturn(false);

    // the daemon being down is data, not a server failure — a 500 here would take the
    // dashboard's own status indicator and the container healthcheck down with it
    mvcFor(props("live", "", SOCKET), hosts).perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.dockerConnected").value(false));
  }

  @Test
  void configJsIsServedAsJavascriptWithNoStoreAndCarriesEveryConfiguredValue() throws Exception {
    mvcFor(props("mock", "https://mc.example/api", SOCKET), mock(HostService.class))
        .perform(get("/config.js"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/javascript"))
        // the browser must not cache a config that changes with the deployment
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
        .andExpect(content().string(Matchers.containsString("dataMode: 'mock'")))
        .andExpect(content().string(Matchers.containsString("apiBaseUrl: 'https://mc.example/api'")))
        .andExpect(content().string(Matchers.containsString("dockerSocket: '" + SOCKET + "'")));
  }

  @Test
  void aSingleQuoteInAConfigValueCannotBreakOutOfTheScriptLiteral() throws Exception {
    String hostile = "');alert(1);//";

    mvcFor(props("live", hostile, SOCKET), mock(HostService.class))
        .perform(get("/config.js"))
        .andExpect(status().isOk())
        // the quote is escaped, so the literal continues rather than closing and handing
        // the rest of the value to the parser as code
        .andExpect(content().string(Matchers.containsString("apiBaseUrl: '\\');alert(1);//',")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("apiBaseUrl: '');"))));
  }

  @Test
  void aClosingScriptTagInAConfigValueIsNeutralised() throws Exception {
    // </script> inside an inline script ends the block regardless of JS quoting, so
    // escaping the slash is the only thing standing between a config value and an XSS
    mvcFor(props("live", "</script><script>alert(1)</script>", SOCKET), mock(HostService.class))
        .perform(get("/config.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.not(Matchers.containsString("</script>"))))
        .andExpect(content().string(Matchers.containsString("<\\/script>")));
  }

  @Test
  void aBackslashIsEscapedBeforeTheOtherReplacementsSoItCannotForgeAnEscape() {
    // ordering matters: escaping the quote first would leave \' as a literal backslash
    // followed by an unescaped quote
    assertEquals("\\\\", RuntimeConfigController.escape("\\"));
    assertEquals("\\\\\\'", RuntimeConfigController.escape("\\'"));
  }

  @Test
  void lineBreaksAreRemovedAndANullValueBecomesAnEmptyString() {
    // a newline would terminate the single-quoted literal and break the whole file
    String escaped = RuntimeConfigController.escape("a\nb\r\nc");
    assertFalse(escaped.contains("\n"));
    assertFalse(escaped.contains("\r"));
    assertEquals("abc", escaped);

    assertEquals("", RuntimeConfigController.escape(null));
  }
}
