package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.docker.ContainerIdListener;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * That the probe cache is actually wired into the container-replacement fan-out.
 *
 * <p>{@code ContainerUpdateService} takes its listeners as an injected {@code List}, and
 * {@code ContainerUpdateServiceTest} hands it a hand-built one. So no test notices if Spring
 * stops handing the cache to it — and unlike a missing bean, an unregistered listener does
 * not fail startup. It just quietly never evicts, leaving the TTL to do all the work while
 * the hook that was written for this reads as though it runs.
 *
 * <p>The context is the one {@code ApplicationContextTest} boots; this only asks it a
 * different question.
 */
@SpringBootTest
@ActiveProfiles("test")
class HermesProfileMcpWiringTest {

  @Autowired
  private List<ContainerIdListener> listeners;

  @Test
  void theProbeCacheIsRegisteredAsAContainerIdListener() {
    assertTrue(listeners.stream().anyMatch(HermesProfileMcp.class::isInstance),
        "a replaced container evicts no probes unless the cache is in the listener list");
  }
}
