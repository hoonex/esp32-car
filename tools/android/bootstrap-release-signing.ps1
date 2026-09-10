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

function Read-SigningSecretFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )

    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw "잘못된 signing secret 파일 형식입니다: $Path"
        }
        $name = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        $values[$name] = $value
    }

    foreach ($required in @(
        "ANDROID_KEYSTORE_B64",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD"
    )) {
        if (-not $values.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($values[$required])) {
            throw "signing secret 파일에 필수 값이 없습니다: $required"
        }
    }

    return $values
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

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$secretFile = [System.IO.Path]::GetFullPath(".github-release-signing.env")
$hasKeystore = Test-Path $resolvedOutput
$hasSecretFile = Test-Path $secretFile

if ($hasKeystore -and -not $hasSecretFile) {
    throw "signing keystore는 있지만 recovery secret 파일이 없습니다: $resolvedOutput`n비밀번호를 안전하게 복구할 수 없으므로 새 키를 만들거나 기존 키를 덮어쓰지 않습니다."
}

if ($hasSecretFile) {
    $existingSecrets = Read-SigningSecretFile -Path $secretFile
    $keystoreBase64 = [string]$existingSecrets["ANDROID_KEYSTORE_B64"]
    $storePassword = [string]$existingSecrets["ANDROID_KEYSTORE_PASSWORD"]
    $Alias = [string]$existingSecrets["ANDROID_KEY_ALIAS"]
    $keyPassword = [string]$existingSecrets["ANDROID_KEY_PASSWORD"]

    try {
        $expectedKeystoreBytes = [Convert]::FromBase64String($keystoreBase64)
    } catch {
        throw "recovery secret 파일의 ANDROID_KEYSTORE_B64 값이 올바른 base64가 아닙니다."
    }

    if ($hasKeystore) {
        $actualKeystoreBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($resolvedOutput))
        if ($actualKeystoreBase64 -ne $keystoreBase64) {
            throw "기존 JKS와 recovery secret 파일이 서로 일치하지 않습니다. 둘 중 하나를 덮어쓰지 않고 중단합니다."
        }
    } else {
        [System.IO.File]::WriteAllBytes($resolvedOutput, $expectedKeystoreBytes)
        Write-Host "Recovery secret 파일에서 signing keystore를 복구했습니다: $resolvedOutput"
    }

    Write-Host "기존 persistent signing 자료를 재사용합니다. 새 signing key를 생성하지 않습니다."
} else {
    $keytool = Get-Command keytool -ErrorAction SilentlyContinue
    if ($null -eq $keytool) {
        throw "keytool을 찾지 못했습니다. JDK 17+를 설치하고 keytool이 PATH에 있는지 확인하세요."
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
    Write-Host "새 persistent signing 자료를 생성했습니다."
}

$uploaded = $false
if (-not $NoGitHubUpload) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($null -eq $gh) {
        Write-Warning "GitHub CLI(gh)가 없어 secret 자동 등록을 건너뜁니다. gh를 설치한 뒤 이 스크립트를 다시 실행하면 기존 JKS/env를 그대로 재사용해 업로드합니다."
    } else {
        & $gh.Source auth status | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "GitHub CLI 로그인이 되어 있지 않습니다. 'gh auth login' 후 이 스크립트를 다시 실행하면 기존 signing 자료를 재사용해 업로드합니다."
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
