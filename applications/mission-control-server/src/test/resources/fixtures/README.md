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
honest about the versions still in the field.

Then read the diff. The script redacts what belongs to the operator rather than to hermes' output
format — the Environment block's values, auth refresh timestamps, gateway PIDs — but only you know
what is sensitive in your own deployment.

## What is in a set, and why

| file | what it pins |
|---|---|
| `version.txt` | provenance: the test asserts it matches the directory name |
| `status.txt` | section markers, row labels and the ✓/✗ marks every provider badge is read from, plus the indented detail lines that must **not** become rows |
| `gateway.log` | the log line format: a timestamp, two spaces, a message — including the blank-message records hermes writes, which must be dropped |
| `bundled_manifest.txt` | `name:hash` per line, which decides whether a skill reads as bundled or user-authored |
| `skill.md` | SKILL.md frontmatter, whose `name` wins over the directory a skill sits in |

## What the assertions check

That the parse is **non-degenerate** — every section yields rows, marks resolve to booleans, detail
lines stay detail, the frontmatter name wins over the directory. A changed format makes these
readers return empty rather than throw, and empty is exactly the failure that is otherwise
invisible. Severity classification and the awkward inputs (malformed timestamps, ANSI-only lines,
missing frontmatter) stay in the unit tests, which can construct cases a healthy container does not
produce.

## Not captured

`hermes mcp test <server>` output, because it is the one command here with a side effect — it opens
a connection and can start a stdio server inside the agent. `HermesProfileMcpEntriesTest` covers its
parsing with synthetic output; that one remains unpinned by provenance.
