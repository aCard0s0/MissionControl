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
set -euo pipefail

container="${1:-}"
profile="${2:-default}"
if [ -z "$container" ]; then
  echo "usage: $0 <container> [profile]" >&2
  exit 64
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

# ── skills ───────────────────────────────────────────────────────────────────
docker exec -u hermes "$container" sh -c \
  'cat /opt/data/skills/.bundled_manifest 2>/dev/null || true' > "$out/bundled_manifest.txt"
docker exec -u hermes "$container" sh -c \
  'find /opt/data/skills -name SKILL.md 2>/dev/null | sort | head -1 | xargs cat 2>/dev/null || true' \
  > "$out/skill.md"

printf 'captured hermes %s into %s\n' "$version" "${out#"$root"/}"
wc -l "$out"/* | sed 's|.*/fixtures/|  fixtures/|'
printf '\nRead the diff before committing: only you know what is sensitive in your deployment.\n'
