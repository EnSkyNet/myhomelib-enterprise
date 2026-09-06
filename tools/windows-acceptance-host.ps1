# Shared Windows host/session identity helpers for final MyHomeLib 7.1 acceptance.
# This file is itself candidate-bound by acceptance-harness.sha256.

function Get-MyHomeLibSha256Text {
    param([Parameter(Mandatory=$true)][string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $digest = $sha.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($digest)).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-MyHomeLibWindowsAcceptanceIdentity {
    if ($env:OS -ne 'Windows_NT') { throw 'Windows acceptance host identity can only be collected on Windows.' }

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($null -eq $identity.User -or [string]::IsNullOrWhiteSpace($identity.User.Value)) {
        throw 'Cannot determine current Windows user SID.'
    }
    $sid = [string]$identity.User.Value

    try {
        $machineGuid = [string](Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Cryptography' -Name MachineGuid -ErrorAction Stop).MachineGuid
    }
    catch {
        throw "Cannot read Windows MachineGuid required for final acceptance host binding: $($_.Exception.Message)"
    }
    if ([string]::IsNullOrWhiteSpace($machineGuid)) { throw 'Windows MachineGuid is blank.' }

    $osInfo = $null
    try { $osInfo = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop } catch { }
    $osCaption = if ($null -ne $osInfo -and -not [string]::IsNullOrWhiteSpace([string]$osInfo.Caption)) { [string]$osInfo.Caption } else { [Environment]::OSVersion.VersionString }
    $osVersion = if ($null -ne $osInfo -and -not [string]::IsNullOrWhiteSpace([string]$osInfo.Version)) { [string]$osInfo.Version } else { [Environment]::OSVersion.Version.ToString() }
    $osBuild = if ($null -ne $osInfo -and -not [string]::IsNullOrWhiteSpace([string]$osInfo.BuildNumber)) { [string]$osInfo.BuildNumber } else { [Environment]::OSVersion.Version.Build.ToString() }
    $rawArch = [string]$env:PROCESSOR_ARCHITECTURE
    $arch = switch -Regex ($rawArch) {
        '^AMD64$' { 'x64'; break }
        '^ARM64$' { 'arm64'; break }
        '^x86$' { 'x86'; break }
        default { if ([Environment]::Is64BitOperatingSystem) { 'x64' } else { 'x86' } }
    }

    return [pscustomobject][ordered]@{
        host = [string]$env:COMPUTERNAME
        user = [Environment]::UserName
        os = $osCaption
        osVersion = $osVersion
        osBuild = $osBuild
        osArchitecture = $arch
        hostFingerprintSha256 = $(Get-MyHomeLibSha256Text -Text ("myhomelib-host-v1`n" + $machineGuid))
        userFingerprintSha256 = $(Get-MyHomeLibSha256Text -Text ("myhomelib-user-v1`n" + $sid))
    }
}

function New-MyHomeLibWindowsAcceptanceHostBinding {
    param(
        [Parameter(Mandatory=$true)][string]$CandidateSha,
        [Parameter(Mandatory=$true)][string]$Repository,
        [Parameter(Mandatory=$true)][long]$AcceptanceRunId,
        [string]$Path = 'target\windows-host-binding\windows-host-binding.json'
    )
    if ($CandidateSha -notmatch '^[0-9a-fA-F]{40}$') { throw 'CandidateSha must be a full 40-character Git commit SHA.' }
    if ($Repository -notmatch '^[^/\s]+/[^/\s]+$') { throw 'Repository must be OWNER/REPO.' }
    if ($AcceptanceRunId -le 0) { throw 'AcceptanceRunId must be positive.' }

    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Final Windows acceptance host binding requires a standard/non-elevated user.'
    }

    $current = Get-MyHomeLibWindowsAcceptanceIdentity
    $fullPath = [IO.Path]::GetFullPath($Path)
    New-Item -ItemType Directory -Force (Split-Path -Parent $fullPath) | Out-Null
    $payload = [ordered]@{
        schemaVersion = 1
        scenario = 'windows-acceptance-host-binding'
        timestamp = (Get-Date).ToString('o')
        overall = 'PASS'
        acceptanceSessionId = [Guid]::NewGuid().ToString('D').ToLowerInvariant()
        candidateSha = $CandidateSha.ToLowerInvariant()
        repository = $Repository
        acceptanceRunId = $AcceptanceRunId
        host = $current.host
        user = $current.user
        os = $current.os
        osVersion = $current.osVersion
        osBuild = $current.osBuild
        osArchitecture = $current.osArchitecture
        hostFingerprintSha256 = $current.hostFingerprintSha256
        userFingerprintSha256 = $current.userFingerprintSha256
        isAdministrator = $false
    }
    $payload | ConvertTo-Json -Depth 6 | Set-Content -Path $fullPath -Encoding utf8
    return [pscustomobject]$payload
}

function Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding {
    param(
        [string]$Path = 'target\windows-host-binding\windows-host-binding.json',
        [string]$CandidateSha = '',
        [string]$Repository = ''
    )
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path $fullPath -PathType Leaf)) { throw "Windows acceptance host binding not found: $fullPath" }
    $binding = Get-Content -Raw $fullPath | ConvertFrom-Json
    if ($binding.schemaVersion -ne 1 -or [string]$binding.scenario -ne 'windows-acceptance-host-binding' -or [string]$binding.overall -ne 'PASS') {
        throw 'Windows acceptance host binding schema/scenario/overall is invalid.'
    }
    if ([string]$binding.acceptanceSessionId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
        throw 'Windows acceptance host binding session id is invalid.'
    }
    if ([string]$binding.hostFingerprintSha256 -notmatch '^[0-9a-fA-F]{64}$' -or [string]$binding.userFingerprintSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'Windows acceptance host binding fingerprints are invalid.'
    }
    if (-not [string]::IsNullOrWhiteSpace($CandidateSha) -and [string]$binding.candidateSha -ne $CandidateSha.ToLowerInvariant()) {
        throw 'Windows acceptance host binding candidate SHA mismatch.'
    }
    if (-not [string]::IsNullOrWhiteSpace($Repository) -and [string]$binding.repository -ne $Repository) {
        throw 'Windows acceptance host binding repository mismatch.'
    }

    $current = Get-MyHomeLibWindowsAcceptanceIdentity
    foreach ($field in @('host','user','osVersion','osBuild','osArchitecture','hostFingerprintSha256','userFingerprintSha256')) {
        if ([string]$binding.$field -ne [string]$current.$field) {
            throw "Windows acceptance host/user identity mismatch for $field. Evidence from another host/session cannot be combined."
        }
    }
    return $binding
}
