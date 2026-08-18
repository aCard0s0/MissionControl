import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile, McpCatalogServer, McpServer } from '../core/models';
import { AgentMcpPanel } from './agent-mcp-panel';

const server = (name: string, patch: Partial<McpServer> = {}): McpServer => ({
  id: `m-${name}`, name, transport: 'http', enabled: true, origin: 'custom',
  catalogServerId: null, syncedRevision: null, catalogRevision: null, updateAvailable: false,
  status: 'connected', tools: 3, latencyMs: 42, error: null, checkedAt: 1,
  url: `https://${name}.example.test/mcp`, ...patch,
});

const profile = (mcp: McpServer[]): AgentProfile => ({
  id: 'a-1', containerId: 'c-1', name: 'ops-bot', role: 'ops', state: 'idle',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…key', cwd: '/home/hermes/ops-bot',
  soul: '', memoryMd: '', configYaml: '', skills: [], mcp, integrations: [], sessions: [],
  msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: 0,
});

const catalogServer = (patch: Partial<McpCatalogServer> = {}): McpCatalogServer => ({
  id: 'mcp-browser', name: 'browser', description: '', kind: 'managed', hostId: 'dh-local',
  transport: 'http', url: null, image: 'playwright:latest', platform: null,
  entrypoint: [], command: [], stdioCommand: null, args: [], internalPort: 1100,
  publishedPort: null, path: '/mcp', crossHostUrl: null, connectionUrl: 'http://browser:1100/mcp',
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  desiredState: 'stopped', runtimeState: 'stopped', operationState: 'idle', operationError: null,
  checkStatus: 'unknown', checkError: null, checkedAt: null, latencyMs: null,
  revision: 1, appliedRevision: 0, pendingChanges: false, serviceKey: 'browser',
  createdAt: 1, updatedAt: 1, ...patch,
});

/** Only what the panel reaches for on the store, so nothing here touches a backend. */
const storeStub = (agent: AgentProfile, catalog: McpCatalogServer[] = []) => ({
  mcpServers: signal(catalog),
  dockerHosts: signal([{ id: 'dh-local', name: 'localhost' }]),
  hostById: (id: string) => ({ id, name: 'localhost' }),
  agentById: () => agent,
  mcpServerById: (id: string) => catalog.find(s => s.id === id) ?? null,
  addMcp: vi.fn().mockResolvedValue(true),
  updateMcp: vi.fn().mockResolvedValue(true),
  setMcpEnabled: vi.fn().mockResolvedValue(true),
  connectCatalogMcp: vi.fn().mockResolvedValue(true),
  syncCatalogMcp: vi.fn().mockResolvedValue(true),
  unlinkCatalogMcp: vi.fn().mockResolvedValue(true),
  removeMcp: vi.fn().mockResolvedValue(true),
  testMcp: vi.fn().mockResolvedValue(true),
});

@Component({
  imports: [AgentMcpPanel],
  template: `<mc-agent-mcp-panel [agent]="agent()" />`,
})
class Host {
  readonly agent = signal(profile([]));
}

