#requires -Version 5.1
# Build android-bridge -> build\dex\classes2.dex  (includes OkHttp + Okio + Kotlin runtime)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
#
# Optional environment overrides:
#   JAVA         full path to java.exe        (default: %JAVA_HOME%\bin\java.exe)
#   ANDROID_JAR  full path to android.jar     (default: tools\android\android-34\android.jar)
#   OKHTTP_JAR   full path to okhttp jar      (default: local .m2 cache)
#   OKIO_JAR     full path to okio jar        (default: local .m2 cache)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Tools = Join-Path $Root 'tools'

function Resolve-First {
    param([string[]] $Candidates, [string] $Label)
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    Write-Host "Missing: $Label" -ForegroundColor Red
    foreach ($c in $Candidates) { Write-Host "  tried: $c" }
    throw "Please set the matching environment variable (see header of build.ps1)."
}

$JavaHome = ${env:JAVA_HOME}
$Java = Resolve-First -Label 'java.exe (JDK 17+)' -Candidates @(
    ${env:JAVA},
    $(if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' }),
    'D:\IDEA\Java\jdk-21\bin\java.exe'
)

$AndroidJar = Resolve-First -Label 'android.jar (Android SDK platform)' -Candidates @(
    ${env:ANDROID_JAR},
    (Join-Path $Tools 'android\android-34\android.jar')
)

$UserProfileDir = ${env:USERPROFILE}
$OkHttp = Resolve-First -Label 'okhttp jar' -Candidates @(
    ${env:OKHTTP_JAR},
    (Join-Path $UserProfileDir '.m2\repository\com\squareup\okhttp3\okhttp\4.9.3\okhttp-4.9.3.jar')
)

$Okio = Resolve-First -Label 'okio jar' -Candidates @(
    ${env:OKIO_JAR},
    (Join-Path $UserProfileDir '.m2\repository\com\squareup\okio\okio-jvm\3.4.0\okio-jvm-3.4.0.jar')
)

$KotlinJars = Join-Path $Tools 'kotlin'
$KotlinStdlib = Join-Path $KotlinJars 'kotlin-stdlib.jar'
$R8 = Join-Path $Tools 'r8.jar'
foreach ($p in @($KotlinStdlib, $R8)) {
    if (-not (Test-Path $p)) { throw "Missing tool: $p" }
}

$KotlinCp = (Get-ChildItem $KotlinJars -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$Classes = Join-Path $Root 'build\classes'
$DexDir = Join-Path $Root 'build\dex'
foreach ($d in @($Classes, $DexDir)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d | Out-Null
}

$Sources = @(Get-ChildItem (Join-Path $Root 'src\main\java\com\shenshen\bridge') -Filter *.kt | ForEach-Object { $_.FullName })
$SourceCount = $Sources.Count

Write-Host "==> kotlinc: $SourceCount source files" -ForegroundColor Cyan
& $Java -cp $KotlinCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$AndroidJar;$OkHttp;$Okio;$KotlinStdlib" -jvm-target 1.8 -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw "kotlinc failed (exit $LASTEXITCODE)" }

$ClassFiles = @(Get-ChildItem $Classes -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$ClassCount = $ClassFiles.Count

Write-Host "==> d8: $ClassCount classes + okhttp + okio + kotlin-stdlib" -ForegroundColor Cyan
& $Java -cp $R8 com.android.tools.r8.D8 --release --min-api 24 --lib $AndroidJar --output $DexDir @ClassFiles $OkHttp $Okio $KotlinStdlib
if ($LASTEXITCODE -ne 0) { throw "d8 failed (exit $LASTEXITCODE)" }

Copy-Item (Join-Path $DexDir 'classes.dex') (Join-Path $DexDir 'classes2.dex') -Force

Write-Host "==> done" -ForegroundColor Green
Get-ChildItem $DexDir | ForEach-Object { "{0,-14} {1,8:N1} KB" -f $_.Name, ($_.Length / 1KB) }
