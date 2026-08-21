param(
    [string]$RunId = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
)

$ErrorActionPreference = "Stop"
$ModuleDir = Split-Path -Parent $PSScriptRoot

Push-Location $ModuleDir
try {
    $MavenArgs = @("-Dtest=GoldenSetRegressionTests", "-Deval.run.id=$RunId")
    $LocalModelFiles = @(
        "models/nomic-embed-text-v1.5/model_quantized.onnx",
        "models/nomic-embed-text-v1.5/tokenizer.json",
        "models/ms-marco-MiniLM-L6-v2/model.onnx",
        "models/ms-marco-MiniLM-L6-v2/tokenizer.json"
    )
    if (($LocalModelFiles | Where-Object { -not (Test-Path $_) }).Count -eq 0) {
        $MavenArgs += "-Dskip.bundle=true"
    }
    $MavenArgs += "test"

    & mvn $MavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Golden-set Maven test failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$ReportPath = Join-Path $ModuleDir "eval-runs/$RunId/report.json"
Write-Host "Golden-set report: $ReportPath"
