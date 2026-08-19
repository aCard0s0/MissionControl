import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { DockerHost, McpCatalogServer, McpRetainedResource } from '../core/models';
import { McpServersPage } from './mcp-servers';
import { button, el, press, settle, text, type } from '../testing/dom';
import { catalogServer as server, dockerHost } from '../testing/models';

const hosts: DockerHost[] = [
  dockerHost('dh-local', { name: 'localhost', url: 'unix:///var/run/docker.sock', kind: 'local' }),
  dockerHost('dh-edge', { name: 'edge', status: 'error', note: 'unreachable' }),
];

const volume: McpRetainedResource = {
  id: 'vol-1', serverId: 'browser', serverName: 'browser', hostId: 'dh-local',
  type: 'volume', name: 'browser-data', createdAt: 1,
};

/** Only what the page and the two modals reach for, so nothing here touches a backend. */
const storeStub = (servers: McpCatalogServer[], retained: McpRetainedResource[] = []) => ({
  mcpServers: signal(servers),
  mcpServersLoading: signal(false),
  retainedMcpResources: signal(retained),
  dockerHosts: signal(hosts),
  refreshMcpServers: vi.fn().mockResolvedValue(undefined),
  refreshRetainedMcpResources: vi.fn().mockResolvedValue(undefined),
  startCatalogMcpServer: vi.fn().mockResolvedValue(true),
  stopCatalogMcpServer: vi.fn().mockResolvedValue(true),
  applyCatalogMcpServer: vi.fn().mockResolvedValue(true),
  checkCatalogMcpServer: vi.fn().mockResolvedValue(true),
  deleteCatalogMcpServer: vi.fn().mockResolvedValue(true),
  purgeRetainedMcpResource: vi.fn().mockResolvedValue(true),
  saveCatalogMcpServer: vi.fn().mockResolvedValue('mcp-new'),
  mcpServerLogTail: vi.fn().mockResolvedValue([]),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(McpServersPage);
  fixture.detectChanges();
  return { fixture, store };
};

describe('McpServersPage roster', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the catalog and what a delete left behind when it opens', () => {
    const { store } = render(storeStub([server('browser')], [volume]));

    expect(store.refreshMcpServers).toHaveBeenCalled();
    expect(store.refreshRetainedMcpResources).toHaveBeenCalled();
  });

  it('groups the catalog by host and shows each server address', () => {
    const { fixture } = render(storeStub([
      server('browser'),
      server('gateway', { hostId: 'dh-edge' }),
      server('files', { kind: 'stdio', hostId: null, stdioCommand: 'npx', args: ['-y', '@acme/fs'] }),
    ]));

    const groups = el(fixture).querySelectorAll('.server-group');
    expect(groups.length).toBe(3);
    expect(groups[0].textContent).toContain('localhost');
    expect(groups[1].textContent).toContain('edge');
    expect(el(fixture).textContent).toContain('http://browser:1100/mcp');
    expect(el(fixture).textContent).toContain('npx -y @acme/fs');
  });

  it('says the registry is empty rather than showing bare sections', () => {
    const { fixture } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('No MCP servers registered.');
    expect(el(fixture).querySelectorAll('.server-group').length).toBe(0);
  });

  it('offers start for a stopped stack and stop for a running one', () => {
    const { fixture, store } = render(storeStub([server('browser', { runtimeState: 'running' })]));

    press(fixture, 'stop', '.server-actions');
    expect(store.stopCatalogMcpServer).toHaveBeenCalledWith('browser');
  });

  it('keeps the lifecycle controls busy while the backend is mid-operation', () => {
    const { fixture } = render(storeStub([server('browser', { operationState: 'starting' })]));

    expect(el(fixture).textContent).toContain('starting…');
    const start = el(fixture).querySelector<HTMLButtonElement>('.server-actions .btn.primary')!;
    expect(start.disabled).toBe(true);
  });
});

describe('McpServersPage editor handoff', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('hands a new draft of the chosen kind to the editor', () => {
    const { fixture } = render(storeStub([]));

    press(fixture, '+ stdio');

    expect(el(fixture).querySelector('mc-mcp-server-editor')).not.toBeNull();
    expect(el(fixture).textContent).toContain('STDIO COMMAND');
    expect(el(fixture).textContent).toContain('ADD MCP SERVER');
  });

  it('hands the stored server to the editor, and a copy of it when duplicating', async () => {
    const { fixture } = render(storeStub([server('browser')]));

    press(fixture, 'edit', '.server-actions');
    await settle(fixture);
    expect(el(fixture).textContent).toContain('EDIT MCP SERVER');
    expect(el(fixture).querySelector<HTMLInputElement>('.editor-modal .input')!.value).toBe('browser');

    press(fixture, 'cancel', '.editor-modal');
    press(fixture, 'duplicate', '.server-actions');
    await settle(fixture);
    expect(el(fixture).textContent).toContain('ADD MCP SERVER');
    expect(el(fixture).querySelector<HTMLInputElement>('.editor-modal .input')!.value)
      .toBe('browser copy');
  });

  it('closes the editor once it reports a save', async () => {
    const { fixture } = render(storeStub([server('browser')]));

    press(fixture, 'edit', '.server-actions');
    press(fixture, 'save changes', '.editor-modal');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-mcp-server-editor')).toBeNull();
  });
});

