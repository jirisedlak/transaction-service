# syntax=docker/dockerfile:1.7

# ---------- build stage: compile and package with the Maven wrapper ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# resolve dependencies first so they are cached until pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline

# tests run in CI/`mvnw verify`; the image build only packages (override with --build-arg SKIP_TESTS=false)
ARG SKIP_TESTS=true
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B package -DskipTests=${SKIP_TESTS} -DskipOpenApi=true \
    && java -Djarmode=tools -jar target/transaction-service-*.jar extract --layers --application-filename transaction-service.jar --destination target/extracted

# ---------- runtime stage: JRE only, layered for cache-friendly rebuilds ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 --create-home app \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# least-changing layers first
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./

USER app
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "transaction-service.jar"]
