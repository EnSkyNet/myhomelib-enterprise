$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Wrapper = Join-Path $Root "mvnw.cmd"
$MinimumMaven = [Version]"3.9.6"
$MinimumJava = 21


function Assert-SupportedJava {
    $JavaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { $null }
    if (-not $JavaCommand -or -not (Test-Path $JavaCommand -PathType Leaf)) {
        $Java = Get-Command java -ErrorAction SilentlyContinue
        if ($null -eq $Java) { throw "JDK 21+ is required but java was not found." }
        $JavaCommand = $Java.Source
    }
    $versionText = (& $JavaCommand -version 2>&1 | Out-String)
    $match = [regex]::Match($versionText, 'version\s+"([0-9]+)')
    if (-not $match.Success) { throw "Cannot parse Java version; MyHomeLib requires JDK 21+." }
    $major = [int]$match.Groups[1].Value
    if ($major -lt $MinimumJava) { throw "JDK $major is unsupported; MyHomeLib requires JDK 21+." }
}

Assert-SupportedJava

function Assert-SupportedMaven([string]$Command) {
    $versionOutput = & $Command -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot determine Maven version; MyHomeLib requires Maven 3.9.6+."
    }
    $versionText = $versionOutput | Out-String
    $match = [regex]::Match($versionText, '(?m)^Apache Maven\s+([0-9]+(?:\.[0-9]+){1,3})')
    if (-not $match.Success) {
        throw "Cannot parse Maven version; MyHomeLib requires Maven 3.9.6+."
    }
    $version = [Version]$match.Groups[1].Value
    if ($version -lt $MinimumMaven) {
        throw "Maven $version is unsupported; MyHomeLib requires Maven 3.9.6+."
    }
}

if (Test-Path $Wrapper -PathType Leaf) {
    Assert-SupportedMaven $Wrapper
    & $Wrapper @args
    return
}

$Maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $Maven) { $Maven = Get-Command mvn -ErrorAction SilentlyContinue }
if (-not $Maven) {
    [Console]::Error.WriteLine("ERROR: Maven is required. The formal source archive intentionally does not bundle Maven or Maven Wrapper. Install Maven 3.9.6+ (or use a repository checkout that provides the wrapper) and retry.")
    $global:LASTEXITCODE = 127
    return
}

Assert-SupportedMaven $Maven.Source
& $Maven.Source @args
