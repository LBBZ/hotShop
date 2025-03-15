# 使用参数化构建
ARG MODULE=admin
FROM openjdk:17-jre-alpine
COPY ./${MODULE}/target/${MODULE}-*.jar /app.jar
CMD ["java", "-jar", "/app.jar"]