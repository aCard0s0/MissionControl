# Hermes CLI — quick reference

The command surface of [Hermes Agent](https://hermes-agent.nousresearch.com/) as it exists
*inside* the containers Mission Control deploys. Kept here because half of what the dashboard
does is shell out to these commands, and the other half is read files they write — when a page
looks wrong, the fastest check is running the same command by hand.

Upstream reference: **[docs/reference/cli-commands](https://hermes-agent.nousresearch.com/docs/reference/cli-commands)**.
Every row below links to its section there. Captured against **Hermes Agent v0.20.5**; if a flag
here disagrees with the container, the container is right — run `hermes <cmd> --help`. Upstream
moves faster than its own reference page, so a handful of rows below carry no docs anchor: they
exist in the binary and are documented only by `--help`.

The same catalog is in the dashboard: the **CLI Reference** page (`/reference`) scopes every line
to a chosen profile, and the terminal panel's **cmds** drawer puts one at the prompt without
running it. Its source is
[`hermes-commands.ts`](../applications/mission-control-fe/src/app/core/hermes-commands.ts), and
`hermes-commands.spec.ts` fails if a command appears there and not here, or the other way
round — so this file and the dashboard cannot drift apart.

## Running it

The dashboard's own terminal already runs as the `hermes` user, so inside it these lines work as
written. From a host shell, `hermes` is not on the `PATH` of a plain `sh -lc`, and running as
another user leaves files in `/opt/data` the agent cannot read:

```bash
docker exec -it -u hermes <container> hermes status
```

Long sessions are easier from inside:

```bash
docker exec -it -u hermes <container> bash
```

Fallback for images where the binary is not resolvable, same shape the fixture capture uses:

```bash
docker exec -u hermes <container> sh -c 'command -v hermes >/dev/null 2>&1 && exec hermes "$@" || exec /opt/hermes/bin/hermes "$@"' _ status
```

Non-default profiles take `-p` **before** the subcommand — `hermes -p work cron list`. The
default profile takes no flag at all.

## Status and diagnosis

| Command | What it does | Docs |
|---|---|---|
| `hermes status` | Environment, API keys, auth providers, gateway, jobs, sessions. `--all`, `--deep` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-status) |
| `hermes doctor` | Diagnose config and dependency problems; `--fix` attempts repairs | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-doctor) |
| `hermes logs` | Tail and filter logs — `-n`, `-f`, `--level`, `--session`, `--since` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-logs) |
| `hermes dump` | Copy-pasteable setup summary; `--show-keys` to unmask | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-dump) |
| `hermes insights` | Token, cost and activity analytics — `--days`, `--source` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-insights) |
| `hermes prompt-size` | System-prompt budget breakdown — `--platform`, `--json` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-prompt-size) |
| `hermes debug share` | Upload logs for support — `--lines`, `--nous`, `--local` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-debug) |
| `hermes debug delete` | Delete a paste an earlier `debug share` uploaded | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-debug) |
| `hermes monitoring` | Gateway health metrics and redacted diagnostics over OTLP — `status`, config under `monitoring.*` | — |

## Chat, models, credentials

| Command | What it does | Docs |
|---|---|---|
| `hermes chat` | Interactive or one-shot chat — `-q`, `-m`, `-t`, `--provider`, `-s`, `--image` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-chat) |
| `hermes model` | Interactive provider + model selector | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-model) |
| `hermes moa` | Mixture-of-Agents presets — `list`, `configure`, `delete` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-moa) |
| `hermes fallback` | Fallback providers — `list`, `add`, `remove`, `clear` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-fallback) |
| `hermes auth` | Credential pools — `add`, `list`, `remove`, `reset`, `status`, `logout` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-auth) |
| `hermes logout` | Clear stored credentials — `--provider nous\|openai-codex\|xai-oauth\|spotify` | — |
| `hermes portal` | Nous Portal status and Tool Gateway — `status`, `open`, `tools` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-portal) |
| `hermes proxy` | Local OpenAI-compatible proxy with OAuth — `start`, `status`, `providers` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-proxy) |

