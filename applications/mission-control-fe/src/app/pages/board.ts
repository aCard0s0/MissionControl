import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { CdkDrag, CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { HermesStore } from '../core/hermes-store';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { BoardColumn, BoardTask } from '../core/models';

const COLUMNS: { key: BoardColumn; label: string }[] = [
  { key: 'queued', label: 'Queued' },
  { key: 'running', label: 'Running' },
  { key: 'review', label: 'Review' },
  { key: 'done', label: 'Done' },
];

@Component({
  selector: 'mc-board',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDropList, CdkDrag, Reveal],
  templateUrl: './board.html',
  styleUrl: './board.scss',
})
export class BoardPage {
  protected readonly store = inject(HermesStore);

  protected readonly columns = COLUMNS;
  protected readonly ago = ago;

  protected readonly byColumn = computed(() => {
    const m: Record<BoardColumn, BoardTask[]> = { queued: [], running: [], review: [], done: [] };
    for (const t of this.store.containerTasks()) m[t.column].push(t);
    return m;
  });

  protected readonly listIds = COLUMNS.map(c => 'col-' + c.key);

  protected agentName(id: string): string {
    return this.store.agentById(id)?.name ?? '?';
  }

  protected drop(ev: CdkDragDrop<BoardColumn>): void {
    const task = ev.item.data as BoardTask;
    const target = ev.container.data;
    if (task.column !== target) this.store.moveTask(task.id, target);
  }
}
