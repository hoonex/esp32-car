package io.github.hoonex.esp32car.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong


enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

@SuppressLint("MissingPermission")
class ClassicBluetoothManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = appContext.getSharedPreferences("rc_bluetooth", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val generation = AtomicLong(0L)

    @Volatile private var connectingSocket: BluetoothSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var reader: BufferedReader? = null
    private var connectJob: Job? = null
    private var listenJob: Job? = null
    private var handshakeJob: Job? = null

    private data class PendingCommand(val generation: Long, val command: String)
    private val driveCommands = Channel<PendingCommand>(Channel.CONFLATED)
    private val controlCommands = Channel<PendingCommand>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val discoveredByAddress = linkedMapOf<String, BluetoothDevice>()
    private var pendingBondAddress: String? = null
    private var receiverRegistered = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _linkVerified = MutableStateFlow(false)
    val linkVerified: StateFlow<Boolean> = _linkVerified.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _wifiProvisionedSsid = MutableStateFlow<String?>(null)
    val wifiProvisionedSsid: StateFlow<String?> = _wifiProvisionedSsid.asStateFlow()

    private val _wifiConnectedIp = MutableStateFlow<String?>(null)
    val wifiConnectedIp: StateFlow<String?> = _wifiConnectedIp.asStateFlow()

    private val _btStatusResponse = MutableStateFlow<JSONObject?>(null)
    val btStatusResponse: StateFlow<JSONObject?> = _btStatusResponse.asStateFlow()

    private val _wifiConnectedEvent = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val wifiConnectedEvent = _wifiConnectedEvent.asSharedFlow()

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PREF_LAST_ADDRESS = "last_device_address"
        private const val DEFAULT_DEVICE_NAME = "ESP32_CAM_RC"
        private const val EXPECTED_PROFILE = "AI_THINKER_ESP32_CAM_2WD_L298N"
        private const val HANDSHAKE_TIMEOUT_MS = 3500L
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isDiscovering.value = true
                    _lastError.value = null
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isDiscovering.value = false
                    if (_connectionState.value == ConnectionState.DISCONNECTED && discoveredByAddress.isEmpty()) {
                        _lastError.value = "ESP32_CAM_RC를 찾지 못했습니다. 보드 전원이 켜져 있고 휴대폰 Bluetooth 목록에 보이는지 확인하세요."
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDeviceExtra() ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    discoveredByAddress[address] = device
                    publishDiscovered()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDeviceExtra() ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            discoveredByAddress[address] = device
                            publishDiscovered()
                            if (pendingBondAddress == address) {
                                pendingBondAddress = null
                                connectToDevice(device)
                            }
                        }

                        BluetoothDevice.BOND_NONE -> {
                            if (pendingBondAddress == address && previous == BluetoothDevice.BOND_BONDING) {
                                pendingBondAddress = null
                                _lastError.value = "Bluetooth 페어링이 취소되었거나 실패했습니다."
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        registerDiscoveryReceiver()
        scope.launch { for (pending in driveCommands) writePending(pending) }
        scope.launch { for (pending in controlCommands) writePending(pending) }
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean =
        runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    fun getPairedDevices(): List<BluetoothDevice> = runCatching {
        bluetoothAdapter?.bondedDevices
            ?.sortedWith(
                compareByDescending<BluetoothDevice> {
                    runCatching { it.name }.getOrNull()?.equals(DEFAULT_DEVICE_NAME, true) == true
                }.thenBy { runCatching { it.name }.getOrNull().orEmpty() }
            ) ?: emptyList()
    }.getOrDefault(emptyList())

    fun lastDeviceAddress(): String? = prefs.getString(PREF_LAST_ADDRESS, null)

    /** Legacy API: discovery only. Bluetooth connection remains explicitly user initiated. */
    fun connectPreferredOrDiscover(preferredName: String = DEFAULT_DEVICE_NAME) {
        startDiscovery(preferredName)
    }

    fun startDiscovery(preferredName: String? = null) {
        if (!isBluetoothAvailable()) {
            _lastError.value = "이 기기는 Bluetooth를 지원하지 않습니다."
            return
        }
        if (!isBluetoothEnabled()) {
            _lastError.value = "휴대폰 Bluetooth가 꺼져 있습니다."
            return
        }
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        discoveredByAddress.clear()
        _discoveredDevices.value = emptyList()
        _lastError.value = null

        runCatching {
            bluetoothAdapter?.cancelDiscovery()
            val started = bluetoothAdapter?.startDiscovery() == true
            _isDiscovering.value = started
            if (!started) {
                _lastError.value = "Bluetooth 검색을 시작하지 못했습니다. Nearby devices 권한을 확인하세요."
            }
        }.onFailure {
            _isDiscovering.value = false
            _lastError.value = it.message ?: "Bluetooth 검색 시작 실패"
        }
    }

    fun stopDiscovery() {
        runCatching { bluetoothAdapter?.cancelDiscovery() }
        _isDiscovering.value = false
    }

    fun pairAndConnect(device: BluetoothDevice) {
        stopDiscovery()
        _lastError.value = null

        val address = runCatching { device.address }.getOrElse {
            _lastError.value = "Bluetooth 기기 주소를 읽지 못했습니다."
            return
        }

        when (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)) {
            BluetoothDevice.BOND_BONDED -> connectToDevice(device)
            BluetoothDevice.BOND_BONDING -> {
                pendingBondAddress = address
                _lastError.value = "휴대폰의 Bluetooth 페어링 요청을 승인하세요."
            }
            else -> {
                pendingBondAddress = address
                val started = runCatching { device.createBond() }.getOrDefault(false)
                if (!started) {
                    pendingBondAddress = null
                    connectToDevice(device)
                }
            }
        }
    }

    fun reconnectLastDevice(): Boolean {
        if (!isBluetoothEnabled()) {
            _lastError.value = "휴대폰 Bluetooth가 꺼져 있습니다."
            return false
        }
        val address = lastDeviceAddress() ?: return false
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        connectToDevice(device)
        return true
    }

    fun connectToDevice(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrElse {
            _lastError.value = "Bluetooth 권한이 필요합니다."
            return
        }
        val name = runCatching { device.name }.getOrNull() ?: address

        stopDiscovery()
        val myGeneration = generation.incrementAndGet()
        cancelConnectionJobsAndSockets()

        _connectionState.value = ConnectionState.CONNECTING
        _linkVerified.value = false
        _lastError.value = null
        _connectedDeviceName.value = name
        _connectedDeviceAddress.value = address

        connectJob = scope.launch {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val newSocket = connectRfcomm(device, myGeneration)
                if (generation.get() != myGeneration) {
                    runCatching { newSocket.close() }
                    return@launch
                }

                val newOutput = newSocket.outputStream
                val newReader = BufferedReader(InputStreamReader(newSocket.inputStream, Charsets.UTF_8))
                socket = newSocket
                outputStream = newOutput
                reader = newReader
                connectingSocket = null

                prefs.edit().putString(PREF_LAST_ADDRESS, address).apply()
                startListening(myGeneration, newSocket, newReader)
                enqueueControl("STATUS", myGeneration)

                handshakeJob?.cancel()
                handshakeJob = scope.launch {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    if (generation.get() == myGeneration && !_linkVerified.value) {
                        _lastError.value = "ESP32 응답 확인 실패: 연결은 열렸지만 STATUS handshake가 오지 않았습니다."
                        disconnectGeneration(myGeneration, clearIdentity = false)
                    }
                }
            } catch (t: Throwable) {
                if (generation.get() == myGeneration) {
                    val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    _lastError.value = "Bluetooth 연결 실패: $reason"
                    disconnectGeneration(myGeneration, clearIdentity = false)
                }
            } finally {
                if (generation.get() == myGeneration) connectJob = null
            }
        }
    }

    private fun connectRfcomm(device: BluetoothDevice, myGeneration: Long): BluetoothSocket {
        fun connect(candidate: BluetoothSocket): BluetoothSocket {
            if (generation.get() != myGeneration) {
                runCatching { candidate.close() }
                error("Connection cancelled")
            }
            connectingSocket = candidate
            candidate.connect()
            if (generation.get() != myGeneration) {
                runCatching { candidate.close() }
                error("Connection cancelled")
            }
            connectingSocket = null
            return candidate
        }

        val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            return connect(secure)
        } catch (first: Throwable) {
            runCatching { secure.close() }
            if (generation.get() != myGeneration) throw first
            val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                return connect(insecure)
            } catch (second: Throwable) {
                runCatching { insecure.close() }
                second.addSuppressed(first)
                throw second
            }
        } finally {
            if (connectingSocket === secure) connectingSocket = null
        }
    }

    private fun startListening(myGeneration: Long, localSocket: BluetoothSocket, localReader: BufferedReader) {
        listenJob?.cancel()
        listenJob = scope.launch {
            var lostLink = false
            try {
                while (generation.get() == myGeneration && socket === localSocket) {
                    val line = localReader.readLine() ?: run {
                        lostLink = true
                        break
                    }
                    processReceivedLine(line.trim(), myGeneration)
                }
            } catch (t: Throwable) {
                if (generation.get() == myGeneration && socket === localSocket) {
                    lostLink = true
                    _lastError.value = "Bluetooth 연결이 끊겼습니다: ${t.message ?: t.javaClass.simpleName}"
                }
            } finally {
                if (generation.get() == myGeneration && socket === localSocket && lostLink) {
                    disconnectGeneration(myGeneration, clearIdentity = false)
                }
                if (generation.get() == myGeneration) listenJob = null
            }
        }
    }

    private fun processReceivedLine(line: String, myGeneration: Long) {
        if (line.isBlank() || generation.get() != myGeneration) return

        if (line.startsWith("{")) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return
            val profile = json.optString("profile")
            val board = json.optString("board")
            if (profile != EXPECTED_PROFILE) {
                _lastError.value = "호환되지 않는 SPP 기기입니다: ${profile.ifBlank { board.ifBlank { "STATUS profile 없음" } }}"
                disconnectGeneration(myGeneration, clearIdentity = false)
                return
            }

            _btStatusResponse.value = json
            json.optString("ssid").takeIf { it.isNotBlank() }?.let { _wifiProvisionedSsid.value = it }
            json.optString("ip").takeIf { it.isNotBlank() }?.let { _wifiConnectedIp.value = it }
            _linkVerified.value = true
            _connectionState.value = ConnectionState.CONNECTED
            handshakeJob?.cancel()
            handshakeJob = null
            return
        }

        when {
            line.startsWith("OK:WIFI_SAVED:") -> {
                _wifiProvisionedSsid.value = line.substringAfter("OK:WIFI_SAVED:").trim()
            }
            line.startsWith("OK:WIFI_CONNECTED:") -> {
                val ip = line.substringAfter("OK:WIFI_CONNECTED:").trim()
                _wifiConnectedIp.value = ip
                _wifiConnectedEvent.tryEmit(ip)
            }
            line.startsWith("OK:RECOVERY_AP:") || line.startsWith("OK:OTA_AP:") -> {
                val ip = line.substringAfterLast(':').trim()
                if (ip.isNotBlank()) {
                    _wifiConnectedIp.value = ip
                    _wifiConnectedEvent.tryEmit(ip)
                }
            }
            line.startsWith("ERR:NO_WIFI_CREDENTIALS") -> {
                _wifiProvisionedSsid.value = null
                _wifiConnectedIp.value = null
                _lastError.value = "ESP32에 저장된 Wi-Fi 정보가 없습니다."
            }
            line.startsWith("ERR:") -> _lastError.value = line
        }
    }

    /**
     * Drive commands are conflated: if Bluetooth stalls, only the newest motion command survives.
     * This prevents stale joystick packets from being replayed after congestion. Configuration and
     * status commands keep FIFO ordering in a small bounded queue.
     */
    fun sendCommand(command: String) {
        if (_connectionState.value != ConnectionState.CONNECTED || !_linkVerified.value) return
        val normalized = command.trimEnd('\r', '\n')
        val myGeneration = generation.get()
        if (isDriveCommand(normalized)) {
            driveCommands.trySend(PendingCommand(myGeneration, normalized))
        } else {
            controlCommands.trySend(PendingCommand(myGeneration, normalized))
        }
    }

    private fun enqueueControl(command: String, myGeneration: Long) {
        controlCommands.trySend(PendingCommand(myGeneration, command.trimEnd('\r', '\n')))
    }

    private fun isDriveCommand(command: String): Boolean =
        command.startsWith("M:") || command == "F" || command == "B" ||
            command == "L" || command == "R" || command == "S"

    private suspend fun writePending(pending: PendingCommand) {
        if (pending.generation != generation.get()) return
        try {
            writeMutex.withLock {
                if (pending.generation != generation.get()) return@withLock
                val out = outputStream ?: return@withLock
                out.write((pending.command + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (t: Throwable) {
            if (pending.generation == generation.get()) {
                _lastError.value = "Bluetooth 전송 실패: ${t.message ?: t.javaClass.simpleName}"
                disconnectGeneration(pending.generation, clearIdentity = false)
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun disconnect(clearIdentity: Boolean = true) {
        stopDiscovery()
        pendingBondAddress = null
        generation.incrementAndGet()
        cancelConnectionJobsAndSockets()
        _connectionState.value = ConnectionState.DISCONNECTED
        _linkVerified.value = false
        if (clearIdentity) {
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
        }
    }

    private fun disconnectGeneration(myGeneration: Long, clearIdentity: Boolean) {
        if (generation.get() != myGeneration) return
        generation.incrementAndGet()
        cancelConnectionJobsAndSockets()
        _connectionState.value = ConnectionState.DISCONNECTED
        _linkVerified.value = false
        if (clearIdentity) {
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
        }
    }

    private fun cancelConnectionJobsAndSockets() {
        handshakeJob?.cancel()
        handshakeJob = null
        connectJob?.cancel()
        connectJob = null
        listenJob?.cancel()
        listenJob = null
        runCatching { connectingSocket?.close() }
        connectingSocket = null
        closeSocketOnly()
    }

    private fun closeSocketOnly() {
        val localReader = reader
        val localOutput = outputStream
        val localSocket = socket
        reader = null
        outputStream = null
        socket = null
        runCatching { localReader?.close() }
        runCatching { localOutput?.close() }
        runCatching { localSocket?.close() }
    }

    private fun publishDiscovered() {
        _discoveredDevices.value = discoveredByAddress.values.sortedWith(
            compareByDescending<BluetoothDevice> {
                runCatching { it.name }.getOrNull()?.equals(DEFAULT_DEVICE_NAME, true) == true
            }.thenBy { runCatching { it.name }.getOrNull().orEmpty() }
        )
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(discoveryReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure {
            _lastError.value = it.message ?: "Bluetooth receiver 등록 실패"
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    fun close() {
        disconnect()
        driveCommands.close()
        controlCommands.close()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
        scope.cancel()
    }
}
