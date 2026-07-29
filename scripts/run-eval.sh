#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
run_id=${1:-$(date -u +%Y%m%dT%H%M%SZ)}

cd "$repo_dir/mcp-server"
mvn -Dtest=GoldenSetRegressionTests -Deval.run.id="$run_id" test

echo "Golden-set report: $repo_dir/eval-runs/$run_id/report.json"
