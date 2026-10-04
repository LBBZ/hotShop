#!/bin/sh
# Project-scoped Docker Compose v2 operations. Build tools run in Docker images.
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
action=Status
service=
usage() {
    echo 'Usage: script/deploy.sh [-a start|status|stop|restart|build|logs|config] [-s SERVICE]'
}
while getopts "a:s:h" option; do
    case "$option" in
        a) case "$OPTARG" in
            start) action=Start ;; status) action=Status ;; stop) action=Stop ;;
            restart) action=Restart ;; build) action=Build ;; logs) action=Logs ;; config) action=Config ;;
            *) usage >&2; exit 2 ;;
        esac ;;
        s) service=$OPTARG ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
[ "$#" -eq 0 ] || { usage >&2; exit 2; }
if [ -n "$service" ]; then
    [ "$action" = Build ] || { echo '-s is available only for build.' >&2; exit 2; }
    exec pwsh -NoProfile -File "$repo_root/script/demo.ps1" -Action "$action" -Service "$service"
fi
exec pwsh -NoProfile -File "$repo_root/script/demo.ps1" -Action "$action"
