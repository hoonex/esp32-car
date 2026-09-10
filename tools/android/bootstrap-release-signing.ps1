param(
    [string]$OutputPath = "release-signing.jks",
    [string]$Alias = "esp32-car",
    [string]$Repository = "hoonex/esp32-car",
    [switch]$NoGitHubUpload,
    [switch]$NoReleaseTrigger
)

$ErrorActionPreference = "Stop"

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function Set-GitHubSecretExact {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$RepositoryName,
        [Parameter(Mandatory = $true)][string]$GhPath
    )

    # Do not use PowerShell's normal pipeline here: it can append a newline to stdin, which is
    # harmless for base64 but can corrupt Android keystore/key passwords. Write the exact value to
    # gh's redirected stdin. This works on both Windows PowerShell 5.1 and PowerShell 7+.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $GhPath
    $psi.Arguments = "secret set `"$Name`" --repo `"$RepositoryName`""
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi

    if (-not $process.Start()) {
        throw "GitHub CLI 시작 실패: $Name"
    }
    try {
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "GitHub secret 등록 실패: $Name`n$stderr"
        }
        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Host $stdout.Trim()
        }
    } finally {
        $process.Dispose()
    }
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

# Local recovery copy. This file and the JKS are gitignored. Keep both somewhere private because
# losing this key breaks Android's in-place update lineage permanently.
$secretText = @"
ANDROID_KEYSTORE_B64=$keystoreBase64
ANDROID_KEYSTORE_PASSWORD=$storePassword
ANDROID_KEY_ALIAS=$Alias
ANDROID_KEY_PASSWORD=$keyPassword
"@
[System.IO.File]::WriteAllText($secretFile, $secretText, [System.Text.UTF8Encoding]::new($false))

$uploaded = $false
if (-not $NoGitHubUpload) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($null -eq $gh) {
        Write-Warning "GitHub CLI(gh)가 없어 secret 자동 등록을 건너뜁니다. 'gh'를 설치한 뒤 이 파일의 4개 값을 Repository secrets에 등록하세요."
    } else {
        & $gh.Source auth status | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "GitHub CLI 로그인이 되어 있지 않습니다. 'gh auth login' 후 secret 등록을 다시 진행하세요."
        } else {
            Write-Host "GitHub Actions signing secrets 등록 중..."
            Set-GitHubSecretExact -Name "ANDROID_KEYSTORE_B64" -Value $keystoreBase64 -RepositoryName $Repository -GhPath $gh.Source
            Set-GitHubSecretExact -Name "ANDROID_KEYSTORE_PASSWORD" -Value $storePassword -RepositoryName $Repository -GhPath $gh.Source
            Set-GitHubSecretExact -Name "ANDROID_KEY_ALIAS" -Value $Alias -RepositoryName $Repository -GhPath $gh.Source
            Set-GitHubSecretExact -Name "ANDROID_KEY_PASSWORD" -Value $keyPassword -RepositoryName $Repository -GhPath $gh.Source
            $uploaded = $true
            Write-Host "GitHub Actions signing secrets 등록 완료."

            if (-not $NoReleaseTrigger) {
                Write-Host "main Android release workflow 시작 중..."
                & $gh.Source workflow run android.yml --repo $Repository --ref main
                if ($LASTEXITCODE -ne 0) {
                    Write-Warning "secret은 등록됐지만 workflow_dispatch 시작은 실패했습니다. 다음 main push에서 자동으로 release가 만들어집니다."
                } else {
                    Write-Host "Release workflow 요청 완료. Actions에서 persistent-signed APK/release가 생성됩니다."
                }
            }
        }
    }
}

Write-Host ""
Write-Host "Persistent Android signing bootstrap complete."
Write-Host "Keystore: $resolvedOutput"
Write-Host "Recovery secret file: $secretFile"
Write-Host "Repository: $Repository"
Write-Host "GitHub secrets uploaded: $uploaded"
Write-Host ""
Write-Warning "release-signing.jks와 .github-release-signing.env를 Git에 커밋하지 마세요. 둘 다 안전한 오프라인 위치에 백업하세요."
Write-Warning "현재 설치된 CI debug APK와 새 persistent-signed APK는 signer가 다를 수 있습니다. 첫 전환에서만 한 번 재설치가 필요하며, 그 이후부터는 앱이 GitHub release를 받아 인플레이스 자동업데이트합니다."
