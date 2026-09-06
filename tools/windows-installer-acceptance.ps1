param(
    [string]$PreviousMsi = "",
    [string]$CurrentMsi = "",
    [string]$PreviousVersion = "",
    [string]$SyntheticPreviousVersion = "7.0.99",
    [switch]$SkipBuild,
    [switch]$RequireStandardUser,
    [string]$HostBindingPath = "",
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if ($env:OS -ne "Windows_NT") {
    throw "Windows installer acceptance must run on Windows."
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($RequireStandardUser -and $isAdministrator) {
    throw "MHL-012 non-admin acceptance requires a standard/non-elevated Windows user. Current token is elevated/admin."
}

$hostBinding = $null
if (-not [string]::IsNullOrWhiteSpace($HostBindingPath)) {
    . .\tools\windows-acceptance-host.ps1
    $hostBinding = Get-VerifiedMyHomeLibWindowsAcceptanceHostBinding -Path $HostBindingPath
}

[xml]$rootPom = Get-Content -Raw "pom.xml"
$CurrentVersion = [string]$rootPom.project.version
if ([string]::IsNullOrWhiteSpace($CurrentVersion)) {
    throw "Cannot determine current version from pom.xml"
}
if ($SyntheticPreviousVersion -eq $CurrentVersion) {
    throw "Synthetic previous version must differ from current version"
}
if (-not [string]::IsNullOrWhiteSpace($PreviousMsi) -and [string]::IsNullOrWhiteSpace($PreviousVersion)) {
    throw "-PreviousVersion is required when -PreviousMsi is supplied"
}
$PreviousPackageSource = if ([string]::IsNullOrWhiteSpace($PreviousMsi)) { "synthetic" } else { "external" }
$ExpectedPreviousVersion = if ($PreviousPackageSource -eq "synthetic") { $SyntheticPreviousVersion } else { $PreviousVersion }

$Work = Join-Path $Root "target\windows-installer-acceptance"
New-Item -ItemType Directory -Force $Work | Out-Null
if ([string]::IsNullOrWhiteSpace($ReportPath)) { $ReportPath = Join-Path $Work "installer-acceptance.json" }
$ReportPath = [System.IO.Path]::GetFullPath($ReportPath)
$MarkdownReportPath = [System.IO.Path]::ChangeExtension($ReportPath, ".md")
$osCaption = try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { [Environment]::OSVersion.VersionString }
$acceptance = [ordered]@{
    schemaVersion = 1
    scenario = "windows-installer-lifecycle"
    timestamp = (Get-Date).ToString("o")
    host = $env:COMPUTERNAME
    os = $osCaption
    user = [Environment]::UserName
    acceptanceSessionId = $(if ($null -ne $hostBinding) { [string]$hostBinding.acceptanceSessionId } else { "" })
    hostFingerprintSha256 = $(if ($null -ne $hostBinding) { [string]$hostBinding.hostFingerprintSha256 } else { "" })
    userFingerprintSha256 = $(if ($null -ne $hostBinding) { [string]$hostBinding.userFingerprintSha256 } else { "" })
    osVersion = $(if ($null -ne $hostBinding) { [string]$hostBinding.osVersion } else { [Environment]::OSVersion.Version.ToString() })
    osBuild = $(if ($null -ne $hostBinding) { [string]$hostBinding.osBuild } else { [Environment]::OSVersion.Version.Build.ToString() })
    osArchitecture = $(if ($null -ne $hostBinding) { [string]$hostBinding.osArchitecture } else { $(if ([Environment]::Is64BitOperatingSystem) { "x64" } else { "x86" }) })
    isAdministrator = $isAdministrator
    requireStandardUser = [bool]$RequireStandardUser
    previousVersion = $null
    currentVersion = $CurrentVersion
    previousPackageSource = $PreviousPackageSource
    previousMsi = $null
    currentMsi = $null
    previousMsiSha256 = $null
    currentMsiSha256 = $null
    msiexecLogs = @()
    installPrevious = "PENDING"
    upgradeCurrent = "PENDING"
    repeatCurrent = "PENDING"
    uninstall = "PENDING"
    shortcutsRemoved = "PENDING"
    userDataPreserved = "PENDING"
    overall = "FAIL"
    note = ""
}
$acceptance.previousVersion = $ExpectedPreviousVersion
$script:MsiInvocation = 0

function Invoke-Msi {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("install", "uninstall")][string]$Action,
        [Parameter(Mandatory=$true)][string]$Path
    )
    if (-not (Test-Path $Path -PathType Leaf)) { throw "MSI not found: $Path" }
    $verb = if ($Action -eq "install") { "/i" } else { "/x" }
    $script:MsiInvocation++
    $log = Join-Path $Work ("msiexec-{0:D2}-{1}.log" -f $script:MsiInvocation, $Action)
    $arguments = "$verb `"$Path`" /qn /norestart /L*v `"$log`""
    $acceptance.msiexecLogs += (Split-Path -Leaf $log)
    $process = Start-Process -FilePath "msiexec.exe" -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) {
        throw "msiexec $Action failed for $Path with exit code $($process.ExitCode)"
    }
}

