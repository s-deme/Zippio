[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v[0-9][0-9A-Za-z._-]*$')]
    [string]$Tag,

    [string]$ProjectFile = (Join-Path $PSScriptRoot '../app/build.gradle')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedProjectFile = [System.IO.Path]::GetFullPath($ProjectFile)
if (-not (Test-Path -LiteralPath $resolvedProjectFile -PathType Leaf)) {
    throw "Android project configuration not found: $resolvedProjectFile"
}

$projectContents = Get-Content -Raw -LiteralPath $resolvedProjectFile
$versionNameMatch = [regex]::Match(
    $projectContents,
    '(?m)^\s*versionName\s+[''\"](?<value>[^''\"]+)[''\"]\s*$')
$versionCodeMatch = [regex]::Match(
    $projectContents,
    '(?m)^\s*versionCode\s+(?<value>\d+)\s*$')

if (-not $versionNameMatch.Success) {
    throw 'versionName was not found in app/build.gradle.'
}
if (-not $versionCodeMatch.Success) {
    throw 'versionCode was not found in app/build.gradle.'
}

$versionName = $versionNameMatch.Groups['value'].Value
$versionCode = [long]$versionCodeMatch.Groups['value'].Value
$tagVersion = $Tag.Substring(1)

if ($versionCode -le 0) {
    throw "versionCode must be a positive integer; found $versionCode."
}
if ($tagVersion -cne $versionName) {
    throw "Tag $Tag does not match versionName $versionName."
}

Write-Host "Release version validated: versionName=$versionName versionCode=$versionCode"
