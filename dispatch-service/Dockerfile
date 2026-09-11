# Build context is the repo root (multi-module reactor). e.g.:
#   docker build -f dispatch-service/Dockerfile -t conveyor/dispatch-service .

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
RUN ./mvnw -q -pl dispatch-service -am dependency:go-offline

COPY conveyor-contracts/src conveyor-contracts/src
COPY conveyor-common/src conveyor-common/src
COPY dispatch-service/src dispatch-service/src
RUN ./mvnw -q -pl dispatch-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S conveyor && adduser -S conveyor -G conveyor
USER conveyor:conveyor
WORKDIR /app
COPY --from=build --chown=conveyor:conveyor /workspace/dispatch-service/target/dispatch-service.jar app.jar

EXPOSE 8085
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8085/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
