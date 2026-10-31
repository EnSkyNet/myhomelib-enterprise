[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatabasePath,
    [string]$CheckpointPath,
    [string]$ProfilePath,
    [ValidateRange(0, 500000)]
    [int]$ImportProbeRows = 50000
)

$ErrorActionPreference = "Stop"

$resolvedDatabase = Resolve-Path -LiteralPath $DatabasePath
if (-not (Test-Path -LiteralPath $resolvedDatabase.Path -PathType Leaf)) {
    throw "DatabasePath must point to an existing SQLite database file."
}
$DatabasePath = $resolvedDatabase.Path

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$invokeMaven = Join-Path $repoRoot "tools\invoke-maven.ps1"
if (-not (Test-Path -LiteralPath $invokeMaven -PathType Leaf)) {
    throw "Maven launcher was not found: $invokeMaven"
}

$runId = [guid]::NewGuid().ToString("N")
if ([string]::IsNullOrWhiteSpace($CheckpointPath)) {
    $CheckpointPath = Join-Path $env:TEMP ("mhl-recovery-checkpoint-" + $runId + ".db")
}
if ([string]::IsNullOrWhiteSpace($ProfilePath)) {
    $ProfilePath = Join-Path $env:TEMP ("mhl-recovery-profile-" + $runId + ".json")
}
$CheckpointPath = [IO.Path]::GetFullPath($CheckpointPath)
$ProfilePath = [IO.Path]::GetFullPath($ProfilePath)
if (Test-Path -LiteralPath $CheckpointPath) {
    throw "Refusing to overwrite an existing checkpoint path: $CheckpointPath"
}
foreach ($suffix in @("-wal", "-shm", "-journal")) {
    if (Test-Path -LiteralPath ($CheckpointPath + $suffix)) {
        throw "Refusing to overwrite an existing checkpoint sidecar: $CheckpointPath$suffix"
    }
}
if ($CheckpointPath -eq $DatabasePath -or $ProfilePath -eq $DatabasePath -or $ProfilePath -eq $CheckpointPath) {
    throw "Database, checkpoint and profile paths must be different."
}

$logPath = "$ProfilePath.log"
$errorPath = "$ProfilePath.stderr.log"
$runnerPath = "$ProfilePath.runner.ps1"
$machineProfilePath = "$ProfilePath.machine.json"
$startedAt = Get-Date
$profileParent = Split-Path -Parent $ProfilePath
if (-not [string]::IsNullOrWhiteSpace($profileParent)) {
    New-Item -ItemType Directory -Path $profileParent -Force | Out-Null
}

function ConvertTo-PowerShellLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

$mavenArguments = @(
    "-pl",
    "myhomelib-infrastructure",
    "-am",
    "-Dtest=RealRecoveryCheckpointPerformanceProbeTest",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-DforkCount=0",
    "-Dmhl.real.db=$DatabasePath",
    "-Dmhl.real.checkpoint=$CheckpointPath",
    "-Dmhl.real.profile=$ProfilePath",
    "-Dmhl.real.import.probe.rows=$ImportProbeRows",
    "test"
)
$quotedArguments = ($mavenArguments | ForEach-Object { ConvertTo-PowerShellLiteral $_ }) -join " "
$runnerContent = '$ErrorActionPreference = "Stop"' + [Environment]::NewLine +
    '& ' + (ConvertTo-PowerShellLiteral $invokeMaven) + ' ' + $quotedArguments + [Environment]::NewLine +
    'if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }'
Set-Content -LiteralPath $runnerPath -Value $runnerContent -Encoding UTF8

