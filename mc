#!/usr/bin/env bash
# Mission Control manager — build and deploy the combined image (Angular +
# Spring Boot in one container), either behind tailscale (default) or as a
# plain docker container with a published port. Exactly one flavor runs at a
# time: starting one cleanly stops the other.
#
# Usage:
#   ./mc start                  # deploy behind tailscale (default flavor, HTTPS)
#   ./mc start --build          # rebuild the image first
#   ./mc start --ts=off         # plain docker on http://localhost:8080
#   ./mc start --ollama=on      # also run the optional local model runtime
#   ./mc help                   # full usage
set -euo pipefail

IMAGE="${IMAGE:-hermes-mission-control}"
TAG="${TAG:-latest}"
# --ts=off maps onto compose's LOCAL_PORT / LOCAL_BIND rather than a second
# container spec. There is no separate container name or data volume any more:
# both flavors are the same `mission-control` service.
PORT="${PORT:-8080}"
BIND_ADDRESS="${BIND_ADDRESS:-127.0.0.1}"
MC_CONTAINER_FILTER="${MC_CONTAINER_FILTER:-hermes}"
MC_NO_KEYCHAIN="${MC_NO_KEYCHAIN:-}"
OLLAMA_PORT="${OLLAMA_PORT:-11434}"

cd "$(dirname "$0")"

COMPOSE_FILE="deploy/compose.yml"
# Opt-in host port. A separate file because compose has no falsy port spec — an
# empty ${VAR} in `ports:` is a parse error, not an omission — so the only way
# to make a published port conditional is to move it out of the base file and
# decide here whether to append the second -f.
LOCAL_COMPOSE_FILE="deploy/compose.local.yml"
ENV_FILE="deploy/.env"
APP_ENV_FILE=".mission-control.env"
COMPOSE=(docker compose -p mission-control -f "${COMPOSE_FILE}")
MC_SECRET_KEY_VALUE=""
LOCAL_ACTIVE="off"        # resolved from --local/--no-local, else LOCAL_PORT_ENABLED
SERVE_MODE_OVERRIDE=""    # --serve=MODE, beats TS_SERVE_MODE in deploy/.env

usage() {
  cat <<EOF
mc — Mission Control manager (combined image: ${IMAGE}:${TAG})

Usage: ./mc <command> [flags]

Commands:
  start [--build] [--ts=on|off] [--local|--no-local] [--serve=https|funnel]
        [--ollama=on|off] [--port=N] [--no-keychain]
                     deploy — default --ts=on (behind tailscale, tailnet-only);
                     --ts=off runs the same service without the sidecar, on a
                     published port, and needs no tailnet or auth key;
                     --local adds a loopback host port to the tailscale flavor
                     (overrides LOCAL_PORT_ENABLED in deploy/.env) — it bypasses
                     the tailnet ACL, so it is off by default;
                     --serve overrides TS_SERVE_MODE for this invocation;
                     the ollama service is NOT started by default — --ollama=on
                     brings it up on port ${OLLAMA_PORT}, --ollama=off takes it down
  stop               stop whichever flavor is running (incl. the ollama service)
  restart [...]      stop + start (same flags as start)
  status             which flavor is running, container states, port/URL, ollama
  logs [-f] [-n N]   app container logs (default: last 100 lines)
  shell              interactive sh in the app container
  ollama up|down     start / remove the optional ollama service on its own
  ollama [args...]   ollama CLI inside the service container (no args: list);
                     './mc ollama logs [-f] [-n N]' shows the service logs
  build              build the image only
  down [--volumes]   stop everything; --volumes also removes the data volumes
                     (and logs the node out of the tailnet first, so the next
                     deploy gets its MagicDNS name back instead of -1)
  help               this text

Examples:
  ./mc start                  # tailscale flavor — https://mission-control.<tailnet>.ts.net
  ./mc start --build          # rebuild the image, then deploy
  ./mc start --ts=off         # plain docker — http://localhost:${PORT}
  ./mc start --ts=off --port=9000          # plain mode on a custom port
  ./mc start --local          # tailnet + a loopback port on 127.0.0.1:8080
  ./mc start --ollama=on      # deploy + local model runtime on port ${OLLAMA_PORT}
  ./mc ollama up              # add the ollama service to a running deploy
  ./mc ollama pull llama3.2   # pull a model into the ollama service
  ./mc ollama list            # models available to the agents
  ./mc ollama logs -f
  ./mc logs -f
  ./mc down --volumes

Env overrides: IMAGE TAG PORT BIND_ADDRESS MC_CONTAINER_FILTER
               PORT/BIND_ADDRESS feed compose's LOCAL_PORT/LOCAL_BIND
               BIND_ADDRESS defaults to 127.0.0.1; remote exposure has no app auth
               OLLAMA_PORT  (host port for the ollama service, default 11434)
               MC_NO_KEYCHAIN=1  (bypass macOS keychain creds in headless runs)
EOF
}

