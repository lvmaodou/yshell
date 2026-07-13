param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseDir,

    [Parameter(Mandatory = $true)]
    [string]$UpdateDir,

    [switch]$RemoveReleaseDir
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ReleaseDir)) {
    throw "Release directory not found: $ReleaseDir"
}

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedReleaseDir = (Resolve-Path -LiteralPath $ReleaseDir).Path
$resolvedUpdateDir = [System.IO.Path]::GetFullPath($UpdateDir)

if (-not $resolvedReleaseDir.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to publish from a release directory outside the workspace: $resolvedReleaseDir"
}

if (-not $resolvedUpdateDir.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to publish to an update directory outside the workspace: $resolvedUpdateDir"
}

$releaseRoot = [System.IO.Path]::GetPathRoot($resolvedReleaseDir)
$updateRoot = [System.IO.Path]::GetPathRoot($resolvedUpdateDir)
if ($resolvedReleaseDir.TrimEnd('\') -eq $releaseRoot.TrimEnd('\')) {
    throw "Refusing to use a drive root as release directory: $resolvedReleaseDir"
}
if ($resolvedUpdateDir.TrimEnd('\') -eq $updateRoot.TrimEnd('\')) {
    throw "Refusing to use a drive root as update directory: $resolvedUpdateDir"
}

if (Test-Path -LiteralPath $resolvedUpdateDir) {
    Remove-Item -LiteralPath $resolvedUpdateDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $resolvedUpdateDir | Out-Null
Get-ChildItem -LiteralPath $resolvedReleaseDir -Force |
    Copy-Item -Destination $resolvedUpdateDir -Recurse -Force

if ($RemoveReleaseDir) {
    Remove-Item -LiteralPath $resolvedReleaseDir -Recurse -Force
}
