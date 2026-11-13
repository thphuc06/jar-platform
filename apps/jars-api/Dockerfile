# syntax=docker/dockerfile:1.7
#
# Build (from the repository root or from apps/jars-api):
#   docker buildx build --platform linux/arm64 -t jars-api:dev apps/jars-api
#
# Multi-stage and multi-platform on purpose: the jar is compiled on the machine that runs the build
# ($BUILDPLATFORM, no emulation) and only the small runtime stage is for the target platform (arm64 on Fargate Graviton).
# The runtime stage has no RUN instruction, so building for arm64 on an x86 laptop or runner needs no QEMU.

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# cache the Maven repository between builds
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
# tests, formatting and coverage run in CI (./mvnw verify) before an image is built
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true package \
    && java -Djarmode=tools -jar target/jars-api-*.jar extract --layers --launcher --destination /layers

# A full JDK, not the -jre image: eclipse-temurin:21-jre is a trimmed runtime without the jdk.random module, so
# RandomGeneratorFactory.of("L64X128MixRandom") (goals simulation) fails there with "No implementation of the random
# number generator algorithm". A jlink'd runtime would need a RUN step on the target platform (QEMU on an x86 build host).
FROM eclipse-temurin:21-jdk AS runtime
WORKDIR /app
# dependencies change rarely, application code often: separate layers keep image pushes small
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./

# non-root without a RUN step: a numeric uid needs no passwd entry
USER 10001:10001
# Tomcat and the JVM write temporary files under /tmp; the ECS task mounts a writable volume there so the root
# filesystem can stay read-only
VOLUME /tmp
EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=aws
# size the heap from the container limit; crash (and let ECS restart the task) instead of limping on after an OOM
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
