import {
  AgentProfile, BoardTask, CronJob, DockerHost, HermesContainer, LogEntry, Webhook,
} from './models';

const NOW = Date.now();
const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

const hist = (base: number, jitter: number, n = 60): number[] =>
  Array.from({ length: n }, (_, i) =>
    Math.max(0, base + Math.sin(i / 7) * jitter * 0.6 + (Math.random() - 0.5) * jitter));

export const seedDockerHosts = (localSocket: string): DockerHost[] => [
  {
    id: 'dh-local', name: 'localhost', url: localSocket, kind: 'local',
    status: 'connected', engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 2, note: null,
  },
];

export const seedContainers = (): HermesContainer[] => [
  {
    id: 'c-prod', name: 'hermes-prod', shortId: 'f3a91c2', hostId: 'dh-local', status: 'running',
    image: 'nousresearch/hermes-agent', version: 'v0.16.0',
    startedAt: NOW - 14 * DAY - 6 * HOUR,
    cpu: 23, ram: 1843, ramTotal: 4096, disk: 18.4, diskTotal: 60,
    netIn: 42, netOut: 18,
    cpuHist: hist(23, 14), ramHist: hist(1843, 120), netHist: hist(60, 35),
  },
  {
    id: 'c-edge', name: 'hermes-edge', shortId: '7b04e9d', hostId: 'dh-local', status: 'unhealthy',
    image: 'nousresearch/hermes-agent', version: 'v0.15.2',
    startedAt: NOW - 2 * DAY - 3 * HOUR,
    cpu: 87, ram: 3712, ramTotal: 4096, disk: 31.2, diskTotal: 40,
    netIn: 8, netOut: 3,
    cpuHist: hist(82, 12), ramHist: hist(3650, 90), netHist: hist(12, 9),
  },
  {
    id: 'c-lab', name: 'hermes-lab', shortId: 'a1c5570', hostId: 'dh-local', status: 'stopped',
    image: 'nousresearch/hermes-agent', version: 'v0.16.0',
    startedAt: null,
    cpu: 0, ram: 0, ramTotal: 2048, disk: 6.1, diskTotal: 20,
    netIn: 0, netOut: 0,
    cpuHist: hist(0, 0), ramHist: hist(0, 0), netHist: hist(0, 0),
  },
];

const SOUL_ATLAS = `# SOUL.md — atlas

You are Atlas, the operations agent for the production fleet.

## Voice
Terse, precise, calm under load. You report numbers before opinions.

## Directives
- Watch deploys, disk pressure, and gateway health.
- Escalate to #ops-alerts only when a threshold is crossed twice.
- Never restart a service without logging the reason first.
- Prefer runbooks in ~/runbooks before improvising.
`;

const SOUL_SCRIBE = `# SOUL.md — scribe

You are Scribe, a research and summarization agent.

## Voice
Clear, structured, source-first. Every claim gets a link.

## Directives
- Morning digest covers: repo activity, papers, mentions.
- Keep summaries under 400 words unless asked.
- File longer reports into ~/reports as markdown.
`;

const SOUL_COURIER = `# SOUL.md — courier

You are Courier, the communications relay.

## Voice
Friendly but brief. One emoji max.

## Directives
- Triage inbound messages across Slack/Telegram/Email.
- Draft replies for approval; never send financial info unprompted.
- Quiet hours 22:00–07:00 UTC except sev-1.
`;

const SOUL_WATCH = `# SOUL.md — watchtower

You are Watchtower, an uptime and anomaly sentinel.

## Directives
- Probe endpoints every 5 minutes; alert on 2 consecutive failures.
- Capture screenshots of failing dashboards for the incident log.
`;

const SOUL_MUSE = `# SOUL.md — muse

You are Muse, a creative drafting agent for the lab.

## Directives
- Generate concepts, name candidates, and copy variants.
- Keep a swipe-file of accepted work in MEMORY.md.
`;

const MEMORY_GENERIC = `# MEMORY.md

- 2026-05-28: prod deploys moved to blue/green; old runbook archived.
- 2026-06-02: Slack workspace migrated to grid — re-auth done.
- 2026-06-07: disk alert threshold raised 80% → 85% after false alarms.
`;

