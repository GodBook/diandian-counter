param(
    [Parameter(Mandatory)][string]$SdkDirectory,
    [string]$JavaDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$NotesPath
)
$ErrorActionPreference = 'Stop'
$counterProject = Split-Path -Parent $PSScriptRoot
Push-Location $counterProject
try {
    $counterDirty = git status --porcelain
    if ($counterDirty) { throw '请先提交本次发布的全部源码和说明，再创建版本。' }
    $counterOrigin = git remote get-url origin
    if ($counterOrigin -notin @('https://github.com/GodBook/diandian-counter.git', 'git@github.com:GodBook/diandian-counter.git')) {
        throw 'origin 与发布目标 GodBook/diandian-counter 不一致。'
    }
    & (Join-Path $PSScriptRoot 'build.ps1') -JavaDirectory $JavaDirectory -OutputDirectory $OutputDirectory
    $counterMetadata = Get-Content -LiteralPath (Join-Path $OutputDirectory 'app\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
    $counterVersion = $counterMetadata.elements[0].versionName
    $counterTag = "v$counterVersion"
    $counterCode = $counterMetadata.elements[0].versionCode
    $counterApk = Join-Path $counterProject "artifacts\diandian-counter-v$counterVersion.apk"
    & (Join-Path $PSScriptRoot 'prepare-release.ps1') -ApkPath $counterApk -SdkDirectory $SdkDirectory -NotesPath $NotesPath
    $counterExisting = gh release view $counterTag --repo GodBook/diandian-counter --json tagName 2>$null
    if ($LASTEXITCODE -eq 0) { throw "GitHub 上已存在 $counterTag，禁止覆盖。" }
    $counterLatest = gh api repos/GodBook/diandian-counter/releases/latest --jq .tag_name 2>$null
    if ($LASTEXITCODE -eq 0) {
        $counterPrevious = gh release download $counterLatest --repo GodBook/diandian-counter --pattern update.json --output - 2>$null
        if ($LASTEXITCODE -eq 0) {
            $counterPreviousManifest = $counterPrevious | ConvertFrom-Json
            if ($counterCode -le $counterPreviousManifest.versionCode) { throw 'versionCode 必须大于当前线上版本。' }
        } elseif ($counterLatest -ne 'v1.0.0') { throw '无法验证上一版的版本码，停止发布。' }
    }
    $counterTaggedCommit = git rev-parse --verify --quiet "refs/tags/$counterTag"
    if ($LASTEXITCODE -eq 0) {
        if ($counterTaggedCommit -ne (git rev-parse HEAD)) { throw "$counterTag 指向其他提交，禁止移动标签。" }
    } else {
        git tag $counterTag
        if ($LASTEXITCODE -ne 0) { throw '创建 Git 标签失败。' }
    }
    git push origin HEAD:main "refs/tags/$counterTag"
    if ($LASTEXITCODE -ne 0) { throw '推送源码或标签失败。' }
    $counterAssets = @($counterApk, (Join-Path $counterProject 'artifacts\update.json'), (Join-Path $counterProject 'artifacts\SHA256SUMS.txt'))
    gh release create $counterTag --repo GodBook/diandian-counter --verify-tag --draft --title "点点计数 $counterVersion" --notes-file $NotesPath @counterAssets
    if ($LASTEXITCODE -ne 0) { throw '草稿发布或附件上传失败，请检查 GitHub 草稿后重试。' }
    gh release edit $counterTag --repo GodBook/diandian-counter --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw '发布草稿失败。' }
    Write-Output "https://github.com/GodBook/diandian-counter/releases/tag/$counterTag"
} finally { Pop-Location }
