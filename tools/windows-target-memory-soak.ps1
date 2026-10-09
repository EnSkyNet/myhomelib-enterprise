param(
    [Parameter(Mandatory=$true)][string]$CandidateSha,
    [Parameter(Mandatory=$true)][int]$ProcessId,
    [int]$SoakSeconds = 3600,
    [int]$SampleIntervalSeconds = 60,
    [string]$HostBindingPath = "target\windows-host-binding\windows-host-binding.json",
    [string]$FunctionalAcceptancePath = "target\post-r5-live-evidence\windows-post-r5-functional\windows-post-r5-functional.json",
    [string]$ReportPath = "target\post-r5-live-evidence\windows-memory-soak\windows-memory-soak.md",
    [string]$EvidenceDir = "",
    [switch]$ChecklistOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($CandidateSha -notmatch '^[0-9a-fA-F]{40}$') { throw "CandidateSha must be a full 40-character Git SHA." }
if ($SoakSeconds -lt 3600 -and -not $ChecklistOnly) { throw "SoakSeconds must be at least 3600 (1 hour)." }
if ($SampleIntervalSeconds -lt 5) { throw "SampleIntervalSeconds must be >= 5." }
if ($env:OS -ne "Windows_NT" -and -not $ChecklistOnly) { throw "Target memory/soak acceptance must run on Windows." }

$ReportPath = [IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force (Split-Path -Parent $ReportPath) | Out-Null
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path (Split-Path -Parent $ReportPath) "evidence" }
$EvidenceDir = [IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force $EvidenceDir | Out-Null

$processPath = ""
$processSha256 = ""
$functionalAcceptanceSha256 = ""
if (-not $ChecklistOnly) {
    . .\tools\windows-acceptance-host.ps1
    $hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding -Path $HostBindingPath -CandidateSha $CandidateSha
    $boundProcess = Get-Process -Id $ProcessId -ErrorAction Stop
    if (-not (Get-Command jcmd -ErrorAction SilentlyContinue)) { throw "jcmd is required on PATH for heap/native-memory evidence." }
    if (-not (Get-Command jstat -ErrorAction SilentlyContinue)) { throw "jstat is required on PATH for cumulative GC evidence." }

    $FunctionalAcceptancePath = [IO.Path]::GetFullPath($FunctionalAcceptancePath)
    if (-not (Test-Path -LiteralPath $FunctionalAcceptancePath -PathType Leaf)) {
        throw "Candidate-bound Windows functional acceptance report not found: $FunctionalAcceptancePath"
    }
    $functional = Get-Content -Raw -LiteralPath $FunctionalAcceptancePath | ConvertFrom-Json
    if ($functional.schemaVersion -ne 1 -or [string]$functional.scenario -ne "windows-post-r5-functional" -or [string]$functional.overall -ne "PASS") {
        throw "Windows functional acceptance report schema/scenario/overall is invalid."
    }
    if ([string]$functional.candidateSha -ne $CandidateSha.ToLowerInvariant()) {
        throw "Windows functional acceptance candidate SHA does not match CandidateSha."
    }
    foreach ($field in @("acceptanceSessionId","hostFingerprintSha256","userFingerprintSha256","osVersion","osBuild","osArchitecture")) {
        if ([string]$functional.$field -ne [string]$hostBinding.$field) {
            throw "Windows functional acceptance does not match the active Windows host/session for $field."
        }
    }
    $expectedLauncherSha256 = ([string]$functional.launcherSha256).ToLowerInvariant()
    if ($expectedLauncherSha256 -notmatch '^[0-9a-f]{64}$') { throw "Windows functional acceptance has no valid launcherSha256." }
    $boundLauncher = [IO.Path]::GetFullPath([string]$functional.launcher)
    $processPath = [IO.Path]::GetFullPath([string]$boundProcess.Path)
    if ($processPath -ne $boundLauncher) {
        throw "ProcessId $ProcessId does not point to the candidate-bound launcher accepted by the Windows functional run."
    }
    $processSha256 = (Get-FileHash -LiteralPath $processPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($processSha256 -ne $expectedLauncherSha256) {
        throw "Process executable SHA-256 does not match the candidate-bound launcher SHA-256."
    }
    $functionalAcceptanceSha256 = (Get-FileHash -LiteralPath $FunctionalAcceptancePath -Algorithm SHA256).Hash.ToLowerInvariant()
} else {
    $hostBinding = [pscustomobject]@{ acceptanceSessionId="CHECKLIST-ONLY"; hostFingerprintSha256=""; userFingerprintSha256=""; osVersion=""; osBuild=""; osArchitecture="" }
}

function Get-RelativeEvidencePath {
    param([string]$TargetPath)
    $base = (Split-Path -Parent $ReportPath).TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
    $baseUri = [Uri]::new($base)
    $targetUri = [Uri]::new([IO.Path]::GetFullPath($TargetPath))
    return ([Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString())).Replace('/', [IO.Path]::DirectorySeparatorChar)
}

function Get-EvidenceSha256 {
    param([string]$Evidence)
    if ([string]::IsNullOrWhiteSpace($Evidence)) { return "" }
    $base = Split-Path -Parent $ReportPath
    $full = [IO.Path]::GetFullPath((Join-Path $base $Evidence))
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "Evidence file not found for hashing: $Evidence" }
    return (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-HeapMetrics {
    $text = (& jcmd $ProcessId GC.heap_info 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "jcmd GC.heap_info failed for PID $ProcessId" }
    $used = $null; $committed = $null
    if ($text -match 'total\s+(\d+)K,\s*used\s+(\d+)K') {
        $committed = [int64]$matches[1] * 1024
        $used = [int64]$matches[2] * 1024
    } elseif ($text -match 'used\s+(\d+)K.*committed\s+(\d+)K') {
        $used = [int64]$matches[1] * 1024
        $committed = [int64]$matches[2] * 1024
    }
    if ($null -eq $used -or $null -eq $committed) { throw "Cannot parse heap used/committed from jcmd GC.heap_info." }
    return [pscustomobject]@{ used=$used; committed=$committed; raw=$text.Trim() }
}


function Convert-JstatNumber {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "-") { return [double]0 }
    return [double]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Get-JstatMetric {
    param([hashtable]$Map,[string]$Name)
    if (-not $Map.ContainsKey($Name)) { return [double]0 }
    return (Convert-JstatNumber ([string]$Map[$Name]))
}

function Get-GcMetrics {
    $text = (& jstat -gcutil $ProcessId 1 1 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "jstat -gcutil failed for PID $ProcessId" }
    $lines = @($text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $headerIndex = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '(^|\s)GCT(\s|$)') { $headerIndex = $i; break }
    }
    if ($headerIndex -lt 0 -or ($headerIndex + 1) -ge $lines.Count) { throw "Cannot parse jstat -gcutil output." }
    $headers = @($lines[$headerIndex] -split '\s+')
    $values = @($lines[$headerIndex + 1] -split '\s+')
    if ($headers.Count -ne $values.Count) { throw "jstat -gcutil header/value count mismatch." }
    $map = @{}
    for ($i = 0; $i -lt $headers.Count; $i++) { $map[$headers[$i]] = $values[$i] }

    $youngCount = [int64](Get-JstatMetric $map "YGC")
    $fullCount = [int64](Get-JstatMetric $map "FGC")
    $concurrentCount = [int64](Get-JstatMetric $map "CGC")
    $youngMs = [int64][Math]::Round((Get-JstatMetric $map "YGCT") * 1000.0)
    $fullMs = [int64][Math]::Round((Get-JstatMetric $map "FGCT") * 1000.0)
    $concurrentMs = [int64][Math]::Round((Get-JstatMetric $map "CGCT") * 1000.0)
    $totalMs = if ($map.ContainsKey("GCT")) {
        [int64][Math]::Round((Get-JstatMetric $map "GCT") * 1000.0)
    } else {
        [int64]($youngMs + $fullMs + $concurrentMs)
    }

    return [pscustomobject]@{
        youngGcCount = $youngCount
        youngGcTimeMs = $youngMs
        fullGcCount = $fullCount
        fullGcTimeMs = $fullMs
        concurrentGcCount = $concurrentCount
        concurrentGcTimeMs = $concurrentMs
        gcCollectionCount = [int64]($youngCount + $fullCount + $concurrentCount)
        gcTotalTimeMs = $totalMs
        raw = $text
    }
}

function Get-Metrics {
    $p = Get-Process -Id $ProcessId -ErrorAction Stop
    $heap = Get-HeapMetrics
    $gc = Get-GcMetrics
    $elapsed = [int64]((Get-Date) - $p.StartTime).TotalMilliseconds
    $native = (& jcmd $ProcessId VM.native_memory summary 2>&1 | Out-String).Trim()
    return [pscustomobject]@{
        timestamp = (Get-Date).ToString("o")
        workingSetBytes = [int64]$p.WorkingSet64
        heapUsedBytes = [int64]$heap.used
        heapCommittedBytes = [int64]$heap.committed
        threadCount = [int]$p.Threads.Count
        handleCount = [int]$p.HandleCount
        elapsedMs = $elapsed
        youngGcCount = [int64]$gc.youngGcCount
        youngGcTimeMs = [int64]$gc.youngGcTimeMs
        fullGcCount = [int64]$gc.fullGcCount
        fullGcTimeMs = [int64]$gc.fullGcTimeMs
        concurrentGcCount = [int64]$gc.concurrentGcCount
        concurrentGcTimeMs = [int64]$gc.concurrentGcTimeMs
        gcCollectionCount = [int64]$gc.gcCollectionCount
        gcTotalTimeMs = [int64]$gc.gcTotalTimeMs
        nativeMemory = $native
        heapRaw = $heap.raw
        gcRaw = $gc.raw
    }
}

function Save-MetricsEvidence {
    param([string]$Id,[object]$Metrics,[string]$Note)
    $file = Join-Path $EvidenceDir ("{0}.json" -f $Id)
    [ordered]@{ id=$Id; note=$Note; metrics=$Metrics } | ConvertTo-Json -Depth 8 | Set-Content -Path $file -Encoding utf8
    return Get-RelativeEvidencePath $file
}

$checks = @(
    @{id="MEM-01"; check="Cold startup → Dashboard."},
    @{id="MEM-02"; check="60 s idle after stabilization."},
    @{id="MEM-03"; check="Open an author with 500+ books."},
    @{id="MEM-04"; check="Open large FB2 Book Details."},
    @{id="MEM-05"; check="Run Search."},
    @{id="MEM-06"; check="Open/use Reader."},
    @{id="MEM-07"; check="Run full/large INPX import."},
    @{id="MEM-08"; check="Post-import idle after stabilization."},
    @{id="MEM-09"; check="Lucene reopen/rebuild."}
)
$results = New-Object System.Collections.Generic.List[object]

foreach ($item in $checks) {
    if ($ChecklistOnly) {
        $results.Add([pscustomobject]@{ id=$item.id; check=$item.check; outcome="PENDING"; note=""; evidence=""; evidenceSha256=""; metrics=@{} }) | Out-Null
        continue
    }
    Write-Host ""; Write-Host "$($item.id) $($item.check)"
    Read-Host "Prepare this exact state, let it stabilize as appropriate, then press Enter to sample" | Out-Null
    $note = Read-Host "Evidence note (what exact state/data was used)"
    if ([string]::IsNullOrWhiteSpace($note)) { throw "$($item.id) requires a non-empty note." }
    $metrics = Get-Metrics
    $ev = Save-MetricsEvidence $item.id $metrics $note
    $results.Add([pscustomobject]@{ id=$item.id; check=$item.check; outcome="PASS"; note=$note; evidence=$ev; evidenceSha256=(Get-EvidenceSha256 $ev); metrics=$metrics }) | Out-Null
}

if ($ChecklistOnly) {
    $results.Add([pscustomobject]@{ id="MEM-10"; check="1–2 h normal production soak."; outcome="PENDING"; note=""; evidence=""; evidenceSha256=""; metrics=@{} }) | Out-Null
} else {
    Write-Host ""; Write-Host "MEM-10 1–2 h normal production soak. Keep using MyHomeLib normally during sampling."
    $note = Read-Host "Describe the representative workload used during soak"
    if ([string]::IsNullOrWhiteSpace($note)) { throw "MEM-10 requires a workload note." }
    $samples = New-Object System.Collections.Generic.List[object]
    $start = Get-Date
    $deadline = $start.AddSeconds($SoakSeconds)
    while ((Get-Date) -lt $deadline) {
        $samples.Add((Get-Metrics)) | Out-Null
        $remaining = [int][Math]::Max(0, ($deadline - (Get-Date)).TotalSeconds)
        Write-Host ("Soak sample {0}; remaining ~{1}s" -f $samples.Count, $remaining)
        if ($remaining -gt 0) { Start-Sleep -Seconds ([Math]::Min($SampleIntervalSeconds, $remaining)) }
    }
    $samples.Add((Get-Metrics)) | Out-Null
    $file = Join-Path $EvidenceDir "MEM-10.json"
    [ordered]@{ id="MEM-10"; note=$note; started=$start.ToString("o"); completed=(Get-Date).ToString("o"); samples=$samples } | ConvertTo-Json -Depth 10 | Set-Content -Path $file -Encoding utf8
    $first = $samples[0]
    $last = $samples[$samples.Count-1]
    $metrics = [pscustomobject]@{
        workingSetBytes = [int64]$last.workingSetBytes
        heapUsedBytes = [int64]$last.heapUsedBytes
        heapCommittedBytes = [int64]$last.heapCommittedBytes
        threadCount = [int]$last.threadCount
        handleCount = [int]$last.handleCount
        elapsedMs = [int64]$last.elapsedMs
        gcCollectionCount = [int64]$last.gcCollectionCount
        gcTotalTimeMs = [int64]$last.gcTotalTimeMs
        sampleCount = [int]$samples.Count
        workingSetPeakBytes = [int64](($samples | Measure-Object -Property workingSetBytes -Maximum).Maximum)
        heapUsedPeakBytes = [int64](($samples | Measure-Object -Property heapUsedBytes -Maximum).Maximum)
        heapCommittedPeakBytes = [int64](($samples | Measure-Object -Property heapCommittedBytes -Maximum).Maximum)
        workingSetDeltaBytes = [int64]($last.workingSetBytes - $first.workingSetBytes)
        heapUsedDeltaBytes = [int64]($last.heapUsedBytes - $first.heapUsedBytes)
        heapCommittedDeltaBytes = [int64]($last.heapCommittedBytes - $first.heapCommittedBytes)
        threadDelta = [int]($last.threadCount - $first.threadCount)
        handleDelta = [int]($last.handleCount - $first.handleCount)
        gcCollectionDelta = [int64]($last.gcCollectionCount - $first.gcCollectionCount)
        gcTotalTimeDeltaMs = [int64]($last.gcTotalTimeMs - $first.gcTotalTimeMs)
    }
    Write-Host ("MEM-10 summary: WS peak={0} B, WS delta={1} B, heap peak={2} B, heap delta={3} B, threads delta={4}, handles delta={5}, GC collections delta={6}, GC time delta={7} ms" -f `
        $metrics.workingSetPeakBytes, $metrics.workingSetDeltaBytes, $metrics.heapUsedPeakBytes, $metrics.heapUsedDeltaBytes, `
        $metrics.threadDelta, $metrics.handleDelta, $metrics.gcCollectionDelta, $metrics.gcTotalTimeDeltaMs)
    $soakOutcome = (Read-Host "Mark MEM-10 after reviewing drift/GC evidence [PASS/FAIL]").Trim().ToUpperInvariant()
    if ($soakOutcome -notin @("PASS", "FAIL")) { throw "MEM-10 result must be PASS or FAIL." }
    $decisionNote = Read-Host "MEM-10 decision note"
    if ([string]::IsNullOrWhiteSpace($decisionNote)) { throw "MEM-10 requires a non-empty decision note." }
    $fullNote = "$note | Decision: $decisionNote"
    $mem10Evidence = Get-RelativeEvidencePath $file
    $results.Add([pscustomobject]@{ id="MEM-10"; check="1–2 h normal production soak."; outcome=$soakOutcome; note=$fullNote; evidence=$mem10Evidence; evidenceSha256=(Get-EvidenceSha256 $mem10Evidence); metrics=$metrics }) | Out-Null
}

$overall = if ($ChecklistOnly) { "PENDING" } elseif (($results | Where-Object { $_.outcome -ne "PASS" }).Count -eq 0) { "PASS" } else { "FAIL" }
$timestamp = (Get-Date).ToString("o")
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# MyHomeLib — target Windows memory/soak acceptance")
$lines.Add("")
$lines.Add("- Timestamp: $timestamp")
$lines.Add("- Candidate SHA: ``$($CandidateSha.ToLowerInvariant())``")
$lines.Add("- PID: $ProcessId")
$lines.Add("- Process path: ``$processPath``")
$lines.Add("- Process SHA-256: ``$processSha256``")
$lines.Add("- Functional acceptance SHA-256: ``$functionalAcceptanceSha256``")
$lines.Add("- Requested soak: $SoakSeconds s")
$lines.Add("- Overall: **$overall**")
$lines.Add("")
$lines.Add("| ID | Result | Check | Working set | Heap used | Heap committed | Threads | Handles | GC count | GC time ms | Evidence | Evidence SHA-256 |")
$lines.Add("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|")
foreach ($row in $results) {
    $m = $row.metrics
    $lines.Add("| $($row.id) | $($row.outcome) | $($row.check) | $($m.workingSetBytes) | $($m.heapUsedBytes) | $($m.heapCommittedBytes) | $($m.threadCount) | $($m.handleCount) | $($m.gcCollectionCount) | $($m.gcTotalTimeMs) | $($row.evidence) | $($row.evidenceSha256) |")
}
[IO.File]::WriteAllLines($ReportPath, $lines, [Text.UTF8Encoding]::new($false))
$jsonPath = [IO.Path]::ChangeExtension($ReportPath, ".json")
[ordered]@{
    schemaVersion = 1
    scenario = "windows-memory-soak"
    timestamp = $timestamp
    overall = $overall
    candidateSha = $CandidateSha.ToLowerInvariant()
    host = $env:COMPUTERNAME
    user = [Environment]::UserName
    acceptanceSessionId = [string]$hostBinding.acceptanceSessionId
    hostFingerprintSha256 = [string]$hostBinding.hostFingerprintSha256
    userFingerprintSha256 = [string]$hostBinding.userFingerprintSha256
    osVersion = [string]$hostBinding.osVersion
    osBuild = [string]$hostBinding.osBuild
    osArchitecture = [string]$hostBinding.osArchitecture
    processId = $ProcessId
    processPath = $processPath
    processSha256 = $processSha256
    functionalAcceptanceSha256 = $functionalAcceptanceSha256
    soakDurationSeconds = $(if($ChecklistOnly){$SoakSeconds}else{$SoakSeconds})
    sampleIntervalSeconds = $SampleIntervalSeconds
    results = $results
} | ConvertTo-Json -Depth 10 | Set-Content -Path $jsonPath -Encoding utf8

Write-Host "Report: $jsonPath"
Write-Host "Overall: $overall"
if ($ChecklistOnly) { exit 0 }
if ($overall -ne "PASS") { exit 2 }
