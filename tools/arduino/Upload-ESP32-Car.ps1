param(
    [string]$Port = ""
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$sourceIno = Join-Path $repoRoot "firmware/ESP32_CAM_RC_Controller.ino"
$sourcePartitions = Join-Path $repoRoot "firmware/partitions.csv"

if (!(Test-Path $sourceIno)) { Fail "firmware/ESP32_CAM_RC_Controller.ino not found." }
if (!(Test-Path $sourcePartitions)) { Fail "firmware/partitions.csv not found." }
if (!(Get-Command arduino-cli -ErrorAction SilentlyContinue)) {
    Fail "arduino-cli is not installed or not in PATH. Install Arduino IDE/Arduino CLI first."
}

if ([string]::IsNullOrWhiteSpace($Port)) {
    $ports = [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object
    if ($ports.Count -eq 0) { Fail "No COM port found. Connect the ESP32-CAM programmer and retry." }
    if ($ports.Count -eq 1) {
        $Port = $ports[0]
    } else {
        Write-Host "Available COM ports:"
        for ($i = 0; $i -lt $ports.Count; $i++) {
            Write-Host "[$i] $($ports[$i])"
        }
        $selection = Read-Host "Select port number"
        if ($selection -notmatch '^\d+$') { Fail "Invalid port selection." }
        $index = [int]$selection
        if ($index -lt 0 -or $index -ge $ports.Count) { Fail "Invalid port selection." }
        $Port = $ports[$index]
    }
}

Write-Host "Using port: $Port"
Write-Host "Preparing Espressif Arduino core 3.3.0..."

try { arduino-cli config init 2>$null | Out-Null } catch {}
arduino-cli config add board_manager.additional_urls https://espressif.github.io/arduino-esp32/package_esp32_index.json | Out-Null
arduino-cli core update-index

$coreList = arduino-cli core list
if ($coreList -notmatch 'esp32:esp32\s+3\.3\.0') {
    arduino-cli core install esp32:esp32@3.3.0
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("esp32-car-upload-" + [guid]::NewGuid().ToString("N"))
$sketchDir = Join-Path $tempRoot "ESP32_CAM_RC_Controller"
New-Item -ItemType Directory -Force -Path $sketchDir | Out-Null
Copy-Item $sourceIno (Join-Path $sketchDir "ESP32_CAM_RC_Controller.ino")
Copy-Item $sourcePartitions (Join-Path $sketchDir "partitions.csv")

try {
    Write-Host "Compiling the exact .ino sketch..." -ForegroundColor Cyan
    arduino-cli compile --fqbn esp32:esp32:esp32cam $sketchDir
    if ($LASTEXITCODE -ne 0) { Fail "Arduino compile failed." }

    Write-Host "Uploading through Arduino's official upload recipe..." -ForegroundColor Cyan
    Write-Host "If your ESP32-CAM does not auto-enter download mode, hold GPIO0 to GND and reset before this step."
    arduino-cli upload --verbose --port $Port --fqbn esp32:esp32:esp32cam $sketchDir
    if ($LASTEXITCODE -ne 0) { Fail "Arduino upload failed." }

    Write-Host "Upload completed." -ForegroundColor Green
    Write-Host "If GPIO0 was tied to GND, disconnect it and reset/power-cycle the board."
    Write-Host "Expected Bluetooth name after boot: ESP32_CAM_RC"
}
finally {
    Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
}
