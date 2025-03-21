# 构建阶段
FROM openjdk:17-jdk-alpine as builder
ARG MODULE
WORKDIR /app

# 复制 POM 并下载依赖
COPY ${MODULE}/pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# 复制源码并编译
COPY ${MODULE}/src ./src
RUN mvn clean package -DskipTests

# 提取 JAR 并分层
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# 运行阶段
FROM openjdk:17-jre-alpine
ARG MODULE
ARG PROFILE=""
ENV SPRING_PROFILES_ACTIVE=${PROFILE}

WORKDIR /app
# 复制分层内容
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "org.springframework.boot.loader.JarLauncher"]