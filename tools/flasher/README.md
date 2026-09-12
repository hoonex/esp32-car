# ESP32 Car Arduino Full Recovery

The old custom Windows raw-binary flasher is no longer the recovery implementation. Real-device attempts did not match the reliability of the Arduino source upload, so the GUI now wraps the same Arduino toolchain path instead of calling Python `esptool` directly.

`ESP32-Car-Arduino-Recovery.exe` bundles:

- `arduino-cli`,
- `ESP32_CAM_RC_Controller.ino`, and
- the required adjacent `partitions.csv`.

On recovery it pins Arduino-ESP32 core 3.3.0, creates the exact sketch folder, compiles the source and uploads with:

`esp32:esp32:esp32cam:EraseFlash=all,UploadSpeed=115200`

That means the board is fully erased and Arduino itself writes the bootloader, partition table, boot_app0 and application using its official upload recipe. The previous separate `esptool erase-flash` + hard-coded raw image write path is not used.

The GUI keeps the complete Arduino CLI output in a scrollable log. If a real board still fails, the final log should distinguish download-mode/serial-port failures from compile/core failures instead of hiding them behind a generic installer error.

A CI-green EXE is still not called physically verified until the real AI Thinker ESP32-CAM is flashed and boots successfully. The manual Arduino recovery ZIP remains the fallback/source-of-truth path.
