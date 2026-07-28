# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace
COPY . .
ARG MODULE
RUN --mount=type=cache,id=hotshop-maven-repository,target=/root/.m2,sharing=locked \
    chmod +x ./mvnw && \
    find /root/.m2 -name '*.lastUpdated' -delete && \
    test -n "${MODULE}" && \
    ./mvnw -B -pl "${MODULE}" -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}
COPY --from=builder /workspace/${MODULE}/target/${MODULE}-*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
