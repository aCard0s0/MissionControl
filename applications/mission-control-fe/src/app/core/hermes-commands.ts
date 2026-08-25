/**
 * The hermes CLI surface, as a catalog the dashboard can search, link and type.
 *
 * Mission Control drives a handful of these commands over the API and reads the files the
 * rest of them write, but the container shell is where an operator ends up whenever the
 * dashboard has no button for something. This is that reference, next to the prompt instead
 * of in a browser tab: one row per command, what it does, the flags worth knowing, and a
 * deep link to the section upstream documents it in.
 *
 * Kept in step with `docs/hermes-cli.md` — the same catalog for someone reading the repo —
 * by hermes-commands.spec.ts, which fails when a command exists in one and not the other.
 * Captured against Hermes Agent v0.20.5; the container is the authority when they disagree,
 * which is why every row carries its docs anchor rather than a copy of the full flag list.
 */
export const HERMES_DOCS = 'https://hermes-agent.nousresearch.com/docs/reference/cli-commands';

export interface HermesCommand {
  /** as typed after `hermes` — 'cron list', not 'hermes cron list' */
  readonly cmd: string;
  readonly summary: string;
  /** the flags and subcommands worth knowing, verbatim; absent when there are none */
  readonly flags?: string;
  /** section on the upstream reference; absent for the few commands it does not anchor */
  readonly anchor?: string;
  /** runs against the whole install rather than one profile — see {@link REWRITES_INSTALL} */
  readonly install?: true;
}

export interface HermesCommandGroup {
  readonly title: string;
  /** why an operator reaches for this group, shown under the heading */
  readonly blurb: string;
  readonly commands: readonly HermesCommand[];
}

/**
 * Commands that rewrite the install itself rather than a profile. In a container deployment
 * the image is the install, so these are flagged in the UI: the supported move is redeploying
 * a new tag from the Containers page, not mutating the running image.
 */
export const REWRITES_INSTALL = 'rewrites the install — redeploy a new image tag instead';