# In some contexts (CI, launchd, IDE tasks, etc.) Docker's osxkeychain credential
# helper can't prompt for access/unlock, which makes even public pulls fail.
# When requested (or when stdin isn't a TTY), run compose with an ephemeral
# DOCKER_CONFIG that has no credential helper configured.
DOCKER_CONFIG_TEMP=""
DOCKER_CONFIG_ORIG=""
maybe_bypass_keychain() {
  local force=""
  if [[ -n "${MC_NO_KEYCHAIN}" ]]; then force=1; fi

  if [[ -z "${force}" && -t 0 ]]; then
    return 0
  fi
  if [[ -n "${DOCKER_CONFIG_TEMP}" ]]; then
    return 0
  fi

  DOCKER_CONFIG_ORIG="${DOCKER_CONFIG:-}"
  DOCKER_CONFIG_TEMP="$(mktemp -d -t mc-docker-config.XXXXXX)"
  # clone the real config minus the credential helpers — dropping the whole
  # file would also lose cliPluginsExtraDirs and with it the compose plugin
  local srcdir="${DOCKER_CONFIG_ORIG:-${HOME}/.docker}"
  local src="${srcdir}/config.json"
  # cli plugins (compose, buildx, …) and context metadata are files inside
  # DOCKER_CONFIG — link them so only the credential config changes
  local d
  for d in cli-plugins contexts; do
    if [[ -d "${srcdir}/${d}" ]]; then
      ln -s "${srcdir}/${d}" "${DOCKER_CONFIG_TEMP}/${d}"
    fi
  done
  if [[ -f "${src}" ]] && command -v python3 >/dev/null; then
    python3 -c 'import json,sys
cfg = json.load(open(sys.argv[1]))
cfg.pop("credsStore", None); cfg.pop("credHelpers", None)
cfg["auths"] = {}
json.dump(cfg, open(sys.argv[2], "w"))' "${src}" "${DOCKER_CONFIG_TEMP}/config.json"
  else
    printf '%s\n' '{"auths":{}}' > "${DOCKER_CONFIG_TEMP}/config.json"
  fi
  export DOCKER_CONFIG="${DOCKER_CONFIG_TEMP}"

  # shellcheck disable=SC2064
  trap '[[ -n "${DOCKER_CONFIG_TEMP}" ]] && rm -rf "${DOCKER_CONFIG_TEMP}"; if [[ -n "${DOCKER_CONFIG_ORIG}" ]]; then export DOCKER_CONFIG="${DOCKER_CONFIG_ORIG}"; else unset DOCKER_CONFIG; fi' EXIT
}

# read-only compose calls must work without deploy/.env — feed the required
# ${VAR:?} interpolations dummy values (never used to 'up' the tailscale flavor;
# the ollama service has no required interpolations of its own)
compose_ro() {
  TS_AUTHKEY="${TS_AUTHKEY:-unset}" MC_SECRET_KEY="${MC_SECRET_KEY_VALUE:-unset}" \
    TS_IMAGE_TAG="${TS_IMAGE_TAG:-unset}" TS_TAILNET="${TS_TAILNET:-unset}" \
    OLLAMA_PORT="${OLLAMA_PORT}" "${COMPOSE[@]}" "$@"
}

# Read one variable out of deploy/.env without sourcing it — that file holds the
# tailnet auth key, and sourcing would put it in this shell's environment and
# then in every child process it spawns.
env_get() {  # $1 = name, $2 = default
  local v=""
  [[ -f "${ENV_FILE}" ]] && v="$(sed -n "s/^$1=//p" "${ENV_FILE}" | tail -n 1)"
  printf '%s' "${v:-$2}"
}

