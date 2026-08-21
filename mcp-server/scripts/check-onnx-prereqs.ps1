[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$JavaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    Write-Error "Java could not be started. Install an x64 JDK 17 or newer and put it on PATH."
    exit 1
}

$ArchMatch = [regex]::Match($JavaSettings, '(?m)^\s*os\.arch\s*=\s*(\S+)')
$JavaArch = if ($ArchMatch.Success) { $ArchMatch.Groups[1].Value } else { "unknown" }
$ArchitectureReady = $JavaArch -in @("amd64", "x86_64")

$SystemDirectory = if ([Environment]::Is64BitOperatingSystem -and
        -not [Environment]::Is64BitProcess) {
    Join-Path $env:WINDIR "Sysnative"
} else {
    Join-Path $env:WINDIR "System32"
}

$VisualCppDlls = @(
    "MSVCP140.dll",
    "MSVCP140_1.dll",
    "VCRUNTIME140.dll",
    "VCRUNTIME140_1.dll"
)
$WindowsDlls = @("dxcore.dll", "ucrtbase.dll")
$Checks = foreach ($Dll in ($VisualCppDlls + $WindowsDlls)) {
    [pscustomobject]@{
        Dependency = $Dll
        Found = Test-Path (Join-Path $SystemDirectory $Dll)
    }
}

Write-Host "Java architecture: $JavaArch"
Write-Host "Windows system directory: $SystemDirectory"
$Checks | Format-Table -AutoSize

$MissingVisualCpp = @($Checks | Where-Object {
    $_.Dependency -in $VisualCppDlls -and -not $_.Found
})
$MissingWindows = @($Checks | Where-Object {
    $_.Dependency -in $WindowsDlls -and -not $_.Found
})

if (-not $ArchitectureReady) {
    Write-Error "ONNX Runtime 1.22 requires an x64 JDK on Windows; os.arch was '$JavaArch'."
    exit 1
}
if ($MissingVisualCpp.Count -gt 0) {
    Write-Host "Install or repair Microsoft Visual C++ 2015-2022 Redistributable (x64):" -ForegroundColor Yellow
    Write-Host "https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist?view=msvc-170"
    exit 1
}
if ($MissingWindows.Count -gt 0) {
    Write-Host "A required Windows component is missing. Apply current Windows updates; dxcore.dll is supplied by Windows." -ForegroundColor Yellow
    exit 1
}

Write-Host "ONNX native prerequisites are present." -ForegroundColor Green
Write-Host "If loading still fails, check whether endpoint security blocks Java from loading DLLs under $env:TEMP."
