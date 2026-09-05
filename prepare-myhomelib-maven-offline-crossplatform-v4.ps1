param(
    [string]$ProjectRoot = "",
    [switch]$FullOfflineVerify
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = $ScriptDir
}

$LogFile = Join-Path $ScriptDir "prepare-myhomelib-maven-offline-v4.log"
$FinalZipName = "maven-offline-repo.zip"
$PartialZipName = "maven-offline-repo-PARTIAL.zip"
$ShaName = "maven-offline-repo.zip.sha256"

$TranscriptStarted = $false
$ExitCode = 0
$Repo = $null
$FinalZip = $null
$PartialZip = $null
$ShaFile = $null
$Maven = $null
$MavenRepoArg = $null

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host ("=== " + $Message + " ===") -ForegroundColor Cyan
}

function Invoke-MavenChecked {
    param(
        [string[]]$Arguments,
        [string]$FailureMessage
    )

    Write-Host ("> mvn " + ($Arguments -join " ")) -ForegroundColor DarkGray
    & $script:Maven @Arguments
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw ($FailureMessage + " Maven exit code: " + $code)
    }
}

function Invoke-MavenWarning {
    param(
        [string[]]$Arguments,
        [string]$WarningMessage
    )

    Write-Host ("> mvn " + ($Arguments -join " ")) -ForegroundColor DarkGray
    & $script:Maven @Arguments
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Write-Warning ($WarningMessage + " Maven exit code: " + $code)
        return $false
    }
    return $true
}

function Get-MavenArtifact {
    param(
        [string]$Artifact,
        [bool]$Transitive
    )

    $transitiveText = "false"
    if ($Transitive) {
        $transitiveText = "true"
    }

    Write-Host ("  -> " + $Artifact)
    $args = @(
        $script:MavenRepoArg,
        "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get",
        ("-Dartifact=" + $Artifact),
        ("-Dtransitive=" + $transitiveText)
    )
    Invoke-MavenChecked -Arguments $args -FailureMessage ("Could not download artifact: " + $Artifact + ".")
}

function Assert-FilePresent {
    param(
        [string]$Path,
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw ($Description + " is missing: " + $Path)
    }
}

function Remove-IfExists {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Force -Recurse
    }
}

function New-ZipFromRepo {
    param(
        [string]$SourceRepo,
        [string]$DestinationZip
    )

    Remove-IfExists -Path $DestinationZip

    $tarCommand = Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($tarCommand) {
        $parent = Split-Path -Parent $SourceRepo
        $leaf = Split-Path -Leaf $SourceRepo
        Write-Host ("Creating ZIP with tar.exe: " + $DestinationZip)
        Push-Location -LiteralPath $parent
        try {
            & $tarCommand.Source -a -c -f $DestinationZip $leaf
            $tarCode = $LASTEXITCODE
            if ($tarCode -ne 0) {
                throw ("tar.exe failed with exit code " + $tarCode)
            }
        }
        finally {
            Pop-Location
        }
    }
    else {
        Write-Host ("Creating ZIP with Compress-Archive: " + $DestinationZip)
        Compress-Archive -LiteralPath $SourceRepo -DestinationPath $DestinationZip -CompressionLevel Optimal -Force
    }

    if (-not (Test-Path -LiteralPath $DestinationZip -PathType Leaf)) {
        throw ("ZIP was not created: " + $DestinationZip)
    }
}

