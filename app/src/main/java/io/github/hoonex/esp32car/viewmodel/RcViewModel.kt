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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign


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
        if (settings.preferredMode == "WIFI") TransportMode.WIFI else TransportMode.BLUETOOTH
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
                    refreshWifiStatus()
                }
            }
        }
        viewModelScope.launch {
            bluetooth.btStatusResponse.collect { status ->
                status ?: return@collect
                status.optString("ota_key").takeIf { it.isNotBlank() }?.let { settings.otaKey = it }
                status.optString("fw").takeIf { it.isNotBlank() }?.let { settings.lastFirmwareVersion = it }
                status.optString("ip").takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let { settings.ipAddress = it }
                if (status.has("motor_swap")) settings.swapMotors = status.optBoolean("motor_swap")
                if (status.has("invert_left")) settings.invertLeftMotor = status.optBoolean("invert_left")
                if (status.has("invert_right")) settings.invertRightMotor = status.optBoolean("invert_right")
            }
        }
    }

    fun setTransportMode(mode: TransportMode) {
        emergencyStop()
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
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.light(normalized))
        }
        if (settings.ipAddress.isNotBlank()) {
            rcClient.sendLight(settings.ipAddress, normalized.toInt())
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

    fun driveVector(throttleInput: Float, steeringInput: Float) {
        val deadzone = settings.controlDeadzone.coerceIn(0.02f, 0.35f)
        fun shape(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= deadzone) return 0f
            val remapped = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            return value.sign * remapped
        }

        var throttle = shape(throttleInput.coerceIn(-1f, 1f))
        var steering = shape(steeringInput.coerceIn(-1f, 1f))
        if (settings.invertThrottle) throttle = -throttle
        if (settings.invertSteering) steering = -steering

        steering = steering.sign * abs(steering).pow(settings.steeringExpo) * settings.steeringGain
        steering = steering.coerceIn(-1f, 1f)

        val trimNorm = (settings.trim / 100f).coerceIn(-0.5f, 0.5f)
        var left = (throttle + steering + trimNorm).coerceIn(-1f, 1f)
        var right = (throttle - steering - trimNorm).coerceIn(-1f, 1f)
        val maxSpeed = settings.speed.roundToInt().coerceIn(50, 255)
        val leftPwm = (left * maxSpeed).roundToInt()
        val rightPwm = (right * maxSpeed).roundToInt()

        if (leftPwm == 0 && rightPwm == 0) {
            emergencyStop()
            return
        }

        when (_transportMode.value) {
            TransportMode.BLUETOOTH -> bluetooth.sendCommand(RcProtocol.motorMix(leftPwm, rightPwm))
            TransportMode.WIFI -> rcClient.sendMotorMix(settings.ipAddress, leftPwm, rightPwm)
        }
    }

    fun emergencyStop() {
        when (_transportMode.value) {
            TransportMode.BLUETOOTH -> bluetooth.sendCommand(RcProtocol.bluetoothDrive(DriveDirection.STOP))
            TransportMode.WIFI -> rcClient.sendMotorMix(settings.ipAddress, 0, 0)
        }
    }

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
            message = "ESP32-CAR-UPDATE Wi-Fi에 연결하세요 · 비밀번호 esp32car · IP 192.168.4.1"
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

    fun applyCameraConfig() {
        val size = when (settings.streamResolution.uppercase()) {
            "QQVGA" -> 0
            "HQVGA" -> 2
            "VGA" -> 7
            "SVGA" -> 8
            else -> 4
        }
        rcClient.setCameraConfig(
            ip = settings.ipAddress,
            frameSize = size,
            quality = settings.streamQuality.toInt(),
            brightness = settings.cameraBrightness.toInt(),
            contrast = settings.cameraContrast.toInt(),
            saturation = settings.cameraSaturation.toInt(),
            mirror = settings.cameraMirror,
            flip = settings.cameraFlip
        )
    }

    fun applyMotorConfig() {
        val swap = settings.swapMotors
        val invertLeft = settings.invertLeftMotor
        val invertRight = settings.invertRightMotor
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.motorConfig(swap, invertLeft, invertRight))
        }
        if (settings.ipAddress.isNotBlank()) {
            rcClient.setMotorConfig(settings.ipAddress, swap, invertLeft, invertRight)
        }
    }

    fun rebootDevice() {
        emergencyStop()
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.REBOOT)
        } else if (settings.ipAddress.isNotBlank()) {
            rcClient.reboot(settings.ipAddress)
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
            failFirmwareUpdate("OTA 키가 없습니다. Bluetooth 연결 후 STATUS를 새로고침하세요.")
            return
        }

        emergencyStop()
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.PREPARING,
            progress = 0,
            message = "AI Thinker 2WD 펌웨어 준비 중"
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
                        message = "ESP32로 전송 중 · $percent%"
                    )
                }
            ) { result ->
                result.onSuccess {
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.REBOOTING,
                        progress = 100,
                        message = "플래시 완료 · ESP32 재부팅 확인 중"
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
        bluetooth.sendCommand(
            RcProtocol.motorConfig(settings.swapMotors, settings.invertLeftMotor, settings.invertRightMotor)
        )
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