const cfg = (name: string, provider: string, model: string, cwd: string) => `# config.yaml — ${name}
provider: ${provider}
model: ${model}
temperature: 0.4
terminal:
  cwd: ${cwd}
toolsets:
  - core
  - files
  - browser
gateway:
  heartbeat: 30s
`;

export const seedAgents = (): AgentProfile[] => [
  {
    id: 'a-atlas', containerId: 'c-prod', name: 'atlas', role: 'Ops & infrastructure',
    state: 'active', provider: 'anthropic', model: 'claude-fable-5',
    apiKeyMasked: 'sk-ant-…J4Q2', cwd: '/srv/ops',
    soul: SOUL_ATLAS, memoryMd: MEMORY_GENERIC, configYaml: cfg('atlas', 'anthropic', 'claude-fable-5', '/srv/ops'),
    skills: [
      { id: 's1', name: 'incident-runbook', source: 'user', version: '1.4.0', description: 'Walks sev runbooks step by step', enabled: true },
      { id: 's2', name: 'docker-doctor', source: 'hub', version: '0.9.2', description: 'Diagnose container restarts and OOM kills', enabled: true },
      { id: 's3', name: 'daily-briefing', source: 'bundled', version: '2.1.0', description: 'Compile and deliver scheduled briefings', enabled: true },
      { id: 's4', name: 'pdf-tools', source: 'bundled', version: '2.1.0', description: 'Read and assemble PDF reports', enabled: false },
    ],
    mcp: [
      { id: 'm1', name: 'github', transport: 'http', status: 'connected', tools: 24, latencyMs: 88 },
      { id: 'm2', name: 'grafana', transport: 'sse', status: 'connected', tools: 9, latencyMs: 41 },
      { id: 'm3', name: 'postgres-ro', transport: 'stdio', status: 'error', tools: 0, latencyMs: null },
    ],
    integrations: [
      { kind: 'slack', status: 'up', detail: '@atlas-ops · #ops-alerts · up 14d' },
      { kind: 'telegram', status: 'up', detail: '@atlas_ops_bot · up 14d' },
      { kind: 'email', status: 'off', detail: 'not configured' },
      { kind: 'filesystem', status: 'up', detail: '/srv/ops (rw)' },
      { kind: 'browser', status: 'up', detail: 'headless chromium' },
      { kind: 'github', status: 'up', detail: 'org: helios-infra' },
    ],
    sessions: [
      { id: 'ses-1', title: 'disk pressure on edge node', platform: 'slack', startedAt: NOW - 38 * MIN, messages: 14, status: 'open' },
      { id: 'ses-2', title: 'friday deploy window', platform: 'telegram', startedAt: NOW - 5 * HOUR, messages: 31, status: 'closed' },
      { id: 'ses-3', title: 'grafana alert rules review', platform: 'cli', startedAt: NOW - DAY, messages: 52, status: 'closed' },
    ],
    msgsToday: 87, tokensToday: 412, errorRate: 0.4, lastActive: NOW - 4 * MIN,
  },
  {
    id: 'a-scribe', containerId: 'c-prod', name: 'scribe', role: 'Research & digests',
    state: 'active', provider: 'anthropic', model: 'claude-sonnet-4-6',
    apiKeyMasked: 'sk-ant-…M9X1', cwd: '/home/hermes/research',
    soul: SOUL_SCRIBE, memoryMd: MEMORY_GENERIC, configYaml: cfg('scribe', 'anthropic', 'claude-sonnet-4-6', '/home/hermes/research'),
    skills: [
      { id: 's5', name: 'web-research', source: 'bundled', version: '2.1.0', description: 'Multi-source search and synthesis', enabled: true },
      { id: 's6', name: 'arxiv-digest', source: 'hub', version: '1.1.3', description: 'Track and summarize new papers', enabled: true },
      { id: 's7', name: 'citation-check', source: 'user', version: '0.3.0', description: 'Verify quotes against sources', enabled: true },
    ],
    mcp: [
      { id: 'm4', name: 'notion', transport: 'http', status: 'connected', tools: 12, latencyMs: 130 },
      { id: 'm5', name: 'readwise', transport: 'http', status: 'disabled', tools: 6, latencyMs: null },
    ],
    integrations: [
      { kind: 'slack', status: 'up', detail: '@scribe · #research · up 9d' },
      { kind: 'email', status: 'up', detail: 'digest@helios.dev' },
      { kind: 'browser', status: 'up', detail: 'headless chromium' },
      { kind: 'filesystem', status: 'up', detail: '~/research (rw)' },
    ],
    sessions: [
      { id: 'ses-4', title: 'monday digest draft', platform: 'email', startedAt: NOW - 2 * HOUR, messages: 6, status: 'open' },
      { id: 'ses-5', title: 'RLHF papers sweep', platform: 'cli', startedAt: NOW - 26 * HOUR, messages: 19, status: 'closed' },
    ],
    msgsToday: 23, tokensToday: 268, errorRate: 0, lastActive: NOW - 41 * MIN,
  },
  {
    id: 'a-courier', containerId: 'c-prod', name: 'courier', role: 'Comms triage & relay',
    state: 'idle', provider: 'openai', model: 'gpt-5.2',
    apiKeyMasked: 'sk-…R7T8', cwd: '/home/hermes',
    soul: SOUL_COURIER, memoryMd: MEMORY_GENERIC, configYaml: cfg('courier', 'openai', 'gpt-5.2', '/home/hermes'),
    skills: [
      { id: 's8', name: 'inbox-triage', source: 'user', version: '2.0.1', description: 'Classify and route inbound messages', enabled: true },
      { id: 's9', name: 'tone-match', source: 'hub', version: '0.5.0', description: 'Match reply tone to sender history', enabled: false },
    ],
    mcp: [
      { id: 'm6', name: 'gmail', transport: 'http', status: 'connected', tools: 8, latencyMs: 210 },
    ],
    integrations: [
      { kind: 'slack', status: 'up', detail: '@courier · DM relay · up 6d' },
      { kind: 'whatsapp', status: 'degraded', detail: 'session re-auth needed in 2d' },
      { kind: 'telegram', status: 'up', detail: '@courier_relay_bot' },
      { kind: 'email', status: 'up', detail: 'relay@helios.dev' },
      { kind: 'signal', status: 'off', detail: 'not configured' },
    ],
    sessions: [
      { id: 'ses-6', title: 'vendor invoice thread', platform: 'email', startedAt: NOW - 3 * HOUR, messages: 9, status: 'closed' },
    ],
    msgsToday: 142, tokensToday: 96, errorRate: 1.2, lastActive: NOW - 92 * MIN,
  },
  {
    id: 'a-watch', containerId: 'c-edge', name: 'watchtower', role: 'Uptime sentinel',
    state: 'active', provider: 'anthropic', model: 'claude-haiku-4-5',
    apiKeyMasked: 'sk-ant-…K3P9', cwd: '/srv/probes',
    soul: SOUL_WATCH, memoryMd: MEMORY_GENERIC, configYaml: cfg('watchtower', 'anthropic', 'claude-haiku-4-5', '/srv/probes'),
    skills: [
      { id: 's10', name: 'endpoint-probe', source: 'user', version: '3.2.0', description: 'HTTP/TCP probes with screenshot capture', enabled: true },
      { id: 's11', name: 'incident-log', source: 'user', version: '1.0.0', description: 'Append-only incident journal', enabled: true },
    ],
    mcp: [
      { id: 'm7', name: 'statuspage', transport: 'http', status: 'error', tools: 0, latencyMs: null },
    ],
    integrations: [
      { kind: 'discord', status: 'up', detail: '#uptime · up 2d' },
      { kind: 'browser', status: 'degraded', detail: 'chromium OOM-killed 3× today' },
      { kind: 'filesystem', status: 'up', detail: '/srv/probes (rw)' },
    ],
    sessions: [
      { id: 'ses-7', title: 'api-gw latency spike', platform: 'discord', startedAt: NOW - 19 * MIN, messages: 8, status: 'open' },
    ],
    msgsToday: 211, tokensToday: 58, errorRate: 6.8, lastActive: NOW - 2 * MIN,
  },
  {
    id: 'a-muse', containerId: 'c-lab', name: 'muse', role: 'Creative drafting',
    state: 'dormant', provider: 'anthropic', model: 'claude-opus-4-8',
    apiKeyMasked: 'sk-ant-…B2W5', cwd: '/home/hermes/studio',
    soul: SOUL_MUSE, memoryMd: MEMORY_GENERIC, configYaml: cfg('muse', 'anthropic', 'claude-opus-4-8', '/home/hermes/studio'),
    skills: [
      { id: 's12', name: 'image-gen', source: 'bundled', version: '2.1.0', description: 'Generate concept imagery', enabled: true },
      { id: 's13', name: 'namestorm', source: 'hub', version: '0.2.1', description: 'Brandable name candidates with domain check', enabled: true },
    ],
    mcp: [],
    integrations: [
      { kind: 'slack', status: 'off', detail: 'container stopped' },
      { kind: 'filesystem', status: 'off', detail: 'container stopped' },
    ],
    sessions: [],
    msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: NOW - 11 * DAY,
  },
  {
    id: 'a-ledger', containerId: 'c-lab', name: 'ledger', role: 'Bookkeeping experiments',
    state: 'dormant', provider: 'openai', model: 'gpt-5.2-mini',
    apiKeyMasked: 'sk-…Q1Z3', cwd: '/home/hermes/books',
    soul: '# SOUL.md — ledger\n\nExperimental bookkeeping agent. Reconcile, never initiate transfers.\n',
    memoryMd: MEMORY_GENERIC, configYaml: cfg('ledger', 'openai', 'gpt-5.2-mini', '/home/hermes/books'),
    skills: [
      { id: 's14', name: 'csv-reconcile', source: 'user', version: '0.1.0', description: 'Match statements to ledger rows', enabled: true },
    ],
    mcp: [],
    integrations: [
      { kind: 'database', status: 'off', detail: 'container stopped' },
    ],
    sessions: [],
    msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: NOW - 23 * DAY,
  },
];