# Decide whether the deploy publishes a loopback host port, and rebuild the
# compose invocation accordingly. An explicit --local/--no-local beats
# LOCAL_PORT_ENABLED in deploy/.env.
resolve_local() {  # $1 = "" | "on" | "off"
  local want="$1"
  if [[ -z "${want}" ]]; then
    case "$(env_get LOCAL_PORT_ENABLED false)" in
      true|1|yes|on) want="on" ;;
      *)             want="off" ;;
    esac
  fi
  LOCAL_ACTIVE="${want}"
  COMPOSE=(docker compose -p mission-control -f "${COMPOSE_FILE}")
  if [[ "${LOCAL_ACTIVE}" == "on" ]]; then
    COMPOSE+=(-f "${LOCAL_COMPOSE_FILE}")
  fi
}

# The effective serve posture for this invocation.
serve_mode() { printf '%s' "${SERVE_MODE_OVERRIDE:-$(env_get TS_SERVE_MODE https)}"; }

# Publishing an unauthenticated, docker.sock-mounting dashboard to the open
# internet must never be a quiet side effect of an env var someone edited last
# month. Serve also sends no identity headers on funnel requests, so there is
# nothing the app could gate on even once it learns to.
warn_if_funnel() {
  [[ "$(serve_mode)" == "funnel" ]] || return 0
  echo "⚠  TS_SERVE_MODE=funnel — this publishes Mission Control to the PUBLIC INTERNET." >&2
  echo "   It has no authentication and mounts /var/run/docker.sock (root-equivalent)." >&2
  echo "   Set TS_SERVE_MODE=https in ${ENV_FILE}, or pass --serve=https." >&2
}

