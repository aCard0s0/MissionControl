import { HermesContainer, LogEntry } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { LogStore } from './log-store';

/** How often the simulated telemetry advances, in mock mode only. */
const TICK_MS = 1_500;

/** Chance per tick that a running container emits a log line. */
const LOG_CHANCE = 0.3;

/**
 * Mock-mode only: drifts CPU/RAM/network for every running container and
 * occasionally emits a plausible log line, so the dashboard looks alive without
 * a backend. Nothing here runs in live mode.
 */
export class MockTelemetry {
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private readonly containers: ContainerStore,
    private readonly agents: AgentStore,
    private readonly logs: LogStore,
  ) {}

  start(): void {
    this.timer ??= setInterval(() => this.tick(), TICK_MS);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private tick(): void {
    this.containers.containers.update(list => list.map(c => c.status === 'stopped' ? c : driftContainer(c)));
    if (Math.random() < LOG_CHANCE) this.emitLog();
  }

  private emitLog(): void {
    const running = this.containers.containers().filter(c => c.status !== 'stopped');
    if (!running.length) return;
    const container = pick(running);
    const [level, source, msg] = pick(logPool(container));
    const agents = this.agents.agents().filter(a => a.containerId === container.id);
    const agent = agents.length && Math.random() < 0.7 ? pick(agents) : null;
    this.logs.append(container.id, {
      ts: Date.now(), level, source, agentId: agent?.id ?? null, msg,
    });
  }
}

const pick = <T>(items: readonly T[]): T => items[Math.floor(Math.random() * items.length)];

const drift = (v: number, jitter: number, min: number, max: number): number =>
  Math.min(max, Math.max(min, v + (Math.random() - 0.5) * jitter));

const push = (history: number[], value: number): number[] => [...history.slice(-59), value];

function driftContainer(c: HermesContainer): HermesContainer {
  const cpu = c.status === 'unhealthy' ? drift(c.cpu, 9, 62, 99) : drift(c.cpu, 7, 4, 70);
  const ram = drift(c.ram, 40, c.ramTotal * 0.2, c.ramTotal * (c.status === 'unhealthy' ? 0.97 : 0.7));
  const netIn = Math.max(0, drift(c.netIn, 25, 0, 400));
  const netOut = Math.max(0, drift(c.netOut, 12, 0, 200));
  return {
    ...c, cpu, ram, netIn, netOut,
    cpuHist: push(c.cpuHist, cpu),
    ramHist: push(c.ramHist, ram),
    netHist: push(c.netHist, netIn + netOut),
  };
}

/** Lines an unhealthy container is likely to print, versus a healthy one. */
function logPool(c: HermesContainer): Array<[LogEntry['level'], string, string]> {
  return c.status === 'unhealthy'
    ? [
        ['warn', 'system', 'memory pressure: page cache reclaim'],
        ['error', 'agent', 'probe timeout after 5000ms'],
        ['warn', 'system', `cpu ${Math.round(c.cpu)}% sustained`],
      ]
    : [
        ['info', 'gateway', `event ack in ${Math.round(40 + Math.random() * 120)}ms`],
        ['debug', 'agent', 'context window compacted'],
        ['info', 'scheduler', 'cron heartbeat ok'],
        ['debug', 'mcp', 'tool registry refreshed'],
      ];
}
