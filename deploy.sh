#!/bin/bash
# ------------------------------------------------------------
# hotShop 一键部署脚本
# 功能：支持启动/停止/重建/清理全栈服务
# 版本：1.0
# ------------------------------------------------------------

# 配置参数
COMPOSE_FILE="docker-compose.yml"
ENV_FILE=".env"
SERVICE_NAME="all"  # 默认操作所有服务

# 颜色定义
RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
BLUE='\033[34m'
RESET='\033[0m'

# -------------------------- 功能函数 --------------------------

# 构建项目
build_project() {
    echo -e "${BLUE}🛠 构建项目...${RESET}"
    mvn clean package
}

# 启动服务
start_services() {
    echo -e "${GREEN}▶ 启动服务...${RESET}"
    docker-compose -f ${COMPOSE_FILE} --env-file ${ENV_FILE} up -d
}

# 停止服务
stop_services() {
    echo -e "${RED}■ 停止服务...${RESET}"
    docker-compose -f ${COMPOSE_FILE} down
}

# 重建服务
rebuild_services() {
    echo -e "${BLUE}⟳ 重建服务...${RESET}"
    docker-compose -f ${COMPOSE_FILE} --env-file ${ENV_FILE} up -d --build --force-recreate
}

# 清理资源
clean_resources() {
    echo -e "${YELLOW}♻ 清理资源...${RESET}"
    docker system prune -af
    rm -rf ./admin/target ./portal/target
}

# 查看日志
show_logs() {
    echo -e "${BLUE}📜 查看日志：${RESET}"
    docker-compose -f ${COMPOSE_FILE} logs -f ${SERVICE_NAME}
}

# 显示帮助
show_help() {
    echo -e "${GREEN}使用说明：${RESET}"
    echo "  ./deploy.sh [命令] [选项]"
    echo
    echo "${GREEN}可用命令：${RESET}"
    echo "  start    启动服务"
    echo "  stop     停止服务"
    echo "  restart  重启服务"
    echo "  rebuild  重建服务（强制重新构建镜像）"
    echo "  clean    清理所有Docker资源"
    echo "  logs     查看服务日志"
    echo
    echo "${GREEN}常用选项：${RESET}"
    echo "  -e <env>  指定环境 (dev/prod)，默认：dev"
    echo "  -s <name> 指定服务名称，默认：所有服务"
    echo "  -b        构建项目"
    echo "  -h        显示帮助信息"
}

# -------------------------- 主流程 --------------------------

# 解析参数
while getopts "e:s:bh" opt; do
    case $opt in
        e) export PROFILE=${OPTARG} ;;
        s) SERVICE_NAME=${OPTARG} ;;
        b) BUILD_PROJECT=true ;;
        h) show_help; exit 0 ;;
        \?) echo -e "${RED}无效选项！${RESET}"; exit 1 ;;
    esac
done

# 设置默认环境
if [ -z "$PROFILE" ]; then
    PROFILE="dev"
fi

# 执行命令
case "$1" in
    start)
        if [ "$BUILD_PROJECT" = true ]; then
            build_project
        fi
        start_services
        ;;
    stop)
        stop_services
        ;;
    restart)
        stop_services
        if [ "$BUILD_PROJECT" = true ]; then
            build_project
        fi
        start_services
        ;;
    rebuild)
        if [ "$BUILD_PROJECT" = true ]; then
            build_project
        fi
        rebuild_services
        ;;
    clean)
        clean_resources
        ;;
    logs)
        show_logs
        ;;
    *)
        show_help
        exit 1
        ;;
esac

echo -e "${GREEN}✅ 操作完成！${RESET}"