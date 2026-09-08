# v4 physical validation

1. Flash `firmware/ESP32_CAM_RC_Controller.ino` once with the same Arduino IDE path that previously worked.
2. Confirm `ESP32_CAM_RC` stays connected while driving at `V255`.
3. Provision 2.4 GHz Wi-Fi from the Android app and send `X`.
4. Confirm Bluetooth driving still works after Wi-Fi connects.
5. Confirm `/api/info` reports `fw=4.0.0`, `mode=HYBRID`, and port 80 remains alive even if camera init fails.
6. Confirm MJPEG on port 81 when the OV2640 is healthy.
7. For the next firmware increment, use Android HTTP OTA and only mark success after the rebooted device reports the new `fw` value.

No Windows flasher result is authoritative for this validation. No physical OTA success is claimed until the hardware completes step 7.