export const seedJobs = (): CronJob[] => [
  {
    id: 'j1', containerId: 'c-prod', agentId: 'a-atlas', name: 'Morning ops briefing',
    schedule: '0 7 * * 1-5', prompt: 'Compile overnight alerts, deploy status, and disk trends. Deliver as one Slack message.',
    deliverTo: 'slack #ops-alerts', enabled: true,
    lastRun: NOW - 5 * HOUR, lastStatus: 'ok', nextRun: NOW + 19 * HOUR,
  },
  {
    id: 'j2', containerId: 'c-prod', agentId: 'a-atlas', name: 'Disk pressure sweep',
    schedule: 'every 4h', prompt: 'Check disk usage across volumes; warn at 85%.',
    deliverTo: 'slack #ops-alerts', enabled: true,
    lastRun: NOW - 71 * MIN, lastStatus: 'ok', nextRun: NOW + 169 * MIN,
  },
  {
    id: 'j3', containerId: 'c-prod', agentId: 'a-scribe', name: 'Research digest',
    schedule: '0 8 * * 1', prompt: 'Weekly digest: repo activity, new papers, brand mentions. Max 400 words.',
    deliverTo: 'email digest@helios.dev', enabled: true,
    lastRun: NOW - 3 * DAY, lastStatus: 'ok', nextRun: NOW + 4 * DAY,
  },
  {
    id: 'j4', containerId: 'c-prod', agentId: 'a-courier', name: 'Inbox triage',
    schedule: 'every 30m', prompt: 'Triage unread; route, label, draft replies for approval.',
    deliverTo: 'telegram', enabled: true,
    lastRun: NOW - 12 * MIN, lastStatus: 'ok', nextRun: NOW + 18 * MIN,
  },
  {
    id: 'j5', containerId: 'c-prod', agentId: 'a-scribe', name: 'Changelog draft',
    schedule: '0 17 * * 5', prompt: 'Draft the weekly changelog from merged PRs.',
    deliverTo: 'slack #eng', enabled: false,
    lastRun: NOW - 7 * DAY, lastStatus: 'fail', nextRun: NOW + 1 * DAY + 7 * HOUR,
  },
  {
    id: 'j6', containerId: 'c-edge', agentId: 'a-watch', name: 'Endpoint probe',
    schedule: 'every 5m', prompt: 'Probe the endpoint list; alert on 2 consecutive failures.',
    deliverTo: 'discord #uptime', enabled: true,
    lastRun: NOW - 3 * MIN, lastStatus: 'ok', nextRun: NOW + 2 * MIN,
  },
  {
    id: 'j7', containerId: 'c-edge', agentId: 'a-watch', name: 'Nightly screenshot archive',
    schedule: '0 2 * * *', prompt: 'Capture dashboards and archive to /srv/probes/archive.',
    deliverTo: 'filesystem', enabled: true,
    lastRun: NOW - 13 * HOUR, lastStatus: 'fail', nextRun: NOW + 11 * HOUR,
  },
  {
    id: 'j8', containerId: 'c-lab', agentId: 'a-muse', name: 'Idea seed',
    schedule: '0 9 * * 6', prompt: 'Generate 5 concept directions from the swipe file.',
    deliverTo: 'cli', enabled: false,
    lastRun: null, lastStatus: null, nextRun: NOW + 2 * DAY,
  },
];

