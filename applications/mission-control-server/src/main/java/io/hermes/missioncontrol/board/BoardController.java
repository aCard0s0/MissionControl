package io.hermes.missioncontrol.board;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/board/tasks")
public class BoardController {

  public record CreateTaskRequest(
      @NotBlank String containerId,
      String agentId,
      @NotBlank String title,
      @Pattern(regexp = "queued|running|review|done") String column,
      @Pattern(regexp = "low|med|high") String priority,
      List<String> tags) {
  }

  public record MoveTaskRequest(@NotBlank @Pattern(regexp = "queued|running|review|done") String column) {}

  private final BoardRepository repository;

  public BoardController(BoardRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public List<BoardTask> list(@RequestParam(required = false) String containerId) {
    return containerId == null ? repository.findAll() : repository.findByContainer(containerId);
  }

  @PostMapping
  public BoardTask create(@Valid @RequestBody CreateTaskRequest request) {
    BoardTask task = new BoardTask(
        "t-" + UUID.randomUUID().toString().substring(0, 8),
        request.containerId(),
        request.agentId(),
        request.title(),
        request.column() == null ? "queued" : request.column(),
        request.priority() == null ? "med" : request.priority(),
        request.tags() == null ? List.of() : request.tags(),
        System.currentTimeMillis());
    repository.insert(task);
    return task;
  }

  @PatchMapping("/{id}")
  public void move(@PathVariable String id, @Valid @RequestBody MoveTaskRequest request) {
    if (repository.updateColumn(id, request.column()) == 0) {
      throw new NoSuchElementException("unknown task: " + id);
    }
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }
}
