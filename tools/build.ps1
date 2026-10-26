param(
    [string]$JavaDirectory,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$counterProject = Split-Path -Parent $PSScriptRoot
if ($JavaDirectory) { $env:JAVA_HOME = $JavaDirectory }
if (-not $OutputDirectory) {
    # Java workers on some Windows installations cannot read non-ASCII classpaths.
    $counterHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($counterProject))).Substring(0, 12)
    $OutputDirectory = Join-Path $env:LOCALAPPDATA "DianDianCounter\build\$counterHash"
}
Push-Location $counterProject
try {
    & '.\gradlew.bat' ":app:testDebugUnitTest" ":app:lint" ":app:assembleRelease" "-PcounterBuildDir=$OutputDirectory" --console plain
    if ($LASTEXITCODE -ne 0) { throw "构建或检查失败：$LASTEXITCODE" }
    $counterSource = Join-Path $OutputDirectory 'app\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $counterSource)) {
        throw '未生成已签名 APK。请先配置 signing.properties 和签名密钥。'
    }
    $counterArtifacts = Join-Path $counterProject 'artifacts'
    New-Item -ItemType Directory -Path $counterArtifacts -Force | Out-Null
    $counterDestination = Join-Path $counterArtifacts 'diandian-counter-v1.0.0.apk'
    Copy-Item -LiteralPath $counterSource -Destination $counterDestination -Force
    Get-Item -LiteralPath $counterDestination | Select-Object FullName, Length
    Get-FileHash -LiteralPath $counterDestination -Algorithm SHA256
} finally { Pop-Location }
