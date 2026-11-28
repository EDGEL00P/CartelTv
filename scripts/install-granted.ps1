param(
    [ValidateSet("debug", "release")][string]$Variant = "debug",
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\$Variant\CartelTV-$Variant.apk",
    [string]$Device,
    [switch]$GrantOnly
)

# adb writes benign rejections (unknown appop, non-runtime permission) to stderr; PS 5.1 would
# promote those to terminating errors under "Stop". Failures are checked explicitly below.
$ErrorActionPreference = "Continue"
$pkg = "com.carteltv.tv"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
$adb = if ($sdk -and (Test-Path "$sdk\platform-tools\adb.exe")) { "$sdk\platform-tools\adb.exe" } else { "adb" }

if (-not $Device) {
    $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devices.Count -eq 0) { throw "No adb device connected." }
    if ($devices.Count -gt 1) { throw "Multiple devices ($($devices -join ', ')); pass -Device <serial>." }
    $Device = $devices[0]
}

function Adb { & $adb -s $Device @args }
function AdbQuiet { & $adb -s $Device @args 2>&1 | Out-String }

if (-not $GrantOnly) {
    if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (build it first: :app:assemble$Variant)" }
    $out = AdbQuiet install -r -g $Apk
    if ($out -notmatch "Success") { throw "Install failed: $out" }
}

# Every permission the installed package requests, straight from the device.
$dump = AdbQuiet shell dumpsys package $pkg
$requested = [regex]::Matches((($dump -split "requested permissions:")[1] -split "install permissions:")[0], "^\s+(\S+?)(?::.*)?\s*$", "Multiline") |
    ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -match "^[\w.]+$" } | Sort-Object -Unique

$failed = @()
foreach ($p in $requested) {
    # Install-time/normal/special permissions reject pm grant by design; the verification below is the source of truth.
    AdbQuiet shell pm grant $pkg $p | Out-Null
}

$appops = @(
    "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "GET_USAGE_STATS",
    "REQUEST_INSTALL_PACKAGES", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM",
    "RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND", "START_FOREGROUND", "POST_NOTIFICATION"
)
foreach ($op in $appops) { AdbQuiet shell appops set $pkg $op allow | Out-Null }
AdbQuiet shell cmd deviceidle whitelist "+$pkg" | Out-Null
AdbQuiet shell cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore | Out-Null

function EnsureSetting($key, $component) {
    $cur = (Adb shell settings get secure $key | Out-String).Trim()
    if ($cur -in @("null", "")) { $new = $component }
    elseif (($cur -split ":") -contains $component) { return }
    else { $new = "${cur}:$component" }
    Adb shell settings put secure $key $new | Out-Null
}
EnsureSetting "enabled_accessibility_services" "$pkg/$pkg.data.remap.KeyRemapService"
Adb shell settings put secure accessibility_enabled 1 | Out-Null
EnsureSetting "enabled_notification_listeners" "$pkg/$pkg.guard.GuardRevival"

# Verify, don't assume.
$dump = AdbQuiet shell dumpsys package $pkg
$denied = [regex]::Matches($dump, "^\s+(\S+): granted=false", "Multiline") | ForEach-Object { $_.Groups[1].Value }
$denied | ForEach-Object { $failed += "$_ -> still denied" }
$ops = AdbQuiet shell appops get $pkg
foreach ($op in "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "GET_USAGE_STATS", "REQUEST_INSTALL_PACKAGES") {
    if ($ops -notmatch "${op}: allow") { $failed += "appop $op not allowed" }
}
if ((Adb shell settings get secure enabled_accessibility_services | Out-String) -notmatch [regex]::Escape("$pkg.data.remap.KeyRemapService")) { $failed += "accessibility service not enabled" }
if ((Adb shell settings get secure enabled_notification_listeners | Out-String) -notmatch [regex]::Escape("$pkg.guard.GuardRevival")) { $failed += "notification listener not enabled" }

if ($failed.Count) {
    Write-Host "FAILED ($($failed.Count)):" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "OK: $pkg on $Device - $($requested.Count) requested permissions processed, all grants verified."
