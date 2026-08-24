import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { HostStore } from '../core/store/host-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { DockerHost } from '../core/models';
import { McpEditorDraft, newMcpDraft } from '../core/mcp/catalog-draft';
import { McpServerEditor } from './mcp-server-editor';
import { el, press } from '../testing/dom';

const managedDraft = (patch: Partial<McpEditorDraft> = {}): McpEditorDraft => ({
  ...newMcpDraft('managed', 'dh-local'), name: 'browser', image: 'mcp/playwright:latest', ...patch,
});

const hosts: DockerHost[] = [{
  id: 'dh-local', name: 'localhost', url: 'unix:///var/run/docker.sock', kind: 'local',
  status: 'connected', engine: null, apiVersion: null, latencyMs: null, note: null,
}];

/** Only what the editor reaches for on the store, so nothing here touches a backend. */
const storeStub = () => ({
  catalog: {
    servers: signal([]),
    save: vi.fn().mockResolvedValue('mcp-1'),
  },
  hosts: {
    hosts: signal(hosts),
  },
});

@Component({
  imports: [McpServerEditor],
  template: `<mc-mcp-server-editor [draft]="draft()" (saved)="savedId = $event" (closed)="closes = closes + 1" />`,
})
class Host {
  readonly draft = signal<McpEditorDraft>(managedDraft());
  savedId: string | null = null;
  closes = 0;
}

const render = (draft: McpEditorDraft, store = storeStub()) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HostStore, useValue: store.hosts }, { provide: McpCatalogStore, useValue: store.catalog }] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.draft.set(draft);
  fixture.detectChanges();
  return { fixture, store, host: fixture.componentInstance };
};

const primary = (fixture: { nativeElement: unknown }): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.primary')!;

describe('McpServerEditor rows', () => {
  it('appends a blank environment row and takes it away again', () => {
    const { fixture, host } = render(managedDraft());

    press(fixture, '+ variable');
    expect(host.draft().environment).toEqual([
      { key: '', value: '', secret: false, set: false, recoverable: true },
    ]);
    expect(el(fixture).querySelectorAll('.kv-row').length).toBe(1);

    press(fixture, 'remove');
    expect(host.draft().environment).toEqual([]);
    expect(el(fixture).querySelectorAll('.kv-row').length).toBe(0);
  });

  it('edits headers and named volumes through the same row helpers', () => {
    const { fixture, host } = render(managedDraft());

    press(fixture, '+ header');
    press(fixture, '+ volume');

    expect(host.draft().headers.length).toBe(1);
    expect(host.draft().volumes).toEqual([{ name: '', target: '' }]);
  });

  it('appends rows to a support service, whose lists the wire leaves optional', () => {
    const { fixture, host } = render(managedDraft());

    press(fixture, '+ dependency');
    expect(host.draft().supportServices.length).toBe(1);

    press(fixture, '+ variable', '.support-editor');
    press(fixture, '+ volume', '.support-editor');

    const service = host.draft().supportServices[0];
    expect(service.environment.length).toBe(1);
    expect(service.volumes).toEqual([{ name: '', target: '' }]);

    press(fixture, 'remove service', '.support-editor');
    expect(host.draft().supportServices).toEqual([]);
  });

  it('toggles a health check on and off for the server and for a dependency', () => {
    const { fixture, host } = render(managedDraft());

    press(fixture, '+ health check');
    expect(host.draft().healthcheck).toMatchObject({ test: ['CMD'], retries: 3 });

    press(fixture, 'remove health check');
    expect(host.draft().healthcheck).toBeNull();

    press(fixture, '+ dependency');
    press(fixture, '+ health check', '.support-editor');
    expect(host.draft().supportServices[0].healthcheck).toMatchObject({ test: ['CMD'] });
  });
});

describe('McpServerEditor save', () => {
  it('sends the request the draft maps to, and answers with the new id', async () => {
    const { fixture, store, host } = render(managedDraft({ description: ' browser stack ' }));

    press(fixture, 'add server');
    await fixture.whenStable();

    expect(store.catalog.save).toHaveBeenCalledTimes(1);
    expect(store.catalog.save.mock.calls[0][0]).toMatchObject({
      name: 'browser', description: 'browser stack', kind: 'managed',
      hostId: 'dh-local', image: 'mcp/playwright:latest', internalPort: 1100, path: '/mcp',
    });
    // a new entry carries no id for the store to update
    expect(store.catalog.save.mock.calls[0][1]).toBeUndefined();
    expect(host.savedId).toBe('mcp-1');
  });

  it('updates in place under the id the draft was loaded with', async () => {
    const { fixture, store } = render(managedDraft({ id: 'mcp-7' }));

    press(fixture, 'save changes');
    await fixture.whenStable();

    expect(store.catalog.save.mock.calls[0][1]).toBe('mcp-7');
  });

  it('stays open when the store refuses the save', async () => {
    const store = storeStub();
    store.catalog.save.mockResolvedValue('');
    const { fixture, host } = render(managedDraft(), store);

    press(fixture, 'add server');
    await fixture.whenStable();

    expect(host.savedId).toBeNull();
    expect(host.closes).toBe(0);
  });

  it('will not send a second save while the first is still in flight', async () => {
    const store = storeStub();
    store.catalog.save.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = render(managedDraft(), store);

    press(fixture, 'add server');
    expect(primary(fixture).textContent?.trim()).toBe('saving…');

    primary(fixture).click();
    expect(store.catalog.save).toHaveBeenCalledTimes(1);
  });

  it('refuses a draft the backend would reject anyway', () => {
    const { fixture } = render(managedDraft({ image: '  ' }));

    expect(primary(fixture).disabled).toBe(true);
  });

  it('refuses a name another catalog entry already answers to', () => {
    const store = storeStub();
    store.catalog.servers.set([{ id: 'other', name: 'BROWSER' }] as never);
    const { fixture } = render(managedDraft(), store);

    expect(primary(fixture).disabled).toBe(true);
  });

  it('reports a cancel to the page rather than closing itself', () => {
    const { fixture, store, host } = render(managedDraft());

    press(fixture, 'cancel');

    expect(host.closes).toBe(1);
    expect(host.savedId).toBeNull();
    expect(store.catalog.save).not.toHaveBeenCalled();
  });
});