try {
    if (Test-Path -LiteralPath $LogFile) {
        Remove-Item -LiteralPath $LogFile -Force
    }
    Start-Transcript -LiteralPath $LogFile -Force | Out-Null
    $TranscriptStarted = $true

    Write-Step "Resolve project root"
    $ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
    Set-Location -LiteralPath $ProjectRoot
    Write-Host ("Project root: " + $ProjectRoot)

    $Pom = Join-Path $ProjectRoot "pom.xml"
    $BundledMaven = Join-Path $ProjectRoot ".mvn\maven\apache-maven-3.9.6\bin\mvn.cmd"
    $Wrapper = Join-Path $ProjectRoot "mvnw.cmd"
    $Repo = Join-Path $ProjectRoot "maven-offline-repo"
    $FinalZip = Join-Path $ProjectRoot $FinalZipName
    $PartialZip = Join-Path $ProjectRoot $PartialZipName
    $ShaFile = Join-Path $ProjectRoot $ShaName

    Assert-FilePresent -Path $Pom -Description "pom.xml"

    if (Test-Path -LiteralPath $BundledMaven -PathType Leaf) {
        $Maven = $BundledMaven
    }
    elseif (Test-Path -LiteralPath $Wrapper -PathType Leaf) {
        $Maven = $Wrapper
    }
    else {
        throw ("Neither bundled Maven nor mvnw.cmd was found under: " + $ProjectRoot)
    }

    Write-Host ("Maven command: " + $Maven)

    Write-Step "Check Java and Maven"
    & $Maven -version
    if ($LASTEXITCODE -ne 0) {
        throw ("Maven cannot start. Exit code: " + $LASTEXITCODE)
    }

    Write-Step "Create clean Maven offline repository"
    Remove-IfExists -Path $Repo
    Remove-IfExists -Path $FinalZip
    Remove-IfExists -Path $PartialZip
    Remove-IfExists -Path $ShaFile
    New-Item -ItemType Directory -Path $Repo | Out-Null
    $MavenRepoArg = "-Dmaven.repo.local=" + $Repo

    Write-Step "Prime all compile and test dependencies"
    Invoke-MavenChecked -Arguments @($MavenRepoArg, "clean", "test-compile", "-DskipTests") -FailureMessage "Online clean test-compile failed."

    Write-Step "Prime packaging plugin dependencies"
    Invoke-MavenWarning -Arguments @($MavenRepoArg, "package", "-DskipTests") -WarningMessage "Online package -DskipTests reported a problem. Explicit cache checks will continue." | Out-Null

    Write-Step "Download Surefire JUnit provider explicitly"
    $SurefireVersion = "3.2.5"
    Get-MavenArtifact -Artifact ("org.apache.maven.surefire:surefire-junit-platform:" + $SurefireVersion) -Transitive $true

    Write-Step "Download JavaFX 21.0.2 for Windows and Linux"
    $JavaFxVersion = "21.0.2"
    $JavaFxModules = @("base", "graphics", "controls", "fxml")
    $JavaFxPlatforms = @("win", "linux")
    foreach ($moduleName in $JavaFxModules) {
        foreach ($platformName in $JavaFxPlatforms) {
            $artifact = "org.openjfx:javafx-" + $moduleName + ":" + $JavaFxVersion + ":jar:" + $platformName
            Get-MavenArtifact -Artifact $artifact -Transitive $false
        }
    }

    Write-Step "Prime actual Surefire and JUnit runtime"
    Invoke-MavenWarning -Arguments @($MavenRepoArg, "-pl", "myhomelib-domain", "-am", "test") -WarningMessage "Online domain smoke test failed. Cache creation will continue because dependencies may still be complete." | Out-Null

    Write-Step "Check critical cache files"
    $SurefireDir = Join-Path $Repo ("org\apache\maven\surefire\surefire-junit-platform\" + $SurefireVersion)
    Assert-FilePresent -Path (Join-Path $SurefireDir ("surefire-junit-platform-" + $SurefireVersion + ".jar")) -Description "Surefire JUnit Platform JAR"
    Assert-FilePresent -Path (Join-Path $SurefireDir ("surefire-junit-platform-" + $SurefireVersion + ".pom")) -Description "Surefire JUnit Platform POM"

    foreach ($moduleName in $JavaFxModules) {
        foreach ($platformName in $JavaFxPlatforms) {
            $javaFxDir = Join-Path $Repo ("org\openjfx\javafx-" + $moduleName + "\" + $JavaFxVersion)
            $javaFxJar = Join-Path $javaFxDir ("javafx-" + $moduleName + "-" + $JavaFxVersion + "-" + $platformName + ".jar")
            Assert-FilePresent -Path $javaFxJar -Description ("JavaFX " + $moduleName + "/" + $platformName)
        }
    }

    Write-Step "Essential OFFLINE test compilation"
    Invoke-MavenChecked -Arguments @($MavenRepoArg, "-o", "clean", "test-compile", "-DskipTests") -FailureMessage "Offline test-compile failed. Cache is not complete."

    Write-Step "OFFLINE Surefire/JUnit smoke test"
    $smokeOk = Invoke-MavenWarning -Arguments @($MavenRepoArg, "-o", "-pl", "myhomelib-domain", "-am", "test") -WarningMessage "Offline domain test failed. Archive will still be created for diagnostics."

    Write-Step "Create final ZIP"
    New-ZipFromRepo -SourceRepo $Repo -DestinationZip $FinalZip
    $hashObject = Get-FileHash -Algorithm SHA256 -LiteralPath $FinalZip
    $hash = $hashObject.Hash.ToLowerInvariant()
    ($hash + "  " + $FinalZipName) | Set-Content -LiteralPath $ShaFile -Encoding ASCII

    $repoBytes = (Get-ChildItem -LiteralPath $Repo -Recurse -File | Measure-Object -Property Length -Sum).Sum
    $zipBytes = (Get-Item -LiteralPath $FinalZip).Length
    $repoMB = [Math]::Round($repoBytes / 1MB, 1)
    $zipMB = [Math]::Round($zipBytes / 1MB, 1)

    Write-Host ""
    Write-Host "CACHE ARCHIVE CREATED." -ForegroundColor Green
    Write-Host ("ZIP: " + $FinalZip)
    Write-Host ("ZIP size: " + $zipMB + " MB")
    Write-Host ("Repository size: " + $repoMB + " MB")
    Write-Host ("SHA-256: " + $hash)
    Write-Host ("Offline domain smoke test: " + $smokeOk)

    if ($FullOfflineVerify) {
        Write-Step "Full OFFLINE clean verify with tests"
        $fullOk = Invoke-MavenWarning -Arguments @($MavenRepoArg, "-o", "clean", "verify") -WarningMessage "Full offline verify failed. ZIP is preserved."
        Write-Host ("Full offline verify: " + $fullOk)
    }

    Write-Step "Done"
    Write-Host "Upload these files:" -ForegroundColor Yellow
    Write-Host ("  " + $FinalZip)
    Write-Host ("  " + $ShaFile)
    Write-Host ("  " + $LogFile)
}
catch {
    $ExitCode = 1
    Write-Host ""
    Write-Host "FAILED." -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ("Detailed log: " + $LogFile) -ForegroundColor Yellow

    if ($Repo -and (Test-Path -LiteralPath $Repo -PathType Container)) {
        try {
            Write-Host "Creating PARTIAL cache archive for diagnostics..." -ForegroundColor Yellow
            New-ZipFromRepo -SourceRepo $Repo -DestinationZip $PartialZip
            Write-Host ("Partial ZIP: " + $PartialZip) -ForegroundColor Yellow
        }
        catch {
            Write-Host ("Could not create partial ZIP: " + $_.Exception.Message) -ForegroundColor Red
        }
    }
}
finally {
    if ($TranscriptStarted) {
        try {
            Stop-Transcript | Out-Null
        }
        catch {
        }
    }
}

if ($ExitCode -ne 0) {
    exit $ExitCode
}
exit 0