$currentPowerShellProcess = Get-Process -Id $PID
$powerShellExe = $currentPowerShellProcess.Path
if ([string]::IsNullOrWhiteSpace($powerShellExe)) {
    $powerShellCommand = if ($PSVersionTable.PSEdition -eq "Core") {
        Get-Command pwsh.exe -ErrorAction SilentlyContinue
    } else {
        Get-Command powershell.exe -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShellCommand) { throw "Cannot locate a PowerShell executable to run the Maven probe." }
    $powerShellExe = $powerShellCommand.Source
}
$process = Start-Process -FilePath $powerShellExe -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"{0}"' -f $runnerPath)) -WorkingDirectory $repoRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $logPath -RedirectStandardError $errorPath

$peakJavaWorkingSetBytes = 0L
$javaCpuByPid = @{}
$sampleCount = 0
while (-not $process.HasExited) {
    $currentJavaWorkingSet = 0L
    $javaProcesses = Get-Process -Name java, javaw -ErrorAction SilentlyContinue
    foreach ($javaProcess in $javaProcesses) {
        try {
            if ($javaProcess.StartTime -lt $startedAt) { continue }
            $currentJavaWorkingSet += [long]$javaProcess.WorkingSet64
            $javaCpuByPid[[string]$javaProcess.Id] = [double]$javaProcess.TotalProcessorTime.TotalSeconds
        } catch {
            # A JVM may exit between process enumeration and metric collection.
        }
    }
    if ($currentJavaWorkingSet -gt $peakJavaWorkingSetBytes) {
        $peakJavaWorkingSetBytes = $currentJavaWorkingSet
    }
    $sampleCount++
    Start-Sleep -Milliseconds 200
    $process.Refresh()
}

$javaCpuSeconds = 0.0
foreach ($cpuSeconds in $javaCpuByPid.Values) { $javaCpuSeconds += $cpuSeconds }
$os = Get-CimInstance Win32_OperatingSystem
$computer = Get-CimInstance Win32_ComputerSystem
$machineProfile = [ordered]@{
    schema = 1
    capturedAt = (Get-Date).ToString("o")
    osCaption = $os.Caption
    osVersion = $os.Version
    osArchitecture = $env:PROCESSOR_ARCHITECTURE
    physicalMemoryBytes = [long]$computer.TotalPhysicalMemory
    peakJavaWorkingSetBytes = $peakJavaWorkingSetBytes
    sampledJavaCpuSeconds = [Math]::Round($javaCpuSeconds, 3)
    samplingIterations = $sampleCount
    mavenExitCode = [int]$process.ExitCode
    databaseFileName = [IO.Path]::GetFileName($DatabasePath)
    profileJsonPath = $ProfilePath
    mavenLogPath = $logPath
}
$machineProfile | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $machineProfilePath -Encoding UTF8

# The checkpoint is a temporary full copy of the user's library. Remove it even when Maven fails.
foreach ($suffix in @("-wal", "-shm", "-journal")) {
    $sidecar = $CheckpointPath + $suffix
    if (Test-Path -LiteralPath $sidecar) {
        Remove-Item -LiteralPath $sidecar -Force
    }
}
if (Test-Path -LiteralPath $CheckpointPath) {
    Remove-Item -LiteralPath $CheckpointPath -Force
}
if (Test-Path -LiteralPath $runnerPath) {
    Remove-Item -LiteralPath $runnerPath -Force
}

Write-Host "Checkpoint profile: $ProfilePath"
Write-Host "Windows process profile: $machineProfilePath"
Write-Host "Maven log: $logPath"
Write-Host ("Peak Java working set: {0:N1} MiB" -f ($peakJavaWorkingSetBytes / 1MB))
Write-Host ("Sampled Java CPU time: {0:N2} s" -f $javaCpuSeconds)
Write-Host ("Physical RAM: {0:N1} GiB" -f ($computer.TotalPhysicalMemory / 1GB))

if ($process.ExitCode -ne 0) {
    if (Test-Path -LiteralPath $logPath) {
        Get-Content -LiteralPath $logPath -Tail 80
    }
    if (Test-Path -LiteralPath $errorPath) {
        Get-Content -LiteralPath $errorPath -Tail 80
    }
    exit $process.ExitCode
}
