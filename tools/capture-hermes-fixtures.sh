#!/usr/bin/env bash
# Captures the hermes CLI output that Mission Control's parsers depend on, into a
# version-named fixture directory the backend tests read.
#
# Why: every parser in io.hermes.missioncontrol.agents was written against output we typed by
# hand. A hermes release can change the status layout, the gateway log format or the skill
# frontmatter and every one of those tests stays green while the dashboard quietly reports an
# agent as unconfigured. These fixtures make that drift a reviewable diff instead.
#
#   ./tools/capture-hermes-fixtures.sh <container> [profile]
#
# Read-only: it runs `hermes --version`, `hermes status`, and cats files. It never writes inside
# the container. Values that belong to the operator rather than to hermes' output format are
# redacted below — read the diff before committing anyway, since only you know what is sensitive
# in your own deployment.
#
# Needs python3, for the two JSON documents only: redacting a field of a JSON object with sed
# means guessing at hermes' formatting, and these fixtures are committed. The check is up front
# rather than at that step because a fixture set is committed and reviewed whole, and a set
# missing half its files is worse than no set.
set -euo pipefail

container="${1:-}"
profile="${2:-default}"
if [ -z "$container" ]; then
  echo "usage: $0 <container> [profile]" >&2
  exit 64
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required: it redacts and normalizes the captured JSON documents" >&2
  exit 69
fi

hermes_in() {
  # the binary is not on the PATH of a plain `sh -lc`, which is why Mission Control execs it as
  # the hermes user; fall back to the absolute path for images that differ
  docker exec -u hermes "$container" sh -c \
    'command -v hermes >/dev/null 2>&1 && exec hermes "$@" || exec /opt/hermes/bin/hermes "$@"' \
    _ "$@"
}

# read it all first: piping into `head` would SIGPIPE the exec and pipefail would call that a
# failure
version_output="$(hermes_in --version 2>&1)"
version_line="$(printf '%s\n' "$version_output" | sed -n '1p')"
# "Hermes Agent v0.16.0 (2026.6.5) · upstream 88dbf951" -> 0.16.0
version="$(printf '%s' "$version_line" | sed -n 's/.*v\([0-9][0-9.]*\).*/\1/p')"
if [ -z "$version" ]; then
  echo "could not read a version out of: $version_line" >&2
  exit 65
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/applications/mission-control-server/src/test/resources/fixtures/hermes-$version"
mkdir -p "$out"

printf '%s\n' "$version_line" > "$out/version.txt"

# ── hermes status ────────────────────────────────────────────────────────────
# Redacted: the Environment block's values, auth refresh timestamps and gateway PIDs are the
# operator's state. Section markers, row labels and the ✓/✗ marks are hermes' own vocabulary,
# which is exactly what the parser reads, so they are kept verbatim.
hermes_in status \
  | sed -E \
      -e 's/^(  (Project|Python|Model|Provider):[[:space:]]*).*$/\1<redacted>/' \
      -e 's/^(    Refreshed:[[:space:]]*).*$/\1<redacted>/' \
      -e 's/^(  PID\(s\):[[:space:]]*).*$/\1<redacted>/' \
  > "$out/status.txt"

# ── the per-profile gateway log ──────────────────────────────────────────────
# Only the supervised banner and its blank-message records: those carry the line format (a
# timestamp, two spaces, a message) without carrying anything an agent said or did.
docker exec -u hermes "$container" sh -c \
  "sed -n '1,40p' /opt/data/logs/gateways/$profile/current 2>/dev/null || true" \
  | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2} ' \
  > "$out/gateway.log" || true

# ── the schedule and the webhook routes ──────────────────────────────────────
# Mission Control reads both of these files rather than parsing a CLI table, so their field
# names, their null-vs-absent choices and the shapes nested inside them are exactly what the
# parsers depend on. Both also carry operator data, so they go through redact_json.
if [ "$profile" = "default" ]; then
  profile_dir="/opt/data"          # the default profile lives at the hermes home
else
  profile_dir="/opt/data/profiles/$profile"
fi

redact_program="$(cat <<'REDACT'
import json, sys

REDACTED = "<redacted>"
# the same 43 base64url characters hermes mints, so the shape survives the redaction
SECRET = "redacted-by-capture-hermes-fixtures-sh-0000"
FREE_TEXT = {"prompt", "workdir", "context_from", "script", "base_url",
             "last_error", "last_delivery_error"}


def scrub(node):
    if isinstance(node, dict):
        clean = {}
        for key, value in node.items():
            if key == "secret" and isinstance(value, str):
                clean[key] = SECRET
            elif key == "deliver_extra" and isinstance(value, dict):
                clean[key] = {inner: REDACTED for inner in value}
            elif key in FREE_TEXT and isinstance(value, str):
                clean[key] = REDACTED
            else:
                clean[key] = scrub(value)
        return clean
    if isinstance(node, list):
        return [scrub(item) for item in node]
    return node


json.dump(scrub(json.load(sys.stdin)), sys.stdout, indent=2)
sys.stdout.write("\n")
REDACT
)"

redact_json() {
  # Reads one hermes JSON document on stdin, writes it back redacted and pretty-printed.
  #
  # The policy is by field name, in one place, because the result is committed to git:
  #  - `secret` becomes a placeholder of the same shape (43 base64url characters). Route
  #    secrets are live credentials, and the length is the only thing a parser reads —
  #    the listing shows a masked four-character tail.
  #  - free-text and location fields become "<redacted>". A cron prompt is the operator's
  #    instructions to an agent, and `workdir`/`base_url`/`last_error` name their machine.
  #  - `deliver_extra` values (chat ids and the like) are addresses of real people.
  #  - everything else is kept, because it is what the parsers read: ids, job and route
  #    names, schedule kinds and displays, repeat counters, states, timestamps, deliver
  #    targets, skills, events.
  #
  # Job and route names are deliberately kept: the tests look their rows up by name, and a
  # set whose rows cannot be told apart is not a fixture. One consequence to expect in the
  # diff — a cron job created without `--name` takes its prompt as its name, so a row's name
  # can read like a prompt while every prompt reads "<redacted>".
  python3 -c "$redact_program"
}

for pair in "cron/jobs.json:cron-jobs.json" \
            "webhook_subscriptions.json:webhook-subscriptions.json"; do
  source_path="$profile_dir/${pair%%:*}"
  fixture="${pair##*:}"
  document="$(docker exec -u hermes "$container" \
    sh -c 'cat "$1" 2>/dev/null || true' _ "$source_path")"
  if [ -z "${document//[[:space:]]/}" ]; then
    # a profile that has never had a job or a route simply has no file
    printf 'no %s for profile %s — skipping %s\n' "${pair%%:*}" "$profile" "$fixture" >&2
    continue
  fi
  printf '%s' "$document" | redact_json > "$out/$fixture"
done

# ── skills ───────────────────────────────────────────────────────────────────
docker exec -u hermes "$container" sh -c \
  'cat /opt/data/skills/.bundled_manifest 2>/dev/null || true' > "$out/bundled_manifest.txt"
docker exec -u hermes "$container" sh -c \
  'find /opt/data/skills -name SKILL.md 2>/dev/null | sort | head -1 | xargs cat 2>/dev/null || true' \
  > "$out/skill.md"

printf 'captured hermes %s into %s\n' "$version" "${out#"$root"/}"
wc -l "$out"/* | sed 's|.*/fixtures/|  fixtures/|'
printf '\nRead the diff before committing: only you know what is sensitive in your deployment.\n'
