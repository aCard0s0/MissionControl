package io.hermes.missioncontrol.support;

import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;

/**
 * The {@code hostId} → {@link DockerHostRef} converter, for a standalone MockMvc.
 *
 * <p>Production registers it in {@code web/WebConfig.addFormatters}, so a handler declaring
 * {@code @PathVariable("hostId") DockerHostRef host} is handed a resolved, probed host. A
 * {@code standaloneSetup} builds its own conversion service and would not have it: the request
 * fails to bind and every such route answers 400, whatever the test was about.
 *
 * <p>Takes the same {@link HostService} mock the test already stubs, so
 * {@code requireConnected} answering or throwing still decides what the route does.
 */
public final class HostPathBinding {

  private HostPathBinding() {}

  public static FormattingConversionService conversionService(HostService hosts) {
    DefaultFormattingConversionService conversion = new DefaultFormattingConversionService();
    conversion.addConverter(String.class, DockerHostRef.class, hosts::requireConnected);
    return conversion;
  }
}
