package io.github.hoonex.esp32car.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hoonex.esp32car.bluetooth.ClassicBluetoothManager
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.DriveDirection
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.network.RCClient
import io.github.hoonex.esp32car.protocol.RcProtocol
import io.github.hoonex.esp32car.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject


data class FirmwareUpdateUiState(
    val stage: Stage = Stage.IDLE,
    val progress: Int = 0,
    val message: String = "",
    val bundledVersion: String = "unknown"
) {
    enum class Stage { IDLE, PREPARING, UPLOADING, REBOOTING, SUCCESS, ERROR }
}

class RcViewModel(application: Application) : AndroidViewModel(application) {
    val settings = SettingsManager(application)
    val bluetooth = ClassicBluetoothManager(application)
    val rcClient = RCClient().apply { motorTrim = settings.trim.toInt() }

    private val _transportMode = MutableStateFlow(
        if (settings.preferredMode == "BT") TransportMode.BLUETOOTH else TransportMode.WIFI
    )
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    private val _wifiStatus = MutableStateFlow<JSONObject?>(null)
    val wifiStatus: StateFlow<JSONObject?> = _wifiStatus.asStateFlow()

    private val _wifiError = MutableStateFlow<String?>(null)
    val wifiError: StateFlow<String?> = _wifiError.asStateFlow()

