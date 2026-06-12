#!/usr/bin/env bash
# Mission Control manager — build and deploy the combined image (Angular +
# Spring Boot in one container), either behind tailscale (default) or as a
# plain docker container with a published port. Exactly one flavor runs at a
# time: starting one cleanly stops the other.
#
# Usage:
#   ./mc start                  # deploy behind tailscale (default flavor)
#   ./mc start --build          # rebuild the image first
#   ./mc start --ts=off         # plain docker on http://localhost:8080
#   ./mc help                   # full usage
set -euo pipefail

IMAGE="${IMAGE:-hermes-mission-control}"
TAG="${TAG:-latest}"
NAME="${NAME:-mission-control}"
PORT="${PORT:-8080}"
MC_DATA_MODE="${MC_DATA_MODE:-live}"
MC_CONTAINER_FILTER="${MC_CONTAINER_FILTER:-hermes}"
DATA_VOLUME="${DATA_VOLUME:-mission-control-data}"
MC_NO_KEYCHAIN="${MC_NO_KEYCHAIN:-}"

cd "$(dirname "$0")"

COMPOSE_FILE="deploy/tailscale/docker-compose.yml"
ENV_FILE="deploy/tailscale/.env"
COMPOSE=(docker compose -p mission-control -f "${COMPOSE_FILE}")

usage() {
  cat <<EOF
mc — Mission Control manager (combined image: ${IMAGE}:${TAG})

Usage: ./mc <command> [flags]

Commands:
  start [--build] [--ts=on|off] [--mock] [--port=N] [--no-socket] [--no-keychain]
                     deploy — default --ts=on (behind tailscale, tailnet-only);
                     --ts=off runs plain docker with a published port
  stop               stop whichever flavor is running
  restart [...]      stop + start (same flags as start)
  status             which flavor is running, container states, port/URL
  logs [-f] [-n N]   app container logs (default: last 100 lines)
  shell              interactive sh in the app container
  build              build the image only
  down [--volumes]   stop everything; --volumes also removes the data volumes
  help               this text

Examples:
  ./mc start                  # tailscale flavor — http://mission-control.<tailnet>.ts.net
  ./mc start --build          # rebuild the image, then deploy
  ./mc start --ts=off         # plain docker — http://localhost:${PORT}
  ./mc start --ts=off --mock --port=9000   # demo mode on a custom port
  ./mc logs -f
  ./mc down --volumes

Env overrides: IMAGE TAG NAME PORT MC_DATA_MODE MC_CONTAINER_FILTER DATA_VOLUME
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
  export DOCKER_CONFIG="${DOCKER_CONFIG_TEMP}"
  printf '%s\n' '{"auths":{}}' > "${DOCKER_CONFIG}/config.json"

  # shellcheck disable=SC2064
  trap '[[ -n "${DOCKER_CONFIG_TEMP}" ]] && rm -rf "${DOCKER_CONFIG_TEMP}"; if [[ -n "${DOCKER_CONFIG_ORIG}" ]]; then export DOCKER_CONFIG="${DOCKER_CONFIG_ORIG}"; else unset DOCKER_CONFIG; fi' EXIT
}

# read-only compose calls must work without deploy/tailscale/.env — feed the
# ${TS_AUTHKEY:?} interpolation a dummy value (never used for 'up')
compose_ro() {
  TS_AUTHKEY="${TS_AUTHKEY:-unset}" "${COMPOSE[@]}" "$@"
}

require_docker() {
  command -v docker >/dev/null || { echo "error: docker not found on PATH" >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "error: docker daemon not reachable" >&2; exit 1; }
}

image_exists()   { docker image inspect "${IMAGE}:${TAG}" >/dev/null 2>&1; }
plain_exists()   { docker container inspect "${NAME}" >/dev/null 2>&1; }
plain_running()  { [[ "$(docker container inspect -f '{{.State.Running}}' "${NAME}" 2>/dev/null)" == "true" ]]; }
ts_exists()      { [[ -n "$(compose_ro ps -aq 2>/dev/null || true)" ]]; }
ts_running()     { [[ -n "$(compose_ro ps -q 2>/dev/null || true)" ]]; }

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
    echo "  cp deploy/tailscale/.env.example deploy/tailscale/.env" >&2
    echo "  then fill in TS_AUTHKEY (admin console → Settings → Keys → auth key;" >&2
    echo "  reusable + tag:server recommended)" >&2
    exit 1
  fi
  ensure_image "$1"
  if plain_exists; then
    echo "→ removing plain container ${NAME} (switching to tailscale flavor)"
    docker rm -f "${NAME}" >/dev/null
  fi
  echo "→ bringing up the tailscale flavor"
  maybe_bypass_keychain
  "${COMPOSE[@]}" up -d
  echo "✓ deployed — http://mission-control.<tailnet>.ts.net  (tailnet only, no host ports)"
  echo "  find the exact URL with './mc status', or:"
  echo "  docker compose -p mission-control -f ${COMPOSE_FILE} exec tailscale tailscale status"
}

