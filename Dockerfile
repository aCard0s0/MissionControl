# Combined Mission Control image — Spring Boot serves both the API and the
# Angular build on one port, one origin, one container.

# ── frontend build ───────────────────────────────────────────────────────────
FROM node:24-alpine AS fe-build
WORKDIR /fe
COPY applications/mission-control-fe/package.json applications/mission-control-fe/package-lock.json ./
RUN npm ci
COPY applications/mission-control-fe/ ./
RUN npx ng build

# ── backend build (bundles the frontend into classpath:/static) ─────────────
FROM maven:3.9-eclipse-temurin-24 AS be-build
WORKDIR /srv
COPY applications/mission-control-server/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY applications/mission-control-server/src ./src
COPY --from=fe-build /fe/dist/MissionControl/browser ./src/main/resources/static
# the static config.js is a dev artifact — /config.js is served dynamically
RUN rm -f ./src/main/resources/static/config.js && mvn -q -B -DskipTests package

# ── runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:24-jre-alpine
WORKDIR /app
COPY --from=be-build /srv/target/mission-control-server-*.jar app.jar

ENV MC_DATA_MODE=live \
    MC_API_BASE_URL="" \
    MC_DOCKER_SOCKET=unix:///var/run/docker.sock \
    MC_DB_PATH=/data/mission-control.db

VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