# Persist one application encryption key across both deployment flavors. The
# file is gitignored and owner-readable only. Never print or pass the value as
# a command-line argument; it enters containers through their environment.
ensure_app_secret() {
  if [[ ! -f "${APP_ENV_FILE}" ]]; then
    command -v openssl >/dev/null || {
      echo "error: openssl is required to generate ${APP_ENV_FILE}" >&2
      exit 1
    }
    local generated old_umask
    generated="$(openssl rand -hex 32)"
    old_umask="$(umask)"
    umask 077
    printf 'MC_SECRET_KEY=%s\n' "${generated}" > "${APP_ENV_FILE}"
    umask "${old_umask}"
    echo "✓ generated persistent Mission Control encryption key in ${APP_ENV_FILE}"
  fi
  chmod 600 "${APP_ENV_FILE}"
  MC_SECRET_KEY_VALUE="$(sed -n 's/^MC_SECRET_KEY=//p' "${APP_ENV_FILE}" | tail -n 1)"
  if [[ ${#MC_SECRET_KEY_VALUE} -lt 32 ]]; then
    echo "error: ${APP_ENV_FILE} must contain MC_SECRET_KEY with at least 32 characters" >&2
    exit 1
  fi
}

require_docker() {
  command -v docker >/dev/null || { echo "error: docker not found on PATH" >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "error: docker daemon not reachable" >&2; exit 1; }
}

image_exists()   { docker image inspect "${IMAGE}:${TAG}" >/dev/null 2>&1; }

# Flavor is a property of WHICH SERVICES are up, not of which runtime started
# them. Both flavors are now the same `mission-control` compose service; the
# plain one simply does not start the sidecar. That is the point of dropping the
# separate `docker run`: it had drifted to a different data volume, no memory
# limit (so -XX:MaxRAMPercentage=50 read the host's RAM and sized a ~11 GiB heap
# instead of 256 MiB), no pids limit, no init and no healthcheck.
#
# The ollama service is in the same project but must not flip flavor detection.
# `ps -aq` counts a stopped container, `ps -q` only a running one.
svc_exists()  { [[ -n "$(compose_ro ps -aq "$1" 2>/dev/null || true)" ]]; }
svc_running() { [[ -n "$(compose_ro ps -q  "$1" 2>/dev/null || true)" ]]; }

ts_exists()      { svc_exists tailscale; }
ts_running()     { svc_running tailscale; }
app_exists()     { svc_exists mission-control; }
app_running()    { svc_running mission-control; }
plain_exists()   { app_exists && ! ts_exists; }
ollama_exists()  { svc_exists ollama; }
ollama_running() { svc_running ollama; }

build_image() {
  echo "→ building ${IMAGE}:${TAG}"
  docker build -t "${IMAGE}:${TAG}" .
  echo "✓ image built: ${IMAGE}:${TAG}"
}

ensure_image() {  # $1 = --build flag value
  if [[ -n "$1" ]]; then
    build_image
  elif ! image_exists; then
    echo "error: image ${IMAGE}:${TAG} not found — run './mc start --build' or './mc build'" >&2
    exit 1
  fi
}

socket_note() {
  echo "  note: mounting docker.sock grants the container daemon-level (root-equivalent) access;"
  echo "  in production put a restricted socket proxy in front (see docs/architecture.md)"
}

start_ts() {  # $1 = --build flag value
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "error: ${ENV_FILE} not found — the tailscale flavor needs an auth key:" >&2
    echo "  cp deploy/.env.example deploy/.env" >&2
    echo "  then fill in TS_AUTHKEY (admin console → Settings → Keys → auth key;" >&2
    echo "  reusable + tag:server recommended) and TS_IMAGE_TAG" >&2
    exit 1
  fi
  local mode; mode="$(serve_mode)"
  if [[ ! -f "deploy/tailscale/serve-${mode}.json" ]]; then
    echo "error: TS_SERVE_MODE=${mode} names deploy/tailscale/serve-${mode}.json, which does not exist." >&2
    echo "  tailscale treats a missing serve config as 'no serve config', not as an" >&2
    echo "  error — the node would come up healthy and serve nothing. Available:" >&2
    ls deploy/tailscale/serve-*.json 2>/dev/null | sed 's/^/    /' >&2
    exit 1
  fi
  warn_if_funnel
  ensure_image "$1"
  # Switching from the plain flavor needs no teardown: it is the same service,
  # so compose recreates it when the published port disappears from the config.
  echo "→ bringing up the tailscale flavor (serve=${mode}, local port=${LOCAL_ACTIVE})"
  maybe_bypass_keychain
  TS_SERVE_MODE="${mode}" \
    "${COMPOSE[@]}" --env-file "${APP_ENV_FILE}" --env-file "${ENV_FILE}" up -d
  echo "✓ deployed — https://mission-control.<tailnet>.ts.net  (TLS terminated by tailscale serve)"
  if [[ "${LOCAL_ACTIVE}" == "on" ]]; then
    echo "  plus a host port on $(env_get LOCAL_BIND 127.0.0.1):$(env_get LOCAL_PORT 8080) —"
    echo "  that path bypasses the tailnet ACL and Serve's identity headers"
  fi
  echo "  find the exact URL with './mc status', or:"
  echo "  docker compose -p mission-control -f ${COMPOSE_FILE} exec tailscale tailscale status"
}

# The same compose service as the tailscale flavor, with the sidecar simply not
# started. It therefore inherits the healthcheck, init, cpus, mem_limit and
# pids_limit, and — importantly — the SAME data volume, so switching flavors no
# longer switches you to a different database.
#
# deploy/.env is deliberately NOT read here. The whole point of --ts=off is that
# it runs with no tailnet at all, so the file holding the auth key should not be
# needed, or even opened. The tailscale-only interpolations get dummies instead,
# exactly as compose_ro does for read-only calls.
start_plain() {  # $1 = --build flag value
  ensure_image "$1"
  if ts_exists; then
    echo "→ removing the tailscale sidecar (switching to the plain flavor)"
    compose_ro rm -sf tailscale
  fi

  if [[ "${BIND_ADDRESS}" != "127.0.0.1" && "${BIND_ADDRESS}" != "localhost" && "${BIND_ADDRESS}" != "[::1]" ]]; then
    echo "⚠ plain mode is unauthenticated and will be exposed on ${BIND_ADDRESS}:${PORT}" >&2
  fi

  echo "→ bringing up the plain flavor on ${BIND_ADDRESS}:${PORT}"
  maybe_bypass_keychain
  # TS_TAILNET is dummied to a name that cannot resolve: compose still builds an
  # https://…​ entry into MC_CORS_ORIGINS from it, and an unreachable origin in
  # the allowlist is inert, where a real one would be a claim this deploy cannot
  # honour. The loopback entries are the ones that matter here.
  TS_AUTHKEY=unset TS_IMAGE_TAG=unset TS_TAILNET=invalid.localdomain \
    LOCAL_PORT="${PORT}" LOCAL_BIND="${BIND_ADDRESS}" \
    docker compose -p mission-control -f "${COMPOSE_FILE}" -f "${LOCAL_COMPOSE_FILE}" \
      --env-file "${APP_ENV_FILE}" up -d mission-control

  echo "✓ deployed — http://${BIND_ADDRESS}:${PORT}  (filter=${MC_CONTAINER_FILTER})"
  socket_note
}

# the ollama service publishes OLLAMA_PORT for Hermes agent containers that
# run on the host daemon outside this stack — skip (don't fail the deploy)
# when a foreign container already holds the port
start_ollama() {
  local holder
  holder="$(docker ps --format '{{.Names}}|{{.Label "com.docker.compose.project"}}|{{.Ports}}' 2>/dev/null \
    | grep -F ":${OLLAMA_PORT}->" | grep -v '|mission-control|' | cut -d'|' -f1 | head -n1 || true)"
  if [[ -n "${holder}" ]]; then
    echo "⚠ port ${OLLAMA_PORT} is already published by container '${holder}' — not part of this stack."
    echo "  SKIPPING the ollama service (the rest of the stack is up). Fix either way:"
    echo "    docker stop ${holder}            # free the port, then './mc start' again"
    echo "    OLLAMA_PORT=11435 ./mc start     # or run ours on another port"
    return 0
  fi
  echo "→ bringing up the ollama service (port ${OLLAMA_PORT})"
  maybe_bypass_keychain
  compose_ro up -d ollama
  echo "✓ ollama up — register it in the dashboard's Models page:"
  echo "    http://host.docker.internal:${OLLAMA_PORT}   (from agent containers)"
  echo "    http://localhost:${OLLAMA_PORT}              (from this machine)"
}

stop_ollama() {
  if ollama_exists; then
    echo "→ removing the ollama service"
    compose_ro rm -sf ollama
    echo "✓ ollama removed (models kept in the ollama-models volume)"
  else
    echo "→ ollama service not deployed — nothing to remove"
  fi
}

cmd_start() {
  # ollama is opt-in: empty means "leave whatever is there alone", so a plain
  # './mc start' neither deploys nor tears down the local model runtime
  local ts="on" build="" ollama="" local_flag="" port_flag="" arg
  for arg in "$@"; do
    case "${arg}" in
      --build)     build=1 ;;
      --ts=on)     ts="on" ;;
      --ts=off)    ts="off" ;;
      --ollama=on)  ollama="on" ;;
      --ollama=off) ollama="off" ;;
      --local)     local_flag="on" ;;
      --no-local)  local_flag="off" ;;
      --serve=*)   SERVE_MODE_OVERRIDE="${arg#--serve=}" ;;
      --port=*)    PORT="${arg#--port=}"; port_flag=1 ;;
      --no-keychain) MC_NO_KEYCHAIN=1 ;;
      *) echo "error: unknown start flag: ${arg}" >&2; exit 1 ;;
    esac
  done

  require_docker
  ensure_app_secret
  resolve_local "${local_flag}"
  if [[ "${ts}" == "on" ]]; then
    if [[ -n "${port_flag}" ]]; then
      echo "→ note: --port only applies to --ts=off — set LOCAL_PORT in ${ENV_FILE} for --local"
    fi
    start_ts "${build}"
  else
    if [[ -n "${local_flag}" || -n "${SERVE_MODE_OVERRIDE}" ]]; then
      echo "→ note: --local/--serve only apply to the tailscale flavor — ignored"
    fi
    start_plain "${build}"
  fi
  case "${ollama}" in
    on)  start_ollama ;;
    off) stop_ollama ;;
    *)   if ! ollama_running; then
           echo "→ ollama: not started (opt in with --ollama=on or './mc ollama up')"
         fi ;;
  esac
}

