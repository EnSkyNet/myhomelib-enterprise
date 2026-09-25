$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$Root = Split-Path -Parent $PSScriptRoot
$Converters = Join-Path $Root "tools\converters"
$CalibreDir = Join-Path $Converters "Calibre"
$CalibreExe = Join-Path $CalibreDir "ebook-convert.exe"
$CalibreWorker = Join-Path $CalibreDir "calibre-parallel.exe"
$Checks = @(
    @{ Name = "fb2cng/fbc"; Path = (Join-Path $Converters "fbc-windows-amd64\fbc.exe"); Args = @("--version") },
    @{ Name = "Pandoc"; Path = (Join-Path $Converters "pandoc-3.11\pandoc.exe"); Args = @("--version") },
    @{ Name = "libmobi"; Path = (Join-Path $Converters "libmobi\mobitool.exe"); Args = @("-v") },
    @{ Name = "Calibre"; Path = $CalibreExe; Args = @("--version") }
)
$Failed = $false
foreach ($Check in $Checks) {
    if (-not (Test-Path $Check.Path -PathType Leaf)) {
        Write-Host ("MISSING  {0}: {1}" -f $Check.Name, $Check.Path)
        $Failed = $true
        continue
    }
    $Output = (& $Check.Path @($Check.Args) 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        Write-Host ("FAILED   {0}: exit {1}`n{2}" -f $Check.Name, $LASTEXITCODE, $Output)
        $Failed = $true
    } else {
        $First = ($Output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
        Write-Host ("OK       {0}: {1}" -f $Check.Name, $First)
    }
}

if (Test-Path $CalibreExe -PathType Leaf) {
    if (-not (Test-Path $CalibreWorker -PathType Leaf)) {
        Write-Host ("FAILED   Calibre runtime: required worker is missing: {0}" -f $CalibreWorker)
        $Failed = $true
    } else {
        $ProbeDir = Join-Path ([System.IO.Path]::GetTempPath()) ("mhl-calibre-probe-" + [guid]::NewGuid().ToString("N"))
        try {
            New-Item -ItemType Directory -Force $ProbeDir | Out-Null
            $ProbeInput = Join-Path $ProbeDir "probe.txt"
            $ProbeOutput = Join-Path $ProbeDir "probe.epub"
            [System.IO.File]::WriteAllText($ProbeInput, "MyHomeLib Calibre functional probe", [System.Text.UTF8Encoding]::new($false))
            $ProbeText = (& $CalibreExe $ProbeInput $ProbeOutput 2>&1 | Out-String).Trim()
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $ProbeOutput -PathType Leaf) -or (Get-Item $ProbeOutput).Length -le 0) {
                Write-Host ("FAILED   Calibre functional conversion: exit {0}`n{1}" -f $LASTEXITCODE, $ProbeText)
                $Failed = $true
            } else {
                Write-Host "OK       Calibre functional conversion: TXT -> EPUB"
            }
        } finally {
            Remove-Item -Recurse -Force $ProbeDir -ErrorAction SilentlyContinue
        }
    }
}
if ($Failed) { exit 2 }
