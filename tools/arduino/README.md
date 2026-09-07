# Arduino upload helper

The only first-install/recovery path currently confirmed on the real AI Thinker ESP32-CAM hardware is opening `firmware/ESP32_CAM_RC_Controller.ino` in Arduino IDE and uploading the sketch normally.

`Upload-ESP32-Car.ps1` is an optional automation of that same Arduino toolchain path. It does **not** flash the repo's generated factory image and it does **not** call the old custom Windows flasher. Instead it:

1. prepares Espressif Arduino core 3.3.0,
2. copies the `.ino` and `partitions.csv` into a correctly named temporary sketch folder,
3. runs `arduino-cli compile --fqbn esp32:esp32:esp32cam`, and
4. runs `arduino-cli upload --port <COM> --fqbn esp32:esp32:esp32cam`.

This helper remains **hardware-unverified** until a real-board upload succeeds. CI only proves that the packaged sketch compiles; it cannot prove that a physical ESP32-CAM entered download mode, flashed, and booted.
