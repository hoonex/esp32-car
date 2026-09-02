# ESP32 Car firmware

Firmware `3.1.0` targets the AI Thinker ESP32-CAM used by the Keyestudio-style 2WD camera car.

## Installation model

- **First install:** flash `bootloader.bin`, `partitions.bin`, and `firmware.bin` by USB-UART. The GitHub Actions Windows artifact contains `ESP32-Car-Installer.exe` and all required binaries.
- **Later updates:** the Android APK contains the matching `firmware.bin`. Open **Device → Firmware Update** and update over Wi-Fi.
- **Offline/recovery:** while Bluetooth is connected, tap **복구/오프라인 업데이트 Wi-Fi 켜기**. Connect the phone to `ESP32-CAR-UPDATE` with password `esp32car`, then update using IP `192.168.4.1`.

## OTA safety

`partitions.csv` defines two ~1.9 MiB OTA application slots plus `otadata`. New firmware is written to the inactive slot and selected only after the image is accepted by the ESP32 Update API.

The HTTP OTA endpoint is `POST /api/ota` with binary body and `X-ESP32-OTA-Key`. The key is generated on-device, stored in NVS, and shared to the Android app through Bluetooth `STATUS`.

## Protocol additions

- `U` — enable recovery/update AP (`ESP32-CAR-UPDATE` / `esp32car`)
- `STATUS` — now reports `fw`, `ota`, and `ota_key` over Bluetooth
- `GET /api/info` — firmware/update capability summary
- `POST /api/ota` — authenticated firmware update
