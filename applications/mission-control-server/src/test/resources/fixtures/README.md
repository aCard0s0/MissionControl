# Captured hermes CLI output

Each `hermes-<version>/` directory holds output taken from a **real** Hermes container, and
`HermesCliFixtureTest` parses all of them. Everything else that tests these parsers feeds them text
we typed ourselves — which proves the rules we believe hermes follows, and nothing about what it
prints. A release can move a section heading, rename a row label or reshape the gateway log, and
the suite stays green while the dashboard reports a configured agent as unconfigured.

## Re-capturing on a hermes bump

```bash
./tools/capture-hermes-fixtures.sh <container> [profile]
```

It reads only — `hermes --version`, `hermes status`, and `cat` of files — and writes into a
directory named after the version it found, so a new hermes release lands as a new set rather than
overwriting the old one. Keep the old set: the test parses every set present, so the parsers stay
honest about the versions still in the field. It needs `python3`, for the two JSON documents only.

Then read the diff. The script redacts what belongs to the operator rather than to hermes' output
format — but only you know what is sensitive in your own deployment.

A set is only as complete as the container it came from: the script skips a document the profile
does not have rather than writing an empty one. `hermes-0.20.5/` is missing `cron-jobs.json` and
`webhook-subscriptions.json` for that reason, so `HermesCronTest` and `HermesWebhooksTest` still
read the v0.16.0 documents. Capturing from a container that has at least one cron job and one
webhook route closes it.

## What is redacted, and what is not

| redacted | why |
|---|---|
| `status.txt`'s Environment values, `Refreshed:` timestamps, gateway `PID(s):` | operator state; the labels and ✓/✗ marks around them are hermes' vocabulary, which is what the parser reads |
| every `prompt` | an operator's instructions to an agent, the one thing in these files with no business in a git repository |
| every route `secret` | a **live HMAC signing key**. Replaced by a placeholder of the same shape — 43 base64url characters — because the length is all a parser reads, and the listing only ever shows a masked four-character tail |
| `deliver_extra` values (chat ids), `workdir`, `base_url`, `context_from`, `script`, `last_error`, `last_delivery_error` | addresses of real people, and paths that name the operator's machine |

Everything else is kept, because it is what the parsers read: ids, job and route names, schedule
kinds and display strings, repeat counters, states, timestamps, deliver targets, skills, events.

Job and route **names are kept on purpose** — the tests look their rows up by name, and a set whose
rows cannot be told apart is not a fixture. One consequence to expect in the diff: a cron job
created without `--name` takes its prompt as its name, so a row's name can read like a prompt while
every `prompt` field reads `<redacted>`. And because prompts are scrubbed, any test that needs real
prompt text — hermes' `{placeholder}` templating, for instance — has to use synthetic input.

## What is in a set, and why

| file | what it pins |
|---|---|
| `version.txt` | provenance: the test asserts it matches the directory name |
| `status.txt` | section markers, row labels and the ✓/✗ marks every provider badge is read from, plus the indented detail lines that must **not** become rows |
| `gateway.log` | the log line format: a timestamp, two spaces, a message — including the blank-message records hermes writes, which must be dropped |
| `bundled_manifest.txt` | `name:hash` per line, which decides whether a skill reads as bundled or user-authored |
| `skill.md` | SKILL.md frontmatter, whose `name` wins over the directory a skill sits in |
| `cron-jobs.json` | the schedule the Jobs page renders straight out of the file — and the trap that only a `cron` schedule carries an `expr`, while `once` stores a timestamp and `interval` a minute count, so the set deliberately holds one job of each kind |
| `webhook-subscriptions.json` | routes keyed by name rather than in an array, `deliver_only`/`deliver_extra`, and an empty `events` list meaning *all* events |

## What the assertions check

That the parse is **non-degenerate** — every section yields rows, marks resolve to booleans, detail
lines stay detail, the frontmatter name wins over the directory, every job displays a schedule and
every route renders a URL carrying its own name. A changed format makes these readers return empty
rather than throw, and empty is exactly the failure that is otherwise invisible.

One assertion is not about parsing at all: every captured route's `secret` must equal the capture
script's placeholder. It fails a careless capture rather than letting a live signing key sit in the
repository unnoticed. Severity classification and the awkward inputs (malformed timestamps, ANSI-only lines,
missing frontmatter) stay in the unit tests, which can construct cases a healthy container does not
produce.

## Not captured

`hermes mcp test <server>` output, because it is the one command here with a side effect — it opens
a connection and can start a stdio server inside the agent. `HermesProfileMcpEntriesTest` covers its
parsing with synthetic output; that one remains unpinned by provenance.
