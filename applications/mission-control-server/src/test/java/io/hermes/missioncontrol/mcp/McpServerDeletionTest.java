package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The ordering between "may this be deleted at all", "release every reference to it" and
 * "drop the row" — a destructive step and the check that is allowed to refuse it.
 *
 * <p>This used to be asserted through MockMvc against {@code McpServersController}, because
 * the protocol was three statements in that controller's handler.
 */
class McpServerDeletionTest {

  private McpRegistryService registry;
  private McpServerDeletionListener listener;
  private McpServerDeletion deletion;

  @BeforeEach
  void setUp() {
    registry = mock(McpRegistryService.class);
    listener = mock(McpServerDeletionListener.class);
    deletion = new McpServerDeletion(registry, List.of(listener));
  }

  @Test
  void aRefusedDeletionReleasesNothingAndDropsNoRow() {
    // a server mid-operation cannot be deleted
    doThrow(new ResourceConflictException("an MCP server operation is already in progress"))
        .when(registry).assertDeletable("mcp-1");

    assertEquals("an MCP server operation is already in progress",
        assertThrows(ResourceConflictException.class, () -> deletion.delete("mcp-1")).getMessage());

    // releasing a reference rewrites config.yaml on every agent holding this server and drops
    // the link rows, and nothing puts them back. Running it before the refusal is ruled out
    // means a rejected request still destroyed the caller's setup.
    verifyNoInteractions(listener);
    verify(registry, never()).delete(anyString());
  }

  @Test
  void referencesAreReleasedOnlyAfterTheRegistryHasAuthorisedTheDeletion() {
    when(registry.delete("mcp-1")).thenReturn(mock(McpServerDto.class));

    deletion.delete("mcp-1");

    InOrder order = inOrder(registry, listener);
    order.verify(registry).assertDeletable("mcp-1");
    order.verify(listener).beforeServerDeleted("mcp-1");
    order.verify(registry).delete("mcp-1");
  }

  @Test
  void aListenerThatCannotFinishAbortsTheDeletionRatherThanDroppingTheRow() {
    // the listener contract says a throw aborts, leaving what it already processed retryable
    doThrow(new ResourceConflictException("could not disable MCP entry tools"))
        .when(listener).beforeServerDeleted("mcp-1");

    assertThrows(ResourceConflictException.class, () -> deletion.delete("mcp-1"));

    verify(registry, never()).delete(anyString());
  }
}
