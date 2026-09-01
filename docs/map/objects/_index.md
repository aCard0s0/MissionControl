# Every noun, one line

Hand-maintained — there is no generator. A noun with no card still gets a line: the owning path
is most of the value.

`✓` has a card · `·` stub, owning file only

## docker

| | Noun | Owner | One line |
|---|---|---|---|
| ✓ | [Docker host](docker/docker-host.md) | `hosts/` | a daemon we can reach. `dh-local` always exists, cannot be removed. |
| ✓ | [Container](docker/container.md) | `docker/ManagedContainer.java` | the `mc.*` label vocabulary that marks one as ours. |
| ✓ | [Image](docker/image.md) | `docker/ImageRef.java` | tags: pulled vs published, and how they order. |
| · | Container stats | `docker/ContainerStats{Reader,Streams}.java` | 3 s poll; network rates derived client-side from cumulative counters. |
| · | Container resources | `docker/ContainerResources.java` | CPU/memory limits as reported. |
| · | Log line | `docker/{ContainerLogReader,LogLineDto}.java` | container-scoped: Docker stdout/stderr has no reliable profile identity. |
| · | Daemon info | `docker/DaemonInfo.java` | engine + API version behind a host's probe. |
| · | Managed container spec | `docker/ManagedContainerSpec.java` | exactly what an upgrade must copy onto the replacement. |
| · | Parked container | `docker/ParkedContainerName.java` | `-mc-upgrade-<hex>` leftovers; hidden from the fleet, reachable via `?all=true`. |
| · | Deployment readiness | `docker/DeploymentReadiness.java` | bounded readiness checks; `hermes gateway status`. |

## agents

| | Noun | Owner | One line |
|---|---|---|---|
| ✓ | [Profile](agents/profile.md) | `agents/ProfilePaths.java` | **the UI's "Agent"**. Every in-container path and the CLI spelling. |
| ✓ | [Cron job](agents/cron-job.md) | `agents/HermesCron.java` | read `cron/jobs.json`, write via `hermes cron`. |
| ✓ | [Webhook subscription](agents/webhook-subscription.md) | `agents/HermesWebhooks.java` | inbound routes. We publish no port, deliberately. |
| ✓ | [Profile template](agents/profile-template.md) | `agents/templates/` | the one dashboard-owned noun here. Encrypted secrets. |
| · | Skill | `agents/HermesSkills.java` | `SkillDto`, `SkillContentDto`. Files under the profile's skills dir. **Not** the [Skill library](dashboard/skill-library.md): this one exists because a container has it. |
| · | Session | `agents/HermesSessions.java` | conversation history; `SessionDto`. |
| · | SOUL / config | `agents/{HermesConfigEditor,YamlValues}.java` | the smallest safe edit surface. Config writes go through hermes. |
| · | Model config | `agents/HermesModelConfig.java` | which provider/model a profile runs; `AuxiliaryModelSpec`. |
| · | Gateway state | `agents/HermesGatewayState.java` | jobs are stored either way, but nothing fires them when it is down. |
| · | Gateway log | `agents/HermesGatewayLogs.java` | `/opt/data/logs/gateways/<profile>`, 5 s poll — **not** the container-wide stream. |
| · | Integration | `agents/api/IntegrationDto.java` | messaging platforms; `MessagingStatusDto`. |
| · | Outbound webhook | `agents/api/OutboundWebhookDto.java` | opposite direction to the subscription. Route `/outbound`. |
| ✓ | [Auth provider](models/auth-provider.md) | `agents/HermesSetup.java` | *filed under models — it is one of the four colliding names.* |
| · | Env catalog / env file | `agents/HermesEnv{Catalog,File}.java` | what a profile's `.env` may carry. |
| · | Container files | `agents/HermesContainerFiles.java` | **the exec seam.** Every profile file read goes through it. |
| · | Hermes CLI | `agents/HermesCli.java` | how a `hermes -p <profile>` command is spelled. One home. |
| · | Agent lifecycle | `agents/AgentLifecycle.java` | create/remove a profile; `agent-removal.ts` on the FE. |
| · | Profile MCP tool counts | `agents/HermesProfileMcp.java` | in-memory cache, **and a `ContainerIdListener`** — repointed on upgrade like the two tables. |

## models

| | Noun | Owner | One line |
|---|---|---|---|
| ✓ | [Provider](models/provider-registry.md) | `agents/ModelProviderRegistry.java` | `/api/providers`. Compiled-in vendor list. Mirrors hermes' own. |
| ✓ | [Model catalog](models/model-catalog.md) | `models/` | `/api/models`. Model names per vendor. `source` has **three** values. |
| ✓ | [Inference endpoint](models/inference-endpoint.md) | `inference/` | `/api/inference-endpoints`, table `inference_endpoints`. **A server you run.** |
| ✓ | [Auth provider](models/auth-provider.md) | `agents/HermesSetup.java` | `…/auth-providers`. Which keys *this container* holds. |
| · | Endpoint model / running model | `inference/{EndpointModelDto,RunningModelDto}.java` | what an endpoint serves and what is loaded. |
| · | Pull status | `inference/PullStatusDto.java` | ollama only — `canManageModels` gates the button. |
| · | Endpoint client | `inference/{Ollama,OpenAiCompat}*.java` | adding a protocol is one bean and nothing else. |

