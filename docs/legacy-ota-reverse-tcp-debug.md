# Legacy OTA reverse-TCP diagnosis

The v3.2.0 migration path can reach the recovery AP while both HTTP :80 and the ArduinoOTA data callback appear unavailable from Android.

## What the hardware test proved

The Android v3.2.4 client completed the ArduinoOTA UDP invitation/authentication phase and then timed out waiting for the ESP32 to connect back to the phone's TCP upload listener. ArduinoOTA on ESP32 uses this reverse TCP connection after the UDP handshake.

## Android mitigation

v3.2.5 requests `NEARBY_WIFI_DEVICES` on Android 13+ so local-network sockets are explicitly authorized on recent Android versions. The ArduinoOTA client also retries callback listeners using a fixed port (3233) and both wildcard and Wi-Fi-local bind addresses before falling back to ephemeral ports.

This does not claim physical migration success. The v3.2.0 -> v3.3.0 transition still requires real-board validation.
