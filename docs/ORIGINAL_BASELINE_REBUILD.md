# Original-baseline rebuild

This rebuild intentionally starts from the user's known-good `ESP32-CAM RC Controller v3.0` behavior instead of layering more compatibility UI over the previous controller.

## Hardware baseline

- AI Thinker ESP32-CAM + OV2640
- L298N 2WD: right GPIO 14/15, left GPIO 13/12
- flash LED GPIO 4
- 25 kHz 8-bit motor PWM
- camera XCLK 20 MHz
- PSRAM camera: QVGA, JPEG quality 8, two frame buffers, `CAMERA_GRAB_LATEST`

## Runtime contract

- Bluetooth SPP `ESP32_CAM_RC` is always alive for drive/safety/control.
- Wi-Fi runs in parallel for camera, status and HTTP OTA.
- Wi-Fi or camera failure must never disable Bluetooth driving.
- Port 80 starts independently of camera init so OTA/status remain recoverable.
- Port 81 is MJPEG video only.
- Firmware update success is only established after the rebooted device reports the target firmware version.

## Android contract

- One clean controller surface, not stacked legacy/premium overlays.
- RC: left thumb throttle, right thumb steering.
- TANK: left/right motor sticks.
- PAD: left steering + right throttle.
- Driving always uses Bluetooth; Wi-Fi is for vision and OTA.
- APK self-update is disabled until persistent Android release signing is configured.
