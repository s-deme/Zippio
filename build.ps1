[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Mode = 'Debug',

    [string]$OutputDirectory = $env:ZIPPIO_OUTPUT_DIR,

    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,

    [string]$JavaHome = $env:JAVA_HOME,

    [string]$AndroidUserHome = $env:ANDROID_USER_HOME,

    [string]$BuildToolsVersion = '36.0.0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = $PSScriptRoot
$isWindowsHost = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT

function Resolve-AndroidSdkRoot {
    param([string]$RequestedRoot)

    if (-not [string]::IsNullOrWhiteSpace($RequestedRoot)) {
        return [System.IO.Path]::GetFullPath($RequestedRoot)
    }

    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        return [System.IO.Path]::GetFullPath($env:ANDROID_HOME)
    }

    $localProperties = Join-Path $repositoryRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $match = [regex]::Match(
            (Get-Content -Raw -LiteralPath $localProperties),
            '(?m)^sdk\.dir=(?<path>.+)$')
        if ($match.Success) {
            $sdkPath = $match.Groups['path'].Value.Trim()
            $sdkPath = $sdkPath.Replace('\:', ':').Replace('\\', '\')
            return [System.IO.Path]::GetFullPath($sdkPath)
        }
    }

    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME.'
}

function Resolve-JavaExecutable {
    param([string]$RequestedJavaHome)

    if (-not [string]::IsNullOrWhiteSpace($RequestedJavaHome)) {
        $resolvedJavaHome = [System.IO.Path]::GetFullPath($RequestedJavaHome)
        $javaName = if ($isWindowsHost) { 'java.exe' } else { 'java' }
        $javaPath = Join-Path $resolvedJavaHome "bin/$javaName"
        if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
            throw "JAVA_HOME does not contain $javaName."
        }
        $env:JAVA_HOME = $resolvedJavaHome
        return $javaPath
    }

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw 'Java not found. Set JAVA_HOME to JDK 17 or newer.'
    }
    return $javaCommand.Source
}

function Assert-SupportedJava {
    param([string]$JavaExecutable)

    $versionOutput = (& $JavaExecutable -version 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to run Java from the configured JDK.'
    }

    $versionMatch = [regex]::Match($versionOutput, 'version\s+"(?<major>\d+)')
    if (-not $versionMatch.Success) {
        throw 'Unable to determine the configured Java version.'
    }

    $majorVersion = [int]$versionMatch.Groups['major'].Value
    if ($majorVersion -lt 17) {
        throw "JDK 17 or newer is required; found JDK $majorVersion."
    }
}

function Assert-ReleaseSigningConfiguration {
    $requiredVariables = @(
        'ANDROID_KEYSTORE_PATH',
        'ANDROID_KEYSTORE_PASSWORD',
        'ANDROID_KEY_ALIAS',
        'ANDROID_KEY_PASSWORD'
    )
    $missingVariables = @($requiredVariables | Where-Object {
        [string]::IsNullOrWhiteSpace([System.Environment]::GetEnvironmentVariable($_))
    })

    if ($missingVariables.Count -gt 0) {
        throw "Release signing is incomplete. Missing: $($missingVariables -join ', ')."
    }

    $keystorePath = [System.IO.Path]::GetFullPath($env:ANDROID_KEYSTORE_PATH)
    if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
        throw 'ANDROID_KEYSTORE_PATH does not point to an existing keystore.'
    }

    $repositoryPrefix = [System.IO.Path]::GetFullPath($repositoryRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($keystorePath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'The release keystore must be stored outside the repository.'
    }

    $env:ANDROID_KEYSTORE_PATH = $keystorePath
}

if ($Mode -eq 'Release') {
    Assert-ReleaseSigningConfiguration
}

$resolvedSdkRoot = Resolve-AndroidSdkRoot -RequestedRoot $AndroidSdkRoot
if (-not (Test-Path -LiteralPath $resolvedSdkRoot -PathType Container)) {
    throw 'The configured Android SDK directory does not exist.'
}

$buildToolsRoot = Join-Path $resolvedSdkRoot "build-tools/$BuildToolsVersion"
if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
    throw "Android build-tools $BuildToolsVersion is not installed."
}

$javaExecutable = Resolve-JavaExecutable -RequestedJavaHome $JavaHome
Assert-SupportedJava -JavaExecutable $javaExecutable

$env:ANDROID_SDK_ROOT = $resolvedSdkRoot
$env:ANDROID_HOME = $resolvedSdkRoot

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = 'dist'
}
if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
} else {
    $resolvedOutputDirectory = [System.IO.Path]::GetFullPath(
        (Join-Path $repositoryRoot $OutputDirectory))
}
New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null

$gradleWrapper = if ($isWindowsHost) {
    Join-Path $repositoryRoot 'gradlew.bat'
} else {
    Join-Path $repositoryRoot 'gradlew'
}

$gradleTasks = if ($Mode -eq 'Release') {
    @('testDebugUnitTest', 'lintRelease', 'assembleRelease')
} else {
    @('testDebugUnitTest', 'lintDebug', 'assembleDebug')
}
$gradleArguments = @()
if (-not [string]::IsNullOrWhiteSpace($AndroidUserHome)) {
    $resolvedAndroidUserHome = [System.IO.Path]::GetFullPath($AndroidUserHome)
    New-Item -ItemType Directory -Force -Path $resolvedAndroidUserHome | Out-Null
    $env:ANDROID_USER_HOME = $resolvedAndroidUserHome
    $gradleArguments += "-Duser.home=$resolvedAndroidUserHome"
}
$gradleArguments += $gradleTasks
$gradleArguments += '--no-daemon'

Push-Location $repositoryRoot
try {
    & $gradleWrapper @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle $Mode build failed."
    }
} finally {
    Pop-Location
}

$sourceApk = if ($Mode -eq 'Release') {
    Join-Path $repositoryRoot 'app/build/outputs/apk/release/app-release.apk'
} else {
    Join-Path $repositoryRoot 'app/build/outputs/apk/debug/app-debug.apk'
}
if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
    throw "Expected APK was not produced: $sourceApk"
}

$outputName = if ($Mode -eq 'Release') { 'zippio.apk' } else { 'zippio-debug.apk' }
$outputApk = Join-Path $resolvedOutputDirectory $outputName
Copy-Item -LiteralPath $sourceApk -Destination $outputApk -Force

$hash = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = "$outputApk.sha256"
[System.IO.File]::WriteAllText(
    $checksumPath,
    "$hash  $outputName`n",
    [System.Text.Encoding]::ASCII)

$verificationArguments = @{
    ApkPath = $outputApk
    AndroidSdkRoot = $resolvedSdkRoot
    BuildToolsVersion = $BuildToolsVersion
    AllowedPermissions = @(
        'android.permission.POST_NOTIFICATIONS'
    )
}
if ($Mode -eq 'Release') {
    $verificationArguments['RejectDebugCertificate'] = $true
}
& (Join-Path $repositoryRoot 'scripts/Verify-Apk.ps1') @verificationArguments

Write-Host "$Mode APK: $outputApk"
Write-Host "SHA-256: $hash"