    private val _speed = MutableStateFlow(settings.speed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _trim = MutableStateFlow(settings.trim)
    val trim: StateFlow<Float> = _trim.asStateFlow()

    private val _light = MutableStateFlow(settings.light)
    val light: StateFlow<Float> = _light.asStateFlow()

    private val _firmwareUpdate = MutableStateFlow(
        FirmwareUpdateUiState(bundledVersion = readBundledFirmwareVersion())
    )
    val firmwareUpdate: StateFlow<FirmwareUpdateUiState> = _firmwareUpdate.asStateFlow()

    init {
        viewModelScope.launch {
            bluetooth.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    syncBluetoothTuning()
                    bluetooth.sendCommand(RcProtocol.STATUS)
                }
            }
        }
        viewModelScope.launch {
            bluetooth.wifiConnectedEvent.collect { ip ->
                if (ip.isNotBlank()) {
                    settings.ipAddress = ip
                    setTransportMode(TransportMode.WIFI)
                    refreshWifiStatus()
                }
            }
        }
        viewModelScope.launch {
            bluetooth.btStatusResponse.collect { status ->
                status ?: return@collect
                status.optString("ota_key").takeIf { it.isNotBlank() }?.let { settings.otaKey = it }
                status.optString("fw").takeIf { it.isNotBlank() }?.let { settings.lastFirmwareVersion = it }
                status.optString("ip").takeIf { it.isNotBlank() }?.let { settings.ipAddress = it }
            }
        }
    }

    fun setTransportMode(mode: TransportMode) {
        _transportMode.value = mode
        settings.preferredMode = if (mode == TransportMode.BLUETOOTH) "BT" else "WIFI"
        if (mode == TransportMode.WIFI) refreshWifiStatus()
    }

    fun pairedDevices(): List<BluetoothDevice> = bluetooth.getPairedDevices()
    fun connect(device: BluetoothDevice) = bluetooth.connectToDevice(device)
    fun reconnectLast(): Boolean = bluetooth.reconnectLastDevice()
    fun disconnectBluetooth() = bluetooth.disconnect()

    fun updateIp(ip: String) {
        settings.ipAddress = ip.trim().removePrefix("http://").removeSuffix("/")
        _wifiStatus.value = null
        _wifiError.value = null
    }

    fun updateSpeed(value: Float) {
        val normalized = value.coerceIn(50f, 255f)
        settings.speed = normalized
        _speed.value = normalized
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.speed(normalized))
        }
    }

    fun updateTrim(value: Float) {
        val normalized = value.coerceIn(-50f, 50f)
        settings.trim = normalized
        _trim.value = normalized
        rcClient.motorTrim = normalized.toInt()
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.trim(normalized))
        }
    }

    fun updateLight(value: Float) {
        val normalized = value.coerceIn(0f, 255f)
        settings.light = normalized
        _light.value = normalized
        when (_transportMode.value) {
            TransportMode.BLUETOOTH -> bluetooth.sendCommand(RcProtocol.light(normalized))
            TransportMode.WIFI -> rcClient.sendLight(settings.ipAddress, normalized.toInt())
        }
    }

    fun drive(direction: DriveDirection) {
        when (_transportMode.value) {
            TransportMode.BLUETOOTH -> bluetooth.sendCommand(RcProtocol.bluetoothDrive(direction))
            TransportMode.WIFI -> rcClient.sendCommand(
                settings.ipAddress,
                RcProtocol.wifiDrive(direction),
                settings.speed.toInt()
            )
        }
    }

    fun emergencyStop() = drive(DriveDirection.STOP)

    fun provisionWifi(ssid: String, password: String) {
        if (ssid.isBlank()) return
        bluetooth.sendCommand(RcProtocol.provisionWifi(ssid, password))
        bluetooth.sendCommand(RcProtocol.STATUS)
    }

    fun switchEsp32ToWifi() {
        bluetooth.sendCommand(RcProtocol.SWITCH_TO_WIFI)
    }

    fun startRecoveryOtaAp() {
        settings.ipAddress = "192.168.4.1"
        bluetooth.sendCommand(RcProtocol.START_OTA_AP)
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.IDLE,
            progress = 0,
            message = "ESP32-CAR-UPDATE Wi-Fi에 연결한 뒤 업데이트를 누르세요. 비밀번호: esp32car"
        )
    }

    fun refreshBluetoothStatus() {
        bluetooth.sendCommand(RcProtocol.STATUS)
    }

    fun refreshWifiStatus() {
        val ip = settings.ipAddress
        if (ip.isBlank()) {
            _wifiError.value = "ESP32 IP를 입력하세요."
            _wifiStatus.value = null
            return
        }
        _wifiError.value = null
        rcClient.requestStatus(ip) { result ->
            result.onSuccess {
                _wifiStatus.value = it
                _wifiError.value = null
                it.optString("fw").takeIf { fw -> fw.isNotBlank() }?.let { fw ->
                    settings.lastFirmwareVersion = fw
                }
            }.onFailure {
                _wifiStatus.value = null
                _wifiError.value = it.message ?: "Wi-Fi 상태 확인 실패"
            }
        }
    }

    fun updateFirmwareFromBundled() {
        val ip = settings.ipAddress
        val key = settings.otaKey
        if (ip.isBlank()) {
            failFirmwareUpdate("ESP32 IP가 없습니다.")
            return
        }
        if (key.isBlank()) {
            failFirmwareUpdate("OTA 키가 없습니다. Bluetooth로 연결한 뒤 STATUS를 한 번 새로고침하세요.")
            return
        }

        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.PREPARING,
            progress = 0,
            message = "앱에 포함된 펌웨어 준비 중"
        )

        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().assets.open("firmware/esp32-car.bin").use { it.readBytes() }
                }
            }.getOrElse {
                failFirmwareUpdate("이 APK에 firmware.bin이 포함되어 있지 않습니다: ${it.message}")
                return@launch
            }

            rcClient.uploadFirmware(
                ip = ip,
                firmware = bytes,
                otaKey = key,
                onProgress = { sent, total ->
                    val percent = if (total <= 0) 0 else ((sent * 100L) / total).toInt().coerceIn(0, 100)
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.UPLOADING,
                        progress = percent,
                        message = "ESP32로 펌웨어 전송 중 · $percent%"
                    )
                }
            ) { result ->
                result.onSuccess {
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.REBOOTING,
                        progress = 100,
                        message = "업데이트 완료. ESP32 재부팅 및 재연결 확인 중"
                    )
                    viewModelScope.launch {
                        delay(3500)
                        _firmwareUpdate.value = _firmwareUpdate.value.copy(
                            stage = FirmwareUpdateUiState.Stage.SUCCESS,
                            progress = 100,
                            message = "펌웨어 업데이트 완료"
                        )
                        refreshWifiStatus()
                    }
                }.onFailure {
                    failFirmwareUpdate(it.message ?: "펌웨어 업데이트 실패")
                }
            }
        }
    }

    fun resetFirmwareUpdateMessage() {
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.IDLE,
            progress = 0,
            message = ""
        )
    }

    fun reloadTuningFromSettings() {
        _speed.value = settings.speed
        _trim.value = settings.trim
        _light.value = settings.light
        rcClient.motorTrim = settings.trim.toInt()
    }

    private fun syncBluetoothTuning() {
        bluetooth.sendCommand(RcProtocol.speed(settings.speed))
        bluetooth.sendCommand(RcProtocol.trim(settings.trim))
        bluetooth.sendCommand(RcProtocol.light(settings.light))
    }

    private fun readBundledFirmwareVersion(): String = runCatching {
        val text = getApplication<Application>().assets.open("firmware/manifest.json")
            .bufferedReader()
            .use { it.readText() }
        JSONObject(text).optString("version", "unknown")
    }.getOrDefault("unknown")

    private fun failFirmwareUpdate(message: String) {
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.ERROR,
            message = message
        )
    }

    override fun onCleared() {
        emergencyStop()
        bluetooth.close()
        rcClient.close()
        super.onCleared()
    }
}