export const HERMES_COMMAND_GROUPS: readonly HermesCommandGroup[] = [
  {
    title: 'Status and diagnosis',
    blurb: 'What this agent thinks its own state is — start here when a page disagrees with the container.',
    commands: [
      { cmd: 'status', summary: 'Environment, API keys, auth providers, gateway, jobs, sessions', flags: '--all, --deep', anchor: 'hermes-status' },
      { cmd: 'doctor', summary: 'Diagnose config and dependency problems', flags: '--fix', anchor: 'hermes-doctor' },
      { cmd: 'logs', summary: 'Tail and filter this profile’s log files', flags: '-n, -f, --level, --session, --since', anchor: 'hermes-logs' },
      { cmd: 'dump', summary: 'Copy-pasteable setup summary', flags: '--show-keys', anchor: 'hermes-dump' },
      { cmd: 'insights', summary: 'Token, cost and activity analytics', flags: '--days, --source', anchor: 'hermes-insights' },
      { cmd: 'prompt-size', summary: 'System-prompt budget breakdown', flags: '--platform, --json', anchor: 'hermes-prompt-size' },
      { cmd: 'debug share', summary: 'Upload logs for support', flags: '--lines, --nous, --local', anchor: 'hermes-debug' },
      { cmd: 'debug delete', summary: 'Delete a paste an earlier debug share uploaded', anchor: 'hermes-debug' },
      { cmd: 'monitoring', summary: 'Gateway health metrics and redacted diagnostics over OTLP', flags: 'status' },
    ],
  },
  {
    title: 'Chat, models, credentials',
    blurb: 'Talking to the agent, and choosing what answers.',
    commands: [
      { cmd: 'chat', summary: 'Interactive or one-shot chat with the agent', flags: '-q, -m, -t, --provider, -s, --image', anchor: 'hermes-chat' },
      { cmd: 'model', summary: 'Interactive provider and model selector', anchor: 'hermes-model' },
      { cmd: 'moa', summary: 'Mixture-of-Agents presets', flags: 'list, configure, delete', anchor: 'hermes-moa' },
      { cmd: 'fallback', summary: 'Fallback providers, in order', flags: 'list, add, remove, clear', anchor: 'hermes-fallback' },
      { cmd: 'auth', summary: 'Credential pools and OAuth logins', flags: 'add, list, remove, reset, status, logout', anchor: 'hermes-auth' },
      { cmd: 'logout', summary: 'Clear stored credentials for one provider', flags: '--provider nous|openai-codex|xai-oauth|spotify' },
      { cmd: 'portal', summary: 'Nous Portal status and Tool Gateway', flags: 'status, open, tools', anchor: 'hermes-portal' },
      { cmd: 'proxy', summary: 'Local OpenAI-compatible proxy with OAuth', flags: 'start, status, providers', anchor: 'hermes-proxy' },
    ],
  },
  {
    title: 'Config, profiles, state',
    blurb: 'The files behind the Agents pages. Editing through hermes keeps its validation and migrations in the loop.',
    commands: [
      { cmd: 'config', summary: 'Show, edit and query this profile’s config.yaml', flags: 'show, edit, get <key>, set <key> <value>, path', anchor: 'hermes-config' },
      { cmd: 'setup', summary: 'Interactive setup wizard', flags: '--quick, --portal, --reset, --non-interactive', anchor: 'hermes-setup' },
      { cmd: 'profile', summary: 'Isolated instances, one home directory each', flags: 'list, use, create, delete, export', anchor: 'hermes-profile' },
      { cmd: 'sessions', summary: 'Browse, export, repair and prune conversation sessions', flags: 'list, browse, export, rename, pin, stats, delete, prune, archive, optimize, repair, recover, import', anchor: 'hermes-sessions' },
      { cmd: 'skin', summary: 'List, switch and recolour the active skin', flags: 'list, use, set' },
      { cmd: 'checkpoints', summary: 'Inspect or prune the trajectory cache', flags: 'status, prune, clear', anchor: 'hermes-checkpoints' },
      { cmd: 'backup', summary: 'Back up the hermes home directory', flags: '-o, -q, -l', anchor: 'hermes-backup' },
      { cmd: 'import', summary: 'Restore a backup zip', flags: '-f', anchor: 'hermes-import' },
      { cmd: 'migrate', summary: 'Rewrite config for retired models', flags: 'xai, --apply, --no-backup', anchor: 'hermes-migrate' },
    ],
  },
  {
    title: 'Automation',
    blurb: 'What the Calendar and Webhooks pages drive. cron create takes the schedule first, then the prompt.',
    commands: [
      { cmd: 'cron', summary: 'Scheduled jobs — the Calendar page’s own backend', flags: 'list, create <schedule> [prompt], edit, pause, resume, run, remove, status', anchor: 'hermes-cron' },
      { cmd: 'webhook', summary: 'Event-driven activation on an inbound POST', flags: 'subscribe <route>, list, remove, test', anchor: 'hermes-webhook' },
      { cmd: 'hooks', summary: 'Shell-script hooks around agent events', flags: 'list, test, revoke, doctor', anchor: 'hermes-hooks' },
      { cmd: 'pause', summary: 'Emergency stop — holds cron, kanban and new gateway turns; in-flight work finishes', flags: '--reason' },
      { cmd: 'resume', summary: 'Lift the emergency stop; dispatch picks up on the next tick' },
      { cmd: 'kanban', summary: 'Multi-profile collaboration board', flags: 'init, create, list, assign, complete', anchor: 'hermes-kanban' },
      { cmd: 'project', summary: 'Named multi-folder workspaces', flags: 'create, list, add-folder, bind-board', anchor: 'hermes-project' },
    ],
  },
  {
    title: 'Capabilities',
    blurb: 'What the agent can reach — skills, MCP servers, tools, memory.',
    commands: [
      { cmd: 'skills', summary: 'Browse, install and publish skills', flags: 'browse, install <id> (--force), list, update, config', anchor: 'hermes-skills' },
      { cmd: 'bundles', summary: 'Group skills behind one slash command', flags: 'list, create, delete, show', anchor: 'hermes-bundles' },
      { cmd: 'curator', summary: 'Background skill maintenance', flags: 'status, run, backup, rollback, pause', anchor: 'hermes-curator' },
      { cmd: 'sync', summary: 'Skill Sync across your devices, and with an organisation', flags: 'status, pull, push, now, enable, disable, device, propose' },
      { cmd: 'mcp', summary: 'MCP servers connected to this profile', flags: 'picker, install, serve, add, list, test', anchor: 'hermes-mcp' },
      { cmd: 'plugins', summary: 'General, memory and context plugins', flags: 'install, search, update, list, doctor', anchor: 'hermes-plugins' },
      { cmd: 'tools', summary: 'Which tools are enabled per platform', flags: '--summary', anchor: 'hermes-tools' },
      { cmd: 'memory', summary: 'External memory provider', flags: 'setup, status, off', anchor: 'hermes-memory' },
      { cmd: 'lsp', summary: 'Language Server Protocol integration', flags: 'status, list, install, restart', anchor: 'hermes-lsp' },
      { cmd: 'computer-use', summary: 'Computer Use backend', flags: 'install, status, doctor, permissions', anchor: 'hermes-computer-use' },
      { cmd: 'journey', summary: 'Timeline of learned skills and memories (aliases: learning, memory-graph)' },
      { cmd: 'pets', summary: 'Browse and install animated pets', flags: 'list, install, select, show, scale', anchor: 'hermes-pets' },
    ],
  },
  {
    title: 'Messaging',
    blurb: 'The gateways behind an agent’s Integrations. In a container the gateway is s6-supervised — stopping it just restarts it.',
    commands: [
      { cmd: 'gateway', summary: 'Run or manage the messaging gateway service', flags: 'run, start, stop, restart, status', anchor: 'hermes-gateway' },
      { cmd: 'send', summary: 'Send a one-shot message to a platform', flags: '-t, -f, -s, -l, -q', anchor: 'hermes-send' },
      { cmd: 'pairing', summary: 'Approve or revoke messaging pairing codes', flags: 'list, approve, revoke', anchor: 'hermes-pairing' },
      { cmd: 'peer', summary: 'Peer gateways and cross-machine DMs', flags: 'add, list, dm, remove', anchor: 'hermes-peer' },
      { cmd: 'slack', summary: 'Slack app manifest generation', flags: 'manifest, --write, --slashes-only', anchor: 'hermes-slack' },
      { cmd: 'whatsapp', summary: 'Configure the WhatsApp bridge', anchor: 'hermes-whatsapp' },
      { cmd: 'whatsapp-cloud', summary: 'Meta WhatsApp Business Cloud API adapter', anchor: 'hermes-whatsapp' },
    ],
  },
  {
    title: 'Interfaces and servers',
    blurb: 'Other front ends onto the same profile. Their ports are inside the container until someone publishes them.',
    commands: [
      { cmd: 'serve', summary: 'Start the headless backend server', flags: '--host, --port, --insecure', anchor: 'hermes-serve' },
      { cmd: 'dashboard', summary: 'Launch hermes’ own web UI', flags: '--port, --no-open, --skip-build', anchor: 'hermes-dashboard' },
      { cmd: 'desktop', summary: 'Build or launch the Electron app (alias: gui)' },
      { cmd: 'acp', summary: 'Run as an ACP stdio server', anchor: 'hermes-acp' },
      { cmd: 'console', summary: 'A curated Hermes command REPL — not a raw shell' },
      { cmd: 'completion', summary: 'Print shell completion scripts', flags: 'bash, zsh, fish', anchor: 'hermes-completion' },
    ],
  },
  {
    title: 'Security and lifecycle',
    blurb: 'Auditing what is installed, and the commands that would rewrite the install itself.',
    commands: [
      { cmd: 'security audit', summary: 'Supply-chain vulnerability scan', flags: '--json, --fail-on, --skip-venv', anchor: 'hermes-security' },
      { cmd: 'egress', summary: 'Outbound credential-injection firewall', flags: 'install, setup, start, stop, status', anchor: 'hermes-egress' },
      { cmd: 'secrets', summary: 'External secret managers, read at startup instead of .env', flags: 'bitwarden|bw, onepassword|op', anchor: 'hermes-secrets' },
      { cmd: 'approvals', summary: 'Mine past approvals into allowlist proposals, or dry-run one verdict', flags: 'suggest, test' },
      { cmd: 'verify', summary: 'Detect a project\u2019s build/test/start recipe and smoke-test it', flags: '--detect-only, --save, --phase, --json' },
      { cmd: 'worktree', summary: 'Audit and reclaim the worktrees `hermes -w` sessions leave behind', flags: 'list, audit, prune' },
      { cmd: 'update', summary: 'Pull the latest code and reinstall dependencies', flags: '--check, --backup, --gateway', anchor: 'hermes-update', install: true },
      { cmd: 'claw', summary: 'OpenClaw migration helpers', flags: 'migrate, --dry-run, --preset', anchor: 'hermes-claw', install: true },
      { cmd: 'import-agent', summary: 'Import a Claude Code or Codex setup', flags: '--source, --dry-run, --overwrite', anchor: 'hermes-import-agent' },
      { cmd: 'uninstall', summary: 'Remove Hermes from the system', anchor: 'hermes-uninstall', install: true },
    ],
  },
];

