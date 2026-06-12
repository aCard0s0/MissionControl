#!/usr/bin/env bash
# Build the combined Mission Control image (Angular + Spring Boot in one
# container) and (re)deploy it. Defaults to live mode — no mock data.
#
# Usage:
#   scripts/deploy-docker.sh                      # build + run on :8080
#   PORT=9000 scripts/deploy-docker.sh            # custom host port
#   MC_DATA_MODE=mock scripts/deploy-docker.sh    # demo deployment with mock data
#   NO_SOCKET=1 scripts/deploy-docker.sh          # do not mount the docker socket
#   scripts/deploy-docker.sh --build-only         # just build the image
set -euo pipefail

IMAGE="${IMAGE:-hermes-mission-control}"
TAG="${TAG:-latest}"
NAME="${NAME:-mission-control}"
PORT="${PORT:-8080}"
MC_DATA_MODE="${MC_DATA_MODE:-live}"
MC_CONTAINER_FILTER="${MC_CONTAINER_FILTER:-hermes}"
DATA_VOLUME="${DATA_VOLUME:-mission-control-data}"

cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "error: docker not found on PATH" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "error: docker daemon not reachable" >&2; exit 1; }

echo "→ building ${IMAGE}:${TAG}"
docker build -t "${IMAGE}:${TAG}" .

if [[ "${1:-}" == "--build-only" ]]; then
  echo "✓ image built: ${IMAGE}:${TAG}"
  exit 0
fi

SOCKET_ARGS=(-v /var/run/docker.sock:/var/run/docker.sock)
if [[ -n "${NO_SOCKET:-}" ]]; then
  SOCKET_ARGS=()
  echo "→ socket mount disabled — container management will be unavailable"
fi

echo "→ replacing container ${NAME}"
docker rm -f "${NAME}" >/dev/null 2>&1 || true
docker run -d --name "${NAME}" \
  -p "${PORT}:8080" \
  "${SOCKET_ARGS[@]}" \
  -v "${DATA_VOLUME}:/data" \
  -e MC_DATA_MODE="${MC_DATA_MODE}" \
  -e MC_CONTAINER_FILTER="${MC_CONTAINER_FILTER}" \
  --restart unless-stopped \
  "${IMAGE}:${TAG}" >/dev/null

echo "✓ deployed — http://localhost:${PORT}  (dataMode=${MC_DATA_MODE}, filter=${MC_CONTAINER_FILTER})"
echo "  note: mounting docker.sock grants the container daemon-level (root-equivalent) access;"
echo "  in production put a restricted socket proxy in front (see docs/architecture.md)"
