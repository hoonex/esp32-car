# ESP32-CAM RC firmware

Target: classic ESP32 / ESP32-CAM with `BluetoothSerial` support (AI Thinker camera pin map).

## Transport

The Android app uses **Classic Bluetooth SPP**, not BLE.

- Device name: `ESP32_CAM_RC`
- SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Commands are UTF-8 lines terminated by `\n`.

### Bluetooth commands

| Command | Meaning |
| --- | --- |
| `F` / `B` / `L` / `R` / `S` | Forward / backward / left / right / stop |
| `V50..255` | Motor speed |
| `T-50..50` | Steering trim |
| `H0..255` | Flash LED level |
| `STATUS` | Return JSON status |
| `W:SSID,PASSWORD` | Save Wi-Fi credentials |
| `X` | Switch from Bluetooth to Wi-Fi |

### Wi-Fi API

- `GET /action?go=STATUS`
- `GET /action?go=forward|backward|left|right|stop&speed=...&trim=...`
- `GET /action?light=...`
- `GET /action?go=MODE:BT` switches back to Bluetooth.
- MJPEG stream: `http://<ip>:81/stream`
- Capture: `http://<ip>/capture`

## v3.0 change

The previous firmware intentionally flipped the saved mode on every reboot. v3.0 keeps the last successful mode instead. If saved Wi-Fi cannot connect, it falls back to Bluetooth and persists BT as the safe mode.