start_plain() {  # $1 = --build flag, $2 = --mock flag, $3 = --no-socket flag
  ensure_image "$1"
  if ts_exists; then
    echo "→ taking down the tailscale flavor (switching to plain docker)"
    compose_ro down
  fi

  local mode="${MC_DATA_MODE}"
  if [[ -n "$2" ]]; then mode="mock"; fi

  local socket_args=(-v /var/run/docker.sock:/var/run/docker.sock)
  if [[ -n "$3" ]]; then
    socket_args=()
    echo "→ socket mount disabled — container management will be unavailable"
  fi

  echo "→ replacing container ${NAME}"
  docker rm -f "${NAME}" >/dev/null 2>&1 || true
  docker run -d --name "${NAME}" \
    -p "${PORT}:8080" \
    ${socket_args[@]+"${socket_args[@]}"} \
    -v "${DATA_VOLUME}:/data" \
    -e MC_DATA_MODE="${mode}" \
    -e MC_CONTAINER_FILTER="${MC_CONTAINER_FILTER}" \
    --restart unless-stopped \
    "${IMAGE}:${TAG}" >/dev/null

  echo "✓ deployed — http://localhost:${PORT}  (dataMode=${mode}, filter=${MC_CONTAINER_FILTER})"
  if [[ -z "$3" ]]; then socket_note; fi
}

cmd_start() {
  local ts="on" build="" mock="" no_socket="" arg
  for arg in "$@"; do
    case "${arg}" in
      --build)     build=1 ;;
      --ts=on)     ts="on" ;;
      --ts=off)    ts="off" ;;
      --mock)      mock=1 ;;
      --port=*)    PORT="${arg#--port=}" ;;
      --no-socket) no_socket=1 ;;
      --no-keychain) MC_NO_KEYCHAIN=1 ;;
      *) echo "error: unknown start flag: ${arg}" >&2; exit 1 ;;
    esac
  done

  require_docker
  if [[ "${ts}" == "on" ]]; then
    if [[ -n "${mock}${no_socket}" ]]; then
      echo "→ note: --mock/--port/--no-socket only apply to --ts=off — ignored"
    fi
    start_ts "${build}"
  else
    start_plain "${build}" "${mock}" "${no_socket}"
  fi
}

cmd_stop() {
  require_docker
  local stopped=""
  if ts_exists; then
    echo "→ taking down the tailscale flavor"
    compose_ro down
    stopped=1
  fi
  if plain_exists; then
    echo "→ removing container ${NAME}"
    docker rm -f "${NAME}" >/dev/null
    stopped=1
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
      if [[ -n "${dns}" ]]; then echo "  url: http://${dns}"; fi
    else
      echo "  tailscale: sidecar not responding (still starting?)"
    fi
  fi
  if plain_exists; then
    found=1
    echo "→ flavor: plain docker"
    docker ps -a --filter "name=^/${NAME}$" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    local port
    port="$(docker port "${NAME}" 8080/tcp 2>/dev/null | head -n1 | sed 's/.*://')"
    if [[ -n "${port}" ]]; then echo "  url: http://localhost:${port}"; fi
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
  if ts_exists; then
    compose_ro logs ${follow:+-f} --tail "${tail}" mission-control
  elif plain_exists; then
    docker logs ${follow:+-f} --tail "${tail}" "${NAME}"
  else
    echo "error: nothing deployed — './mc start' first" >&2
    exit 1
  fi
}

cmd_shell() {
  require_docker
  if ts_running; then
    compose_ro exec mission-control sh
  elif plain_running; then
    docker exec -it "${NAME}" sh
  else
    echo "error: nothing running — './mc start' first" >&2
    exit 1
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
    printf "remove the data volumes (%s, tailscale-state)? this is irreversible [y/N] " "${DATA_VOLUME}"
    read -r answer
    case "${answer}" in
      y|Y|yes|YES) ;;
      *) echo "→ aborted"; exit 1 ;;
    esac
    echo "→ taking everything down (incl. volumes)"
    compose_ro down --volumes 2>/dev/null || true
    docker rm -f "${NAME}" >/dev/null 2>&1 || true
    docker volume rm "${DATA_VOLUME}" >/dev/null 2>&1 || true
    echo "✓ down — volumes removed"
  else
    echo "→ taking everything down"
    compose_ro down 2>/dev/null || true
    docker rm -f "${NAME}" >/dev/null 2>&1 || true
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
  build)        require_docker; build_image ;;
  down)         cmd_down "$@" ;;
  help|-h|--help) usage ;;
  *) echo "error: unknown command: ${cmd}" >&2; usage >&2; exit 1 ;;
esac
