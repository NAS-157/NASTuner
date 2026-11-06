param([switch]$OnlySetup)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
python preparar_android.py
if ($LASTEXITCODE -ne 0) { throw 'Tool download failed' }
$env:JAVA_HOME = Join-Path $PSScriptRoot '.tuner-tools/jdk-17.0.17+10'
$env:ANDROID_HOME = Join-Path $PSScriptRoot '.tuner-tools/android-sdk'
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.tuner-tools/android-user'
$buildCache = Join-Path ([System.IO.Path]::GetTempPath()) 'NASTuner-build'
$env:GRADLE_USER_HOME = Join-Path $buildCache 'gradle-cache'
$oldCache = Join-Path $PSScriptRoot '.tuner-tools/gradle-cache'
if (!(Test-Path -LiteralPath $env:GRADLE_USER_HOME) -and (Test-Path -LiteralPath $oldCache)) {
    New-Item -ItemType Directory -Force -Path $buildCache | Out-Null
    Copy-Item -LiteralPath $oldCache -Destination $env:GRADLE_USER_HOME -Recurse
}
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/19.0/bin/sdkmanager.bat'
if (!(Test-Path (Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar')) -or !(Test-Path (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/aapt2.exe'))) {
    & $sdkManager --sdk_root=$env:ANDROID_HOME 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'SDK install failed' }
}
if ($OnlySetup) { exit 0 }
& .\gradlew.bat --no-daemon --project-cache-dir (Join-Path $buildCache 'project-cache') :app:testDebugUnitTest :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
$apk = Join-Path $PSScriptRoot 'NASTuner-prueba.apk'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apk -Force
Write-Host "APK listo: $apk" -ForegroundColor Green