/** Every command, flattened — for search and for the docs-parity check. */
export const HERMES_COMMANDS: readonly HermesCommand[] =
  HERMES_COMMAND_GROUPS.flatMap(g => g.commands);

/** The upstream section for a command, falling back to the reference page itself. */
export const hermesDocsUrl = (command: HermesCommand): string =>
  command.anchor ? `${HERMES_DOCS}#${command.anchor}` : HERMES_DOCS;

/**
 * The characters hermes allows in a profile directory name. Anything else is refused rather
 * than escaped: the result is typed into a live shell, and a name carrying `;` or a backtick
 * would run as a second command.
 */
const SAFE_PROFILE = /^[A-Za-z0-9._-]+$/;

/**
 * The CLI invocation that drops you into a session with `name`. Hermes takes `-p` only for
 * named profiles — `default` lives at /opt/data and is invoked bare (the same special-case
 * the backend applies in HermesProfiles). Returns undefined for a name that could carry
 * shell metacharacters, which downgrades a shortcut to a plain shell rather than typing it
 * blind.
 */
export function agentSessionCommand(name: string): string | undefined {
  if (!SAFE_PROFILE.test(name)) return undefined;
  return name === 'default' ? 'hermes' : `hermes -p ${name}`;
}

/**
 * One command line, scoped to `profile` when there is one to scope it to. An unsafe or absent
 * profile falls back to the bare invocation: the operator sees a runnable line either way, and
 * adds the `-p` themselves if they need it.
 */
export function hermesLine(command: HermesCommand, profile?: string): string {
  const prefix = profile ? agentSessionCommand(profile) ?? 'hermes' : 'hermes';
  return `${prefix} ${command.cmd}`;
}

/** Case-insensitive match over the name, summary and flags — the three things an operator
 *  half-remembers. Groups with nothing left are dropped by the caller, not here. */
export function searchHermesCommands(query: string): readonly HermesCommandGroup[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return HERMES_COMMAND_GROUPS;
  const hit = (c: HermesCommand) =>
    `${c.cmd} ${c.summary} ${c.flags ?? ''}`.toLowerCase().includes(needle);
  return HERMES_COMMAND_GROUPS
    .map(g => ({ ...g, commands: g.commands.filter(hit) }))
    .filter(g => g.commands.length > 0);
}
