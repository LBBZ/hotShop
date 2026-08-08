# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-alpine AS builder

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

FROM eclipse-temurin:21-jre-alpine

ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}
COPY --from=builder /workspace/app.jar /app.jar
# The Alpine Temurin C2 compiler crashes reproducibly under the local Docker Desktop VM.
# C1 keeps the reproducible local stack stable; production images should benchmark their own JVM.
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-jar", "/app.jar"]