cmd_stop() {
  require_docker
  local stopped=""
  if app_exists || ts_exists; then
    echo "→ taking down the $(ts_exists && echo "tailscale" || echo "plain") flavor"
    stopped=1
  fi
  if ollama_exists; then
    echo "→ taking down the ollama service"
    stopped=1
  fi
  if [[ -n "${stopped}" ]]; then
    # --profile ollama puts the ollama service in scope alongside the flavor
    compose_ro --profile ollama down
  fi
  if [[ -n "${stopped}" ]]; then
    echo "✓ stopped"
  else
    echo "→ nothing running"
  fi
}

cmd_status() {
  require_docker
  local found=""
  if ts_exists; then
    found=1
    echo "→ flavor: tailscale (compose project mission-control)"
    compose_ro ps
    local ts_json state dns
    if ts_json="$(compose_ro exec -T tailscale tailscale status --json 2>/dev/null)"; then
      state="$(printf '%s\n' "${ts_json}" | grep -m1 '"BackendState"' | sed 's/.*: *"\([^"]*\)".*/\1/')"
      dns="$(printf '%s\n' "${ts_json}" | grep -m1 '"DNSName"' | sed 's/.*: *"\([^"]*\)".*/\1/; s/\.$//')"
      echo "  tailscale: ${state:-unknown}"
      if [[ -n "${dns}" ]]; then echo "  url: https://${dns}"; fi
      # what the daemon actually loaded, not what .env says it should have
      local serve_out
      serve_out="$(compose_ro exec -T tailscale tailscale serve status 2>/dev/null || true)"
      if [[ -z "$(printf '%s' "${serve_out}" | tr -d '[:space:]')" ]]; then
        echo "  serve: NO CONFIG LOADED — the node is up and serving nothing"
        echo "         (check TS_SERVE_MODE in ${ENV_FILE} names a file in deploy/tailscale/)"
      else
        printf '%s\n' "${serve_out}" | sed 's/^/  serve: /'
      fi
      if printf '%s' "${serve_out}" | grep -qi funnel; then
        echo "  ⚠ funnel is ON — this node is reachable from the public internet"
      fi
    else
      echo "  tailscale: sidecar not responding (still starting?)"
    fi
    local lport
    lport="$(compose_ro port mission-control 8080 2>/dev/null | head -n1 || true)"
    # `compose port` prints "invalid IP:0" rather than nothing when the service
    # publishes no host port, so match a real host:port instead of testing for
    # empty — otherwise a correctly-closed stack reports itself as exposed, and
    # a warning that cries wolf is a warning nobody reads.
    if [[ "${lport}" =~ :[1-9][0-9]*$ ]]; then
      echo "  ⚠ host port published on ${lport} — bypasses the tailnet ACL and Serve"
    fi
  fi
  if plain_exists; then
    found=1
    echo "→ flavor: plain (same compose service, no tailscale sidecar)"
    compose_ro ps mission-control
    local pport
    pport="$(compose_ro port mission-control 8080 2>/dev/null | head -n1 || true)"
    # same "invalid IP:0" caveat as the tailscale branch above
    if [[ "${pport}" =~ :[1-9][0-9]*$ ]]; then
      echo "  url: http://${pport}"
    else
      echo "  ⚠ no host port published — nothing can reach it; './mc start --ts=off' publishes one"
    fi
  fi
  if ollama_running; then
    found=1
    # the actually-published port, not the env default — the service may have
    # been started with a different OLLAMA_PORT
    local oport
    oport="$(compose_ro port ollama 11434 2>/dev/null | head -n1 | sed 's/.*://' || true)"
    oport="${oport:-${OLLAMA_PORT}}"
    echo "→ ollama: running — port ${oport}"
    local models
    models="$(compose_ro exec -T ollama ollama list 2>/dev/null | tail -n +2 | wc -l | tr -d '[:space:]' || true)"
    echo "  models: ${models:-?}  (manage with './mc ollama …')"
    echo "  register: http://host.docker.internal:${oport} (containers) / http://localhost:${oport} (host)"
  elif ollama_exists; then
    found=1
    echo "→ ollama: stopped — './mc ollama up' brings it back"
  else
    echo "→ ollama: absent (opt-in) — './mc ollama up' or './mc start --ollama=on' deploys it"
  fi
  if [[ -z "${found}" ]]; then echo "→ nothing deployed"; fi
}

cmd_logs() {
  local follow="" tail=100
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -f) follow=1 ;;
      -n) [[ $# -ge 2 ]] || { echo "error: -n needs a value" >&2; exit 1; }
          tail="$2"; shift ;;
      *) echo "error: unknown logs flag: $1" >&2; exit 1 ;;
    esac
    shift
  done

  require_docker
  if app_exists; then
    compose_ro logs ${follow:+-f} --tail "${tail}" mission-control
  else
    echo "error: nothing deployed — './mc start' first" >&2
    exit 1
  fi
}

cmd_shell() {
  require_docker
  if app_running; then
    compose_ro exec mission-control sh
  else
    echo "error: nothing running — './mc start' first" >&2
    exit 1
  fi
}

cmd_ollama() {
  require_docker

  # 'up'/'down' are ours, not ollama CLI verbs — manage the service itself
  # without redeploying the dashboard
  if [[ "${1:-}" == "up" ]]; then
    [[ $# -eq 1 ]] || { echo "error: './mc ollama up' takes no arguments" >&2; exit 1; }
    start_ollama
    return
  fi
  if [[ "${1:-}" == "down" ]]; then
    [[ $# -eq 1 ]] || { echo "error: './mc ollama down' takes no arguments" >&2; exit 1; }
    stop_ollama
    return
  fi

  if [[ "${1:-}" == "logs" ]]; then
    shift
    local follow="" tail=100
    while [[ $# -gt 0 ]]; do
      case "$1" in
        -f) follow=1 ;;
        -n) [[ $# -ge 2 ]] || { echo "error: -n needs a value" >&2; exit 1; }
            tail="$2"; shift ;;
        *) echo "error: unknown ollama logs flag: $1" >&2; exit 1 ;;
      esac
      shift
    done
    if ! ollama_exists; then
      echo "error: ollama service not deployed — './mc ollama up' first" >&2
      exit 1
    fi
    compose_ro logs ${follow:+-f} --tail "${tail}" ollama
    return
  fi

  if ! ollama_running; then
    echo "error: ollama service not running — './mc ollama up' first" >&2
    exit 1
  fi
  if [[ $# -eq 0 ]]; then
    compose_ro exec -T ollama ollama list
  elif [[ "$1" == "run" && -t 0 ]]; then
    compose_ro exec ollama ollama "$@"       # interactive chat needs a TTY
  else
    compose_ro exec -T ollama ollama "$@"
  fi
}

cmd_down() {
  local wipe="" arg
  for arg in "$@"; do
    case "${arg}" in
      --volumes) wipe=1 ;;
      *) echo "error: unknown down flag: ${arg}" >&2; exit 1 ;;
    esac
  done

  require_docker
  if [[ -n "${wipe}" ]]; then
    [[ -t 0 ]] || { echo "error: 'down --volumes' needs an interactive terminal to confirm" >&2; exit 1; }
    printf "remove the data volumes (mission-control-data, tailscale-state, ollama-models)? this is irreversible [y/N] "
    read -r answer
    case "${answer}" in
      y|Y|yes|YES) ;;
      *) echo "→ aborted"; exit 1 ;;
    esac
    # Order matters: `tailscale logout` invalidates the node key and removes the
    # machine entry while the container can still reach the coordination server.
    # After the state volume is gone the only way to clean up is the admin
    # console — and until someone does, the dead entry still holds the MagicDNS
    # name, so the next deploy comes up as mission-control-1 and every URL, ACL
    # and bookmark points at the corpse. A plain `down` keeps the volume, so the
    # node returns intact and must NOT be logged out.
    if ts_running; then
      echo "→ logging the node out of the tailnet (frees the MagicDNS name)"
      compose_ro exec -T tailscale tailscale logout >/dev/null 2>&1 || true
    fi
    echo "→ taking everything down (incl. volumes)"
    compose_ro --profile ollama down --volumes 2>/dev/null || true
    echo "✓ down — volumes removed"
  else
    echo "→ taking everything down"
    compose_ro --profile ollama down 2>/dev/null || true
    echo "✓ down"
  fi
}

cmd="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi

case "${cmd}" in
  start)        cmd_start "$@" ;;
  stop)         cmd_stop ;;
  restart)      cmd_stop; cmd_start "$@" ;;
  status)       cmd_status ;;
  logs)         cmd_logs "$@" ;;
  shell)        cmd_shell ;;
  ollama)       cmd_ollama "$@" ;;
  build)        require_docker; build_image ;;
  down)         cmd_down "$@" ;;
  help|-h|--help) usage ;;
  *) echo "error: unknown command: ${cmd}" >&2; usage >&2; exit 1 ;;
esac
