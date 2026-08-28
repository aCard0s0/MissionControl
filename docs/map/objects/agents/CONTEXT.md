# agents — hermes' own state, read and written on hermes' terms

The UI's **Agent** is a **profile** inside a container. One container holds several. Every route
here is keyed `(hostId, containerId, profileName)`.

| Card | One line |
|---|---|
| [profile](profile.md) | the agent identity: SOUL, config, skills, sessions. All the paths and the CLI spelling. |
| [cron-job](cron-job.md) | scheduled runs. Hermes fires them; we manage them. |
| [webhook-subscription](webhook-subscription.md) | inbound routes. We publish no port, deliberately. |
| [profile-template](profile-template.md) | the one dashboard-owned noun here — a recipe, in SQLite. |

Stubs with no card body: skills (`HermesSkills`), sessions (`HermesSessions`), SOUL and config
(`HermesConfigEditor`, `HermesModelConfig`, `YamlValues`), integrations, gateway state
(`HermesGatewayState`, `HermesGatewayLogs`). See [../_index.md](../_index.md).

## The rule that governs this whole cluster

**Reads come from the files hermes owns. Writes go through its CLI.**

Not a style choice. Hermes parses schedule expressions, mints job ids, generates HMAC secrets and
owns its config schema — so a write we compose ourselves is a second implementation of its rules.
Reading its JSON is stable; parsing its printed tables is presentation and drifts on any release.

Both sides of that rule live in one place each: `HermesContainerFiles` owns the exec seam,
`HermesCli` owns how a profile-scoped hermes command is spelled (`agents/HermesCli.java:25`).