function Get-MyHomeLibUninstallEntries {
    $roots = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
    )
    $entries = @()
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        foreach ($item in Get-ChildItem $root -ErrorAction SilentlyContinue) {
            $value = Get-ItemProperty $item.PSPath -ErrorAction SilentlyContinue
            if ($null -ne $value -and $value.DisplayName -eq "MyHomeLib") {
                $entries += $value
            }
        }
    }
    return @($entries)
}

function Assert-SingleRegistration {
    param([Parameter(Mandatory=$true)][string]$ExpectedVersion)
    $entries = @(Get-MyHomeLibUninstallEntries)
    if ($entries.Count -ne 1) {
        throw "Expected exactly one MyHomeLib uninstall registration, found $($entries.Count)"
    }
    if ([string]$entries[0].DisplayVersion -ne $ExpectedVersion) {
        throw "Expected installed version $ExpectedVersion, found $($entries[0].DisplayVersion)"
    }
}

function Find-Shortcut {
    param([Parameter(Mandatory=$true)][string]$RootPath)
    if (-not (Test-Path $RootPath -PathType Container)) { return $null }
    return Get-ChildItem -Path $RootPath -Filter "*MyHomeLib*.lnk" -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Assert-ShortcutTarget {
    param(
        [Parameter(Mandatory=$true)][System.IO.FileInfo]$Shortcut,
        [Parameter(Mandatory=$true)][string]$ExpectedLauncher,
        [Parameter(Mandatory=$true)][string]$Label
    )
    $shell = New-Object -ComObject WScript.Shell
    $target = $shell.CreateShortcut($Shortcut.FullName).TargetPath
    if ([System.IO.Path]::GetFullPath($target) -ne [System.IO.Path]::GetFullPath($ExpectedLauncher)) {
        throw "$Label shortcut target mismatch: $target"
    }
}

function Assert-InstalledShape {
    param([Parameter(Mandatory=$true)][string]$ExpectedVersion)
    Assert-SingleRegistration -ExpectedVersion $ExpectedVersion
    $installDir = Join-Path $env:LOCALAPPDATA "MyHomeLib"
    $launcher = Join-Path $installDir "MyHomeLib.exe"
    if (-not (Test-Path $launcher -PathType Leaf)) {
        throw "Installed launcher not found at expected per-user location: $launcher"
    }

    $desktopShortcut = Find-Shortcut -RootPath ([Environment]::GetFolderPath("Desktop"))
    if ($null -eq $desktopShortcut) { throw "Desktop MyHomeLib shortcut not found" }
    Assert-ShortcutTarget -Shortcut $desktopShortcut -ExpectedLauncher $launcher -Label "Desktop"

    $startMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
    $menuShortcut = Find-Shortcut -RootPath $startMenu
    if ($null -eq $menuShortcut) { throw "Start Menu MyHomeLib shortcut not found" }
    Assert-ShortcutTarget -Shortcut $menuShortcut -ExpectedLauncher $launcher -Label "Start Menu"

    $process = Start-Process -FilePath $launcher -ArgumentList "--release-smoke" -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Installed launcher smoke failed with exit code $($process.ExitCode)"
    }
    return $launcher
}

$existingRegistrations = @(Get-MyHomeLibUninstallEntries)
$profileData = Join-Path $env:USERPROFILE ".myhomelibcorp"
$installDir = Join-Path $env:LOCALAPPDATA "MyHomeLib"
if ($existingRegistrations.Count -ne 0 -or (Test-Path $installDir) -or (Test-Path $profileData)) {
    throw "Acceptance requires a disposable clean Windows user profile: existing MyHomeLib install/data detected."
}

