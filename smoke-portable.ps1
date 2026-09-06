param(
    [string]$Archive = "",
    [string]$ReportPath = "",
    [string]$HostBindingPath = ""
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Set-Location $PSScriptRoot

if ($env:OS -ne "Windows_NT") { throw "Windows portable acceptance must run on Windows." }

$hostBinding = $null
if (-not [string]::IsNullOrWhiteSpace($HostBindingPath)) {
    . .\tools\windows-acceptance-host.ps1
    $hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding -Path $HostBindingPath
}

[xml]$rootPom = Get-Content -Raw "pom.xml"
$Version = if ($env:MHL_VERSION) { $env:MHL_VERSION } else { [string]$rootPom.project.version }
if ([string]::IsNullOrWhiteSpace($Version)) { throw "Cannot determine application version from pom.xml" }

if ([string]::IsNullOrWhiteSpace($Archive)) {
    $matches = @(Get-ChildItem -Path "dist" -Filter "myhomelib-$Version-windows-*.zip" -File -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { throw "Expected exactly one Windows portable archive, found $($matches.Count)" }
    $Archive = $matches[0].FullName
}
if (-not (Test-Path $Archive -PathType Leaf)) { throw "Portable archive not found: $Archive" }
$Archive = [IO.Path]::GetFullPath($Archive)

$acceptanceDir = Join-Path $PSScriptRoot "target\windows-portable-acceptance"
New-Item -ItemType Directory -Force $acceptanceDir | Out-Null
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $acceptanceDir "portable-smoke.json"
}
$ReportPath = [IO.Path]::GetFullPath($ReportPath)
$markdownPath = [IO.Path]::ChangeExtension($ReportPath, ".md")

# Intentionally exercise Unicode + spaces in all three path roles that historically
# caused packaging/launcher regressions on Windows.
$unicodeLeaf = "Моя бібліотека Ω 日本"
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) (("myhomelib-portable-smoke-" + [Guid]::NewGuid().ToString("N")))
$extract = Join-Path $tempRoot ("extract " + $unicodeLeaf)
$home = Join-Path $tempRoot ("home " + $unicodeLeaf)
$cwd = Join-Path $tempRoot ("cwd " + $unicodeLeaf)
$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$oldUserProfile = $env:USERPROFILE
$oldHome = $env:HOME
$oldAppData = $env:APPDATA
$oldLocalAppData = $env:LOCALAPPDATA
$osCaption = try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { [Environment]::OSVersion.VersionString }
$pushed = $false
$result = [ordered]@{
    schemaVersion = 1
    scenario = "windows-portable-unicode-smoke"
    timestamp = (Get-Date).ToString("o")
    host = $env:COMPUTERNAME
    user = [Environment]::UserName
    acceptanceSessionId = $(if ($null -ne $hostBinding) { [string]$hostBinding.acceptanceSessionId } else { "" })
    hostFingerprintSha256 = $(if ($null -ne $hostBinding) { [string]$hostBinding.hostFingerprintSha256 } else { "" })
    userFingerprintSha256 = $(if ($null -ne $hostBinding) { [string]$hostBinding.userFingerprintSha256 } else { "" })
    osVersion = $(if ($null -ne $hostBinding) { [string]$hostBinding.osVersion } else { [Environment]::OSVersion.Version.ToString() })
    osBuild = $(if ($null -ne $hostBinding) { [string]$hostBinding.osBuild } else { [Environment]::OSVersion.Version.Build.ToString() })
    osArchitecture = $(if ($null -ne $hostBinding) { [string]$hostBinding.osArchitecture } else { $(if ([Environment]::Is64BitOperatingSystem) { "x64" } else { "x86" }) })
    os = $osCaption
    archive = $Archive
    archiveSha256 = (Get-FileHash -Algorithm SHA256 $Archive).Hash.ToLowerInvariant()
    extractPath = $extract
    syntheticHome = $home
    workingDirectory = $cwd
    launcher = $null
    markerPresent = $false
    launcherExitCode = $null
    portableDataCreated = $false
    profileEnvironmentRedirected = $false
    syntheticHomeWriteDetected = $false
    workingDirectoryWriteDetected = $false
    profileWriteDetected = $false
    overall = "FAIL"
    note = ""
}
try {
    New-Item -ItemType Directory -Force $extract, $home, $cwd | Out-Null
    Expand-Archive -Path $Archive -DestinationPath $extract -Force
    $launcher = Join-Path $extract "MyHomeLib\MyHomeLib.exe"
    $result.launcher = $launcher
    if (-not (Test-Path $launcher -PathType Leaf)) { throw "Extracted launcher not found: $launcher" }
    $launcherDir = Split-Path $launcher -Parent
    $marker = Join-Path $launcherDir "myhomelib2.ini"
    if (-not (Test-Path $marker -PathType Leaf)) { throw "Portable marker is missing beside launcher: $marker" }
    $result.markerPresent = $true

    # Redirect the common Windows/Java profile locations into the synthetic home.
    # Any file created there is evidence that portable mode leaked outside the launcher tree.
    $env:USERPROFILE = $home
    $env:HOME = $home
    $env:APPDATA = Join-Path $home "AppData\Roaming"
    $env:LOCALAPPDATA = Join-Path $home "AppData\Local"
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=`"$home`""
    $result.profileEnvironmentRedirected = $true
    Push-Location $cwd
    $pushed = $true
    $process = Start-Process -FilePath $launcher -ArgumentList "--release-smoke" -Wait -PassThru
    Pop-Location
    $pushed = $false
    $result.launcherExitCode = $process.ExitCode
    if ($process.ExitCode -ne 0) { throw "Extracted portable launcher smoke failed with exit code $($process.ExitCode)" }

    $dataDir = Join-Path $launcherDir "data"
    $result.portableDataCreated = Test-Path $dataDir -PathType Container
    if (-not $result.portableDataCreated) { throw "Portable data directory was not created beside launcher: $dataDir" }
    $result.syntheticHomeWriteDetected = @((Get-ChildItem -Force -Path $home -ErrorAction SilentlyContinue)).Count -gt 0
    $result.workingDirectoryWriteDetected = @((Get-ChildItem -Force -Path $cwd -ErrorAction SilentlyContinue)).Count -gt 0
    $result.profileWriteDetected = [bool]($result.syntheticHomeWriteDetected -or $result.workingDirectoryWriteDetected)
    if ($result.syntheticHomeWriteDetected) { throw "Portable launch wrote outside the launcher tree into the synthetic user profile" }
    if ($result.workingDirectoryWriteDetected) { throw "Portable launch wrote outside the launcher tree into the working directory" }

    $result.overall = "PASS"
    Write-Host "Extracted portable Unicode-path smoke: PASS ($Archive)"
}
catch {
    $result.note = $_.Exception.Message
    throw
}
finally {
    if ($pushed) { Pop-Location }
    $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
    $env:USERPROFILE = $oldUserProfile
    $env:HOME = $oldHome
    $env:APPDATA = $oldAppData
    $env:LOCALAPPDATA = $oldLocalAppData
    New-Item -ItemType Directory -Force (Split-Path -Parent $ReportPath) | Out-Null
    $result | ConvertTo-Json -Depth 6 | Set-Content -Path $ReportPath -Encoding utf8
    @(
        "# MyHomeLib Windows portable acceptance",
        "",
        "- Overall: **$($result.overall)**",
        "- Host: $($result.host)",
        "- OS: $($result.os)",
        "- Archive: ``$($result.archive)``",
        "- Archive SHA-256: ``$($result.archiveSha256)``",
        "- Unicode extract path: ``$($result.extractPath)``",
        "- Unicode synthetic home: ``$($result.syntheticHome)``",
        "- Unicode working directory: ``$($result.workingDirectory)``",
        "- Launcher exit code: $($result.launcherExitCode)",
        "- Portable marker present: $($result.markerPresent)",
        "- Portable data created beside launcher: $($result.portableDataCreated)",
        "- Profile environment redirected: $($result.profileEnvironmentRedirected)",
        "- Synthetic profile write detected: $($result.syntheticHomeWriteDetected)",
        "- Working-directory write detected: $($result.workingDirectoryWriteDetected)",
        "- Any profile/outside-launcher write detected: $($result.profileWriteDetected)",
        "- Note: $($result.note)"
    ) | Set-Content -Path $markdownPath -Encoding utf8
    Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
}
