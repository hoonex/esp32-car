package io.github.hoonex.esp32car.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hoonex.esp32car.bluetooth.ClassicBluetoothManager
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.DriveDirection
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.network.LanArduinoOtaClient
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

    private val _legacyMigrationSession = MutableStateFlow(false)
    val legacyMigrationSession: StateFlow<Boolean> = _legacyMigrationSession.asStateFlow()

    private var lastRequestedDevice: BluetoothDevice? = null
    private var migrationExpectedVersion: String? = null
    private var otaSourceVersion: String? = null
    private var legacyFallbackAttempted = false
    private var legacyFallbackFirmware: ByteArray? = null
    private var legacyFallbackKey: String? = null
    private var legacyFallbackIp: String? = null

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
                if (ip.isNotBlank() && ip != "0.0.0.0") {
                    settings.ipAddress = ip
                    _wifiError.value = null
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

                val reportedIp = status.optString("ip").trim()
                if (reportedIp.isNotBlank() && reportedIp != "0.0.0.0") {
                    settings.ipAddress = reportedIp
                } else if (!firmwareUpdateBusy()) {
                    settings.ipAddress = ""
                }

                if (status.has("motor_swap")) settings.swapMotors = status.optBoolean("motor_swap")
                if (status.has("invert_left")) settings.invertLeftMotor = status.optBoolean("invert_left")
                if (status.has("invert_right")) settings.invertRightMotor = status.optBoolean("invert_right")
                if (status.has("stream_fps")) {
                    settings.streamFps = status.optInt("stream_fps", settings.streamFps.toInt()).toFloat()
                }

                val expected = migrationExpectedVersion
                val actual = status.optString("fw")
                val updateStage = _firmwareUpdate.value.stage
                if (
                    expected != null &&
                    (updateStage == FirmwareUpdateUiState.Stage.REBOOTING || updateStage == FirmwareUpdateUiState.Stage.ERROR) &&
                    actual == expected &&
                    status.optInt("protocol", 1) >= 2
                ) {
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.SUCCESS,
                        progress = 100,
                        message = if (reportedIp.isNotBlank() && reportedIp != "0.0.0.0") {
                            "펌웨어 v$actual Bluetooth 부팅 확인 완료 · Wi-Fi $reportedIp 재확인 중"
                        } else {
                            "펌웨어 v$actual Bluetooth 부팅 확인 완료 · Wi-Fi는 필요할 때 다시 연결할 수 있습니다."
                        }
                    )
                    clearMigrationTracking()
                    if (reportedIp.isNotBlank() && reportedIp != "0.0.0.0") refreshWifiStatus()
                }
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

    fun connect(device: BluetoothDevice) {
        lastRequestedDevice = device
        bluetooth.connectToDevice(device)
    }

    fun pairAndConnect(device: BluetoothDevice) {
        lastRequestedDevice = device
        bluetooth.pairAndConnect(device)
    }

    fun scanBluetooth() = bluetooth.startDiscovery(RcProtocol.DEVICE_NAME)
    fun stopBluetoothScan() = bluetooth.stopDiscovery()

    fun reconnectLast(): Boolean {
        val remembered = lastRequestedDevice
        if (remembered != null) {
            bluetooth.connectToDevice(remembered)
            return true
        }
        return bluetooth.reconnectLastDevice()
    }

    fun disconnectBluetooth() {
        if (!firmwareUpdateBusy()) clearMigrationTracking()
        bluetooth.disconnect()
    }

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
        _wifiStatus.value = null
        _wifiError.value = null
        bluetooth.sendCommand(RcProtocol.provisionWifi(ssid, password))
    }

    fun switchEsp32ToWifi() {
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
            _wifiStatus.value = null
            _wifiError.value = null
            bluetooth.sendCommand(RcProtocol.SWITCH_TO_WIFI)
            viewModelScope.launch {
                // v3.3.1 can block its Bluetooth command loop for roughly six seconds while the
                // station associates. v4 allows up to ten seconds. Probe after both windows instead
                // of declaring the link healthy from a stale saved IP.
                delay(10_500)
                refreshBluetoothStatus()
                delay(750)
                if (settings.ipAddress.isNotBlank()) refreshWifiStatus()
            }
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
                it.optString("ip").trim().takeIf { value -> value.isNotBlank() && value != "0.0.0.0" }?.let { value ->
                    settings.ipAddress = value
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
            failFirmwareUpdate("ESP32 IP가 없습니다. 먼저 Wi-Fi 연결을 확인하세요.")
            return
        }
        if (key.isBlank()) {
            failFirmwareUpdate("OTA 키가 없습니다. Bluetooth 연결 후 STATUS를 새로고침하세요.")
            return
        }

        val sourceVersion = bluetooth.btStatusResponse.value?.optString("fw").orEmpty()
            .ifBlank { settings.lastFirmwareVersion }
        otaSourceVersion = sourceVersion
        val v3ToV4Migration = sourceVersion.trim().startsWith("3.")
        _legacyMigrationSession.value = v3ToV4Migration
        legacyFallbackAttempted = false
        legacyFallbackFirmware = null
        legacyFallbackKey = null
        legacyFallbackIp = null

        emergencyStop()
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.PREPARING,
            progress = 0,
            message = if (v3ToV4Migration) {
                "v$sourceVersion → v4 마이그레이션 준비 · 번들 무결성 확인 중"
            } else {
                "번들 펌웨어 무결성 확인 중"
            }
        )

        viewModelScope.launch {
            val bundle = withContext(Dispatchers.IO) { loadAndValidateBundledFirmware() }
                .getOrElse {
                    clearMigrationTracking()
                    failFirmwareUpdate(it.message ?: "번들 펌웨어 검증 실패")
                    return@launch
                }

            if (sourceVersion == bundle.version) {
                clearMigrationTracking()
                _firmwareUpdate.value = _firmwareUpdate.value.copy(
                    stage = FirmwareUpdateUiState.Stage.SUCCESS,
                    progress = 100,
                    message = "이미 펌웨어 v${bundle.version}가 설치되어 있습니다."
                )
                return@launch
            }

            // Always keep the expected version until either Wi-Fi or Bluetooth proves the new boot.
            migrationExpectedVersion = bundle.version
            if (v3ToV4Migration) {
                legacyFallbackFirmware = bundle.bytes
                legacyFallbackKey = key
                legacyFallbackIp = ip
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
                        message = "HTTP OTA 전송 중 · $percent%"
                    )
                }
            ) { result ->
                result.onSuccess {
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.REBOOTING,
                        progress = 100,
                        message = if (v3ToV4Migration) {
                            "HTTP OTA 전송 완료 · v${bundle.version} 실제 부팅 확인 중"
                        } else {
                            "플래시 전송 완료 · v${bundle.version} 재부팅 검증 중"
                        }
                    )
                    verifyFirmwareAfterOta(ip, bundle.version, attempt = 0, v3ToV4Migration = v3ToV4Migration)
                }.onFailure {
                    clearMigrationTracking()
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
        clearMigrationTracking()
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
        // Motor swap/inversion are device calibration. Never overwrite them automatically on connect.
        // STATUS imports the device's actual values; changes are sent only by applyMotorConfig().
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

    private fun verifyFirmwareAfterOta(
        originalIp: String,
        expectedVersion: String,
        attempt: Int,
        v3ToV4Migration: Boolean
    ) {
        viewModelScope.launch {
            if (_firmwareUpdate.value.stage == FirmwareUpdateUiState.Stage.SUCCESS) return@launch
            delay(if (attempt == 0) 3_500 else 1_800)
            if (_firmwareUpdate.value.stage == FirmwareUpdateUiState.Stage.SUCCESS) return@launch

            // OTA reboot tears down SPP. Wi-Fi may also come back on a different DHCP address.
            // Reconnect the same bonded ESP32 and ask STATUS; that is the authoritative fallback.
            if (attempt >= 1 && bluetooth.connectionState.value == ConnectionState.DISCONNECTED) {
                _firmwareUpdate.value = _firmwareUpdate.value.copy(
                    stage = FirmwareUpdateUiState.Stage.REBOOTING,
                    progress = 100,
                    message = if (v3ToV4Migration) {
                        "v$expectedVersion 부팅 확인 중 · Bluetooth 재연결 + Wi-Fi 재탐색"
                    } else {
                        "v$expectedVersion 부팅 확인 중 · Bluetooth 보조 검증"
                    }
                )
                val remembered = lastRequestedDevice
                if (remembered != null) bluetooth.connectToDevice(remembered) else reconnectLast()
            } else if (bluetooth.connectionState.value == ConnectionState.CONNECTED) {
                bluetooth.sendCommand(RcProtocol.STATUS)
            }

            val probeIp = settings.ipAddress.takeIf { it.isNotBlank() } ?: originalIp
            rcClient.requestStatus(probeIp) { result ->
                result.onSuccess { json ->
                    val actual = json.optString("fw")
                    if (actual == expectedVersion) {
                        settings.lastFirmwareVersion = actual
                        json.optString("ip").trim().takeIf { value -> value.isNotBlank() && value != "0.0.0.0" }?.let { value ->
                            settings.ipAddress = value
                        }
                        _wifiStatus.value = json
                        _wifiError.value = null
                        _firmwareUpdate.value = _firmwareUpdate.value.copy(
                            stage = FirmwareUpdateUiState.Stage.SUCCESS,
                            progress = 100,
                            message = "펌웨어 v$actual Wi-Fi 부팅 확인 완료"
                        )
                        clearMigrationTracking()
                    } else if (attempt < 10) {
                        verifyFirmwareAfterOta(originalIp, expectedVersion, attempt + 1, v3ToV4Migration)
                    } else {
                        finishFirmwareVerificationTimeout(expectedVersion, v3ToV4Migration)
                    }
                }.onFailure {
                    if (attempt < 10) {
                        verifyFirmwareAfterOta(originalIp, expectedVersion, attempt + 1, v3ToV4Migration)
                    } else {
                        finishFirmwareVerificationTimeout(expectedVersion, v3ToV4Migration)
                    }
                }
            }
        }
    }

    private fun finishFirmwareVerificationTimeout(expectedVersion: String, v3ToV4Migration: Boolean) {
        if (_firmwareUpdate.value.stage == FirmwareUpdateUiState.Stage.SUCCESS) return
        val observed = bluetooth.btStatusResponse.value?.optString("fw").orEmpty()

        // Field evidence from v3.3.1 showed a full HTTP upload followed by an authoritative
        // Bluetooth STATUS that still reported v3.3.1. Only in that proven rollback case do we
        // invoke the independent ArduinoOTA transport, and only once per user update request.
        if (
            v3ToV4Migration &&
            observed.startsWith("3.") &&
            observed != expectedVersion &&
            !legacyFallbackAttempted &&
            legacyFallbackFirmware != null &&
            !legacyFallbackKey.isNullOrBlank()
        ) {
            attemptLegacyArduinoOtaFallback(expectedVersion)
            return
        }

        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.ERROR,
            progress = 100,
            message = when {
                observed.isNotBlank() && observed != expectedVersion && legacyFallbackAttempted ->
                    "HTTP OTA와 ArduinoOTA 2차 경로 후에도 Bluetooth에서 v${observed}가 확인됩니다. 부팅 파티션 진단이 필요합니다."
                observed.isNotBlank() && observed != expectedVersion ->
                    "v$expectedVersion 전송 후 현재 Bluetooth에서 v${observed}가 확인됩니다."
                v3ToV4Migration ->
                    "v$expectedVersion 전송은 끝났지만 Wi-Fi와 Bluetooth 모두에서 새 부팅을 확인하지 못했습니다. 재연결하면 늦게라도 자동으로 성공 판정합니다."
                else ->
                    "v$expectedVersion 전송은 끝났지만 Wi-Fi/Bluetooth 부팅 확인이 아직 없습니다. 재연결 후 STATUS로 다시 확인합니다."
            }
        )
        // Keep migrationExpectedVersion on purpose. A late Bluetooth reconnect can still convert
        // this timeout into SUCCESS when the device reports the exact expected firmware version.
    }

    private fun attemptLegacyArduinoOtaFallback(expectedVersion: String) {
        val firmware = legacyFallbackFirmware ?: return
        val key = legacyFallbackKey?.takeIf { it.isNotBlank() } ?: return
        val ip = settings.ipAddress.takeIf { it.isNotBlank() }
            ?: legacyFallbackIp?.takeIf { it.isNotBlank() }
            ?: run {
                failFirmwareUpdate("3.3.1 ArduinoOTA 2차 경로를 시작할 ESP32 IP가 없습니다.")
                return
            }

        legacyFallbackAttempted = true
        _firmwareUpdate.value = _firmwareUpdate.value.copy(
            stage = FirmwareUpdateUiState.Stage.REBOOTING,
            progress = 0,
            message = "HTTP OTA 후 v3.x 재부팅 확인 · ArduinoOTA 독립 경로로 1회 복구 시도"
        )

        viewModelScope.launch(Dispatchers.IO) {
            // Give v3.3.1 time to restore its station link and ArduinoOTA UDP listener after reboot.
            delay(1_500)
            val result = LanArduinoOtaClient.upload(
                remoteHost = ip,
                firmware = firmware,
                password = key,
                onProgress = { sent, total ->
                    val percent = if (total <= 0) 0 else ((sent * 100L) / total).toInt().coerceIn(0, 100)
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.UPLOADING,
                        progress = percent,
                        message = "3.3.1 ArduinoOTA 복구 전송 중 · $percent%"
                    )
                }
            )

            withContext(Dispatchers.Main) {
                result.onSuccess {
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.REBOOTING,
                        progress = 100,
                        message = "ArduinoOTA 2차 전송 완료 · v$expectedVersion 실제 부팅 재검증 중"
                    )
                    verifyFirmwareAfterOta(ip, expectedVersion, attempt = 0, v3ToV4Migration = true)
                }.onFailure { error ->
                    _firmwareUpdate.value = _firmwareUpdate.value.copy(
                        stage = FirmwareUpdateUiState.Stage.ERROR,
                        progress = 100,
                        message = "HTTP OTA 후 v3.x 복귀 + ArduinoOTA 2차 경로 실패: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private fun clearMigrationTracking() {
        _legacyMigrationSession.value = false
        migrationExpectedVersion = null
        otaSourceVersion = null
        legacyFallbackAttempted = false
        legacyFallbackFirmware = null
        legacyFallbackKey = null
        legacyFallbackIp = null
    }

    private fun readBundledFirmwareVersion(): String = runCatching {
        val text = getApplication<Application>().assets.open("firmware/manifest.json")
            .bufferedReader()
            .use { it.readText() }
        JSONObject(text).optString("version", "unknown")
    }.getOrDefault("unknown")

    private fun firmwareUpdateBusy(): Boolean = when (_firmwareUpdate.value.stage) {
        FirmwareUpdateUiState.Stage.PREPARING,
        FirmwareUpdateUiState.Stage.UPLOADING,
        FirmwareUpdateUiState.Stage.REBOOTING -> true
        else -> false
    }

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
