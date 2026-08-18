import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { LogEntry, McpCatalogServer } from '../core/models';
import { McpServerLogs } from './mcp-server-logs';

const server = (id: string): McpCatalogServer => ({
  id, name: id, description: '', kind: 'managed', hostId: 'dh-local',
  transport: 'http', url: null, image: 'image:latest', platform: null,
  entrypoint: [], command: [], stdioCommand: null, args: [], internalPort: 1100,
  publishedPort: null, path: '/mcp', crossHostUrl: null, connectionUrl: `http://${id}:1100/mcp`,
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  desiredState: 'running', runtimeState: 'running', operationState: 'idle', operationError: null,
  checkStatus: 'connected', checkError: null, checkedAt: null, latencyMs: null,
  revision: 1, appliedRevision: 1, pendingChanges: false, serviceKey: id,
  createdAt: 1, updatedAt: 1,
});

const line = (msg: string, source = 'browser'): LogEntry =>
  ({ ts: 1_700_000_000_000, level: 'info', source, agentId: null, msg });

const storeStub = () => ({ mcpServerLogTail: vi.fn().mockResolvedValue([line('ready')]) });

@Component({
  imports: [McpServerLogs],
  template: `<mc-mcp-server-logs [server]="server()" (closed)="closes = closes + 1" />`,
})
class Host {
  readonly server = signal(server('browser'));
  closes = 0;
}

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

/** Lets the pending read resolve and paints what came back. */
const settle = async (fixture: { detectChanges(): void }, ms = 0): Promise<void> => {
  await vi.advanceTimersByTimeAsync(ms);
  fixture.detectChanges();
};

describe('McpServerLogs', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the tail when it opens and renders one row per line', async () => {
    const store = storeStub();
    store.mcpServerLogTail.mockResolvedValue([line('ready'), line('listening on 1100')]);
    const { fixture } = render(store);
    await settle(fixture);

    expect(store.mcpServerLogTail).toHaveBeenCalledWith('browser', 150);
    expect(el(fixture).querySelectorAll('.log-line').length).toBe(2);
    expect(el(fixture).textContent).toContain('listening on 1100');
    expect(el(fixture).textContent).toContain('LOGS — browser');
  });

  it('says the server returned nothing rather than looking still busy', async () => {
    const store = storeStub();
    store.mcpServerLogTail.mockResolvedValue([]);
    const { fixture } = render(store);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('No log lines returned.');
  });

  it('surfaces why a read failed instead of blanking the pane', async () => {
    const store = storeStub();
    store.mcpServerLogTail.mockRejectedValue(new Error('container is not running'));
    const { fixture } = render(store);
    await settle(fixture);

    expect(el(fixture).querySelector('.error')?.textContent).toContain('container is not running');
  });

  it('keeps re-reading while it is open', async () => {
    const store = storeStub();
    const { fixture } = render(store);
    await settle(fixture);

    await settle(fixture, 3_000);
    expect(store.mcpServerLogTail).toHaveBeenCalledTimes(2);
    await settle(fixture, 3_000);
    expect(store.mcpServerLogTail).toHaveBeenCalledTimes(3);
  });

  it('drops a read that lands after the viewer moved to another server', async () => {
    const store = storeStub();
    let answerFirst: (lines: LogEntry[]) => void = () => { /* replaced below */ };
    store.mcpServerLogTail.mockImplementationOnce(
      () => new Promise<LogEntry[]>(resolve => { answerFirst = resolve; }));
    store.mcpServerLogTail.mockResolvedValue([line('gateway up', 'gateway')]);

    const { fixture, host } = render(store);
    host.server.set(server('gateway'));
    await settle(fixture);

    answerFirst([line('stale line', 'browser')]);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('gateway up');
    expect(el(fixture).textContent).not.toContain('stale line');
  });

  it('stops polling once the viewer is gone, so a closed modal leaks no interval', async () => {
    const store = storeStub();
    const { fixture } = render(store);
    await settle(fixture);
    expect(store.mcpServerLogTail).toHaveBeenCalledTimes(1);

    fixture.destroy();
    await vi.advanceTimersByTimeAsync(30_000);

    expect(store.mcpServerLogTail).toHaveBeenCalledTimes(1);
  });

  it('reports a close request to the page rather than hiding itself', () => {
    const { fixture, host } = render(storeStub());

    el(fixture).querySelector<HTMLButtonElement>('.panel-h .btn')!.click();

    expect(host.closes).toBe(1);
  });
});
