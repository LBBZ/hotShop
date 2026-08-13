# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS builder

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

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c

ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}
RUN addgroup -S -g 10001 hotshop \
    && adduser -S -D -H -u 10001 -G hotshop hotshop
COPY --from=builder --chown=10001:10001 /workspace/app.jar /app.jar
# The Alpine Temurin C2 compiler crashes reproducibly under the local Docker Desktop VM.
# C1 keeps the reproducible local stack stable; production images should benchmark their own JVM.
USER 10001:10001
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-jar", "/app.jar"]
