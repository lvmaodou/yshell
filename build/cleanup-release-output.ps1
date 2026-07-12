param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseDir,

    [Parameter(Mandatory = $true)]
    [string]$KeepVersion
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ReleaseDir)) {
    return
}

$resolvedReleaseDir = (Resolve-Path -LiteralPath $ReleaseDir).Path
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

if (-not $resolvedReleaseDir.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean a release directory outside the workspace: $resolvedReleaseDir"
}

$root = [System.IO.Path]::GetPathRoot($resolvedReleaseDir)
if ($resolvedReleaseDir.TrimEnd('\') -eq $root.TrimEnd('\')) {
    throw "Refusing to clean a drive root: $resolvedReleaseDir"
}

Get-ChildItem -LiteralPath $resolvedReleaseDir -Force |
        Where-Object { $_.Name -ne 'latest.json' -and $_.Name -ne $KeepVersion } |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
