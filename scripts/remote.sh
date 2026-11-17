#!/bin/sh
set -eu
fleet_remote_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if node -e 'process.exit(typeof fetch === "function" ? 0 : 1)' >/dev/null 2>&1; then
    exec node "$fleet_remote_root/scripts/remote.mjs" "$@"
fi
for fleet_remote_node in "$HOME"/.nvm/versions/node/*/bin/node; do
    if [ -x "$fleet_remote_node" ] && "$fleet_remote_node" -e 'process.exit(typeof fetch === "function" ? 0 : 1)' >/dev/null 2>&1; then
        exec "$fleet_remote_node" "$fleet_remote_root/scripts/remote.mjs" "$@"
    fi
done
printf '%s\n' 'Native fetch requires Node 18 or newer.' >&2
exit 1