const L = (minAgo: number, level: LogEntry['level'], source: string, agentId: string | null, msg: string): LogEntry =>
  ({ ts: NOW - minAgo * MIN, level, source, agentId, msg });

export const seedLogs = (): Record<string, LogEntry[]> => ({
  'c-prod': [
    L(2, 'info', 'gateway', 'a-atlas', 'slack event ack in 84ms'),
    L(4, 'info', 'scheduler', 'a-courier', 'job j4 "Inbox triage" finished ok (11.2s)'),
    L(9, 'debug', 'mcp', 'a-atlas', 'grafana: 9 tools registered'),
    L(14, 'warn', 'mcp', 'a-atlas', 'postgres-ro: connection refused (attempt 3) — backing off 60s'),
    L(22, 'info', 'agent', 'a-scribe', 'session ses-4 opened via email'),
    L(31, 'info', 'gateway', 'a-courier', 'whatsapp session expires in 2d — re-auth advised'),
    L(44, 'error', 'mcp', 'a-atlas', 'postgres-ro: handshake failed: SSL required'),
    L(58, 'info', 'scheduler', 'a-atlas', 'job j2 "Disk pressure sweep" finished ok (4.1s)'),
    L(73, 'debug', 'agent', 'a-atlas', 'memory consolidation pass: 3 entries merged'),
    L(96, 'info', 'gateway', null, 'heartbeat ok · 3 profiles · 2 gateways up'),
  ],
  'c-edge': [
    L(1, 'warn', 'agent', 'a-watch', 'api-gw p95 latency 2.4s (threshold 2.0s) — strike 1'),
    L(3, 'info', 'scheduler', 'a-watch', 'job j6 "Endpoint probe" finished ok (2.0s)'),
    L(7, 'error', 'agent', 'a-watch', 'chromium renderer OOM-killed during screenshot'),
    L(12, 'warn', 'system', null, 'memory 91% — container under pressure'),
    L(18, 'error', 'mcp', 'a-watch', 'statuspage: 401 unauthorized — token expired'),
    L(25, 'warn', 'system', null, 'cpu sustained > 80% for 10m'),
    L(40, 'info', 'gateway', 'a-watch', 'discord gateway resumed after 1.2s gap'),
  ],
  'c-lab': [
    L(15_840, 'info', 'system', null, 'container stopped by user (hermes stop)'),
  ],
});

