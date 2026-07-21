param(
    [string]$OutputDirectory = "",
    [switch]$SkipSelfTest
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$bridgeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $bridgeRoot
$source = Join-Path $bridgeRoot 'src\HeartRateBridge\Program.cs'
$manifest = Join-Path $bridgeRoot 'src\HeartRateBridge\app.manifest'
$icon = Join-Path $bridgeRoot 'assets\heart-relay.ico'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot 'dist\windows'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)

$csc = $null
$programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
$vswhere = Join-Path $programFilesX86 'Microsoft Visual Studio\Installer\vswhere.exe'
if (Test-Path $vswhere) {
    $vsInstall = & $vswhere -latest -products '*' -requires Microsoft.Component.MSBuild -property installationPath
    if ($vsInstall) {
        $candidate = Join-Path $vsInstall 'MSBuild\Current\Bin\Roslyn\csc.exe'
        if (Test-Path $candidate) { $csc = $candidate }
    }
}
if (-not $csc) {
    $candidate = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (Test-Path $candidate) { $csc = $candidate }
}
if (-not $csc) {
    throw 'C# compiler not found. Install Visual Studio Build Tools or .NET Framework 4.x developer tools.'
}

$referenceRoot = Join-Path $programFilesX86 'Reference Assemblies\Microsoft\Framework\.NETFramework'
$referenceDirectory = Get-ChildItem $referenceRoot -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'System.Windows.Forms.dll') } |
    Sort-Object { [version](($_.Name -replace '^v','') -replace '[^0-9.]','') } -Descending |
    Select-Object -First 1
if (-not $referenceDirectory) {
    throw '.NET Framework reference assemblies not found.'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$exe = Join-Path $OutputDirectory 'VrcRealtimeHeartbeat.exe'
$references = @(
    'System.dll',
    'System.Core.dll',
    'System.Drawing.dll',
    'System.Windows.Forms.dll',
    'System.Web.Extensions.dll'
) | ForEach-Object { Join-Path $referenceDirectory.FullName $_ }

$arguments = @(
    '/nologo',
    '/target:winexe',
    '/platform:anycpu',
    '/optimize+',
    '/debug-',
    '/langversion:latest',
    "/win32icon:$icon",
    "/win32manifest:$manifest",
    "/out:$exe"
)
$arguments += $references | ForEach-Object { "/reference:$_" }
$arguments += $source

& $csc $arguments
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
    throw "Windows EXE build failed with exit code $LASTEXITCODE"
}

if (-not $SkipSelfTest) {
    $process = Start-Process -FilePath $exe -ArgumentList '--self-test' -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "EXE protocol self-test failed with exit code $($process.ExitCode)"
    }
}

Copy-Item (Join-Path $bridgeRoot 'README.md') (Join-Path $OutputDirectory 'README.md') -Force
$hash = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  VrcRealtimeHeartbeat.exe" | Set-Content (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ASCII

Write-Output "Windows EXE created: $exe"
Write-Output "SHA256: $hash"
