# ---------------------------------------------------------------------------
# Build stage.
# Build context is the repository root (see deploy/docker-compose.yml), so only
# the aggregator POM and the modules this service actually depends on
# ('bitstorm-svr-api' -> 'bitstorm-svr-common' -> this service) are copied in.
# The aggregator POM is installed non-recursively (-N) so the other sibling
# modules are NOT required to be present to build this service.
# ---------------------------------------------------------------------------
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY bitstorm-svr-api ./bitstorm-svr-api
COPY bitstorm-svr-common ./bitstorm-svr-common
COPY bitstorm-svr-msgcenter ./bitstorm-svr-msgcenter

RUN mvn -B -N -f pom.xml install -DskipTests \
 && mvn -B -f bitstorm-svr-api/pom.xml install -DskipTests \
 && mvn -B -f bitstorm-svr-common/pom.xml install -DskipTests \
 && mvn -B -f bitstorm-svr-msgcenter/pom.xml package -DskipTests

# ---------------------------------------------------------------------------
# Runtime stage: slim JRE, non-root, container-aware heap sizing.
# ---------------------------------------------------------------------------
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app

# curl is used by the container healthcheck to poll the actuator endpoint.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r app && useradd -r -g app app
COPY --from=build /workspace/bitstorm-svr-msgcenter/target/bitstorm-svr-msgcenter*.jar /app/app.jar
USER app

EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
