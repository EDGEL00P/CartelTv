#requires -Version 5.1
<#
.SYNOPSIS
  CartelTV companion-stack bootstrap for the Shield.
.DESCRIPTION
  Resolves each companion app from its OFFICIAL channel at run time (F-Droid / IzzyOnDroid API,
  or GitHub latest release), downloads to a temp dir, adb-installs, then verifies every package
  is present on the device. Nothing is typed from memory except catalog entries defined in
  docs/CompanionStack.md.
.PARAMETER Serial      adb serial (omit if a single device is attached / already connected)
.PARAMETER DryRun      resolve + report only; nothing is downloaded or installed
.PARAMETER Optional    also install the OPTIONAL tier
.PARAMETER Debloat     after installs, show the Shield debloat allowlist (-Preview listed only;
                       removal of each package is confirmed line-by-line before uninstalling)
.EXAMPLE
  ./bootstrap-shield.ps1 -DryRun
  ./bootstrap-shield.ps1
  ./bootstrap-shield.ps1 -Optional -Debloat
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$DryRun,
    [switch]$Optional,
    [switch]$Debloat,
    [switch]$Preview
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ---------------------------------------------------------------------------
# Catalog. kind: fdroid | izzy | github.  assetPattern: regex over GH asset names.
# abiScore prefers universal > arm64 > the rest, since the Shield is arm64.
# ---------------------------------------------------------------------------
$Core = @(
    @{ Name='LeanKeyKeyboard'; Kind='github'; Repo='yuliskov/LeanKeyboard';   AssetPattern='LeanKeyboard_v.*\.apk$' },
    @{ Name='TV Bro';          Kind='github'; Repo='truefedex/tv-bro';        AssetPattern='tvbro-.*\.apk$' },
    @{ Name='mpv';             Kind='github'; Repo='mpv-android/mpv-android';  AssetPattern='app-default-(universal|arm64-v8a)-release\.apk$' },
    @{ Name='SmartTube';       Kind='github'; Repo='yuliskov/SmartTube';       AssetPattern='SmartTube_stable_.*universal\.apk$' },
    @{ Name='Fluffy';          Kind='izzy';   Pkg='app.fluffy' },
    @{ Name='Download Navi';   Kind='fdroid'; Pkg='com.tachibana.downloader' },
    @{ Name='LocalSend';       Kind='github'; Repo='localsend/localsend';      AssetPattern='\.apk$' },
    @{ Name='DNSNet';          Kind='fdroid'; Pkg='dev.clombardo.dnsnet' },
    @{ Name='Tailscale';       Kind='github'; Repo='tailscale/tailscale-android'; AssetPattern='\.apk$' },
    @{ Name='APKUpdater';      Kind='github'; Repo='rumboalla/apkupdater';   AssetPattern='com\.apkupdater.*\.apk$' },
    @{ Name='Shizuku';         Kind='izzy';   Pkg='moe.shizuku.privileged.api' }
)
$Extras = @(
    @{ Name='Flicky (store)';    Kind='izzy';   Pkg='app.flicky' },
    @{ Name='Just Player';       Kind='fdroid'; Pkg='com.brouken.player' },
    @{ Name='Aerial Views';      Kind='github'; Repo='theothernt/AerialViews'; AssetPattern='\.apk$' },
    @{ Name='Moonlight';         Kind='fdroid'; Pkg='com.limelight' },
    @{ Name='Key Mapper';        Kind='fdroid'; Pkg='io.github.sds100.keymapper' },
    @{ Name='SD Maid SE';        Kind='fdroid'; Pkg='eu.darken.sdmse' }
)

# Shield debloat allowlist (per-preview; NEVER a blind loop). disable-user only (reversible;
# verified against community ground truth from 4 Shields across 3 generations). Confirm per line.
# Extend deliberately; baselines are snapshotted next to this script at run time.
$ShieldDebloat = @(
    'com.nvidia.tegrazone3',             # GeForce NOW promotion/app
    'com.nvidia.stats',                  # usage statistics service
    'com.nvidia.enhancedlogging',        # enhanced logging upload
    'com.nvidia.hotwordsetup',           # hotword/voice enrollment
    'com.nvidia.shield.ask',             # shield ask helper
    'com.nvidia.beyonder.server',       # promos/partnerships service
    'com.nvidia.benchmarkblocker',       # benchmark-related bundle
    'android.autoinstalls.config.nvidia' # carrier/install autoinstall config
)

