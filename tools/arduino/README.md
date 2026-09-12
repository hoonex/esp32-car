# Arduino full recovery helper

`Upload-ESP32-Car.ps1` automates the same recovery path as a manual Arduino IDE source upload. It does **not** flash the repo's generated factory image and does not use the old raw-binary Windows flasher.

The helper now supports both repository layout and the packaged recovery ZIP layout. The previous artifact layout placed the script beside `ESP32_CAM_RC_Controller/`, while the script only searched for `../../firmware/...`; that packaging mismatch has been removed.

Recovery behavior:

1. finds `arduino-cli` from PATH, the recovery package, or a normal Arduino IDE 2.x installation,
2. pins Espressif Arduino-ESP32 core 3.3.0,
3. requires the exact `.ino + partitions.csv` pair,
4. stages them in a correctly named `ESP32_CAM_RC_Controller` sketch folder,
5. compiles with `esp32:esp32:esp32cam:EraseFlash=all,UploadSpeed=115200`, and
6. uploads with Arduino's official serial upload recipe using the same FQBN.

`EraseFlash=all` is mandatory for recovery so a stale/no-OTA partition table cannot survive the migration. The 115200 baud setting is intentionally conservative for ESP32-CAM recovery.

If the board does not auto-enter download mode, use GPIO0 -> GND and reset/power-cycle before retrying. After a successful upload, disconnect GPIO0 from GND and reset/power-cycle for normal boot.

The real ESP32-CAM result remains the final proof. CI proves the packaged sketch, partition table, script and Windows wrapper are internally consistent; it cannot physically toggle or observe a particular board.
