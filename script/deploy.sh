#!/bin/bash
# ------------------------------------------------------------
# hotShop 智能部署脚本
# 功能：启动/停止/重启/清理/日志查看/健康检查
# 版本：2.0
# ------------------------------------------------------------

# 配置参数
COMPOSE_FILE="docker-compose.yml"
SERVICES=("mysql" "redis" "task" "admin" "portal")  # 服务列表
DEFAULT_ENV=""                            # 默认环境

# 颜色定义
RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'
BLUE='\033[34m'; CYAN='\033[36m'; RESET='\033[0m'

# -------------------------- 核心功能 --------------------------

# 显示帮助信息
show_help() {
    echo -e "${CYAN}
============================================================
hotShop 智能部署脚本 - 帮助文档
版本: 2.0
功能: 构建/启动/停止/重启/清理/日志查看/健康检查
用法: ./script.sh [选项]
选项:
  -a <操作>  指定操作类型，可选值:
               build     - 构建项目
               start     - 启动服务
               stop      - 停止服务
               restart   - 重启服务
               rebuild   - 重建服务
               clean     - 清理资源
               logs      - 查看日志
               status    - 检查服务状态
  -e <环境>  指定部署环境，默认: ${DEFAULT_ENV}
               可选值: dev/test/prod
  -s <服务>  指定操作的服务名称（仅在 logs 时需要）
  -h         显示此帮助信息
============================================================
${RESET}"
}

# 构建项目
build_project() {
  echo -e "${GREEN}🚀 构建项目 ${RESET}"

  mvn clean package -DskipTests

  echo -e "${GREEN}✅ 构建项目完成!${RESET}"
}

# 启动服务（带健康检查）
start_services() {
    echo -e "${GREEN}🚀 启动服务(环境:${ENV})...${RESET}"

    docker-compose -f ${COMPOSE_FILE} up -d

    echo -e "${GREEN}✅ 所有服务已启动!${RESET}"
}

# 停止服务
stop_services() {
    echo -e "${RED}🛑 停止服务...${RESET}"
    docker-compose -f ${COMPOSE_FILE} stop
}

# 重建服务
rebuild_services() {
    echo -e "${BLUE}🔨 重建服务...${RESET}"
    stop_services
    clean_images
    mvn clean package -DskipTests
    docker-compose -f ${COMPOSE_FILE} build --no-cache
}

# 清理资源（带确认）
clean_all() {
    read -p "⚠️  确认清理所有Docker资源和构建文件?(y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}🧹 清理中...${RESET}"
        docker system prune -af
        rm -rf ./admin/target ./portal/target
        echo -e "${GREEN}✅ 清理完成！${RESET}"
    fi
}

# 查看实时日志
show_logs() {
    echo -e "${CYAN}📜 日志追踪模式${RESET}"
    docker-compose -f ${COMPOSE_FILE} logs -f ${SERVICE}
}

# 服务状态检查
check_status() {
    echo -e "${CYAN}🩺 服务健康检查${RESET}"
    for service in "${SERVICES[@]}"; do
        container_id=$(docker ps -qf "name=hotShop-${service}")
        if [ -z "$container_id" ]; then
            echo -e "${RED}■ ${service}：未运行${RESET}"
        else
            health=$(docker inspect --format='{{.State.Health.Status}}' ${container_id})
            echo -e "${GREEN}● ${service}：运行中（健康状态：${health}）${RESET}"
        fi
    done
}

# -------------------------- 主流程 --------------------------

# 参数解析
while getopts "a:e:s:h" opt; do
    case $opt in
        a) ACTION=${OPTARG} ;;
        e) ENV=${OPTARG} ;;
        s) SERVICE=${OPTARG} ;;
        h) show_help; exit 0 ;;
        *) echo -e "${RED}无效选项！${RESET}"; exit 1 ;;
    esac
done

# 设置默认环境
ENV=${ENV:-${DEFAULT_ENV}}

# 执行主命令
case "${ACTION}" in
    bulid)    build_project ;;
    start)    start_services ;;
    stop)     stop_services ;;
    restart)  stop_services; start_services ;;
    rebuild)  rebuild_services ;;
    clean)    clean_all ;;
    logs)     show_logs ;;
    status)   check_status ;;
    *)        echo -e "${RED}未知操作！${RESET}"; exit 1 ;;
esac