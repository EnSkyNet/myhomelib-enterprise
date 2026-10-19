param(
    [Parameter(Mandatory=$true)][string]$RunRoot,
    [ValidateSet('Check','Maven')][string]$Mode = 'Check',
    [string]$Maven = 'mvn.cmd',
    [string]$LocalRepository,
    [string[]]$MavenArguments = @('clean','verify')
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$RunRoot = [IO.Path]::GetFullPath($RunRoot)
if (Test-Path -LiteralPath $RunRoot) { throw 'Use a new run directory; existing data must not be reused.' }
function Assert-NoLinks([string]$path) {
    for ($p = $path; $p; $p = Split-Path $p -Parent) {
        if ((Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Reparse point is forbidden: $p"
        }
    }
}
Assert-NoLinks $RunRoot
Assert-NoLinks $repo
if (-not $LocalRepository) { $LocalRepository = Join-Path $env:USERPROFILE '.m2\repository' }
$java = (Get-Command java.exe -ErrorAction Stop).Source
$jdk = Split-Path (Split-Path $java -Parent) -Parent
$mavenCommand = if ($Mode -eq 'Maven') { (Get-Command $Maven -ErrorAction Stop).Source } else { $null }
$saved = @{}
$names = @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS',
    'TEMP','TMP','HOME','USERPROFILE','APPDATA','LOCALAPPDATA','MYHOMELIB_ENCRYPTION_KEY',
    'SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','SPRING_PROFILES_ACTIVE')
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$null,'Process') }
    foreach ($dir in @('classes','home','tmp','appdata','localappdata','reports')) {
        New-Item -ItemType Directory -Path (Join-Path $RunRoot $dir) -Force | Out-Null
    }
    foreach ($name in @('TEMP','TMP')) { [Environment]::SetEnvironmentVariable($name,(Join-Path $RunRoot 'tmp'),'Process') }
    foreach ($name in @('HOME','USERPROFILE')) { [Environment]::SetEnvironmentVariable($name,(Join-Path $RunRoot 'home'),'Process') }
    $env:APPDATA = Join-Path $RunRoot 'appdata'
    $env:LOCALAPPDATA = Join-Path $RunRoot 'localappdata'
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'windows-test-isolation') -Filter '*.java' | ForEach-Object FullName)
    & "$jdk\bin\javac.exe" --release 21 -d "$RunRoot\classes" @sources
    if ($LASTEXITCODE -ne 0) { throw 'Isolation helper compilation failed' }
    [IO.File]::WriteAllText("$RunRoot\MANIFEST.MF", "Premain-Class: com.myhomelibcorp.testisolation.IsolationAgent`n`n")
    & "$jdk\bin\jar.exe" --create --file "$RunRoot\isolation.jar" --manifest "$RunRoot\MANIFEST.MF" -C "$RunRoot\classes" .
    if ($LASTEXITCODE -ne 0) { throw 'Isolation helper packaging failed' }
    $env:JAVA_TOOL_OPTIONS = '"-javaagent:' + "$RunRoot\isolation.jar=$RunRoot" + '"'
    # Explicitly inherited by Surefire and Java subprocesses (including crash workers).
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue' # Native stderr is captured, exit status remains authoritative.
    & $java -cp "$RunRoot\isolation.jar" com.myhomelibcorp.testisolation.IsolationProbe *> "$RunRoot\probe.log"
    $probeExit = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($probeExit -ne 0) { throw "Isolation preflight failed; see $RunRoot\probe.log" }
    if ($Mode -eq 'Maven') {
        if (($MavenArguments -join ' ') -match 'mhl\.real\.|javaagent|java\.util\.prefs\.PreferencesFactory') { throw 'Unsafe Maven override' }
        # clean only touches generated module targets; reject linked paths before allowing it.
        [xml]$pom = Get-Content -LiteralPath "$repo\pom.xml"
        $modules = @('.') + @($pom.project.modules.module)
        foreach ($module in $modules) {
            $target = [IO.Path]::GetFullPath((Join-Path $repo "$module\target"))
            if (-not $target.StartsWith($repo + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Target escaped repository' }
            Assert-NoLinks $target
            if (Test-Path -LiteralPath $target) {
                if (Get-ChildItem -LiteralPath $target -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } | Select-Object -First 1) {
                    throw "Linked artifact under $target; inspect before clean"
                }
            }
        }
        Push-Location $repo
        try {
            $ErrorActionPreference = 'Continue'
            & $mavenCommand -B -ntp "-Dmaven.repo.local=$LocalRepository" @MavenArguments *> "$RunRoot\maven.log"
            $buildExit = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
        } finally { Pop-Location }
        foreach ($module in $modules) {
            $reports = Join-Path $repo "$module\target\surefire-reports"
            if (Test-Path -LiteralPath $reports) { Copy-Item -LiteralPath $reports -Destination "$RunRoot\reports\$module" -Recurse }
        }
        [IO.File]::WriteAllText("$RunRoot\result.txt", "exit=$buildExit`n")
        Write-Output "Maven exit=$buildExit; evidence=$RunRoot"
        if ($buildExit -ne 0) { throw 'Maven failed; full log and Surefire reports preserved' }
    } else { Write-Output "Isolation preflight PASS; evidence=$RunRoot" }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
