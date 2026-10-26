param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$SdkDirectory,
    [string]$NotesPath,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$counterProject = Split-Path -Parent $PSScriptRoot
$counterApk = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $counterProject 'artifacts' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$counterBuildTools = Get-ChildItem -LiteralPath (Join-Path $SdkDirectory 'build-tools') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe') } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $counterBuildTools) { throw '找不到 Android Build Tools。' }
$counterCertificate = & (Join-Path $counterBuildTools.FullName 'apksigner.bat') verify --print-certs $counterApk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败。' }
$counterExpectedSigner = (Get-Content -LiteralPath (Join-Path $counterProject 'release-signing.sha256') -Raw).Trim()
$counterSignerLine = $counterCertificate | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' } | Select-Object -First 1
if ($counterSignerLine -notmatch '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$' -or $Matches[1] -ne $counterExpectedSigner) {
    throw '签名证书与已发布应用不一致，不能用于覆盖升级。'
}
# aapt.exe cannot open Unicode Windows paths; inspect an isolated ASCII-named copy.
$counterInspection = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('diandian-inspect-' + [guid]::NewGuid().ToString('N')))
$counterInspectionApk = Join-Path $counterInspection.FullName 'release.apk'
try {
    Copy-Item -LiteralPath $counterApk -Destination $counterInspectionApk
    $counterBadging = & (Join-Path $counterBuildTools.FullName 'aapt.exe') dump badging $counterInspectionApk
    if ($LASTEXITCODE -ne 0) { throw '无法读取 APK 版本。' }
} finally {
    if (Test-Path -LiteralPath $counterInspectionApk) { Remove-Item -LiteralPath $counterInspectionApk -Force }
    Remove-Item -LiteralPath $counterInspection.FullName
}
$counterPackage = $counterBadging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($counterPackage -notmatch "name='cn.diandian.counter' versionCode='(\d+)' versionName='([\d.]+)'") {
    throw 'APK 包名或版本不符合发布要求。'
}
$counterCode = [int]$Matches[1]
$counterVersion = $Matches[2]
if ($counterVersion -notmatch '^\d+\.\d+\.\d+$') { throw '正式版本名必须为 主版本.次版本.补丁版本。' }
$counterSdk = $counterBadging | Where-Object { $_ -match "^sdkVersion:'(\d+)'" } | Select-Object -First 1
if ($counterSdk -notmatch "^sdkVersion:'(\d+)'" ) { throw '缺少最低 Android 版本。' }
$counterMinSdk = [int]$Matches[1]
$counterNotes = if ($NotesPath) { Get-Content -LiteralPath $NotesPath -Raw } else { "点点计数 $counterVersion" }
$counterHash = (Get-FileHash -LiteralPath $counterApk -Algorithm SHA256).Hash.ToLowerInvariant()
$counterName = "diandian-counter-v$counterVersion.apk"
$counterManifest = [ordered]@{
    schemaVersion = 1
    packageName = 'cn.diandian.counter'
    versionCode = $counterCode
    versionName = $counterVersion
    minSdk = $counterMinSdk
    apkUrl = "https://github.com/GodBook/diandian-counter/releases/download/v$counterVersion/$counterName"
    sha256 = $counterHash
    size = (Get-Item -LiteralPath $counterApk).Length
    notes = $counterNotes.Trim()
}
# Generated release metadata is a mechanical serialization, never contains signing secrets.
$counterManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'update.json') -Encoding utf8NoBOM
"$counterHash  $counterName" | Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding utf8NoBOM
Write-Output "Prepared v$counterVersion ($counterCode): $counterHash"
