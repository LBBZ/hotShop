# 统一构建所有模块
FROM maven:3.9.9-openjdk-17 AS build
WORKDIR /app
COPY . .
# 通过构建参数指定激活的 profile
ARG PROFILE=dev
RUN mvn clean package -DskipTests -P${PROFILE}

# 按需运行指定服务
FROM openjdk:17-jre-slim
# 通过环境变量指定运行的服务
ENV SERVICE_TYPE=portal
ENV SPRING_PROFILES_ACTIVE=${PROFILE:-dev}

# 复制所有模块的构建结果
COPY --from=build /app/admin/target/admin-*.jar /app/admin.jar
COPY --from=build /app/portal/target/portal-*.jar /app/portal.jar

# 动态启动逻辑
CMD case "$SERVICE_TYPE" in \
    "admin") java -jar /app/admin.jar ;; \
    "portal") java -jar /app/portal.jar ;; \
    *) echo "未知服务类型: $SERVICE_TYPE"; exit 1 ;; \
    esac