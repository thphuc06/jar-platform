# syntax=docker/dockerfile:1
#
# jars-api image (P3.1). From apps/jars-api:
#
#   docker build -t jars-api .                    plain image, needs nothing else
#   docker build --target aot -t jars-api \       + JDK 25 AOT cache: starts ~2.4x faster (measured, P3.1);
#     --secret id=training_db_password,env=PW \     the training run needs a THROWAWAY PostgreSQL (local compose,
#     --build-arg TRAINING_DB_URL=jdbc:postgresql://host.docker.internal:5432/jars .   or a CI service container)
#
# For Fargate Graviton (ADR-0006) build on/for linux/arm64: an AOT cache only works on the JVM and CPU architecture
# that created it. Base images are pinned by digest: the same Dockerfile always builds on the same JDK and OS
# packages; a newer base arrives as a reviewed change (Dependabot), not silently.
ARG JDK_IMAGE=eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:2e6f25a996cdbd9344d03372b7195e7f159a1c83a2199da4102e164ebb2b2edd
ARG JRE_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble@sha256:af7b616f7f2c23bcff0bf706daf8aaab088c6f1843fdab637dcc6bd70ae573c3

# --- build: compile and split the jar into layers (tests run in CI before the image is built) -----------------------
FROM ${JDK_IMAGE} AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# the Maven repository lives in a BuildKit cache, not in a layer: rebuilds do not download the internet again.
# No --launcher: app.jar + lib/*.jar on the plain JVM class path, which the AOT cache (like CDS) requires; classes
# loaded by Spring Boot's own launcher class loader cannot be cached.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q package -DskipTests -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar target/jars-api-*.jar extract --layers --destination /extracted --application-filename app.jar

# --- runtime: JRE only, no build tools, no source -------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime
WORKDIR /app
# least to most often changed: a code change only replaces the small application layer (app.jar, ~0.25 MB)
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# -XX:MaxRAMPercentage=75: heap from the task's memory limit, leaving room for metaspace, threads and buffers;
# -XX:+ExitOnOutOfMemoryError: a broken JVM dies (ECS starts a new task) instead of limping on;
# -XX:-UsePerfData: no /tmp/hsperfdata file, the root filesystem is read-only in ECS;
# -XX:+UseSerialGC: what the JVM picks by itself for 0.5 vCPU / 1 GB anyway, written down so the AOT training run on
# a big build machine (which would pick G1) uses the same GC as the task, otherwise the cache can be refused
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:-UsePerfData -XX:+UseSerialGC"

# non-root with a fixed numeric id: the files above stay owned by root and read-only for the process
USER 10001:10001
EXPOSE 8080
# exec form: SIGTERM reaches java directly (as PID 1 locally, or forwarded by ECS's init process with
# initProcessEnabled); a "sh -c" wrapper would swallow it and the task would be SIGKILLed after stopTimeout
ENTRYPOINT ["java", "-jar", "app.jar"]

# --- aot: the runtime image plus an AOT cache from one training start (JEP 514/515) ----------------------------------
FROM runtime AS aot
ARG TRAINING_DB_URL
ARG TRAINING_DB_USER=jars
USER root
# The training run starts the application up to a refreshed context and exits; it needs a real database because
# Flyway, Hibernate's schema validation and Spring Modulith's JDBC outbox open a connection at startup. The password
# comes in as a BuildKit secret, so it is not stored in the image history like an ARG would be.
RUN --mount=type=secret,id=training_db_password,env=SPRING_DATASOURCE_PASSWORD \
    test -n "$TRAINING_DB_URL" \
 && java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
         -Dspring.datasource.url="$TRAINING_DB_URL" -Dspring.datasource.username="$TRAINING_DB_USER" \
         -jar app.jar \
 && test -s app.aot
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:-UsePerfData -XX:+UseSerialGC -XX:AOTCache=app.aot"
USER 10001:10001

# --- default target: the plain image, so "docker build ." needs no database ----------------------------------------
FROM runtime AS plain
