$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dest = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
$expected = '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7'
$url = 'https://services.gradle.org/distributions/gradle-9.5.0-wrapper.jar'
if (-not (Test-Path $dest)) {
    $tempFile = "$dest.tmp"
    try {
        Invoke-WebRequest -Uri $url -OutFile $tempFile
        $actual = (Get-FileHash $tempFile -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Gradle Wrapper checksum mismatch' }
        Move-Item $tempFile $dest
    } finally {
        if (Test-Path $tempFile) { Remove-Item $tempFile }
    }
}
$actual = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'Gradle Wrapper checksum mismatch' }
