param(
    [string]$Python = "python",
    [string]$OutputDirectory = "",
    [switch]$SkipTests
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$bridgeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $bridgeRoot
$pythonCommand = $Python
if (Test-Path -LiteralPath $Python) {
    $pythonCommand = (Resolve-Path -LiteralPath $Python).Path
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot 'dist\windows-python'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$icon = Join-Path $repoRoot 'pc-bridge\assets\heart-relay.ico'
$entry = Join-Path $bridgeRoot 'run_app.py'
$work = Join-Path $bridgeRoot 'build\pyinstaller'
$spec = Join-Path $bridgeRoot 'build\spec'

if (-not $SkipTests) {
    & $pythonCommand -m pytest (Join-Path $bridgeRoot 'tests') -q -p no:cacheprovider
    if ($LASTEXITCODE -ne 0) { throw "pytest failed with exit code $LASTEXITCODE" }
    Push-Location $bridgeRoot
    try {
        & $pythonCommand -m vrc_heartbeat --self-test
        if ($LASTEXITCODE -ne 0) { throw "Python entry-point self-test failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

New-Item -ItemType Directory -Path $OutputDirectory, $work, $spec -Force | Out-Null
& $pythonCommand -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --name 'VrcRealtimeHeartbeat-Python' `
    --icon $icon `
    --add-data "$icon;." `
    --paths $bridgeRoot `
    --distpath $OutputDirectory `
    --workpath $work `
    --specpath $spec `
    $entry
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed with exit code $LASTEXITCODE" }

$exe = Join-Path $OutputDirectory 'VrcRealtimeHeartbeat-Python.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "Python EXE was not created: $exe" }

Copy-Item (Join-Path $bridgeRoot 'README.md') (Join-Path $OutputDirectory 'README-Python.md') -Force
$hash = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  VrcRealtimeHeartbeat-Python.exe" | Set-Content (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ASCII
Write-Output "Python Windows EXE created: $exe"
Write-Output "SHA256: $hash"
