# ESP32 Car First Installer

The custom Windows first-install flasher is **not a validated recovery path on the physical ESP32-CAM car**. The package has passed CI checks for file presence and flash offsets, but repeated real-device attempts have not matched the reliability of uploading the Arduino sketch directly.

## Canonical first install

Use Arduino IDE to upload `firmware/ESP32_CAM_RC_Controller.ino` with `firmware/partitions.csv` in the same sketch folder. That is the only first-install/recovery path currently treated as physically verified.

Do not tell users that `ESP32-Car-Installer.exe`, the merged factory binary, or the multi-image package is known-good until a physical board has been flashed and booted successfully with that exact path.

The Windows flasher code remains in the repository for diagnosis, but its CI result only proves that the executable/package was built, not that the ESP32-CAM accepted and booted the image.
