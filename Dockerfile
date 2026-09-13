# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS builder

WORKDIR /workspace
COPY . .
ARG MODULE
RUN --mount=type=cache,id=hotshop-maven-repository,target=/root/.m2,sharing=locked \
    chmod +x ./mvnw && \
    find /root/.m2 -name '*.lastUpdated' -delete && \
    test -n "${MODULE}" && \
    ./mvnw -B -pl "${MODULE}" -am clean package -DskipTests && \
    EXECUTABLE_JAR="$(find "${MODULE}/target" -maxdepth 1 -name "${MODULE}-*-exec.jar" -print -quit)" && \
    if [ -z "${EXECUTABLE_JAR}" ]; then \
      EXECUTABLE_JAR="$(find "${MODULE}/target" -maxdepth 1 -name "${MODULE}-*.jar" -print -quit)"; \
    fi && \
    test -n "${EXECUTABLE_JAR}" && \
    cp "${EXECUTABLE_JAR}" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699

ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}
RUN apk add --no-cache --upgrade \
        libcrypto3=3.5.8-r0 \
        libexpat=2.8.4-r0 \
        libssl3=3.5.8-r0 \
        openssl=3.5.8-r0 \
    && addgroup -S -g 10001 hotshop \
    && adduser -S -D -H -u 10001 -G hotshop hotshop
COPY --from=builder --chown=10001:10001 /workspace/app.jar /app.jar
# The Alpine Temurin C2 compiler crashes reproducibly under the local Docker Desktop VM.
# C1 keeps the reproducible local stack stable; production images should benchmark their own JVM.
USER 10001:10001
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-jar", "/app.jar"]
