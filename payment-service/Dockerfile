# Build context is the repo root (multi-module reactor). e.g.:
#   docker build -f payment-service/Dockerfile -t conveyor/payment-service .

# Phase 13 (CLAUDE.md §7): base images pinned by digest, not just tag, so a rebuild of
# this exact Dockerfile can never silently pick up a mutated eclipse-temurin:21-*-alpine tag --
# required for "images are reproducible: same commit -> same digest" (PLAN.md Phase 13).
FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY conveyor-contracts/pom.xml conveyor-contracts/pom.xml
COPY conveyor-common/pom.xml conveyor-common/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY saga-orchestrator/pom.xml saga-orchestrator/pom.xml
COPY dispatch-service/pom.xml dispatch-service/pom.xml
COPY conveyor-verifier/pom.xml conveyor-verifier/pom.xml
RUN ./mvnw -q -pl payment-service -am dependency:go-offline

COPY conveyor-contracts/src conveyor-contracts/src
COPY conveyor-common/src conveyor-common/src
COPY payment-service/src payment-service/src
RUN ./mvnw -q -pl payment-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
# BUG-0035 (Trivy, Phase 13): pin exact patched Alpine package versions rather than a bare
# `apk upgrade` -- an unpinned upgrade would silently pick up whatever Alpine's repo serves on
# build day, which is exactly the kind of non-determinism "images are reproducible: same
# commit -> same digest" (PLAN.md Phase 13) rules out.
RUN apk add --no-cache --upgrade libcrypto3=3.5.8-r0 libssl3=3.5.8-r0 libexpat=2.8.4-r0
RUN addgroup -S conveyor && adduser -S conveyor -G conveyor
# Numeric, not "conveyor:conveyor" (BUG-0033): Kubernetes's runAsNonRoot admission check
# cannot verify a symbolic USER against the image config, only a numeric one -- addgroup -S/
# adduser -S already assign fixed system UID/GID 100/101 on Alpine, this just spells them out.
USER 100:101
WORKDIR /app
COPY --from=build --chown=conveyor:conveyor /workspace/payment-service/target/payment-service.jar app.jar

EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8083/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
