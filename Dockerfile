# 仅需运行时环境
FROM openjdk:17-jre-slim

# 动态参数
ARG MODULE=admin
ENV SPRING_PROFILES_ACTIVE=${PROFILE:-dev}

# 复制本地构建的JAR包
COPY ./${MODULE}/target/${MODULE}-*.jar /app/app.jar

CMD ["java", "-jar", "/app/app.jar"]