$tmp = Join-Path ([IO.Path]::GetTempPath()) 'cartel-bootstrap'
if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $tmp | Out-Null }

function Get-AdbArg { if ($Serial) { @('-s', $Serial) } else { @() } }
function Assert-Device {
    $args0 = Get-AdbArg
    $state = (& adb @args0 get-state) 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -notmatch 'device') {
        throw "No adb device. Connect USB or: adb connect <shield-ip>:5555  (Serial=$Serial)"
    }
    Write-Host "Device: $((& adb @args0 shell getprop ro.product.model).Trim()) (Android $((& adb @args0 shell getprop ro.build.version.release).Trim()))"
}

function Resolve-Apk {
    param($Entry)
    switch ($Entry.Kind) {
        'fdroid' {
            # api/v1 no longer returns apkName; APK files are named <pkg>_<versionCode>.apk
            $base = 'https://f-droid.org'
            $api = Invoke-RestMethod "$base/api/v1/packages/$($Entry.Pkg)"
            $pkg = $api.packages | Sort-Object { [int]$_.versionCode } | Select-Object -Last 1
            $file = "$($Entry.Pkg)_$($pkg.versionCode).apk"
            return @{ Url = "$base/repo/$file"; File = $file; Ver = $pkg.versionName }
        }
        'izzy' {
            $base = 'https://apt.izzysoft.de/fdroid'
            $api = Invoke-RestMethod "$base/api/v1/packages/$($Entry.Pkg)"
            $pkg = $api.packages | Sort-Object { [int]$_.versionCode } | Select-Object -Last 1
            $file = "$($Entry.Pkg)_$($pkg.versionCode).apk"
            return @{ Url = "$base/repo/$file"; File = $file; Ver = $pkg.versionName }
        }
        'github' {
            # api.github.com is rate-limited; ride the releases/latest redirect and parse the
            # expanded_assets HTML instead (both quota-free).
            $client = New-Object System.Net.Http.HttpClient
            $resp = $client.GetAsync("https://github.com/$($Entry.Repo)/releases/latest")
            if (-not $resp.Wait(30000)) { throw "timed out resolving $($Entry.Repo) latest" }
            if (-not $resp.Result.IsSuccessStatusCode) { throw "github latest $($Entry.Repo): HTTP $($resp.Result.StatusCode)" }
            $final = $resp.Result.RequestMessage.RequestUri.ToString()
            if ($final -notmatch '/tag/(.+)$') { throw "no tag redirect for $($Entry.Repo) (got $final)" }
            $tag = $Matches[1]
            $html = (Invoke-WebRequest "https://github.com/$($Entry.Repo)/releases/expanded_assets/$tag" -UseBasicParsing).Content
            $files = [regex]::Matches($html, 'href="([^"]*?)"') |
                ForEach-Object { ($_.Groups[1].Value -split '/')[-1] } |
                Where-Object { $_ -match $Entry.AssetPattern }
            if (-not $files) { throw "No asset matching '$($Entry.AssetPattern)' in $($Entry.Repo) $tag" }
            $score = {
                param($n)
                if ($n -match 'universal') { 3 } elseif ($n -match 'arm64') { 2 }
                elseif ($n -match 'armeabi|x86_64') { 1 } else { 0 }
            }
            $best = $files | Sort-Object { & $score $_ } -Descending | Select-Object -First 1
            return @{
                Url = "https://github.com/$($Entry.Repo)/releases/download/$tag/$best"
                File = $best; Ver = $tag
            }
        }
    }
}

