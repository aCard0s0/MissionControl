import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DockerHost, McpCatalogServer, McpGroup, McpRetainedResource } from '../core/models';
import { McpServersPage } from './mcp-servers';
import { button, buttonWith, el, fill, press, settle, text, type } from '../testing/dom';
import { catalogServer as server, dockerHost } from '../testing/models';
import { provideStores } from '../testing/store';

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
  catalog: {
    servers: signal(servers),
    loading: signal(false),
    retainedResources: signal(retained),
    refresh: vi.fn().mockResolvedValue(undefined),
    refreshRetainedResources: vi.fn().mockResolvedValue(undefined),
    start: vi.fn().mockResolvedValue(true),
    stop: vi.fn().mockResolvedValue(true),
    apply: vi.fn().mockResolvedValue(true),
    check: vi.fn().mockResolvedValue(true),
    remove: vi.fn().mockResolvedValue(true),
    purgeRetainedResource: vi.fn().mockResolvedValue(true),
    save: vi.fn().mockResolvedValue('mcp-new'),
    logTail: vi.fn().mockResolvedValue([]),
  },
  hosts: {
    hosts: signal(hosts),
  },
  mcpGroups: {
    groups: signal<McpGroup[]>([]),
    refresh: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue('mg-new'),
    remove: vi.fn().mockResolvedValue(true),
    deploy: vi.fn().mockResolvedValue([]),
    byId: () => null,
  },
});

const mcpGroup = (id: string, patch: Partial<McpGroup> = {}): McpGroup => ({
  id, name: `group-${id}`, description: '', serverIds: [], agents: [],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
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

    expect(store.catalog.refresh).toHaveBeenCalled();
    expect(store.catalog.refreshRetainedResources).toHaveBeenCalled();
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
    expect(store.catalog.stop).toHaveBeenCalledWith('browser');
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
    expect(store.catalog.logTail).toHaveBeenCalledWith('browser', 150);

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

    expect(store.catalog.remove).toHaveBeenCalledWith('browser');
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

    expect(store.catalog.purgeRetainedResource).toHaveBeenCalledWith('vol-1');
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

    expect(store.catalog.apply).toHaveBeenCalledWith('browser');
  });

  it('probes a server on demand', async () => {
    const { fixture, store } = render(storeStub([server('browser', { runtimeState: 'running' })]));

    press(fixture, 'check', '.server-actions');
    await settle(fixture);

    expect(store.catalog.check).toHaveBeenCalledWith('browser');
  });

  it('will not start the same server twice while the first start is in flight', async () => {
    const store = storeStub([server('browser')]);
    // the real store patches the entry into its in-flight state before its first await, and
    // that is what makes the button unavailable — the page keeps no busy set of its own
    store.catalog.start.mockImplementation(() => {
      store.catalog.servers.update(servers =>
        servers.map(entry => ({ ...entry, operationState: 'starting' })));
      return new Promise(() => { /* never settles */ });
    });
    const { fixture } = render(store);

    press(fixture, 'start', '.server-actions');
    await settle(fixture);
    expect(button(fixture, 'start', '.server-actions').disabled).toBe(true);

    expect(store.catalog.start).toHaveBeenCalledTimes(1);
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

    expect(store.catalog.check).toHaveBeenCalledWith('remote');
  });
});

