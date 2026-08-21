#!/bin/sh
# Evaluate the adaptive-ranking learners against real logged traffic.
#
# Works on a COPY of the workspace database, opened read-only, so this can be run while the
# application is up without touching its single shared SQLite connection.
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
run_id=${1:-$(date -u +%Y%m%dT%H%M%SZ)}

source_db="$module_dir/data/mcpserver.db"
if [ ! -f "$source_db" ]; then
    echo "No workspace database at $source_db — nothing to replay." >&2
    exit 0
fi

replay_db="$module_dir/eval-runs/$run_id/replay-snapshot.db"
mkdir -p "$(dirname -- "$replay_db")"
# sqlite3's .backup takes a consistent snapshot of a live database; plain cp can catch a torn WAL.
if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$source_db" ".backup '$replay_db'"
else
    cp "$source_db" "$replay_db"
fi

cd "$module_dir"
mvn -Dtest=ReplayHarnessTests -Deval.run.id="$run_id" -Dreplay.db="$replay_db" test

echo "Replay report: $module_dir/eval-runs/$run_id/replay.json"
