param(
    [string]$GitHubEvidenceRoot = "target\github-connected-acceptance",
    [string]$Output = "target\myhomelib-7.1-final-external-evidence.zip"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if ($env:OS -ne "Windows_NT") {
    throw "Final 7.1 external acceptance packaging must run on the Windows acceptance host."
}

$GitHubEvidenceRoot = [IO.Path]::GetFullPath($GitHubEvidenceRoot)
$GitHubJson = Join-Path $GitHubEvidenceRoot "github-connected-acceptance.json"
if (-not (Test-Path $GitHubJson -PathType Leaf)) { throw "Missing connected GitHub evidence: $GitHubJson" }

Write-Host "[1/6] Strict Windows MHL-011/MHL-012 evidence validation"
& python "tools\windows-acceptance-evidence-check.py" `
    --root target `
    --require-standard-user `
    --require-real-previous `
    --dpi `
    --release-desktop `
    --require-host-binding
if ($LASTEXITCODE -ne 0) { throw "Strict Windows evidence validation failed with exit code $LASTEXITCODE" }

Write-Host "[2/6] Build immutable Windows evidence archive"
& .\tools\windows-final-evidence-pack.ps1 -Root target
if ($LASTEXITCODE -ne 0) { throw "Windows evidence packaging failed with exit code $LASTEXITCODE" }

Write-Host "[3/6] Run candidate-bound six-item final external gate"
& python "tools\v71-final-external-acceptance-check.py" `
    --windows-root target `
    --windows-archive "target\windows-final-acceptance-evidence.zip" `
    --github-json $GitHubJson
if ($LASTEXITCODE -ne 0) { throw "Final 7.1 external acceptance gate failed with exit code $LASTEXITCODE" }

Write-Host "[4/6] Stage consolidated reviewer evidence"
$Output = [IO.Path]::GetFullPath($Output)
$OutputSidecar = "$Output.sha256"
$stage = Join-Path ([IO.Path]::GetTempPath()) ("myhomelib-v71-final-evidence-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force $stage | Out-Null
    $githubStage = Join-Path $stage "github"
    $windowsStage = Join-Path $stage "windows"
    $finalStage = Join-Path $stage "final"
    New-Item -ItemType Directory -Force $githubStage, $windowsStage, $finalStage | Out-Null

    Copy-Item -Force $GitHubJson $githubStage
    $GitHubMd = Join-Path $GitHubEvidenceRoot "github-connected-acceptance.md"
    if (-not (Test-Path $GitHubMd -PathType Leaf)) { throw "Missing connected GitHub Markdown evidence: $GitHubMd" }
    Copy-Item -Force $GitHubMd $githubStage
    $GitHubIngest = Join-Path $GitHubEvidenceRoot "github-connected-acceptance-ingest.json"
    if (-not (Test-Path $GitHubIngest -PathType Leaf)) { throw "Missing GitHub artifact ingest evidence: $GitHubIngest" }
    Copy-Item -Force $GitHubIngest $githubStage
    $HarnessManifest = Join-Path $GitHubEvidenceRoot "acceptance-harness.sha256"
    if (-not (Test-Path $HarnessManifest -PathType Leaf)) { throw "Missing candidate-bound acceptance harness manifest: $HarnessManifest" }
    Copy-Item -Force $HarnessManifest $githubStage
    $HarnessBinding = "target\windows-harness-binding\windows-harness-binding.json"
    if (-not (Test-Path $HarnessBinding -PathType Leaf)) { throw "Missing local Windows acceptance harness binding evidence: $HarnessBinding" }
    Copy-Item -Force $HarnessBinding $windowsStage
    $HostBinding = "target\windows-host-binding\windows-host-binding.json"
    if (-not (Test-Path $HostBinding -PathType Leaf)) { throw "Missing Windows host/session binding evidence: $HostBinding" }
    Copy-Item -Force $HostBinding $windowsStage
    $CandidateManifest = Join-Path $GitHubEvidenceRoot "candidate-windows\candidate-windows.sha256"
    if (-not (Test-Path $CandidateManifest -PathType Leaf)) { throw "Missing bound candidate manifest: $CandidateManifest" }
    Copy-Item -Force $CandidateManifest $githubStage

    Copy-Item -Force "target\windows-final-acceptance-evidence.zip" $windowsStage
    Copy-Item -Force "target\windows-final-acceptance-evidence.zip.sha256" $windowsStage
    Copy-Item -Force "target\v71-final-external-acceptance\v71-final-external-acceptance.json" $finalStage
    Copy-Item -Force "target\v71-final-external-acceptance\v71-final-external-acceptance.md" $finalStage

    $manifestLines = New-Object System.Collections.Generic.List[string]
    $prefix = $stage.TrimEnd([char[]]@('\', '/')) + [IO.Path]::DirectorySeparatorChar
    foreach ($file in Get-ChildItem -Path $stage -File -Recurse | Sort-Object FullName) {
        $relative = $file.FullName.Substring($prefix.Length).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 $file.FullName).Hash.ToLowerInvariant()
        $manifestLines.Add("$hash  $relative") | Out-Null
    }
    [IO.File]::WriteAllLines((Join-Path $stage "manifest.sha256"), $manifestLines, [Text.UTF8Encoding]::new($false))

    New-Item -ItemType Directory -Force (Split-Path -Parent $Output) | Out-Null
    Remove-Item -Force $Output, $OutputSidecar -ErrorAction SilentlyContinue
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $Output -CompressionLevel Optimal
    $archiveHash = (Get-FileHash -Algorithm SHA256 $Output).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($OutputSidecar, "$archiveHash  $(Split-Path -Leaf $Output)`n", [Text.UTF8Encoding]::new($false))

    Write-Host "[5/6] Verify completed reviewer evidence bundle"
    & python "tools\v71-final-evidence-bundle-check.py" $Output
    if ($LASTEXITCODE -ne 0) { throw "Final reviewer evidence bundle verification failed with exit code $LASTEXITCODE" }

    Write-Host "[6/6] MyHomeLib 7.1 final external evidence: PASS"
    Write-Host "Bundle: $Output"
    Write-Host "SHA-256: $archiveHash"
}
finally {
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
}
