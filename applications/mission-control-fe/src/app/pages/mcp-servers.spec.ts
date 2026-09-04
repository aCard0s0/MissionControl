import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DockerHost, McpCatalogServer, McpGroup, McpRetainedResource } from '../core/models';
import { McpServersPage } from './mcp-servers';
import { button, buttonWith, el, fill, press, settle, text, type, stubConfirm } from '../testing/dom';
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

  it('narrows the roster by server name and says so when nothing matches', () => {
    const { fixture } = render(storeStub([server('browser'), server('gateway'), server('files')]));
    const input = el(fixture).querySelector<HTMLInputElement>('input.find')!;

    input.value = 'GATE';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('.server-row').length).toBe(1);
    expect(el(fixture).textContent).toContain('gateway');
    expect(el(fixture).textContent).not.toContain('browser:1100');

    input.value = 'nothing-here';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('.server-group').length).toBe(0);
    expect(el(fixture).textContent).toContain('No MCP server named like “nothing-here”');
    expect(el(fixture).textContent).not.toContain('No MCP servers registered.');
  });

  it('refreshes the registry from the filter bar, and says which project a managed stack is', () => {
    const { fixture, store } = render(storeStub([server('browser')]));
    store.catalog.refresh.mockClear();

    el(fixture).querySelector<HTMLButtonElement>('.filter button.refresh')!.click();

    expect(store.catalog.refresh).toHaveBeenCalledTimes(1);
    expect(el(fixture).querySelector('.server-group .panel-h .chip.project')?.textContent)
      .toContain('mission-control-mcp');
  });

  it('counts what the search left visible against the whole registry', () => {
    const { fixture } = render(storeStub([server('browser'), server('gateway')]));
    expect(el(fixture).querySelector('.head-stats')!.textContent).toContain('2 registered');

    const input = el(fixture).querySelector<HTMLInputElement>('input.find')!;
    input.value = 'brow';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(el(fixture).querySelector('.head-stats')!.textContent).toContain('1 of 2 registered');
  });

  it('shows a check result only while a managed service runs, and says who uses it', () => {
    const { fixture } = render(storeStub([
      server('browser', { runtimeState: 'running', checkedAt: Date.now() - 60_000, latencyMs: 32,
        linkedAgents: 2, imageAsOf: Date.now() - 3 * 86_400_000 }),
      server('files', { runtimeState: 'stopped', checkedAt: Date.now() - 60_000, latencyMs: 271 }),
    ]));
    const rows = el(fixture).querySelectorAll('.server-row');

    // the running one: a live result, its image age, and its consumers
    expect(rows[0].textContent).toContain('checked 1m ago');
    expect(rows[0].textContent).toContain('32ms');
    expect(rows[0].textContent).toContain('image as of 3d ago');
    expect(rows[0].textContent).toContain('used by 2 agents');
    // the stopped one: the result predates the stop, so it is not shown beside STOPPED
    expect(rows[1].textContent).not.toContain('checked');
    expect(rows[1].textContent).not.toContain('271ms');
    expect(rows[1].textContent).not.toContain('image as of');
    expect(rows[1].textContent).toContain('unused');
  });

  it('offers pull & restart on a running service with nothing pending — apply always pulls', () => {
    const { fixture, store } = render(storeStub([
      server('browser', { runtimeState: 'running' }),
      server('files', { runtimeState: 'running', revision: 2, appliedRevision: 1, pendingChanges: true }),
    ]));
    const rows = el(fixture).querySelectorAll('.server-row');
    const verb = (row: Element, text: string) =>
      [...row.querySelectorAll<HTMLButtonElement>('.server-actions button')].find(b => b.textContent!.trim() === text);

    expect(verb(rows[0], 'pull & restart')).toBeTruthy();
    expect(verb(rows[0], 'apply & restart')).toBeUndefined();
    expect(verb(rows[1], 'apply & restart')).toBeTruthy();
    expect(verb(rows[1], 'pull & restart')).toBeUndefined();

    verb(rows[0], 'pull & restart')!.click();
    expect(store.catalog.apply).toHaveBeenCalledWith('browser');
  });

  it('starts or stops a whole managed stack from its header, skipping what is already there', async () => {
    const { fixture, store } = render(storeStub([
      server('browser', { runtimeState: 'running' }),
      server('files', { runtimeState: 'stopped' }),
      server('think', { runtimeState: 'stopped', operationState: 'starting' }),
    ]));
    const header = el(fixture).querySelector('.server-group .panel-h')!;
    const verb = (text: string) =>
      [...header.querySelectorAll<HTMLButtonElement>('button.stack-verb')].find(b => b.textContent!.trim() === text)!;

    verb('start all').click();
    await settle(fixture);
    // the running one and the one mid-operation are left alone
    expect(store.catalog.start).toHaveBeenCalledTimes(1);
    expect(store.catalog.start).toHaveBeenCalledWith('files');

    verb('stop all').click();
    await settle(fixture);
    expect(store.catalog.stop).toHaveBeenCalledTimes(1);
    expect(store.catalog.stop).toHaveBeenCalledWith('browser');
  });

  it('collapses retained data to one line when there is nothing in it', () => {
    const none = render(storeStub([server('browser')]));
    expect(el(none.fixture).querySelector('.retained.compact')).toBeTruthy();
    expect(el(none.fixture).querySelector('.retained .panel-h')).toBeNull();

    const some = render(storeStub([server('browser')], [volume]));
    expect(el(some.fixture).querySelector('.retained.compact')).toBeNull();
    expect(el(some.fixture).textContent).toContain('browser-data');
  });

  it('says the registry is empty rather than showing bare sections, and still offers a refresh', () => {
    const { fixture, store } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('No MCP servers registered.');
    expect(el(fixture).querySelectorAll('.server-group').length).toBe(0);

    store.catalog.refresh.mockClear();
    el(fixture).querySelector<HTMLButtonElement>('.filter button.refresh')!.click();
    expect(store.catalog.refresh).toHaveBeenCalledTimes(1);
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

  it('asks for the server name typed back before deleting it', async () => {
    const { fixture, store } = render(storeStub([server('browser')]));
    const confirmed = stubConfirm(true);

    press(fixture, 'delete', '.server-actions');
    await settle(fixture);

    expect(confirmed).toHaveBeenCalledWith(expect.objectContaining({
      typed: 'browser', action: 'delete permanently' }));
    expect(confirmed.mock.calls[0][0].message).toContain('No Agent profile carries this entry.');
    expect(confirmed.mock.calls[0][0].message).toContain('Retained Data');
    expect(store.catalog.remove).toHaveBeenCalledWith('browser');
  });

  it('counts the agent copies a delete will disable', async () => {
    const { fixture } = render(storeStub([server('browser', { linkedAgents: 2 })]));
    const confirmed = stubConfirm(false);

    press(fixture, 'delete', '.server-actions');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message)
      .toContain('2 Agent profiles carry this entry — their copies will be disabled and unlinked first.');
  });

  it('stops tailing a server that has just been removed', async () => {
    const { fixture } = render(storeStub([server('browser', { runtimeState: 'running' })]));
    stubConfirm(true);

    press(fixture, 'logs', '.server-actions');
    await settle(fixture);
    expect(el(fixture).querySelector('mc-mcp-server-logs')).not.toBeNull();

    press(fixture, 'delete', '.server-actions');
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

describe('McpServersPage repository link', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('offers a repo link only for a server that has one', async () => {
    const { fixture } = render(storeStub([
      server('linked', { repoUrl: 'https://github.com/o/r' }),
      server('unlinked', { repoUrl: '' }),
    ]));
    await settle(fixture);

    const rows = Array.from(el(fixture).querySelectorAll<HTMLElement>('.server-row'));
    const linked = rows.find(r => r.textContent?.includes('linked'))!;
    const unlinked = rows.find(r => r.textContent?.includes('unlinked'))!;

    expect(linked.querySelector('a.btn')).toBeTruthy();
    expect(unlinked.querySelector('a.btn')).toBeNull();
  });

  it('opens the repository in a new tab, without handing it the opener', async () => {
    const { fixture } = render(storeStub([
      server('linked', { repoUrl: 'https://github.com/o/r' }),
    ]));
    await settle(fixture);

    const link = el(fixture).querySelector<HTMLAnchorElement>('.server-row a.btn')!;
    expect(link.getAttribute('href')).toBe('https://github.com/o/r');
    expect(link.target).toBe('_blank');
    expect(link.rel).toContain('noopener');
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

  it('narrows groups by name, and refreshes the groups rather than the registry', () => {
    const { fixture, store } = onGroupsTab(withGroups([server('files')], [
      mcpGroup('mg-1', { name: 'research' }), mcpGroup('mg-2', { name: 'ops' })]));
    expect(el(fixture).querySelectorAll('.group-row').length).toBe(2);
    expect(el(fixture).querySelector('.head-stats')!.textContent).toContain('2 groups');

    const input = el(fixture).querySelector<HTMLInputElement>('input.find')!;
    input.value = 'RESEARCH';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('.group-row').length).toBe(1);
    expect(el(fixture).querySelector('.head-stats')!.textContent).toContain('1 of 2 groups');

    input.value = 'nope';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('No group named like “nope”');
    expect(el(fixture).textContent).not.toContain('No groups yet.');

    store.catalog.refresh.mockClear();
    store.mcpGroups.refresh.mockClear();
    el(fixture).querySelector<HTMLButtonElement>('.filter button.refresh')!.click();
    expect(store.mcpGroups.refresh).toHaveBeenCalledTimes(1);
    expect(store.catalog.refresh).not.toHaveBeenCalled();
  });

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
    const { fixture, store } = onGroupsTab(withGroups(
      [server('files')], [mcpGroup('mg-1', { serverIds: ['files'] })]));
    await settle(fixture);
    const confirmed = stubConfirm(false);

    press(fixture, 'delete', '.mcp-groups .group-head-row');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message).toContain('stays connected');
    expect(store.mcpGroups.remove).not.toHaveBeenCalled();
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
