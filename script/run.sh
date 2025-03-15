#!/bin/bash
# ------------------------------------------------------------
# hotShop 极简启动脚本（无需.env文件）
# 版本：1.0
# ------------------------------------------------------------

# 清理旧容器
docker-compose down

# 构建并启动
mvn clean package -DskipTests && \
docker-compose up --build -d

# 查看日志
docker-compose logs -f