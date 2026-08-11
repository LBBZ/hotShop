#!/bin/sh
set -eu

SOURCE_PATH="${AGENT_ASSERTION_PRIVATE_KEY_SOURCE_PATH:-/run/secrets/hotshop/agent-service-private.pem}"
TARGET_DIRECTORY="/run/hotshop-agent"
TARGET_PATH="$TARGET_DIRECTORY/agent-service-private.pem"
TEMPORARY_PATH="$TARGET_DIRECTORY/.agent-service-private.pem.$$"

fail() {
    echo "agent entrypoint: private key initialization failed" >&2
    exit 70
}

[ "$(id -u)" = "0" ] || fail
[ -f "$SOURCE_PATH" ] && [ -s "$SOURCE_PATH" ] && [ -r "$SOURCE_PATH" ] || fail

install -d -o 10001 -g 10001 -m 0700 "$TARGET_DIRECTORY" || fail
trap 'rm -f "$TEMPORARY_PATH"' EXIT HUP INT TERM
install -o 10001 -g 10001 -m 0400 "$SOURCE_PATH" "$TEMPORARY_PATH" || fail
mv -f "$TEMPORARY_PATH" "$TARGET_PATH" || fail
trap - EXIT HUP INT TERM

[ -s "$TARGET_PATH" ] || fail
[ "$(stat -c '%u:%g:%a' "$TARGET_PATH")" = "10001:10001:400" ] || fail

export AGENT_ASSERTION_PRIVATE_KEY_PATH="$TARGET_PATH"
exec setpriv \
    --reuid=10001 \
    --regid=10001 \
    --clear-groups \
    --no-new-privs \
    -- "$@"
