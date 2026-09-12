# ESP32 Car firmware

Current firmware: **v4.0.1**, targeting the AI Thinker ESP32-CAM used by the 2WD L298N camera car.

## Canonical first install / recovery

The physical recovery baseline is the **Arduino source sketch**, not a prebuilt raw binary flasher.

Keep these two files together inside one sketch folder named `ESP32_CAM_RC_Controller`:

- `ESP32_CAM_RC_Controller.ino`
- `partitions.csv`

Then use Espressif Arduino-ESP32 core **3.3.0**, the AI Thinker ESP32-CAM profile, and a **full-chip erase before upload**. The packaged helper/tool uses the equivalent recovery FQBN:

`esp32:esp32:esp32cam:EraseFlash=all`

Upload speed and reset behavior are left to the pinned AI Thinker board profile so the recovery tool follows Arduino's official upload recipe instead of inventing another serial recipe.

## Why the adjacent `partitions.csv` is mandatory

Core 3.3.0 reports the AI Thinker ESP32-CAM default partition scheme as **Huge APP (3MB No OTA/1MB SPIFFS)**. That is a large single-app layout with no OTA slot.

The application image is small enough to boot in that layout. This can make a bad migration look successful: Bluetooth/camera firmware boots, but there is no inactive OTA app partition for `Update.begin()`.

The repo `partitions.csv` explicitly creates:

- `otadata`
- `app0` / `ota_0` — 1900K
- `app1` / `ota_1` — 1900K

Opening/uploading the `.ino` without its adjacent `partitions.csv` can therefore recreate a board that boots normally but cannot accept OTA updates. For recovery, **do not upload the `.ino` by itself**.

## Arduino first-install checklist

1. Keep the complete `ESP32_CAM_RC_Controller` folder together.
2. Open `ESP32_CAM_RC_Controller.ino` from that folder.
3. Use ESP32 board core 3.3.0 and select AI Thinker ESP32-CAM.
4. Select the correct COM port.
5. Enable **Erase All Flash Before Sketch Upload**.
6. Leave the board's normal upload recipe/speed unchanged.
7. Upload the sketch.
8. If auto-download does not work: GPIO0 -> GND, reset/power-cycle, upload, then disconnect GPIO0 from GND and reset/power-cycle again.
9. Confirm `ESP32_CAM_RC` appears over Classic Bluetooth.

A real-board boot remains the source of truth. CI can prove that the exact source, partition table and recovery tool build correctly, but cannot prove a particular USB-UART adapter/board entered download mode and booted successfully.

## Later OTA updates

After one known-good full source recovery, the Android app can use the matching application image for authenticated HTTP OTA updates. New firmware is written to the inactive OTA slot and selected only after the ESP32 Update API accepts it.

The HTTP control/OTA server on port 80 starts before and independently of OV2640 camera initialization. A camera failure may disable `/capture` and the port-81 MJPEG stream, but must not disable `GET /api/info` or `POST /api/ota`.

`STATUS` reports `http_ready`, `stream_ready`, and the running/boot/next OTA partition information so the Android app can distinguish camera/network failures from flash-layout failures.

The HTTP OTA endpoint is `POST /api/ota` with a binary body and `X-ESP32-OTA-Key`. The key is generated on-device, stored in NVS, and shared to the Android app through Bluetooth `STATUS`.
