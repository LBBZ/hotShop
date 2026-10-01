#!/bin/sh
# Container-only build and start; no host Maven installation or implicit shutdown.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$script_dir/deploy.sh" -a start "$@"