## mcp

| | Noun | Owner | One line |
|---|---|---|---|
| ✓ | [MCP server entry](mcp/mcp-server-entry.md) | `mcp/McpRegistryService.java` | the catalog row. `definition` vs `live` — pick right. |
| ✓ | [Managed MCP stack](mcp/managed-mcp-stack.md) | `mcp/ManagedMcpStack.java` | `mission-control-mcp` + its per-host Compose lock. |
| ✓ | [MCP agent link](mcp/agent-mcp-link.md) | `mcp/AgentMcpLink.java` | profile ↔ entry, with the revision that shows drift. |
| ✓ | [MCP group](mcp/mcp-group.md) | `mcp/McpGroupController.java` | a set of entries with a deploy. Stores no agents — coverage is derived from the links. |
| · | Retained resource | `mcp/RetainedResource*.java` | volumes kept when a server is deleted. Purged deliberately. |
| · | Support service | `mcp/SupportService{Dto,Request}.java` | a sidecar an entry may declare (e.g. a database). |
| · | Volume / healthcheck spec | `mcp/{VolumeSpec,HealthcheckSpec}.java` | named volumes only; host binds rejected. |
| · | Config value | `mcp/{ConfigValueDto,ConfigValueInput,McpConfigStore}.java` | env + headers, secret ones encrypted. Shares `SecretsAtRest`. |
| · | Runtime / operation state | `mcp/Mcp{Runtime,Operation}State.java` | six runtime, eight operation values. |
| · | Catalog seeder | `mcp/McpCatalogSeeder.java` | seeds Playwright/Context7/Sequential/Postgres **stopped**, once, by seed key. |
| · | Startup reconciler | `mcp/McpStartupReconciler.java` | reconciles desired vs actual on boot. |
| · | Health probe | `mcp/McpHealthProbe.java` | attaches MC itself to the network to probe by service name. |

## dashboard

| | Noun | Owner | One line |
|---|---|---|---|
| ✓ | [API contract](dashboard/api-contract.md) | `applications/api-contract.txt` | generated; both sides assert on it; CI fails on drift. |
| ✓ | [Secret](dashboard/secret.md) | `secrets/SecretsAtRest.java` | four rules, one envelope at a time, two callers. |
| ✓ | [Terminal session](dashboard/terminal-session.md) | `shared/terminal-session.ts` | one xterm ↔ one `docker exec`. The column floor. |
| ✓ | [Scrim](dashboard/scrim.md) | `shared/scrim.ts` | the backdrop: click-outside and Escape. The only way any dialog is dismissed. |
| · | Field caption | `styles.scss` (`.field-cap`) | a caption over what a `<label>` cannot name; the group carries `aria-labelledby`. |
| ✓ | [Board task](dashboard/board-task.md) | `board/` | kanban card keyed by container id. Repointed on upgrade. |
| ✓ | [Prompt](dashboard/prompt.md) | `prompts/` | operator's text library. Nothing in a container reads it. |
| ✓ | [Skill library](dashboard/skill-library.md) | `skills/` | skills the dashboard holds and deploys onto an agent. `hub` = a pointer, `local` = the files. |
| ✓ | [Guide](dashboard/guide.md) | `skills/SkillGuideController.java` | prose composing several skills + MCP servers. Deploys as an umbrella SKILL.md the agent reads. |
| ✓ | [Skill group](dashboard/skill-group.md) | `skills/SkillGroupController.java` | how the library is filed: a named set of skills, optionally pointing at the guide that explains it. No deploy. |
| ✓ | [Prompt group](dashboard/prompt-group.md) | `prompts/PromptGroupController.java` | the same filing over prompts. No guide link — there is no prose object over prompts. |
| · | Server log buffer | `web/ServerLogBuffer.java` | in-memory ring behind `/api/server/logs`. |
| · | Runtime config | `web/RuntimeConfigController.java` | `/config.js` from `MC_*` — no rebuild to repoint the FE. |
| · | App properties | `config/AppProperties.java` | every `MC_*` binding. Table in `architecture.md`. |
| · | Schema upgrades | `config/SchemaUpgrades.java` | **where a new column goes.** Not the CREATE statement. |
| · | Startup summary | `config/StartupSummary.java` | replaces Spring's banner: port, daemon, db, stack dir, key. |
| · | API error | `errors/Api{Errors,ExceptionHandler}.java` | one error shape; `ConnectionFailure`, `ResourceConflictException`. |
| · | Store context | `core/store/store-context.ts` | FE backend status + toasts. |
| · | Wire mappers | `core/store/wire-mappers.ts` | the one place API shapes become FE models. |
| · | Contract tests | `contract/{RouteContractTest,ApiDocCoverageTest,RepoDocs}.java` | the wire agrees with the client, and every route is written down. |

## Not nouns — read these instead

| Thing | Where |
|---|---|
| the five movements | [../processes/](../processes/) |
| "what does changing X hit" | [../effects/CONTEXT.md](../effects/CONTEXT.md) |
| every `MC_*` variable, logging levels, security notes | [../../architecture.md](../../architecture.md) |
| test seams | [../../testing.md](../../testing.md) |