const render = (store: ReturnType<typeof storeStub>, agent: AgentProfile) => {
  TestBed.configureTestingModule({
    // the panel links to /mcp-servers, so RouterLink needs a router present
    providers: [provideRouter([]), { provide: HermesStore, useValue: store }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.agent.set(agent);
  fixture.detectChanges();
  return fixture;
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

const buttonWith = (fixture: { nativeElement: unknown }, label: string): HTMLButtonElement => {
  const match = Array.from(el(fixture).querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim().toLowerCase() === label.toLowerCase());
  if (!match) throw new Error(`no button labelled "${label}"`);
  return match as HTMLButtonElement;
};

/** Types into an `[(ngModel)]` field and lets the two-way write settle. */
/** The add form's submit button — the catalog panel above it also says "connect". */
const submitAdd = (fixture: { nativeElement: unknown }): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.mcp-add .btn.primary')!;

const type = async (
  fixture: { nativeElement: unknown; whenStable(): Promise<unknown>; detectChanges(): void },
  selector: string, value: string,
): Promise<void> => {
  const input = el(fixture).querySelector<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await fixture.whenStable();
  fixture.detectChanges();
};

describe('AgentMcpPanel roster', () => {
  it('summarizes each status, counting unknown and checking as unchecked', () => {
    const agent = profile([
      server('github'),
      server('files', { status: 'disabled', enabled: false }),
      server('search', { status: 'error', error: 'unreachable' }),
      server('notes', { status: 'unknown' }),
      server('mail', { status: 'checking' }),
    ]);
    const fixture = render(storeStub(agent), agent);

    expect(el(fixture).textContent)
      .toContain('1 connected · 1 disabled · 1 failing · 2 unchecked');
    expect(el(fixture).querySelectorAll('.mcp-row')).toHaveLength(5);
  });

  it('offers reconnect for a disconnected server and disconnect for a live one', () => {
    const agent = profile([server('github'), server('files', { status: 'disabled', enabled: false })]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'disconnect').click();
    expect(store.setMcpEnabled).toHaveBeenCalledWith('a-1', 'github', false);

    buttonWith(fixture, 'reconnect').click();
    expect(store.setMcpEnabled).toHaveBeenCalledWith('a-1', 'files', true);
  });

  it('probes every enabled server when the tab opens, and skips disabled ones', async () => {
    const agent = profile([server('github'), server('files', { status: 'disabled', enabled: false })]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();

    expect(store.testMcp.mock.calls.map(call => call[1])).toEqual(['github']);
  });

  it('asks twice before forgetting a server', () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'forget').click();
    fixture.detectChanges();
    expect(store.removeMcp).not.toHaveBeenCalled();

    buttonWith(fixture, 'confirm forget').click();
    expect(store.removeMcp).toHaveBeenCalledWith('a-1', 'm-github');
  });

  it('shows sync only while the catalog is ahead of the linked alias', () => {
    const linked = server('browser', {
      origin: 'catalog', catalogServerId: 'mcp-browser', syncedRevision: 1,
      catalogRevision: 2, updateAvailable: true,
    });
    const agent = profile([linked]);
    const store = storeStub(agent);
    const fixture = render(store, agent);

    buttonWith(fixture, 'sync').click();
    expect(store.syncCatalogMcp).toHaveBeenCalledWith('a-1', 'browser');
    expect(el(fixture).textContent).toContain('update available');
  });
});

describe('AgentMcpPanel add form', () => {
  it('requires a url for http and a command for stdio', async () => {
    const agent = profile([]);
    const fixture = render(storeStub(agent), agent);

    expect(submitAdd(fixture).disabled).toBe(true);
    await type(fixture, '.name-in', 'github');
    expect(submitAdd(fixture).disabled).toBe(true);

    await type(fixture, '.url-in', 'https://mcp.example.test/mcp');
    expect(submitAdd(fixture).disabled).toBe(false);

    const transport = el(fixture).querySelector<HTMLSelectElement>('.transport-in')!;
    transport.value = 'stdio';
    transport.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(submitAdd(fixture).disabled).toBe(true);   // the url does not count for stdio

    await type(fixture, '.cmd-in', 'npx');
    expect(submitAdd(fixture).disabled).toBe(false);
  });

  it('saves a trimmed http server, then probes what it configured', async () => {
    const agent = profile([server('github', { status: 'unknown' })]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();
    store.testMcp.mockClear();

    await type(fixture, '.name-in', ' github ');
    await type(fixture, '.url-in', ' https://mcp.example.test/mcp ');
    submitAdd(fixture).click();
    await fixture.whenStable();

    expect(store.addMcp).toHaveBeenCalledWith(
      'a-1', 'github', 'http', { url: 'https://mcp.example.test/mcp' });
    expect(store.testMcp).toHaveBeenCalledWith('a-1', 'github');
  });

  it('loads an existing server for editing and updates it under its old name', async () => {
    const agent = profile([server('github')]);
    const store = storeStub(agent);
    const fixture = render(store, agent);
    await fixture.whenStable();

    buttonWith(fixture, 'edit').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('EDIT CUSTOM MCP SERVER');
    expect(el(fixture).querySelector<HTMLInputElement>('.name-in')!.value).toBe('github');

    await type(fixture, '.name-in', 'github-enterprise');
    submitAdd(fixture).click();
    await fixture.whenStable();

    expect(store.updateMcp).toHaveBeenCalledWith(
      'a-1', 'github', 'github-enterprise', 'http',
      { url: 'https://github.example.test/mcp' });
  });
});

describe('AgentMcpPanel catalog connect', () => {
  it('warns that a stopped managed server has to be started first', () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer()]);
    const fixture = render(store, agent);

    const select = el(fixture).querySelector<HTMLSelectElement>('.select')!;
    select.value = 'mcp-browser';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('start & connect');
    expect(el(fixture).textContent).toContain('stopped on localhost');
  });

  it('says plain connect once the server is already running', () => {
    const agent = profile([]);
    const store = storeStub(agent, [catalogServer({ runtimeState: 'running' })]);
    const fixture = render(store, agent);

    const select = el(fixture).querySelector<HTMLSelectElement>('.select')!;
    select.value = 'mcp-browser';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(el(fixture).textContent).not.toContain('start & connect');
    expect(el(fixture).querySelector<HTMLButtonElement>('.catalog-connect .btn')!.disabled).toBe(false);
  });
});