export const seedTasks = (): BoardTask[] => [
  { id: 't1', containerId: 'c-prod', agentId: 'a-atlas', title: 'Rotate postgres-ro MCP credentials', column: 'queued', priority: 'high', tags: ['mcp', 'security'], createdAt: NOW - 50 * MIN },
  { id: 't2', containerId: 'c-prod', agentId: 'a-courier', title: 'Re-auth WhatsApp session', column: 'queued', priority: 'med', tags: ['gateway'], createdAt: NOW - 2 * HOUR },
  { id: 't3', containerId: 'c-prod', agentId: 'a-atlas', title: 'Investigate disk growth on /var/log', column: 'running', priority: 'med', tags: ['ops'], createdAt: NOW - 3 * HOUR },
  { id: 't4', containerId: 'c-prod', agentId: 'a-scribe', title: 'Monday digest — final pass', column: 'running', priority: 'low', tags: ['digest'], createdAt: NOW - 2 * HOUR },
  { id: 't5', containerId: 'c-prod', agentId: 'a-scribe', title: 'Summarize RLHF paper sweep', column: 'review', priority: 'low', tags: ['research'], createdAt: NOW - DAY },
  { id: 't6', containerId: 'c-prod', agentId: 'a-atlas', title: 'Blue/green runbook v2', column: 'review', priority: 'high', tags: ['ops', 'docs'], createdAt: NOW - 26 * HOUR },
  { id: 't7', containerId: 'c-prod', agentId: 'a-courier', title: 'Vendor invoice thread closed out', column: 'done', priority: 'med', tags: ['email'], createdAt: NOW - 2 * DAY },
  { id: 't8', containerId: 'c-prod', agentId: 'a-atlas', title: 'Grafana alert rules pruned', column: 'done', priority: 'low', tags: ['ops'], createdAt: NOW - 3 * DAY },
  { id: 't9', containerId: 'c-edge', agentId: 'a-watch', title: 'Fix statuspage MCP token', column: 'queued', priority: 'high', tags: ['mcp'], createdAt: NOW - 20 * MIN },
  { id: 't10', containerId: 'c-edge', agentId: 'a-watch', title: 'Chromium memory cap for screenshots', column: 'running', priority: 'high', tags: ['stability'], createdAt: NOW - HOUR },
];

