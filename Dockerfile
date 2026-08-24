# Combined Mission Control image — Spring Boot serves both the API and the
# Angular build on one port, one origin, one container.

# ── frontend build ───────────────────────────────────────────────────────────
FROM node:24-alpine AS fe-build
WORKDIR /fe
COPY applications/mission-control-fe/package.json applications/mission-control-fe/package-lock.json ./
RUN npm ci
COPY applications/mission-control-fe/ ./
RUN npm test -- --watch=false && npx ng build

# ── backend build (bundles the frontend into classpath:/static) ─────────────
FROM maven:3.9-eclipse-temurin-24 AS be-build
WORKDIR /srv
COPY applications/mission-control-server/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY applications/mission-control-server/src ./src
COPY --from=fe-build /fe/dist/mission-control/browser ./src/main/resources/static
# the static config.js is a dev artifact — /config.js is served dynamically
RUN rm -f ./src/main/resources/static/config.js && mvn -q -B package

# ── runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:24-jre-alpine
WORKDIR /app
RUN apk add --no-cache docker-cli docker-cli-compose
COPY --from=be-build /srv/target/mission-control-server-*.jar app.jar

ENV MC_API_BASE_URL="" \
    MC_DOCKER_SOCKET=unix:///var/run/docker.sock \
    MC_DB_PATH=/data/mission-control.db \
    MC_MCP_STACK_DIR=/data/mcp-stacks

VOLUME /data
EXPOSE 8080
# Heap sized from the container's cgroup memory limit (set in compose), not host
# RAM — the container shares the tailscale netns and would otherwise see host
# totals. CPU pool sizing is left to the cgroup CPU quota (compose `cpus`); do
# not pin -XX:ActiveProcessorCount. Fail fast on OOM so restart policy recovers.
#
# MaxRAMPercentage governs the heap alone, and everything else this process needs
# sits on top of it: metaspace, code cache, thread stacks, GC structures and the
# transport's buffers measured together at ~188 MiB. That figure is close to
# fixed, so the share left to it has to grow as the limit shrinks — 75% of the
# old 1g and 50% of today's 512m both leave it the same ~68 MiB of headroom.
# Move this and compose's `mem_limit` together, or the two disagree about how
# much of the cgroup the heap may claim and the first full heap is an OOM kill.
# --enable-native-access: sqlite-jdbc loads its native library through
# System::load, which on Java 24 prints a four-line unformatted warning to stderr
# in the middle of startup. Granting it up front is the documented way to silence
# that, and it is required rather than merely tidy once JEP 472 blocks the call.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-XX:+ExitOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
