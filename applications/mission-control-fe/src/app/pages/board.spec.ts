import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CdkDragDrop } from '@angular/cdk/drag-drop';
import { describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { BoardColumn, BoardTask } from '../core/models';
import { BoardPage } from './board';
import { el } from '../testing/dom';

const task = (id: string, column: BoardColumn, patch: Partial<BoardTask> = {}): BoardTask => ({
  id, containerId: 'c-1', agentId: 'a-1', title: `task ${id}`, column,
  createdAt: 1, updatedAt: 2, ...patch,
} as BoardTask);

const storeStub = (tasks: BoardTask[]) => ({
  containerTasks: signal(tasks),
  selectedContainer: signal({ id: 'c-1', name: 'hermes-prod' }),
  agentById: (id: string) => (id === 'a-1' ? { id, name: 'atlas' } : null),
  moveTask: vi.fn(),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(BoardPage);
  fixture.detectChanges();
  return { fixture, store };
};

/** The drop the CDK would deliver. Synthesizing a real drag in jsdom is not
 *  possible, so the handler is called with the shape it is given. */
const drop = (
  fixture: { componentInstance: unknown; detectChanges(): void }, moved: BoardTask, into: BoardColumn,
): void => {
  const page = fixture.componentInstance as { drop(ev: CdkDragDrop<BoardColumn>): void };
  page.drop({ item: { data: moved }, container: { data: into } } as CdkDragDrop<BoardColumn>);
  fixture.detectChanges();
};

describe('BoardPage', () => {
  it('gives every column a lane, even the empty ones', () => {
    const { fixture } = render(storeStub([task('t-1', 'running')]));

    const lanes = el(fixture).querySelectorAll('.col');
    expect(lanes.length).toBe(4);
    expect(Array.from(lanes).map(l => l.textContent?.match(/Queued|Running|Review|Done/)?.[0]))
      .toEqual(['Queued', 'Running', 'Review', 'Done']);
  });

  it('files each task under the column it is in', () => {
    const { fixture } = render(storeStub([
      task('t-1', 'queued'), task('t-2', 'done'), task('t-3', 'queued'),
    ]));

    const lanes = el(fixture).querySelectorAll('.col');
    expect(lanes[0].textContent).toContain('task t-1');
    expect(lanes[0].textContent).toContain('task t-3');
    expect(lanes[3].textContent).toContain('task t-2');
    expect(lanes[1].textContent).not.toContain('task t-');
  });

  it('names the agent a task belongs to, and marks an unknown one', () => {
    const { fixture } = render(storeStub([task('t-1', 'queued'), task('t-2', 'queued', { agentId: 'gone' })]));

    expect(el(fixture).textContent).toContain('atlas');
    expect(el(fixture).textContent).toContain('?');
  });

  it('moves a task dropped into another column', () => {
    const moved = task('t-1', 'queued');
    const { fixture, store } = render(storeStub([moved]));

    drop(fixture, moved, 'running');

    expect(store.moveTask).toHaveBeenCalledWith('t-1', 'running');
  });

  it('leaves a task dropped back into its own column alone', () => {
    const moved = task('t-1', 'queued');
    const { fixture, store } = render(storeStub([moved]));

    drop(fixture, moved, 'queued');

    expect(store.moveTask).not.toHaveBeenCalled();
  });
});
