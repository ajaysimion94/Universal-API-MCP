#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
run_id=${1:-$(date -u +%Y%m%dT%H%M%SZ)}

cd "$module_dir"
if [ -f "models/nomic-embed-text-v1.5/model_quantized.onnx" ] \
    && [ -f "models/nomic-embed-text-v1.5/tokenizer.json" ] \
    && [ -f "models/ms-marco-MiniLM-L6-v2/model.onnx" ] \
    && [ -f "models/ms-marco-MiniLM-L6-v2/tokenizer.json" ]; then
    mvn -Dskip.bundle=true -Dtest=GoldenSetRegressionTests -Deval.run.id="$run_id" test
else
    mvn -Dtest=GoldenSetRegressionTests -Deval.run.id="$run_id" test
fi

echo "Golden-set report: $module_dir/eval-runs/$run_id/report.json"
