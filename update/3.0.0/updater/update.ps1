param(
    [Parameter(Mandatory = $true)][string]$PlanDir,
    [Parameter(Mandatory = $true)][int]$PidToWait
)

$ErrorActionPreference = "Stop"

function Read-PlanProperties($Path) {
    $props = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match "^\s*$" -or $_.StartsWith("#")) {
            return
        }
        $idx = $_.IndexOf("=")
        if ($idx -gt 0) {
            $props[$_.Substring(0, $idx)] = $_.Substring($idx + 1)
        }
    }
    return $props
}

function Join-InstallPath($Root, $RelativePath) {
    $normalized = $RelativePath -replace "/", [IO.Path]::DirectorySeparatorChar
    return [IO.Path]::GetFullPath((Join-Path -Path $Root -ChildPath $normalized))
}

try {
    try {
        Wait-Process -Id $PidToWait -ErrorAction SilentlyContinue
    } catch {
    }

    Start-Sleep -Milliseconds 500

    $props = Read-PlanProperties (Join-Path $PlanDir "update-plan.properties")
    $installDir = $props["installDir"]
    $launcherPath = $props["launcherPath"]
    $stagingDir = $props["stagingDir"]

    $copyList = Join-Path $PlanDir "copy-files.tsv"
    if (Test-Path -LiteralPath $copyList) {
        Get-Content -LiteralPath $copyList | ForEach-Object {
            if ($_ -match "^\s*$") {
                return
            }
            $parts = $_ -split "`t", 2
            $source = Join-InstallPath $stagingDir $parts[0]
            $target = Join-InstallPath $installDir $parts[1]
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item -LiteralPath $source -Destination $target -Force
        }
    }

    $deleteList = Join-Path $PlanDir "delete-files.txt"
    if (Test-Path -LiteralPath $deleteList) {
        Get-Content -LiteralPath $deleteList | ForEach-Object {
            if ($_ -match "^\s*$") {
                return
            }
            $target = Join-InstallPath $installDir $_
            Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
        }
    }

    if ($props["runtimeUpdate"] -eq "true") {
        $archive = Join-Path $stagingDir $props["runtimeArchive"]
        $extractDir = Join-Path $PlanDir "runtime-extracted"
        Remove-Item -LiteralPath $extractDir -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

        Expand-Archive -LiteralPath $archive -DestinationPath $extractDir -Force
        $children = @(Get-ChildItem -LiteralPath $extractDir)
        $sourceRuntime = $extractDir
        if ($children.Count -eq 1 -and $children[0].PSIsContainer) {
            $sourceRuntime = $children[0].FullName
        }

        $runtimePath = Join-Path $installDir "runtime"
        $backupPath = "$runtimePath.backup"
        Remove-Item -LiteralPath $backupPath -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $runtimePath) {
            Move-Item -LiteralPath $runtimePath -Destination $backupPath -Force
        }
        Move-Item -LiteralPath $sourceRuntime -Destination $runtimePath -Force
        Remove-Item -LiteralPath $backupPath -Recurse -Force -ErrorAction SilentlyContinue
    }

    if ($props["relaunch"] -ne "false" -and (Test-Path -LiteralPath $launcherPath)) {
        Start-Process -FilePath $launcherPath -WorkingDirectory $installDir
    }

    Start-Sleep -Seconds 2
    Remove-Item -LiteralPath $PlanDir -Recurse -Force -ErrorAction SilentlyContinue
} catch {
    Add-Content -LiteralPath (Join-Path $PlanDir "update-error.log") -Value $_.Exception.ToString()
    exit 1
}
