# Restore the unmodified Gradle Wrapper JAR from its text transport representation.
$ErrorActionPreference = 'Stop'
$directory = Join-Path (Split-Path $PSScriptRoot -Parent) 'gradle/wrapper'
$jar = Join-Path $directory 'gradle-wrapper.jar'
$expected = (Get-Content -Raw "$jar.sha256").Trim()
if (-not (Test-Path $jar)) {
    $temporary = "$jar.tmp.$PID"
    try {
        [IO.File]::WriteAllBytes($temporary, [Convert]::FromBase64String([IO.File]::ReadAllText("$jar.base64")))
        if ((Get-FileHash -Algorithm SHA256 $temporary).Hash.ToLowerInvariant() -ne $expected) { throw 'Gradle Wrapper checksum mismatch' }
        Move-Item $temporary $jar
    } finally {
        if (Test-Path $temporary) { Remove-Item $temporary }
    }
}
if ((Get-FileHash -Algorithm SHA256 $jar).Hash.ToLowerInvariant() -ne $expected) { throw 'Gradle Wrapper checksum mismatch' }
