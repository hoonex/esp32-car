# ESP32 Car First Installer

The Windows first-install package is built by GitHub Actions. It contains `ESP32-Car-Installer.exe` plus the matching bootloader, partition table, and firmware binaries.

Use this only for the initial USB/UART flash or disaster recovery. Normal firmware updates should be installed from the Android app over the device's authenticated Wi-Fi OTA endpoint.

For ESP32-CAM boards that do not auto-enter download mode, connect GPIO0 to GND, reset the board, flash, then remove GPIO0 from GND and reset again.