## Config, profiles, state

| Command | What it does | Docs |
|---|---|---|
| `hermes config` | `show`, `edit`, `get <key>`, `set <key> <value>`, `path` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-config) |
| `hermes setup` | Interactive wizard — `--quick`, `--portal`, `--reset`, `--non-interactive` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-setup) |
| `hermes profile` | Isolated instances — `list`, `use`, `create`, `delete`, `export` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-profile) |
| `hermes sessions` | `list`, `browse`, `export`, `rename`, `pin`, `stats`, `delete`, `prune`, `archive`, `optimize`, `repair`, `recover`, `import` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-sessions) |
| `hermes skin` | Skins — `list`, `use`, `set <token> <colour>` | — |
| `hermes checkpoints` | Trajectory cache — `status`, `prune`, `clear` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-checkpoints) |
| `hermes backup` | Back up the hermes home directory — `-o`, `-q`, `-l` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-backup) |
| `hermes import` | Restore a backup zip — `-f` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-import) |
| `hermes migrate` | Rewrite config for retired models — `xai`, `--apply`, `--no-backup` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-migrate) |

## Automation

| Command | What it does | Docs |
|---|---|---|
| `hermes cron` | Scheduled jobs — `list`, `create <schedule> [prompt]`, `edit`, `pause`, `resume`, `run`, `remove`, `status` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-cron) |
| `hermes webhook` | Event-driven activation — `subscribe <route>`, `list`, `remove`, `test` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-webhook) |
| `hermes hooks` | Shell-script hooks — `list`, `test`, `revoke`, `doctor` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-hooks) |
| `hermes pause` | **Emergency stop.** Holds cron dispatch, kanban dispatch and new gateway turns — `--reason` | — |
| `hermes resume` | Lift the pause; dispatch picks up on the next tick, no restart | — |
| `hermes kanban` | Multi-profile board — `init`, `create`, `list`, `assign`, `complete` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-kanban) |
| `hermes project` | Named multi-folder workspaces — `create`, `list`, `add-folder`, `bind-board` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-project) |

`cron create` takes the schedule first, then the prompt, then `--name`, `--deliver`,
`--repeat <n>` and one `--skill` per skill. `webhook subscribe` takes the route name, then
`--prompt`, `--description`, `--events`, `--skills`, `--deliver`, `--deliver-chat-id`,
`--deliver-only`.

## Capabilities — skills, MCP, tools

| Command | What it does | Docs |
|---|---|---|
| `hermes skills` | `browse`, `install <id>` (`--force`), `list`, `update`, `config` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-skills) |
| `hermes bundles` | Group skills behind one slash command — `list`, `create`, `delete`, `show` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-bundles) |
| `hermes curator` | Background skill maintenance — `status`, `run`, `backup`, `rollback`, `pause` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-curator) |
| `hermes sync` | Skill Sync across devices and with an org — `status`, `pull`, `push`, `now`, `enable`, `disable`, `device`, `propose` | — |
| `hermes mcp` | MCP servers — `picker`, `install`, `serve`, `add`, `list`, `test` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-mcp) |
| `hermes plugins` | General / memory / context plugins — `install`, `search`, `update`, `list`, `doctor` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-plugins) |
| `hermes tools` | Enabled tools per platform — `--summary` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-tools) |
| `hermes memory` | External memory provider — `setup`, `status`, `off` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-memory) |
| `hermes lsp` | Language Server Protocol — `status`, `list`, `install`, `restart` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-lsp) |
| `hermes computer-use` | Computer-Use backend — `install`, `status`, `doctor`, `permissions` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-computer-use) |
| `hermes journey` | Timeline of learned skills and memories (aliases: `learning`, `memory-graph`) | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands) |
| `hermes pets` | Animated pets — `list`, `install`, `select`, `show`, `scale` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-pets) |

