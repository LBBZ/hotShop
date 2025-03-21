# 使用多阶段构建优化镜像大小
FROM openjdk:17-jdk-alpine as builder
ARG MODULE
WORKDIR /app
COPY ${MODULE}/pom.xml .
COPY ${MODULE}/src ./src
# 如果使用Maven可在此添加构建命令
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# 最终运行时镜像
FROM openjdk:17-jdk-alpine
ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}
COPY --from=builder /app/dependencies/ ./dependencies
COPY --from=builder /app/spring-boot-loader/ ./spring-boot-loader
COPY --from=builder /app/snapshot-dependencies/ ./snapshot-dependencies
COPY --from=builder /app/application/ ./application
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "org.springframework.boot.loader.JarLauncher"]