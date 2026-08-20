import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { ReferencePage } from './reference';
import { el, press } from '../testing/dom';

const agents = [
  { id: 'a-1', name: 'atlas', containerId: 'c-1' },
  { id: 'a-2', name: 'scribe', containerId: 'c-2' },
];

const containers = [
  { id: 'c-1', name: 'hermes-prod', hostId: 'dh-local' },
  { id: 'c-2', name: 'hermes-lab', hostId: 'dh-lab' },
];

/** Only what the page reaches for on the store. */
const storeStub = (profiles = agents, selected: unknown = containers[0]) => ({
  agents: { forSelectedContainer: signal(profiles) },
  containers: { containers: signal(containers), selected: signal(selected) },
  terminal: { open: vi.fn() },
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: AgentStore, useValue: store.agents },
      { provide: ContainerStore, useValue: store.containers },
      { provide: TerminalRequestStore, useValue: store.terminal },
    ],
  });
  const fixture = TestBed.createComponent(ReferencePage);
  fixture.detectChanges();
  return { fixture, store };
};

/** The insert action on the row for this command line. */
const insert = (fixture: ReturnType<typeof render>['fixture'], line: string): void => {
  const row = Array.from(el(fixture).querySelectorAll<HTMLElement>('.cmd'))
    .find(r => (r.querySelector('.line')?.textContent ?? '').trim() === line);
  if (!row) throw new Error(`no row for "${line}"`);
  const action = Array.from(row.querySelectorAll<HTMLButtonElement>('button.act'))
    .find(b => (b.textContent ?? '').trim() === 'insert');
  if (!action) throw new Error(`no insert action on "${line}"`);
  action.click();
  fixture.detectChanges();
};

describe('ReferencePage', () => {
  it('lists the commands unscoped until a profile is picked', () => {
    const { fixture } = render(storeStub());

    expect(el(fixture).textContent).toContain('hermes cron');
    expect(el(fixture).textContent).not.toContain('hermes -p atlas');
  });

  it('scopes every line to the profile picked, so nothing silently reads `default`', () => {
    const { fixture } = render(storeStub());

    press(fixture, '@atlas');

    expect(el(fixture).textContent).toContain('hermes -p atlas cron');
  });

  it('goes back to unscoped lines', () => {
    const { fixture } = render(storeStub());
    press(fixture, '@atlas');

    press(fixture, 'no profile');

    expect(el(fixture).textContent).not.toContain('hermes -p atlas');
  });

  it('says so when the selected container has no profiles to scope by', () => {
    const { fixture } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('no profiles on hermes-prod');
  });

  it('sends the line to the terminal to type, never to run', () => {
    const { fixture, store } = render(storeStub());

    insert(fixture, 'hermes status');

    expect(store.terminal.open).toHaveBeenCalledWith({
      hostId: 'dh-local', containerId: 'c-1', label: 'hermes-prod', insert: 'hermes status',
    });
    // `command` would run it; nothing here may
    expect(store.terminal.open.mock.calls[0][0]).not.toHaveProperty('command');
  });

  it('targets the scoped profile’s own container, not whichever one is selected', () => {
    const { fixture, store } = render(storeStub());

    press(fixture, '@scribe');
    insert(fixture, 'hermes -p scribe doctor');

    expect(store.terminal.open).toHaveBeenCalledWith({
      hostId: 'dh-lab', containerId: 'c-2', label: 'hermes-lab',
      insert: 'hermes -p scribe doctor',
    });
  });

  it('still sends the line with no container to aim it at', () => {
    const { fixture, store } = render(storeStub([], null));

    insert(fixture, 'hermes status');

    expect(store.terminal.open).toHaveBeenCalledWith({ insert: 'hermes status' });
  });
});
