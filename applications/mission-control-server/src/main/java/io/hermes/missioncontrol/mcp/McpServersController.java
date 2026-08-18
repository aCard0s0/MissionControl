package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.LogLineDto;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp-servers")
public class McpServersController {

  private final McpRegistryService registry;
  private final List<McpServerDeletionListener> deletionListeners;

  public McpServersController(
      McpRegistryService registry, List<McpServerDeletionListener> deletionListeners) {
    this.registry = registry;
    this.deletionListeners = deletionListeners;
  }

  @GetMapping
  public List<McpServerDto> list() {
    return registry.list();
  }

  @PostMapping
  public ResponseEntity<McpServerDto> create(@RequestBody McpServerRequest request) {
    McpServerDto created = registry.create(request);
    URI location = URI.create("/api/mcp-servers/" + created.id());
    return "managed".equals(created.kind())
        ? ResponseEntity.accepted().location(location).body(created)
        : ResponseEntity.created(location).body(created);
  }

  @PutMapping("/{id}")
  public ResponseEntity<McpServerDto> update(
      @PathVariable String id, @RequestBody McpServerRequest request) {
    McpServerDto updated = registry.update(id, request);
    return "idle".equals(updated.operationState())
        ? ResponseEntity.ok(updated)
        : ResponseEntity.accepted().body(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<McpServerDto> delete(@PathVariable String id) {
    // Unlinking is destructive and irreversible — it disables the server on every Agent
    // holding it and drops the link rows. Ask the registry whether the deletion can go
    // ahead at all first, so a refused DELETE leaves the caller's Agents untouched.
    registry.assertDeletable(id);
    for (McpServerDeletionListener listener : deletionListeners) {
      listener.beforeServerDeleted(id);
    }
    McpServerDto result = registry.delete(id);
    return "managed".equals(result.kind()) ? ResponseEntity.accepted().body(result) : ResponseEntity.ok(result);
  }

  @PostMapping("/{id}/start")
  public ResponseEntity<McpServerDto> start(@PathVariable String id) {
    return ResponseEntity.accepted().body(registry.start(id));
  }

  @PostMapping("/{id}/stop")
  public ResponseEntity<McpServerDto> stop(@PathVariable String id) {
    return ResponseEntity.accepted().body(registry.stop(id));
  }

  @PostMapping("/{id}/apply")
  public ResponseEntity<McpServerDto> apply(@PathVariable String id) {
    return ResponseEntity.accepted().body(registry.apply(id));
  }

  @PostMapping("/{id}/check")
  public McpServerDto check(@PathVariable String id) {
    return registry.check(id);
  }

  @GetMapping("/{id}/logs")
  public List<LogLineDto> logs(
      @PathVariable String id,
      @RequestParam(defaultValue = "200") int tail) {
    return registry.logs(id, tail);
  }

  @GetMapping("/retained-resources")
  public List<RetainedResourceDto> retainedResources() {
    return registry.retainedResources();
  }

  @GetMapping("/retained-resources/{id}")
  public RetainedResourceDto retainedResource(@PathVariable String id) {
    return registry.retainedResource(id);
  }

  @DeleteMapping("/retained-resources/{id}")
  public ResponseEntity<Void> purgeRetainedResource(@PathVariable String id) {
    registry.purgeRetainedResource(id);
    return ResponseEntity.noContent().build();
  }
}
