# Manual Android app update flow

The Android client checks GitHub release metadata at startup but does not download or install an APK automatically.

1. Compare the installed app version with the newest published `android-v*` release.
2. Show the installed and latest versions in the app update dialog.
3. Download only after the user presses the update button.
4. Validate APK size, ZIP magic, SHA-256 when GitHub provides a digest, package name, versionCode, and Android signing certificate.
5. Persist the verified APK so a restart does not require another download.
6. Install only after the user presses install. If the car is connected, send emergency stop and disconnect Bluetooth before opening Android's installer.
7. Android's Package Installer still requires the normal platform confirmation; the app never bypasses it.

The ESP32 firmware updater remains a separate flow from the Android app updater.
