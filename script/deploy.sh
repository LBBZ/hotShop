#!/bin/sh
# Project-scoped Docker Compose v2 operations. Build tools run in Docker images.
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
action=status
project=${COMPOSE_PROJECT_NAME:-hotshop}
service=
env_file=
usage() {
    cat <<'HELP'
Usage: script/deploy.sh [-a ACTION] [-p PROJECT] [-s SERVICE] [-f ENV_FILE]
Actions: build, start, stop, restart, rebuild, clean, logs, status, config
Uses Docker Compose v2 with the app and agent profiles.
clean removes only this project's containers and networks; data volumes stay intact.
-f supplies a Compose environment file. See .env.example for configuration.
For an isolated seeded browser demo, use script/demo.ps1 instead.
HELP
}
while getopts "a:p:s:f:h" option; do
    case "$option" in
        a) action=$OPTARG ;;
        p) project=$OPTARG ;;
        s) service=$OPTARG ;;
        f) env_file=$OPTARG ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
[ "$#" -eq 0 ] || { usage >&2; exit 2; }
case "$project" in
    ''|*[!a-z0-9_-]*|-*|_*) echo 'Invalid Compose project name.' >&2; exit 2 ;;
esac
case "$service" in
    ''|mysql|database-migrator|redis-cache|redis-seckill|rabbitmq|qdrant|java-key-material-init|portal-service|admin-service|task-service|agent-service) ;;
    *) echo 'Unknown service; use a service name from docker-compose.yml.' >&2; exit 2 ;;
esac
docker compose version >/dev/null
compose() {
    if [ -n "$env_file" ]; then
        docker compose --project-name "$project" --env-file "$env_file" \
            -f docker-compose.yml --profile app --profile agent "$@"
    else
        docker compose --project-name "$project" -f docker-compose.yml \
            --profile app --profile agent "$@"
    fi
}
selected() {
    if [ -n "$service" ]; then compose "$@" "$service"; else compose "$@"; fi
}
case "$action" in
    build) selected build ;;
    start) selected up -d --build --wait --wait-timeout 300 ;;
    stop) selected stop ;;
    restart) selected restart ;;
    rebuild) selected up -d --build --force-recreate --wait --wait-timeout 300 ;;
    clean)
        [ -z "$service" ] || { echo 'clean applies to the selected project; omit -s.' >&2; exit 2; }
        compose down --remove-orphans ;;
    logs) selected logs --follow --tail 200 ;;
    status) selected ps -a ;;
    config) compose config --quiet ;;
    *) usage >&2; exit 2 ;;
esac
