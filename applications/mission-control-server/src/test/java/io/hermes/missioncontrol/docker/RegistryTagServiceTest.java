package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegistryTagServiceTest {

  private final ObjectMapper json = new ObjectMapper();

  /** Shape of a real hub.docker.com tag page, trimmed to the fields that are read. */
  private static final String PAGE = """
      {
        "count": 21,
        "next": "https://hub.docker.com/v2/repositories/nousresearch/hermes-agent/tags?page=2",
        "results": [
          {"name": "latest", "tag_status": "active", "full_size": 1234,
           "last_updated": "2026-08-13T10:25:55.603362Z", "digest": "sha256:aaa"},
          {"name": "v2026.8.3", "tag_status": "active", "full_size": 5678,
           "last_updated": "2026-08-03T17:10:04.321211Z", "digest": "sha256:bbb"},
          {"name": "v2026.1.1", "tag_status": "inactive",
           "last_updated": "2026-01-01T00:00:00Z", "digest": "sha256:ccc"}
        ]
      }
      """;

  @Test
  void readsNameSizeDigestAndLastUpdated() throws Exception {
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(json.readTree(PAGE));

    assertEquals(2, tags.size());
    ImageTagDto release = tags.get(1);
    assertEquals("v2026.8.3", release.tag());
    assertTrue(release.remote());
    assertEquals(false, release.pulled());   // pulled-ness comes from the daemon, not the registry
    assertEquals(5678L, release.sizeBytes());
    assertEquals("sha256:bbb", release.digest());
    assertEquals(
        OffsetDateTime.parse("2026-08-03T17:10:04.321211Z").toInstant().toEpochMilli(),
        release.lastUpdated());
  }

  @Test
  void dropsInactiveTagsBecauseTheyCannotBePulled() throws Exception {
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(json.readTree(PAGE));
    assertTrue(tags.stream().noneMatch(t -> "v2026.1.1".equals(t.tag())));
  }

  @Test
  void toleratesAMissingOrEmptyResultsArray() throws Exception {
    assertEquals(List.of(), RegistryTagService.parseHubPage(json.readTree("{}")));
    assertEquals(List.of(), RegistryTagService.parseHubPage(json.readTree("{\"results\": []}")));
    assertEquals(List.of(), RegistryTagService.parseHubPage(null));
  }

  @Test
  void followsPaginationOnlyWithinDockerHub() throws Exception {
    assertEquals(
        "https://hub.docker.com/v2/repositories/nousresearch/hermes-agent/tags?page=2",
        RegistryTagService.nextHubPage(json.readTree(PAGE)));
  }

  @Test
  void refusesAPageLinkPointingAtAnotherHost() throws Exception {
    // a 'next' is followed without further checks, so it must not be able to
    // redirect the backend at an arbitrary host
    assertNull(RegistryTagService.nextHubPage(
        json.readTree("{\"next\": \"https://evil.example/v2/repositories/x/tags\"}")));
    assertNull(RegistryTagService.nextHubPage(json.readTree("{\"next\": null}")));
    assertNull(RegistryTagService.nextHubPage(json.readTree("{}")));
  }

  @Test
  void reportsUnsupportedForRepositoriesOutsideDockerHub() {
    RegistryTagService.RemoteTags result =
        new RegistryTagService(json, true).tags("ghcr.io/nousresearch/hermes-agent");

    assertEquals(RegistryTagService.UNSUPPORTED, result.status());
    assertEquals(List.of(), result.tags());
    assertNull(result.checkedAt());
  }
}
