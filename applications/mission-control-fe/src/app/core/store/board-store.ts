import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { BoardColumn, BoardTask } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

/** The kanban board. Moves are optimistic and roll back if the backend says no. */
@Injectable({ providedIn: 'root' })
export class BoardStore {
  readonly tasks: WritableSignal<BoardTask[]>;

  readonly forSelectedContainer = computed(() =>
    this.tasks().filter(t => t.containerId === this.containers.selectedContainerId()));

  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);

  constructor() {
    this.tasks = signal([]);
  }

  async refresh(): Promise<void> {
    try {
      const tasks = await this.ctx.api.board.tasks();
      this.tasks.set(tasks.map(t => ({ ...t, agentId: t.agentId ?? '', tags: t.tags ?? [] })));
    } catch { /* board is non-critical */ }
  }

  move(id: string, column: BoardColumn): void {
    const before = this.tasks().find(t => t.id === id)?.column;
    if (before === undefined || before === column) return;
    this.tasks.update(ts => ts.map(t => t.id === id ? { ...t, column } : t));
    this.ctx.api.board.moveTask(id, column).catch(e => {
      // roll back only this card. Restoring a whole-board snapshot also reverted any move
      // that landed while this one was in flight — and the board is loaded once, not
      // polled, so that divergence stood until a reload.
      this.tasks.update(ts => ts.map(t => t.id === id ? { ...t, column: before } : t));
      this.ctx.toastFailure('move', e);
    });
  }

  dropByContainer(containerId: string): void {
    this.tasks.update(ts => ts.filter(t => t.containerId !== containerId));
  }

  dropByAgent(agentId: string): void {
    this.tasks.update(ts => ts.filter(t => t.agentId !== agentId));
  }

  reassignContainer(fromId: string, toId: string): void {
    this.tasks.update(ts => ts.map(t => t.containerId === fromId ? { ...t, containerId: toId } : t));
  }
}
