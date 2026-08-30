[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,

    [string]$BuildToolsVersion = '36.0.0',

    [string]$ExpectedVersionName,

    [switch]$RejectDebugCertificate,

    [string[]]$AllowedPermissions = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$isWindowsHost = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$resolvedApkPath = [System.IO.Path]::GetFullPath($ApkPath)
if (-not (Test-Path -LiteralPath $resolvedApkPath -PathType Leaf)) {
    throw "APK not found: $resolvedApkPath"
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    throw 'ANDROID_SDK_ROOT is required to verify an APK.'
}

$resolvedSdkRoot = [System.IO.Path]::GetFullPath($AndroidSdkRoot)
$buildToolsRoot = Join-Path $resolvedSdkRoot "build-tools/$BuildToolsVersion"
$apksignerName = if ($isWindowsHost) { 'apksigner.bat' } else { 'apksigner' }
$aapt2Name = if ($isWindowsHost) { 'aapt2.exe' } else { 'aapt2' }
$apksigner = Join-Path $buildToolsRoot $apksignerName
$aapt2 = Join-Path $buildToolsRoot $aapt2Name

foreach ($tool in @($apksigner, $aapt2)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android build tool not found: $tool"
    }
}

$signatureOutput = (& $apksigner verify --verbose --print-certs $resolvedApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw 'APK signature verification failed.'
}
$signatureText = $signatureOutput -join "`n"
if ($RejectDebugCertificate -and $signatureText -match 'CN=Android Debug') {
    throw 'Release APK is signed with an Android debug certificate.'
}

$permissionOutput = (& $aapt2 dump permissions $resolvedApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect APK permissions with aapt2.'
}
$permissionText = $permissionOutput -join "`n"
$declaredPermissions = @(
    [regex]::Matches(
        $permissionText,
        '(?m)^\s*uses-permission(?:-sdk-\d+)?:\s+name=''(?<name>[^'']+)''') |
        ForEach-Object { $_.Groups['name'].Value } |
        Sort-Object -Unique
)
$unexpectedPermissions = @(
    $declaredPermissions | Where-Object { $AllowedPermissions -notcontains $_ }
)
if ($unexpectedPermissions.Count -gt 0) {
    throw "Unexpected Android permissions: $($unexpectedPermissions -join ', ')"
}

$badgingOutput = (& $aapt2 dump badging $resolvedApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the APK manifest with aapt2.'
}
$badgingText = $badgingOutput -join "`n"
$packageMatch = [regex]::Match(
    $badgingText,
    "(?m)^package:\s+name='(?<package>[^']+)'\s+versionCode='(?<code>\d+)'\s+versionName='(?<name>[^']+)'"
)
if (-not $packageMatch.Success) {
    throw 'Unable to read package version information from the APK.'
}

$versionCode = [long]$packageMatch.Groups['code'].Value
$versionName = $packageMatch.Groups['name'].Value
if ($versionCode -le 0) {
    throw "APK versionCode must be a positive integer; found $versionCode."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedVersionName) -and
        $versionName -cne $ExpectedVersionName) {
    throw "APK versionName $versionName does not match expected version $ExpectedVersionName."
}

$checksumPath = "$resolvedApkPath.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "SHA-256 sidecar not found: $checksumPath"
}
$actualHash = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedChecksumLine = "$actualHash  $([System.IO.Path]::GetFileName($resolvedApkPath))"
$actualChecksumLine = (Get-Content -Raw -LiteralPath $checksumPath).Trim()
if ($actualChecksumLine -cne $expectedChecksumLine) {
    throw 'SHA-256 sidecar does not match the APK.'
}

Write-Host 'APK signature verified.'
Write-Host "APK manifest verified: package=$($packageMatch.Groups['package'].Value) versionName=$versionName versionCode=$versionCode"
Write-Host "Android permissions verified: $($declaredPermissions.Count) declared."
Write-Host "SHA-256 verified: $actualHash"
