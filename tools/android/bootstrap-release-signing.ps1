param(
    [string]$OutputPath = "release-signing.jks",
    [string]$Alias = "esp32-car"
)

$ErrorActionPreference = "Stop"

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if ($null -eq $keytool) {
    throw "keytool을 찾지 못했습니다. JDK 17+를 설치하고 keytool이 PATH에 있는지 확인하세요."
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$secretFile = [System.IO.Path]::GetFullPath(".github-release-signing.env")

if (Test-Path $resolvedOutput) {
    throw "이미 signing keystore가 있습니다: $resolvedOutput`n기존 키를 덮어쓰거나 재생성하지 마세요."
}
if (Test-Path $secretFile) {
    throw "이미 secret export 파일이 있습니다: $secretFile`n기존 signing 자료가 있는지 먼저 확인하세요."
}

$storePassword = New-RandomSecret
$keyPassword = New-RandomSecret

& $keytool.Source `
    -genkeypair `
    -v `
    -keystore $resolvedOutput `
    -storetype JKS `
    -storepass $storePassword `
    -keypass $keyPassword `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname "CN=ESP32 Car, OU=Personal, O=hoonex, L=Daegu, C=KR"

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $resolvedOutput)) {
    throw "keytool이 release keystore를 만들지 못했습니다."
}

$keystoreBytes = [System.IO.File]::ReadAllBytes($resolvedOutput)
$keystoreBase64 = [Convert]::ToBase64String($keystoreBytes)

$secretText = @"
ANDROID_KEYSTORE_B64=$keystoreBase64
ANDROID_KEYSTORE_PASSWORD=$storePassword
ANDROID_KEY_ALIAS=$Alias
ANDROID_KEY_PASSWORD=$keyPassword
"@

[System.IO.File]::WriteAllText($secretFile, $secretText, [System.Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "Persistent Android signing bootstrap complete."
Write-Host "Keystore: $resolvedOutput"
Write-Host "GitHub secrets export: $secretFile"
Write-Host ""
Write-Host "다음 단계: GitHub repository Settings > Secrets and variables > Actions 에서"
Write-Host ".github-release-signing.env의 4개 값을 각각 Repository secret으로 등록하세요."
Write-Host ""
Write-Warning "release-signing.jks와 secret export 파일을 Git에 커밋하지 마세요. JKS는 안전한 곳에 백업하고 절대 재생성하지 마세요."
Write-Warning "기존 CI debug APK는 signer가 다르므로 첫 persistent-signed release 전환 때는 한 번 재설치가 필요합니다."