## Messaging

| Command | What it does | Docs |
|---|---|---|
| `hermes gateway` | The messaging gateway service — `run`, `start`, `stop`, `restart`, `status` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-gateway) |
| `hermes send` | One-shot message to a platform — `-t`, `-f`, `-s`, `-l`, `-q` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-send) |
| `hermes pairing` | Messaging pairing codes — `list`, `approve`, `revoke` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-pairing) |
| `hermes peer` | Peer gateways and cross-machine DMs — `add`, `list`, `dm`, `remove` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-peer) |
| `hermes slack` | Slack app manifest — `manifest`, `--write`, `--slashes-only` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-slack) |
| `hermes whatsapp` | Configure the WhatsApp bridge | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-whatsapp) |
| `hermes whatsapp-cloud` | Meta WhatsApp Business Cloud API adapter | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-whatsapp) |

In a Mission Control deployment the gateway is supervised by s6, so prefer restarting the
container over `hermes gateway stop` — s6 will bring it straight back.

## Interfaces and servers

| Command | What it does | Docs |
|---|---|---|
| `hermes serve` | Headless backend server — `--host`, `--port`, `--insecure` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-serve) |
| `hermes dashboard` | Hermes' own web UI — `--port`, `--no-open`, `--skip-build` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-dashboard) |
| `hermes desktop` | Build / launch the Electron app (alias: `gui`) | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands) |
| `hermes acp` | Run as an ACP stdio server | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-acp) |
| `hermes console` | A curated Hermes command REPL — not a raw shell, and not the full CLI | — |
| `hermes completion` | Shell completion scripts — `bash`, `zsh`, `fish` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-completion) |

## Security and lifecycle

