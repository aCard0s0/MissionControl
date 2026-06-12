package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** One cached Docker client per daemon url (unix:// or tcp://). */
@Component
public class DockerClients {

  private final Map<String, DockerClient> cache = new ConcurrentHashMap<>();

  public DockerClient forUrl(String url) {
    return cache.computeIfAbsent(url, DockerClients::build);
  }

  private static DockerClient build(String url) {
    DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(url)
        .build();
    ZerodepDockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .connectionTimeout(Duration.ofSeconds(3))
        .responseTimeout(Duration.ofSeconds(20))
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }
}
