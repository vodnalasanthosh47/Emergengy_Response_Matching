# syntax=docker/dockerfile:1
# Request Service image (spec §7.1-G). Build from the REPOSITORY ROOT, because the
# service needs the shared `common` module:
#     docker build -f services/request_service/Dockerfile -t emergency/request-service:local .

# ---- build stage: Maven + JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# The whole Java tree (the root .dockerignore keeps out Go code, .git and target/). Every
# module listed in the root pom must be present for Maven to load the build.
COPY pom.xml ./
COPY common common
COPY services services
# The cache mount keeps downloaded dependencies between builds, so a code change does
# not re-download them. Tests run in `make test`, not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/request_service -am -DskipTests package \
 && cp services/request_service/target/request-service-*.jar /app.jar

# ---- runtime stage: JRE only ----
FROM eclipse-temurin:21-jre
# Run as an unprivileged user, never as root.
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8080
# Size the heap from the container's memory limit (mem_limit / Kubernetes limits).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Ready = RabbitMQ and the storage coordinator are reachable (see application.yml).
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