$originalSkipBuild = $env:MHL_SKIP_BUILD
try {
    if (-not $SkipBuild) {
        $env:MHL_SKIP_BUILD = "1"
        if ([string]::IsNullOrWhiteSpace($PreviousMsi)) {
            & .\package-desktop.ps1 -Type msi -PackageVersion $SyntheticPreviousVersion
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            $builtPrevious = Join-Path $Root "dist\MyHomeLib-$SyntheticPreviousVersion.msi"
            if (-not (Test-Path $builtPrevious -PathType Leaf)) { throw "Synthetic previous MSI not produced: $builtPrevious" }
            $PreviousMsi = Join-Path $Work (Split-Path $builtPrevious -Leaf)
            Move-Item -Force $builtPrevious $PreviousMsi
        }
        if ([string]::IsNullOrWhiteSpace($CurrentMsi)) {
            & .\package-desktop.ps1 -Type msi -PackageVersion $CurrentVersion
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            $builtCurrent = Join-Path $Root "dist\MyHomeLib-$CurrentVersion.msi"
            if (-not (Test-Path $builtCurrent -PathType Leaf)) { throw "Current MSI not produced: $builtCurrent" }
            $CurrentMsi = Join-Path $Work (Split-Path $builtCurrent -Leaf)
            # Keep the release-candidate MSI in dist for publication while using an
            # immutable acceptance copy under target for lifecycle evidence.
            Copy-Item -Force $builtCurrent $CurrentMsi
        }
    }

    $PreviousMsi = [System.IO.Path]::GetFullPath($PreviousMsi)
    $CurrentMsi = [System.IO.Path]::GetFullPath($CurrentMsi)
    if (-not (Test-Path $PreviousMsi -PathType Leaf)) { throw "Previous MSI not found: $PreviousMsi" }
    if (-not (Test-Path $CurrentMsi -PathType Leaf)) { throw "Current MSI not found: $CurrentMsi" }
    $acceptance.previousMsi = $PreviousMsi
    $acceptance.currentMsi = $CurrentMsi
    $acceptance.previousMsiSha256 = (Get-FileHash -Algorithm SHA256 $PreviousMsi).Hash.ToLowerInvariant()
    $acceptance.currentMsiSha256 = (Get-FileHash -Algorithm SHA256 $CurrentMsi).Hash.ToLowerInvariant()
    if ($acceptance.previousMsiSha256 -eq $acceptance.currentMsiSha256) {
        throw "Previous and current MSI hashes are identical; upgrade acceptance requires two distinct packages"
    }

    Write-Host "[1/6] Install previous package ($PreviousPackageSource)"
    Invoke-Msi -Action install -Path $PreviousMsi
    Assert-InstalledShape -ExpectedVersion $ExpectedPreviousVersion | Out-Null
    $acceptance.installPrevious = "PASS"

    # Create deterministic user-data sentinels only after the installed launcher has selected
    # the normal profile path. Uninstall/upgrade must never remove or alter these files.
    New-Item -ItemType Directory -Force (Join-Path $profileData "libraries") | Out-Null
    $sentinel = Join-Path $profileData "acceptance-user-data.txt"
    $librarySentinel = Join-Path $profileData "libraries\acceptance-library.db"
    [System.IO.File]::WriteAllText($sentinel, "MyHomeLib Windows acceptance user-data sentinel", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($librarySentinel, "synthetic library sentinel", [System.Text.UTF8Encoding]::new($false))
    $sentinelHash = (Get-FileHash -Algorithm SHA256 $sentinel).Hash
    $libraryHash = (Get-FileHash -Algorithm SHA256 $librarySentinel).Hash

    Write-Host "[2/6] Upgrade previous package to current package"
    Invoke-Msi -Action install -Path $CurrentMsi
    Assert-InstalledShape -ExpectedVersion $CurrentVersion | Out-Null
    if ((Get-FileHash -Algorithm SHA256 $sentinel).Hash -ne $sentinelHash -or
        (Get-FileHash -Algorithm SHA256 $librarySentinel).Hash -ne $libraryHash) {
        throw "User data changed during upgrade"
    }
    $acceptance.upgradeCurrent = "PASS"

    Write-Host "[3/6] Repeat current-package installation (repair/idempotency)"
    Invoke-Msi -Action install -Path $CurrentMsi
    Assert-InstalledShape -ExpectedVersion $CurrentVersion | Out-Null
    if ((Get-FileHash -Algorithm SHA256 $sentinel).Hash -ne $sentinelHash -or
        (Get-FileHash -Algorithm SHA256 $librarySentinel).Hash -ne $libraryHash) {
        throw "User data changed during repeated current-package installation"
    }
    $acceptance.repeatCurrent = "PASS"

    Write-Host "[4/6] Uninstall current package"
    Invoke-Msi -Action uninstall -Path $CurrentMsi
    Start-Sleep -Seconds 1
    if (@(Get-MyHomeLibUninstallEntries).Count -ne 0) {
        throw "MyHomeLib uninstall registration remains after uninstall"
    }
    if (Test-Path (Join-Path $installDir "MyHomeLib.exe") -PathType Leaf) {
        throw "Installed launcher remains after uninstall"
    }
    $acceptance.uninstall = "PASS"

    Write-Host "[5/6] Verify shortcuts removed and user data preserved"
    $desktopShortcut = Find-Shortcut -RootPath ([Environment]::GetFolderPath("Desktop"))
    $startShortcut = Find-Shortcut -RootPath (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs")
    if ($null -ne $desktopShortcut -or $null -ne $startShortcut) {
        throw "MyHomeLib shortcut remains after uninstall"
    }
    $acceptance.shortcutsRemoved = "PASS"
    if (-not (Test-Path $sentinel -PathType Leaf) -or -not (Test-Path $librarySentinel -PathType Leaf)) {
        throw "Uninstall deleted MyHomeLib user data"
    }
    if ((Get-FileHash -Algorithm SHA256 $sentinel).Hash -ne $sentinelHash -or
        (Get-FileHash -Algorithm SHA256 $librarySentinel).Hash -ne $libraryHash) {
        throw "Uninstall modified MyHomeLib user data"
    }

    $acceptance.userDataPreserved = "PASS"
    $acceptance.overall = "PASS"
    Write-Host "[6/6] Windows installer lifecycle acceptance: PASS"
    Write-Host "Previous -> current upgrade: PASS"
    Write-Host "Repeated current install: PASS"
    Write-Host "Desktop + Start Menu shortcuts: PASS"
    Write-Host "Uninstall preserves .myhomelibcorp: PASS"
}
catch {
    $acceptance.note = $_.Exception.Message
    throw
}
finally {
    New-Item -ItemType Directory -Force (Split-Path -Parent $ReportPath) | Out-Null
    $acceptance | ConvertTo-Json -Depth 6 | Set-Content -Path $ReportPath -Encoding utf8
    @(
        "# MyHomeLib Windows installer acceptance",
        "",
        "- Overall: **$($acceptance.overall)**",
        "- Host: $($acceptance.host)",
        "- OS: $($acceptance.os)",
        "- User: $($acceptance.user)",
        "- Administrator/elevated: $($acceptance.isAdministrator)",
        "- Standard user required: $($acceptance.requireStandardUser)",
        "- Previous version: $($acceptance.previousVersion)",
        "- Current version: $($acceptance.currentVersion)",
        "- Previous package source: $($acceptance.previousPackageSource)",
        "- Previous MSI: ``$($acceptance.previousMsi)``",
        "- Current MSI: ``$($acceptance.currentMsi)``",
        "- Previous MSI SHA-256: ``$($acceptance.previousMsiSha256)``",
        "- Current MSI SHA-256: ``$($acceptance.currentMsiSha256)``",
        "- msiexec logs: $([string]::Join(", ", @($acceptance.msiexecLogs)))",
        "",
        "| Check | Result |",
        "|---|---|",
        "| Install previous | $($acceptance.installPrevious) |",
        "| Upgrade to current | $($acceptance.upgradeCurrent) |",
        "| Repeat current install | $($acceptance.repeatCurrent) |",
        "| Uninstall | $($acceptance.uninstall) |",
        "| Shortcuts removed | $($acceptance.shortcutsRemoved) |",
        "| User data preserved | $($acceptance.userDataPreserved) |",
        "",
        "Note: $($acceptance.note)"
    ) | Set-Content -Path $MarkdownReportPath -Encoding utf8
    if ($null -eq $originalSkipBuild) { Remove-Item Env:MHL_SKIP_BUILD -ErrorAction SilentlyContinue }
    else { $env:MHL_SKIP_BUILD = $originalSkipBuild }

    # The acceptance profile is disposable and started clean, so remove only the synthetic
    # data created by this harness after preservation has already been proven.
    if (Test-Path $profileData -PathType Container) {
        Remove-Item -Recurse -Force $profileData -ErrorAction SilentlyContinue
    }
}
