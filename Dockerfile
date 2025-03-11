# 通用模板 Dockerfile（保存在项目根目录）
FROM openjdk:17-jdk-alpine
ARG MODULE_DIR
ARG JAR_FILE=${MODULE_DIR}/target/*.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]