describe('McpServersPage log handoff', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('hands the chosen server to the log viewer, and takes it back on close', async () => {
    const { fixture, store } = render(storeStub([server('browser', { runtimeState: 'running' })]));

    press(fixture, 'logs', '.server-actions');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-mcp-server-logs')).not.toBeNull();
    expect(store.mcpServerLogTail).toHaveBeenCalledWith('browser', 150);

    press(fixture, 'close', '.log-modal');
    expect(el(fixture).querySelector('mc-mcp-server-logs')).toBeNull();
  });
});

describe('McpServersPage destructive confirmations', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('refuses to remove a server until its name is typed exactly', async () => {
    const { fixture, store } = render(storeStub([server('browser')]));

    press(fixture, 'remove', '.server-actions');
    const confirm = () => el(fixture).querySelector<HTMLButtonElement>('.btn.danger:not(.ghost)')!;
    expect(confirm().disabled).toBe(true);

    await type(fixture, '.modal .input', 'brows');
    expect(confirm().disabled).toBe(true);

    await type(fixture, '.modal .input', 'browser');
    expect(confirm().disabled).toBe(false);
    confirm().click();
    await settle(fixture);

    expect(store.deleteCatalogMcpServer).toHaveBeenCalledWith('browser');
    expect(el(fixture).querySelector('.crit-h')).toBeNull();
  });

  it('stops tailing a server that has just been removed', async () => {
    const { fixture } = render(storeStub([server('browser', { runtimeState: 'running' })]));

    press(fixture, 'logs', '.server-actions');
    await settle(fixture);
    expect(el(fixture).querySelector('mc-mcp-server-logs')).not.toBeNull();

    press(fixture, 'remove', '.server-actions');
    await type(fixture, '.modal .input', 'browser');
    el(fixture).querySelector<HTMLButtonElement>('.btn.danger:not(.ghost)')!.click();
    await settle(fixture);

    expect(el(fixture).querySelector('mc-mcp-server-logs')).toBeNull();
  });

  it('refuses to purge retained data until its volume name is typed exactly', async () => {
    const { fixture, store } = render(storeStub([], [volume]));

    press(fixture, 'purge', '.resource-row');
    const confirm = () => el(fixture).querySelector<HTMLButtonElement>('.btn.danger:not(.ghost)')!;
    expect(confirm().disabled).toBe(true);

    await type(fixture, '.modal .input', 'browser-data');
    confirm().click();
    await settle(fixture);

    expect(store.purgeRetainedMcpResource).toHaveBeenCalledWith('vol-1');
  });
});

describe('McpServersPage lifecycle verbs', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('restarts a managed server whose definition has moved on', async () => {
    const { fixture, store } = render(storeStub([
      server('browser', { runtimeState: 'running', pendingChanges: true, appliedRevision: 0 }),
    ]));

    expect(text(fixture)).toContain('apply required');
    press(fixture, 'apply & restart', '.server-actions');
    await settle(fixture);

    expect(store.applyCatalogMcpServer).toHaveBeenCalledWith('browser');
  });

  it('probes a server on demand', async () => {
    const { fixture, store } = render(storeStub([server('browser', { runtimeState: 'running' })]));

    press(fixture, 'check', '.server-actions');
    await settle(fixture);

    expect(store.checkCatalogMcpServer).toHaveBeenCalledWith('browser');
  });

  it('will not start the same server twice while the first start is in flight', async () => {
    const store = storeStub([server('browser')]);
    store.startCatalogMcpServer.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = render(store);

    press(fixture, 'start', '.server-actions');
    await settle(fixture);
    expect(button(fixture, 'start', '.server-actions').disabled).toBe(true);

    expect(store.startCatalogMcpServer).toHaveBeenCalledTimes(1);
  });

  it('locks the probe button while one is already running', () => {
    const { fixture } = render(storeStub([
      server('browser', { runtimeState: 'running', checkStatus: 'checking' }),
    ]));

    expect(button(fixture, 'checking…', '.server-actions').disabled).toBe(true);
  });

  it('probes an external endpoint too, which has no container to start', async () => {
    const { fixture, store } = render(storeStub([
      server('remote', { kind: 'external', hostId: null, url: 'https://mcp.example.test/mcp' }),
    ]));

    press(fixture, 'check', '.server-actions');
    await settle(fixture);

    expect(store.checkCatalogMcpServer).toHaveBeenCalledWith('remote');
  });
});
