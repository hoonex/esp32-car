param(
    [string]$Port = ""
)

$ErrorActionPreference = "Stop"

$CoreVersion = "3.3.0"
$BoardBase = "esp32:esp32:esp32cam"
$RecoveryFqbn = "esp32:esp32:esp32cam:EraseFlash=all,UploadSpeed=115200"
$EspressifIndex = "https://espressif.github.io/arduino-esp32/package_esp32_index.json"

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Resolve-ArduinoCli {
    $command = Get-Command arduino-cli -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        (Join-Path $PSScriptRoot "arduino-cli.exe"),
        (Join-Path $env:ProgramFiles "Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe")
    )
    if (${env:ProgramFiles(x86)}) {
        $candidates += (Join-Path ${env:ProgramFiles(x86)} "Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe")
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    return $null
}

function Resolve-RecoverySources {
    $packagedSketch = Join-Path $PSScriptRoot "ESP32_CAM_RC_Controller"
    $packagedIno = Join-Path $packagedSketch "ESP32_CAM_RC_Controller.ino"
    $packagedPartitions = Join-Path $packagedSketch "partitions.csv"
    if ((Test-Path $packagedIno) -and (Test-Path $packagedPartitions)) {
        return @($packagedIno, $packagedPartitions)
    }

    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
    $repoIno = Join-Path $repoRoot "firmware/ESP32_CAM_RC_Controller.ino"
    $repoPartitions = Join-Path $repoRoot "firmware/partitions.csv"
    if ((Test-Path $repoIno) -and (Test-Path $repoPartitions)) {
        return @($repoIno, $repoPartitions)
    }

    Fail "ESP32_CAM_RC_Controller.ino + partitions.csv pair not found. Do not flash the .ino by itself."
}

function Invoke-Arduino([string[]]$Arguments) {
    & $script:ArduinoCli @Arguments
    if ($LASTEXITCODE -ne 0) {
        Fail "arduino-cli failed (exit $LASTEXITCODE): $($Arguments -join ' ')"
    }
}

$ArduinoCli = Resolve-ArduinoCli
if (!$ArduinoCli) {
    Fail "arduino-cli not found. Install Arduino IDE 2.x or place arduino-cli.exe beside this script."
}

$sources = Resolve-RecoverySources
$sourceIno = $sources[0]
$sourcePartitions = $sources[1]

if ([string]::IsNullOrWhiteSpace($Port)) {
    $ports = [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object
    if ($ports.Count -eq 0) { Fail "No COM port found. Connect the ESP32-CAM programmer and retry." }
    if ($ports.Count -eq 1) {
        $Port = $ports[0]
    } else {
        Write-Host "Available COM ports:"
        for ($i = 0; $i -lt $ports.Count; $i++) { Write-Host "[$i] $($ports[$i])" }
        $selection = Read-Host "Select port number"
        if ($selection -notmatch '^\d+$') { Fail "Invalid port selection." }
        $index = [int]$selection
        if ($index -lt 0 -or $index -ge $ports.Count) { Fail "Invalid port selection." }
        $Port = $ports[$index]
    }
}

Write-Host "Using port: $Port" -ForegroundColor Cyan
Write-Host "Arduino CLI: $ArduinoCli"
Write-Host "Recovery mode: FULL CHIP ERASE + exact .ino + adjacent partitions.csv" -ForegroundColor Yellow
Write-Host "Preparing Espressif Arduino core $CoreVersion..."

try { & $ArduinoCli config init 2>$null | Out-Null } catch {}
& $ArduinoCli config add board_manager.additional_urls $EspressifIndex 2>$null | Out-Null
Invoke-Arduino @("core", "update-index")

$coreList = & $ArduinoCli core list
if ($LASTEXITCODE -ne 0) { Fail "Unable to list Arduino cores." }
if (($coreList | Out-String) -notmatch ('esp32:esp32\s+' + [regex]::Escape($CoreVersion))) {
    Invoke-Arduino @("core", "install", "esp32:esp32@$CoreVersion")
}

$details = & $ArduinoCli board details --fqbn $BoardBase
if ($LASTEXITCODE -ne 0) { Fail "Unable to inspect AI Thinker ESP32-CAM board options." }
$detailsText = $details | Out-String
if ($detailsText -notmatch 'EraseFlash') {
    Fail "Installed ESP32 core does not expose EraseFlash; refusing a non-full recovery flash."
}
if ($detailsText -notmatch 'UploadSpeed') {
    Fail "Installed ESP32 core does not expose UploadSpeed; refusing an unknown upload recipe."
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("esp32-car-full-recovery-" + [guid]::NewGuid().ToString("N"))
$sketchDir = Join-Path $tempRoot "ESP32_CAM_RC_Controller"
New-Item -ItemType Directory -Force -Path $sketchDir | Out-Null
Copy-Item $sourceIno (Join-Path $sketchDir "ESP32_CAM_RC_Controller.ino")
Copy-Item $sourcePartitions (Join-Path $sketchDir "partitions.csv")

try {
    Write-Host "Compiling exact recovery sketch with OTA partition table..." -ForegroundColor Cyan
    Invoke-Arduino @("compile", "--fqbn", $RecoveryFqbn, $sketchDir)

    Write-Host "FULL FLASH upload through Arduino's official upload recipe..." -ForegroundColor Yellow
    Write-Host "If connection fails: GPIO0 -> GND, reset/power-cycle, then retry. Close Serial Monitor first."
    Invoke-Arduino @("upload", "--verbose", "--port", $Port, "--fqbn", $RecoveryFqbn, $sketchDir)

    Write-Host "FULL FLASH completed." -ForegroundColor Green
    Write-Host "If GPIO0 is tied to GND, disconnect it now and reset/power-cycle the board."
    Write-Host "Expected Bluetooth name after boot: ESP32_CAM_RC"
}
finally {
    Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
}