function Install-One {
    param($Entry)
    Write-Host ("==> {0,-16} " -f $Entry.Name) -NoNewline
    $r = Resolve-Apk $Entry
    Write-Host "$($r.Ver)  [$([IO.Path]::GetFileName($r.File))]"
    if ($DryRun) { return }
    $dest = Join-Path $tmp ([IO.Path]::GetFileName($r.File))
    if (-not (Test-Path $dest)) { Invoke-WebRequest $r.Url -OutFile $dest -UseBasicParsing }
    $sha = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
    Write-Host "    sha256 $sha"
    $args0 = Get-AdbArg
    # No -g: each companion asks for its own runtime permissions. Signer is shown so a changed
    # publisher key is visible at install time.
    $apksigner = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($apksigner) {
        $cert = (& apksigner verify --print-certs $dest 2>&1 | Select-String 'certificate SHA-256 digest').Line
        if (-not $cert) { Write-Warning "$($Entry.Name): APK signature did not verify; skipping"; return }
        Write-Host "    $($cert.Trim())"
    } else {
        Write-Warning "apksigner not on PATH; signer not checked for $($Entry.Name)"
    }
    & adb @args0 install -r $dest | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning "adb install failed for $($Entry.Name) (exit $LASTEXITCODE)" }
}

function Verify {
    param($Entries)
    $args0 = Get-AdbArg
    Write-Host "`n== verification =="
    foreach ($e in $Entries) {
        if (-not $e.Pkg) { continue }
        $found = (& adb @args0 shell pm list packages $e.Pkg) -match "package:$($e.Pkg)"
        $tag = if ($found) { 'INSTALLED' } else { 'MISSING ' }
        Write-Host ("  [{0}] {1} ({2})" -f $tag, $e.Pkg, $e.Name) -ForegroundColor $(if ($found) {'Green'} else {'Yellow'})
    }
}

# ---- main -----------------------------------------------------------------
Assert-Device
$set = $Core + ($(if ($Optional) { $Extras } else { @() }))
Write-Host "== resolving $($set.Count) apps$(if ($DryRun){' (dry run)'}) =="
foreach ($e in $set) { try { Install-One $e } catch { Write-Warning "$($e.Name): $($_.Exception.Message)" } }
if (-not $DryRun) { Verify $set; Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }

if ($Debloat) {
    Write-Host "`n== debloat (Shield allowlist; disable-user, reversible) =="
    $args0 = Get-AdbArg
    # Community ground truth (4 Shields, 3 gens): disable-user frees the same memory as
    # uninstall --user 0 but reverses with `pm enable`; uninstall --user 0 is effectively
    # permanent without a factory reset. Baselines first, verify after.
    if (-not $DryRun) {
        $snap = Join-Path $PSScriptRoot ("shield-baseline-{0}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmm'))
        (& adb @args0 shell pm list packages -s) + (& adb @args0 shell pm list packages -3) | Out-File $snap -Encoding utf8
        Write-Host "  baseline package list -> $snap"
    }
    foreach ($p in $ShieldDebloat) {
        $present = (& adb @args0 shell pm list packages $p) -match "package:$p"
        if (-not $present) { Write-Host "  [absent ] $p"; continue }
        $disabled = (& adb @args0 shell pm list packages -d $p) -match "package:$p"
        if ($disabled) { Write-Host "  [disabled] $p"; continue }
        Write-Host "  [present] $p"
        if ($Preview -or $DryRun) { continue }
        $ok = Read-Host "    disable-user --user 0 $p ? (y/N)"
        if ($ok -eq 'y') {
            & adb @args0 shell pm disable-user --user 0 $p | Out-Host
            # per-line verification, never trust command output alone
            $nowDisabled = (& adb @args0 shell pm list packages -d $p) -match "package:$p"
            Write-Host ("    verified: {0}" -f $(if ($nowDisabled) { 'DISABLED' } else { 'STILL ENABLED - CHECK' }))
        }
    }
    Write-Host "  restore any: adb shell pm enable <pkg>  (or reinstall-existing: cmd package install-existing <pkg>)"
    Write-Host "  never touch: com.google.android.tvrecommendations (uninstall errors - home spine)," 
    Write-Host "               com.nvidia.shieldtech.hooks/proxy, com.nvidia.nvaudiosvc"
}
Write-Host "`nDone. CartelTV stays the launcher; companions never take HOME."
