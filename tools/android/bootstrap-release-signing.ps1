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
            throw "Invalid signing secret file format: $Path"
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
            throw "Missing required signing secret value: $required"
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
        throw "Failed to start GitHub CLI while setting secret: $Name"
    }
    try {
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "Failed to set GitHub secret: $Name`n$stderr"
        }
        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Host $stdout.Trim()
        }
    } finally {
        $process.Dispose()
    }
}

$resolvedOutput = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
$secretFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath(".github-release-signing.env")
$hasKeystore = Test-Path $resolvedOutput
$hasSecretFile = Test-Path $secretFile

if ($hasKeystore -and -not $hasSecretFile) {
    throw "A signing keystore exists without its recovery secret file: $resolvedOutput`nPasswords cannot be recovered safely, so the existing key will not be overwritten and a new key will not be generated."
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
        throw "ANDROID_KEYSTORE_B64 in the recovery secret file is not valid base64."
    }

    if ($hasKeystore) {
        $actualKeystoreBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($resolvedOutput))
        if ($actualKeystoreBase64 -ne $keystoreBase64) {
            throw "The existing JKS does not match the recovery secret file. Neither file will be overwritten."
        }
    } else {
        [System.IO.File]::WriteAllBytes($resolvedOutput, $expectedKeystoreBytes)
        Write-Host "Recovered signing keystore from the recovery secret file: $resolvedOutput"
    }

    Write-Host "Reusing existing persistent signing material. No new signing key was generated."
} else {
    $keytool = Get-Command keytool -ErrorAction SilentlyContinue
    if ($null -eq $keytool) {
        throw "keytool was not found. Install JDK 17+ and ensure keytool is available on PATH."
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
        throw "keytool failed to create the release keystore."
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
    Write-Host "Created new persistent signing material."
}

$uploaded = $false
if (-not $NoGitHubUpload) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($null -eq $gh) {
        Write-Warning "GitHub CLI (gh) is not installed. Install gh and rerun this script; the existing JKS/env will be reused without rotating the signing key."
    } else {
        & $gh.Source auth status | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "GitHub CLI is not authenticated. Run 'gh auth login' and rerun this script; the existing signing material will be reused."
        } else {
            Write-Host "Uploading GitHub Actions signing secrets..."
            Set-GitHubSecretExact -Name "ANDROID_KEYSTORE_B64" -Value $keystoreBase64 -RepositoryName $Repository -GhPath $gh.Source
            Set-GitHubSecretExact -Name "ANDROID_KEYSTORE_PASSWORD" -Value $storePassword -RepositoryName $Repository -GhPath $gh.Source
            Set-GitHubSecretExact -Name "ANDROID_KEY_ALIAS" -Value $Alias -RepositoryName $Repository -GhPath $gh.Source
            Set-GitHubSecretExact -Name "ANDROID_KEY_PASSWORD" -Value $keyPassword -RepositoryName $Repository -GhPath $gh.Source
            $uploaded = $true
            Write-Host "GitHub Actions signing secrets uploaded."

            if (-not $NoReleaseTrigger) {
                Write-Host "Starting the main Android release workflow..."
                & $gh.Source workflow run android.yml --repo $Repository --ref main
                if ($LASTEXITCODE -ne 0) {
                    Write-Warning "Secrets were uploaded, but workflow_dispatch failed. The next push to main can create the release."
                } else {
                    Write-Host "Release workflow dispatch requested. Actions can now build the persistent-signed APK and release."
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
Write-Warning "Do not commit release-signing.jks or .github-release-signing.env. Back up both in a secure offline location."
Write-Warning "The currently installed CI debug APK may use a different signer. The first transition may require one reinstall; later releases can update in place while the same signing key is retained."
