# ESP32 Car firmware

Firmware targets the AI Thinker ESP32-CAM used by the 2WD L298N camera car.

## Installation model

- **First install / recovery (physically validated path):** upload `ESP32_CAM_RC_Controller.ino` from Arduino IDE using the AI Thinker ESP32-CAM board profile. Keep `partitions.csv` in the same sketch folder so the custom OTA partition table is compiled with the sketch.
- **Do not treat the generated Windows/factory binary package as a validated recovery path.** CI can verify file presence and binary offsets, but that does not prove that a real ESP32-CAM will enter download mode, flash, and boot correctly. Until a physical board test passes, the source-sketch upload is the canonical recovery method.
- **Later updates:** the Android APK contains the matching application image. After a known-good source upload, use the Android app's authenticated HTTP OTA flow for subsequent firmware updates.
- **Offline/recovery OTA:** while Bluetooth is connected, enable the recovery/update AP, connect the phone to `ESP32-CAR-UPDATE` with password `esp32car`, and use IP `192.168.4.1` only when the HTTP status endpoint is actually reachable.

## Arduino first-install checklist

1. Put `ESP32_CAM_RC_Controller.ino` and `partitions.csv` in the same `ESP32_CAM_RC_Controller` sketch folder.
2. Open the `.ino` in the same Arduino IDE / ESP32 board environment that has already succeeded on the physical car.
3. Select the AI Thinker ESP32-CAM board profile and the correct COM port.
4. If the programmer does not auto-enter download mode, hold GPIO0 low for flashing, reset, upload, then release GPIO0 and reset again for normal boot.
5. Confirm that `ESP32_CAM_RC` appears over Classic Bluetooth before attempting app-driven OTA migration.

The physical board result is the source of truth. A CI-green precompiled flasher is not considered working until this exact board has successfully booted from it.

## OTA safety

`partitions.csv` defines two ~1.9 MiB OTA application slots plus `otadata`. New firmware is written to the inactive slot and selected only after the image is accepted by the ESP32 Update API.

The HTTP OTA endpoint is `POST /api/ota` with binary body and `X-ESP32-OTA-Key`. The key is generated on-device, stored in NVS, and shared to the Android app through Bluetooth `STATUS`.

## Protocol additions

- `U` — enable recovery/update AP (`ESP32-CAR-UPDATE` / `esp32car`)
- `STATUS` — reports firmware/update capability and `ota_key` over Bluetooth
- `GET /api/info` — firmware/update capability summary
- `POST /api/ota` — authenticated firmware update