| Command | What it does | Docs |
|---|---|---|
| `hermes security audit` | Supply-chain vulnerability scan — `--json`, `--fail-on`, `--skip-venv` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-security) |
| `hermes egress` | Outbound credential-injection firewall — `install`, `setup`, `start`, `stop`, `status` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-egress) |
| `hermes secrets` | External secret managers, read at startup instead of `.env` — `bitwarden`/`bw`, `onepassword`/`op` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-secrets) |
| `hermes approvals` | `suggest` mines past approvals into `command_allowlist` entries; `test` dry-runs one verdict | — |
| `hermes verify` | Detect a project's build/test/start recipe and smoke-test it — `--detect-only`, `--save`, `--phase`, `--json` | — |
| `hermes worktree` | Audit and reclaim the trees `hermes -w` sessions leave behind — `list`, `audit`, `prune` | — |
| `hermes update` | Pull latest code, reinstall deps — `--check`, `--backup`, `--gateway` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-update) |
| `hermes claw` | OpenClaw migration — `migrate`, `--dry-run`, `--preset` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-claw) |
| `hermes import-agent` | Import a Claude Code / Codex setup — `--source`, `--dry-run`, `--overwrite` | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-import-agent) |
| `hermes uninstall` | Remove Hermes from the system | [↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#hermes-uninstall) |

`update` and `uninstall` rewrite the image's own install. In a container deployment, redeploy
from a new image tag instead — see [architecture.md](architecture.md).

## Global options

Before the subcommand, on every command
([↗](https://hermes-agent.nousresearch.com/docs/reference/cli-commands#global-options)):

| Flag | Meaning |
|---|---|
| `--version`, `-V` | Print version and exit |
| `--profile <name>`, `-p <name>` | Select a profile. **No longer listed in `--help`** as of v0.20.5, but still honoured — it is how every Mission Control exec is scoped |
| `-z <prompt>`, `--oneshot` | Send one prompt, print only the final response. No banner, no spinner; approvals auto-bypassed |
| `--usage-file <path>` | One-shot only: write a JSON cost/token/api_calls report, *even when the run fails* |
| `-m <model>`, `--model` | Model override for this invocation |
| `--provider <name>` | Provider override for this invocation |
| `--reasoning <level>` | Reasoning effort: `none`, `minimal`, … through the top tiers |
| `-t <sets>`, `--toolsets` | Toolsets for this invocation |
| `-s <skills>`, `--skills` | Preload skills for the session (repeatable) |
| `--resume <session>`, `-r` | Resume a session by id or title |
| `--continue [name]`, `-c` | Resume the most recent session |
| `--no-restore-cwd` | Do not cd into a resumed session's recorded directory |
| `--in <dir>` | Change directory before starting |
| `--worktree`, `-w` | Start in an isolated git worktree |
| `--accept-hooks` | Auto-approve unseen shell hooks |
| `--yolo` | Bypass dangerous-command prompts |
| `--pass-session-id` | Put the session id in the agent's system prompt |
| `--ignore-user-config` / `--ignore-rules` | Skip `config.yaml` / skip AGENTS.md + SOUL.md injection |
| `--safe-mode` | Troubleshooting: disable all customizations |
| `--tui` / `--cli` | Terminal UI / classic REPL |
| `--dev` | Run the TypeScript sources directly |

## What Mission Control runs for you

Reach for the dashboard first for these — it validates the input and refreshes its view. Running
them by hand is for when a page disagrees with the container.

| Mission Control does | by running |
|---|---|
| Config edits | `hermes -p <profile> config set <key> <value>` |
| Scheduled jobs | `cron create\|edit\|pause\|resume\|run\|remove\|status` |
| Webhook routes | `webhook subscribe\|remove\|test` |
| Skill install | `skills install <id> --force` |
| Profiles | `profile create [--clone --clone-from <name>]`, `profile delete <name> --yes` |
| Version and health | `--version`, `status`, plus `code_version` straight out of `gateway_state.json` |
| Emergency stop | `pause [--reason …]`, `resume` |

Everything else it *reads* rather than runs, straight off the container filesystem:

| Read | Path |
|---|---|
| Cron jobs | `/opt/data/cron/jobs.json` |
| Webhook routes | `/opt/data/webhook_subscriptions.json` |
| Gateway log | `/opt/data/logs/gateways/<profile>/current` |
| Gateway state, turns in flight, running version | `/opt/data/gateway_state.json` |
| Whether `hermes pause` is engaged | `/opt/data/ESTOP` — presence is the pause; the body is `{"engaged_at", "reason"}` |
| Skills | `/opt/data/skills/`, manifest at `.bundled_manifest` |

Non-default profiles live under `/opt/data/profiles/<name>/`; the default profile *is*
`/opt/data`.

Those output formats are pinned by fixtures under
`applications/mission-control-server/src/test/resources/fixtures/hermes-<version>/`. After a
Hermes upgrade, re-capture them so a format change shows up as a reviewable diff rather than a
quietly wrong dashboard:

```bash
./tools/capture-hermes-fixtures.sh <container> [profile]
```

The v0.20.5 set was captured from a container with no cron jobs and no webhook routes, so
`cron-jobs.json` and `webhook-subscriptions.json` are absent from it and the parsers for those
two still test against the v0.16.0 documents. Re-run the capture against a container that has
both to close that gap — the script skips what it cannot find rather than writing an empty
fixture.

## Related references

- [Slash commands](https://hermes-agent.nousresearch.com/docs/reference/slash-commands) — in-chat, not shell
- [Environment variables](https://hermes-agent.nousresearch.com/docs/reference/environment-variables)
- [Tools and skills reference](https://hermes-agent.nousresearch.com/docs/reference/tools-reference)
- [CLI symbols glossary](https://hermes-agent.nousresearch.com/docs/reference/cli-symbols)
- [FAQ and troubleshooting](https://hermes-agent.nousresearch.com/docs/reference/faq)
