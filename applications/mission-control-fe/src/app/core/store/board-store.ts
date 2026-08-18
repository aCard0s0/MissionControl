import { WritableSignal, computed, signal } from '@angular/core';
import { BoardColumn, BoardTask } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

/** The kanban board. Moves are optimistic and roll back if the backend says no —
 *  in mock data mode that backend is {@link MockHttp}, so this has one path. */
export class BoardStore {
  readonly tasks: WritableSignal<BoardTask[]>;

  readonly forSelectedContainer = computed(() =>
    this.tasks().filter(t => t.containerId === this.containers.selectedContainerId()));

  constructor(private readonly ctx: StoreContext, private readonly containers: ContainerStore) {
    this.tasks = signal([]);
  }

  async refresh(): Promise<void> {
    try {
      const tasks = await this.ctx.api.board.tasks();
      this.tasks.set(tasks.map(t => ({ ...t, agentId: t.agentId ?? '', tags: t.tags ?? [] })));
    } catch { /* board is non-critical */ }
  }

  move(id: string, column: BoardColumn): void {
    const before = this.tasks();
    this.tasks.update(ts => ts.map(t => t.id === id ? { ...t, column } : t));
    this.ctx.api.board.moveTask(id, column).catch(e => {
      this.tasks.set(before);   // optimistic move failed — roll back
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
