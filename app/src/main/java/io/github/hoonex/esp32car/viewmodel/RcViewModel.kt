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
import java.security.MessageDigest
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
    val rcClient = RCClient().apply {
        motorTrim = settings.trim.toInt()
        controlKey = settings.otaKey
    }

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
                    _transportMode.value = TransportMode.BLUETOOTH
                    settings.preferredMode = "BT"
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
                status.optString("ota_key").takeIf { it.isNotBlank() }?.let { key ->
                    settings.otaKey = key
                    rcClient.controlKey = key
                }
                status.optString("fw").takeIf { it.isNotBlank() }?.let { settings.lastFirmwareVersion = it }
                status.optString("ip").takeIf { it.isNotBlank() && it != "0.0.0.0" }?.let { settings.ipAddress = it }
                if (status.has("motor_swap")) settings.swapMotors = status.optBoolean("motor_swap")
                if (status.has("invert_left")) settings.invertLeftMotor = status.optBoolean("invert_left")
                if (status.has("invert_right")) settings.invertRightMotor = status.optBoolean("invert_right")
                if (status.has("stream_fps")) settings.streamFps = status.optInt("stream_fps", settings.streamFps.toInt()).toFloat()
            }
        }
    }

    fun setTransportMode(mode: TransportMode) {
        emergencyStop()
        _transportMode.value = mode
        settings.preferredMode = if (mode == TransportMode.BLUETOOTH) "BT" else "WIFI"
        if (mode == TransportMode.WIFI && settings.ipAddress.isNotBlank()) refreshWifiStatus()
    }

    fun pairedDevices(): List<BluetoothDevice> = bluetooth.getPairedDevices()
    fun connect(device: BluetoothDevice) = bluetooth.connectToDevice(device)
    fun pairAndConnect(device: BluetoothDevice) = bluetooth.pairAndConnect(device)
    fun scanBluetooth() = bluetooth.startDiscovery(RcProtocol.DEVICE_NAME)
    fun stopBluetoothScan() = bluetooth.stopDiscovery()
    fun reconnectLast(): Boolean = bluetooth.reconnectLastDevice()
    fun disconnectBluetooth() = bluetooth.disconnect()

    fun isControlLinkReady(): Boolean = when (_transportMode.value) {
        TransportMode.BLUETOOTH -> bluetooth.connectionState.value == ConnectionState.CONNECTED && bluetooth.linkVerified.value
        TransportMode.WIFI -> settings.ipAddress.isNotBlank() && rcClient.controlKey.isNotBlank()
    }

    fun updateIp(ip: String) {
        settings.ipAddress = ip.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
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
            TransportMode.BLUETOOTH -> {
                if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
                    bluetooth.sendCommand(RcProtocol.light(normalized))
                }
            }
            TransportMode.WIFI -> {
                if (settings.ipAddress.isNotBlank()) rcClient.sendLight(settings.ipAddress, normalized.toInt())
            }
        }
    }

    fun drive(direction: DriveDirection) {
        when (_transportMode.value) {
            TransportMode.BLUETOOTH -> {
                if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
                    bluetooth.sendCommand(RcProtocol.bluetoothDrive(direction))
                }
            }
            TransportMode.WIFI -> {
                if (settings.ipAddress.isNotBlank()) {
                    rcClient.sendCommand(
                        settings.ipAddress,
                        RcProtocol.wifiDrive(direction),
                        settings.speed.toInt()
                    )
                }
            }
        }
    }

    fun driveVector(throttleInput: Float, steeringInput: Float) {
        if (!isControlLinkReady()) return

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
        val left = (throttle + steering + trimNorm).coerceIn(-1f, 1f)
        val right = (throttle - steering - trimNorm).coerceIn(-1f, 1f)
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
            TransportMode.BLUETOOTH -> {
                if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
                    bluetooth.sendCommand(RcProtocol.bluetoothDrive(DriveDirection.STOP))
                }
            }
            TransportMode.WIFI -> {
                if (settings.ipAddress.isNotBlank()) rcClient.sendMotorMix(settings.ipAddress, 0, 0)
            }
        }
    }

    fun provisionWifi(ssid: String, password: String) {
        if (ssid.isBlank() || bluetooth.connectionState.value != ConnectionState.CONNECTED) return
        bluetooth.sendCommand(RcProtocol.provisionWifi(ssid, password))
    }

    fun switchEsp32ToWifi() {
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.SWITCH_TO_WIFI)
        }
    }

    fun startRecoveryOtaAp() {
        if (bluetooth.connectionState.value != ConnectionState.CONNECTED) {
            failFirmwareUpdate("Bluetooth를 먼저 연결하세요.")
            return
        }
        bluetooth.sendCommand(RcProtocol.START_OTA_AP)
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.IDLE,
            progress = 0,
            message = "복구 AP 시작 요청됨 · ESP32-CAR-UPDATE / esp32car"
        )
    }

    fun refreshBluetoothStatus() {
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            bluetooth.sendCommand(RcProtocol.STATUS)
        } else {
            bluetooth.connectPreferredOrDiscover(RcProtocol.DEVICE_NAME)
        }
    }

    fun refreshWifiStatus() {
        val ip = settings.ipAddress
        if (ip.isBlank()) {
            _wifiStatus.value = null
            _wifiError.value = null
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
        if (settings.ipAddress.isBlank()) {
            _wifiError.value = "카메라를 사용하려면 먼저 ESP32 Wi-Fi를 시작하세요."
            return
        }
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
            streamFps = settings.streamFps.toInt(),
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
        } else if (settings.ipAddress.isNotBlank()) {
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
            failFirmwareUpdate("ESP32 IP가 없습니다. 먼저 Wi-Fi 또는 복구 AP를 시작하세요.")
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
            message = "번들 펌웨어 무결성 확인 중"
        )

        viewModelScope.launch {
            val bundle = withContext(Dispatchers.IO) { loadAndValidateBundledFirmware() }
                .getOrElse {
                    failFirmwareUpdate(it.message ?: "번들 펌웨어 검증 실패")
                    return@launch
                }

            rcClient.uploadFirmware(
                ip = ip,
                firmware = bundle.bytes,
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
                        message = "플래시 전송 완료 · v${bundle.version} 재부팅 검증 중"
                    )
                    verifyFirmwareAfterOta(ip, bundle.version, attempt = 0)
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

    private data class BundledFirmware(val bytes: ByteArray, val version: String, val sha256: String)

    private fun loadAndValidateBundledFirmware(): Result<BundledFirmware> = runCatching {
        val app = getApplication<Application>()
        val manifestText = app.assets.open("firmware/manifest.json").bufferedReader().use { it.readText() }
        val manifest = JSONObject(manifestText)
        val expectedSha = manifest.optString("sha256").lowercase().trim()
        val version = manifest.optString("version").ifBlank { error("펌웨어 manifest에 version이 없습니다.") }
        if (expectedSha.length != 64) error("펌웨어 manifest SHA-256이 유효하지 않습니다.")

        val bytes = app.assets.open("firmware/esp32-car.bin").use { it.readBytes() }
        if (bytes.isEmpty()) error("APK에 포함된 firmware.bin이 비어 있습니다.")
        val actualSha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) error("APK 내부 펌웨어 SHA-256 불일치")
        BundledFirmware(bytes, version, actualSha)
    }

    private fun verifyFirmwareAfterOta(ip: String, expectedVersion: String, attempt: Int) {
        viewModelScope.launch {
            delay(if (attempt == 0) 3500 else 1400)
            rcClient.requestStatus(ip) { result ->
                result.onSuccess { json ->
                    val actual = json.optString("fw")
                    if (actual == expectedVersion) {
                        settings.lastFirmwareVersion = actual
                        _wifiStatus.value = json
                        _firmwareUpdate.value = _firmwareUpdate.value.copy(
                            stage = FirmwareUpdateUiState.Stage.SUCCESS,
                            progress = 100,
                            message = "펌웨어 v$actual 부팅 확인 완료"
                        )
                    } else if (attempt < 5) {
                        verifyFirmwareAfterOta(ip, expectedVersion, attempt + 1)
                    } else {
                        _firmwareUpdate.value = _firmwareUpdate.value.copy(
                            stage = FirmwareUpdateUiState.Stage.REBOOTING,
                            progress = 100,
                            message = "전송은 완료됐지만 실행 버전 확인 실패 · Bluetooth 재연결 후 FW v$expectedVersion 확인 필요"
                        )
                    }
                }.onFailure {
                    if (attempt < 5) {
                        verifyFirmwareAfterOta(ip, expectedVersion, attempt + 1)
                    } else {
                        _firmwareUpdate.value = _firmwareUpdate.value.copy(
                            stage = FirmwareUpdateUiState.Stage.REBOOTING,
                            progress = 100,
                            message = "전송은 완료됐지만 재부팅 후 장치에 다시 닿지 않음 · Bluetooth 재연결로 v$expectedVersion 확인 필요"
                        )
                    }
                }
            }
        }
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
