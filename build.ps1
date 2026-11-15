# Builds PulseForge AI with plain javac (JDK 17+). No external dependencies.
# Usage:  .\build.ps1        (build)      .\build.ps1 test   (build + run tests)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory -Force build/classes, build/test-classes | Out-Null
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d build/classes $sources
if ($LASTEXITCODE -ne 0) { exit 1 }
jar --create --file build/pulseforge.jar --main-class com.pulseforge.app.Main -C build/classes .
if ($args.Count -gt 0 -and $args[0] -eq "test") {
    $tests = Get-ChildItem -Recurse src/test/java -Filter *.java | ForEach-Object { $_.FullName }
    javac -encoding UTF-8 -cp build/classes -d build/test-classes $tests
    if ($LASTEXITCODE -ne 0) { exit 1 }
    java -cp "build/classes;build/test-classes" com.pulseforge.TestRunner
    if ($LASTEXITCODE -ne 0) { exit 1 }
}
Write-Host "Built build/pulseforge.jar"
