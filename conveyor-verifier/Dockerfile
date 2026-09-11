# Build context is the repo root (multi-module reactor). e.g.:
#   docker build -f conveyor-verifier/Dockerfile -t conveyor/conveyor-verifier .

FROM eclipse-temurin:21-jdk-alpine AS build
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
RUN ./mvnw -q -pl conveyor-verifier -am dependency:go-offline

COPY conveyor-contracts/src conveyor-contracts/src
COPY conveyor-common/src conveyor-common/src
COPY conveyor-verifier/src conveyor-verifier/src
RUN ./mvnw -q -pl conveyor-verifier -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S conveyor && adduser -S conveyor -G conveyor
# ViolationReportWriter writes here (VERIFIER_REPORT_DIR); docker-compose.yml mounts a named volume
# on top of it, but Docker only seeds a *new* named volume's content/ownership from what already
# exists at that path in the image, so this has to be created and owned by the non-root user here,
# before the volume is first created, or every write fails with AccessDeniedException (BUG-0024).
RUN mkdir -p /var/log/conveyor-verifier && chown -R conveyor:conveyor /var/log/conveyor-verifier
USER conveyor:conveyor
WORKDIR /app
COPY --from=build --chown=conveyor:conveyor /workspace/conveyor-verifier/target/conveyor-verifier.jar app.jar

EXPOSE 8086
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8086/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