describe('McpServersPage groups', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  /** A store with `servers` in the catalog and `groups` already loaded. */
  const withGroups = (servers: McpCatalogServer[], groups: McpGroup[] = []) => {
    const store = storeStub(servers);
    store.mcpGroups.groups.set(groups);
    return store;
  };

  /** Renders and switches to the groups tab, which is where all of this lives. */
  const onGroupsTab = (store: ReturnType<typeof storeStub>) => {
    const rendered = render(store);
    press(rendered.fixture, 'groups');
    return rendered;
  };

  it('opens on the roster, and swaps it for the groups when the tab is pressed', async () => {
    const { fixture } = render(withGroups(
      [server('files')], [mcpGroup('mg-1', { name: 'research', serverIds: ['files'] })]));
    await settle(fixture);

    // the roster is the default: the registry is what this page is for
    expect(el(fixture).querySelector('.server-row')).toBeTruthy();
    expect(el(fixture).querySelector('.mcp-groups')).toBeNull();

    press(fixture, 'groups');
    await settle(fixture);

    expect(el(fixture).querySelector('.server-row')).toBeNull();
    expect(el(fixture).querySelector('.mcp-groups')).toBeTruthy();
    expect(text(fixture)).toContain('research');
  });

  it('offers each tab its own create buttons, and only its own', async () => {
    const { fixture } = render(storeStub([server('files')]));
    await settle(fixture);

    expect(button(fixture, '+ managed server')).toBeTruthy();
    expect(() => button(fixture, '+ new group')).toThrow();

    press(fixture, 'groups');
    await settle(fixture);

    expect(button(fixture, '+ new group')).toBeTruthy();
    expect(() => button(fixture, '+ managed server')).toThrow();
  });

  it('keeps the retained-data panel with the roster it belongs to', async () => {
    const { fixture } = render(storeStub([server('files')]));
    await settle(fixture);
    expect(text(fixture)).toContain('RETAINED DATA');

    press(fixture, 'groups');
    await settle(fixture);

    expect(text(fixture)).not.toContain('RETAINED DATA');
  });

  it('teaches what a group is for when there are none', () => {
    const { fixture } = onGroupsTab(storeStub([server('files')]));

    expect(text(fixture)).toContain('connects several catalog entries to an agent');
  });

  it('lists a group with the catalog entries it names', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files'), server('search')],
      [mcpGroup('mg-1', { name: 'research', serverIds: ['files', 'search'] })]));
    await settle(fixture);

    const row = el(fixture).querySelector<HTMLElement>('.mcp-groups .group-row')!;
    expect(row.textContent).toContain('research');
    expect(row.querySelector('.parts')!.textContent).toContain('files');
    expect(row.querySelector('.parts')!.textContent).toContain('search');
  });

  it('marks a server the catalog has lost, because a deploy will skip it', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files')],
      [mcpGroup('mg-1', { serverIds: ['files', 'deleted'] })]));
    await settle(fixture);

    const parts = el(fixture).querySelector<HTMLElement>('.mcp-groups .parts')!;
    expect(parts.textContent).toContain('deleted ⚠');
    expect(parts.querySelector('.chip.warn')).toBeTruthy();
  });

  it('says which agents the group reaches, from the coverage the backend derived', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files'), server('search')],
      [mcpGroup('mg-1', {
        serverIds: ['files', 'search'],
        agents: [{ hostId: 'dh-local', containerId: 'c-1', profile: 'atlas', linked: 2 }],
      })]));
    await settle(fixture);

    const agents = el(fixture).querySelector<HTMLElement>('.mcp-groups .agents')!;
    expect(agents.textContent).toContain('atlas 2/2');
    expect(agents.querySelector('.chip.on')).toBeTruthy();
  });

  it('warns on an agent that has only part of the group', async () => {
    // the whole reason the coverage is derived rather than stored
    const { fixture } = onGroupsTab(withGroups(
      [server('files'), server('search')],
      [mcpGroup('mg-1', {
        serverIds: ['files', 'search'],
        agents: [{ hostId: 'dh-local', containerId: 'c-1', profile: 'atlas', linked: 1 }],
      })]));
    await settle(fixture);

    const agents = el(fixture).querySelector<HTMLElement>('.mcp-groups .agents')!;
    expect(agents.textContent).toContain('atlas 1/2');
    expect(agents.querySelector('.chip.warn')).toBeTruthy();
  });

  it('lists every agent a group reaches, not just one', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files')],
      [mcpGroup('mg-1', {
        serverIds: ['files'],
        agents: [
          { hostId: 'dh-local', containerId: 'c-1', profile: 'atlas', linked: 1 },
          { hostId: 'dh-local', containerId: 'c-1', profile: 'borealis', linked: 1 },
        ],
      })]));
    await settle(fixture);

    const agents = el(fixture).querySelector<HTMLElement>('.mcp-groups .agents')!;
    expect(agents.textContent).toContain('atlas');
    expect(agents.textContent).toContain('borealis');
  });

  it('says so when a group is on no agent yet', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files')], [mcpGroup('mg-1', { serverIds: ['files'] })]));
    await settle(fixture);

    expect(el(fixture).querySelector('.mcp-groups .agents')!.textContent)
      .toContain('no agent yet');
  });

  it('sends the group the editor composed', async () => {
    const { fixture, store } = onGroupsTab(storeStub([server('files'), server('search')]));

    press(fixture, '+ new group');
    await settle(fixture);
    await fill(fixture, 'name', 'research');
    press(fixture, 'files', '.picker');
    await settle(fixture);
    press(fixture, 'save group');
    await settle(fixture);

    expect(store.mcpGroups.save).toHaveBeenCalledWith(
      { name: 'research', description: '', serverIds: ['files'] }, undefined);
  });

  it('will not save a group with no name', async () => {
    const { fixture, store } = onGroupsTab(storeStub([server('files')]));

    press(fixture, '+ new group');
    await settle(fixture);

    expect(buttonWith(fixture, 'save group').disabled).toBe(true);
    expect(store.mcpGroups.save).not.toHaveBeenCalled();
  });

  it('loads a group into the editor with its servers picked', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files'), server('search')],
      [mcpGroup('mg-1', { name: 'research', serverIds: ['files'] })]));
    await settle(fixture);

    press(fixture, 'edit', '.mcp-groups .group-head-row');
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('#mcp-group-name')!.value)
      .toBe('research');
    expect(button(fixture, 'files', '.picker').classList).toContain('on');
  });

  it('says a group delete leaves every agent connected, and confirms first', async () => {
    const confirmed = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { fixture, store } = onGroupsTab(withGroups(
      [server('files')], [mcpGroup('mg-1', { serverIds: ['files'] })]));
    await settle(fixture);

    press(fixture, 'delete', '.mcp-groups .group-head-row');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0]).toContain('stays connected');
    expect(store.mcpGroups.remove).not.toHaveBeenCalled();
    confirmed.mockRestore();
  });

  it('opens the deploy dialog for the group whose button was pressed', async () => {
    const { fixture } = onGroupsTab(withGroups(
      [server('files')], [mcpGroup('mg-1', { name: 'research', serverIds: ['files'] })]));
    await settle(fixture);

    press(fixture, 'deploy', '.mcp-groups .group-head-row');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-deploy-dialog')).toBeTruthy();
    // the explanation is projected by this page, not owned by the dialog
    expect(text(fixture)).toContain('a top-up, not a sync');
  });
});
