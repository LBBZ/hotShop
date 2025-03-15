# 使用参数化构建
FROM openjdk:17-jdk-alpine
ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}
COPY ./${MODULE}/target/${MODULE}-*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
