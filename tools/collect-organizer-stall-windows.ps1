#requires -Version 5.1
<#
.SYNOPSIS
  Collects read-only JVM thread snapshots when the organizer appears stuck.
.DESCRIPTION
  Does not modify Flib.db or any library files. Writes only to -OutputDirectory.
  Reports may contain source paths or other private information: review before sharing.
.EXAMPLE
  .\tools\collect-organizer-stall-windows.ps1 -ProcessId 12345 -OutputDirectory C:\Temp\mhl-diagnostics
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$ProcessId,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [ValidateRange(1, 10)]
    [int]$Snapshots = 3,
    [ValidateRange(1, 120)]
    [int]$IntervalSeconds = 10
)
$ErrorActionPreference = 'Stop'
$destination = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$jcmdCommand = Get-Command jcmd.exe -ErrorAction SilentlyContinue
if (-not $jcmdCommand) {
    throw 'jcmd.exe was not found. Install or select a JDK 21 and add its bin folder to PATH.'
}
$proc = Get-Process -Id $ProcessId -ErrorAction Stop
$start = Get-Date -Format 'yyyyMMdd-HHmmss'
$overview = Join-Path $destination "mhl-organizer-$ProcessId-$start-process.txt"
@(
    "Timestamp: $(Get-Date -Format o)"
    "ProcessId: $ProcessId"
    "ProcessName: $($proc.ProcessName)"
    "StartTime: $($proc.StartTime)"
    "WorkingSetBytes: $($proc.WorkingSet64)"
    'Diagnostics only: no files in the book library are touched.'
    'WARNING: JVM stack traces may contain private paths; review before sharing.'
) | Set-Content -Path $overview -Encoding UTF8
for ($i = 1; $i -le $Snapshots; $i++) {
    $out = Join-Path $destination "mhl-organizer-$ProcessId-$start-thread-$i.txt"
    $err = Join-Path $destination "mhl-organizer-$ProcessId-$start-thread-$i.err.txt"
    $p = Start-Process -FilePath $jcmdCommand.Source -ArgumentList @($ProcessId, 'Thread.print', '-l') `
        -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    if (-not $p.WaitForExit(15000)) {
        try { $p.Kill() } catch { }
        "jcmd attach timed out after 15 seconds at $(Get-Date -Format o)" | Add-Content -Path $err
    } elseif ($p.ExitCode -ne 0) {
        "jcmd exit code $($p.ExitCode)" | Add-Content -Path $err
    }
    if ($i -lt $Snapshots) { Start-Sleep -Seconds $IntervalSeconds }
}
Write-Host "Diagnostic snapshots written to: $destination"
Write-Host 'Do not publish these files without reviewing potential personal information.'
