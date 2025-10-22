$ErrorActionPreference = "Stop"

# Resolve repo root and change directory
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# ADB path (uses your installed SDK)
$adb = "C:\Users\EXOUSIA\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
  Write-Error "adb not found at $adb. Install Android SDK platform-tools or update the path in this script."; exit 1
}

# Build APK
Write-Host "Building debug APK..." -ForegroundColor Cyan
& "$root\gradlew.bat" assembleDebug

$apk = "$root\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
  Write-Error "APK not found at $apk. Build may have failed."; exit 1
}

# List connected devices/emulators
Write-Host "Listing connected devices..." -ForegroundColor Cyan
$devicesOutput = & $adb devices

# Parse device IDs that end with status 'device'
$deviceIds = @()
foreach ($line in $devicesOutput) {
  if ($line -match "^\S+\s+device$") {
    $device = ($line -replace "\s+device$", "").Trim()
    $deviceIds += $device
  }
}

if (-not $deviceIds -or $deviceIds.Count -eq 0) {
  Write-Error "No connected devices/emulators detected. Please start at least one Android emulator via Android Studio."; exit 1
}

Write-Host "Found devices: $($deviceIds -join ', ')" -ForegroundColor Green

# Install and launch on each device
foreach ($d in $deviceIds) {
  Write-Host "Installing on $d ..." -ForegroundColor Yellow
  & $adb -s $d install -r -t $apk

  Write-Host "Launching MainActivity on $d ..." -ForegroundColor Yellow
  & $adb -s $d shell am start -n "com.btsi.swiftcab/.MainActivity"
}

Write-Host "All done. App launched on: $($deviceIds -join ', ')" -ForegroundColor Green