export const seedWebhooks = (): Webhook[] => [
  {
    id: 'w1', agentId: 'a-atlas', name: 'Grafana alerts', slug: '/hooks/atlas/grafana',
    secretMasked: 'whsec_…9f2a', events: ['alert.firing', 'alert.resolved'], active: true,
    deliveries: [
      { ts: NOW - 21 * MIN, event: 'alert.firing', status: 'ok', code: 200 },
      { ts: NOW - 2 * HOUR, event: 'alert.resolved', status: 'ok', code: 200 },
      { ts: NOW - 6 * HOUR, event: 'alert.firing', status: 'ok', code: 200 },
    ],
  },
  {
    id: 'w2', agentId: 'a-atlas', name: 'GitHub deploys', slug: '/hooks/atlas/gh-deploy',
    secretMasked: 'whsec_…4c11', events: ['deployment.created', 'deployment_status'], active: true,
    deliveries: [
      { ts: NOW - 5 * HOUR, event: 'deployment.created', status: 'ok', code: 200 },
      { ts: NOW - 5 * HOUR + 4 * MIN, event: 'deployment_status', status: 'fail', code: 500 },
    ],
  },
  {
    id: 'w3', agentId: 'a-courier', name: 'Helpdesk inbound', slug: '/hooks/courier/helpdesk',
    secretMasked: 'whsec_…77e0', events: ['ticket.created'], active: false,
    deliveries: [
      { ts: NOW - 3 * DAY, event: 'ticket.created', status: 'ok', code: 200 },
    ],
  },
  {
    id: 'w4', agentId: 'a-watch', name: 'Statuspage incidents', slug: '/hooks/watchtower/statuspage',
    secretMasked: 'whsec_…b3d8', events: ['incident.created', 'incident.updated'], active: true,
    deliveries: [
      { ts: NOW - 40 * MIN, event: 'incident.created', status: 'fail', code: 401 },
    ],
  },
];
