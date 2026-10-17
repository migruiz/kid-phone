<#
  Builds both apps on this PC and publishes them as a GitHub release. The apps check
  github.com/migruiz/kid-phone/releases/latest and update themselves from it.

  1. Set appVersionName in gradle.properties (e.g. 1.2.0) and commit everything.
  2. Run:  .\release.ps1

  It runs the tests, builds Kid Phone and Kid Phone Parent, checks both are signed with the Kid Phone
  key (this PC's %USERPROFILE%\.android\debug.keystore; Android refuses updates signed with any other
  key), tags v<version>, pushes, and uploads kid-phone.apk and kid-phone-parent.apk.
#>
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# The certificate Kid Phone was first installed with.
$KidPhoneCert = '30a1b1b139275b396a280343bdeaaaa884eef3c725ca05933097e1a4ea2a8705'
$Repo = 'git@github.com:migruiz/kid-phone.git'

function Fail($message) { Write-Host $message -ForegroundColor Red; exit 1 }
function Run($what, [scriptblock]$block) {
    Write-Host "== $what" -ForegroundColor Cyan
    & $block
    if ($LASTEXITCODE -ne 0) { Fail "$what failed" }
}

$version = (Select-String -Path gradle.properties -Pattern '^appVersionName=(.+)$').Matches[0].Groups[1].Value.Trim()
$tag = "v$version"
if (git status --porcelain) { Fail 'Commit or stash your changes first.' }
if ((git rev-parse --abbrev-ref HEAD) -ne 'main') { Fail 'Release from the main branch.' }
if (git tag --list $tag) { Fail "$tag already exists. Raise appVersionName in gradle.properties." }
if (-not (Test-Path parent\google-services.json)) { Fail 'parent\google-services.json is missing (Firebase config for the parent app).' }

Run 'Hub tests' { Push-Location hub; node --test; $code = $LASTEXITCODE; Pop-Location; $global:LASTEXITCODE = $code }
Run "Build $tag" { .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :parent:assembleRelease --console=plain -q }

$buildTools = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object { [version]($_.Name -replace '-.*$', '') } | Select-Object -Last 1).FullName
$out = New-Item -ItemType Directory -Force build\release
$files = @{
    'app\build\outputs\apk\release\app-release.apk'       = "$out\kid-phone.apk"
    'parent\build\outputs\apk\release\parent-release.apk' = "$out\kid-phone-parent.apk"
}
foreach ($apk in $files.Keys) {
    $certs = & "$buildTools\apksigner.bat" verify --print-certs $apk
    if (-not ($certs -match $KidPhoneCert)) { Fail "$apk is not signed with the Kid Phone key." }
    Copy-Item $apk $files[$apk] -Force
}

Run "Tag and push $tag" {
    $env:GIT_SSH_COMMAND = 'ssh'  # this PC's git defaults to plink; GitHub is set up for OpenSSH
    git tag $tag
    git push -q $Repo main $tag
}
Run 'Publish the release' { gh release create $tag $files.Values --repo migruiz/kid-phone --title $tag --generate-notes }
Write-Host "Published $tag. Phones pick it up within hours (Kid Phone: hourly, with the screen off; parents: every 6 hours)." -ForegroundColor Green
