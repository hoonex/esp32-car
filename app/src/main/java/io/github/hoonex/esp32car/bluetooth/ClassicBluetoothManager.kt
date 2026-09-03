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


enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

@SuppressLint("MissingPermission")
class ClassicBluetoothManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs = appContext.getSharedPreferences("rc_bluetooth", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null
    private var connectJob: Job? = null
    private var listenJob: Job? = null
    private var rediscoveryJob: Job? = null

    private val discoveredByAddress = linkedMapOf<String, BluetoothDevice>()
    private var preferredAutoConnectName: String? = null
    private var pendingBondAddress: String? = null
    private var receiverRegistered = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

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
                        _lastError.value = "주변 Bluetooth 기기를 찾지 못했습니다. ESP32_CAM_RC가 휴대폰 Bluetooth 목록에 보이는지 확인한 뒤 다시 검색하세요."
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDeviceExtra() ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    discoveredByAddress[address] = device
                    _discoveredDevices.value = discoveredByAddress.values.sortedWith(
                        compareByDescending<BluetoothDevice> {
                            runCatching { it.name }.getOrNull()?.equals(DEFAULT_DEVICE_NAME, true) == true
                        }.thenBy { runCatching { it.name }.getOrNull().orEmpty() }
                    )

                    val name = runCatching { device.name }.getOrNull()
                    val preferred = preferredAutoConnectName
                    if (
                        preferred != null &&
                        name?.equals(preferred, ignoreCase = true) == true &&
                        _connectionState.value == ConnectionState.DISCONNECTED
                    ) {
                        stopDiscovery()
                        pairAndConnect(device)
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDeviceExtra() ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            discoveredByAddress[address] = device
                            _discoveredDevices.value = discoveredByAddress.values.toList()
                            if (pendingBondAddress == address) {
                                pendingBondAddress = null
                                connectToDevice(device)
                            }
                        }

                        BluetoothDevice.BOND_NONE -> {
                            if (pendingBondAddress == address && previous == BluetoothDevice.BOND_BONDING) {
                                pendingBondAddress = null
                                _lastError.value = "Bluetooth 페어링이 취소되었거나 실패했습니다. 다시 Pair & Connect를 눌러보세요."
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        registerDiscoveryReceiver()
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
    fun isBluetoothEnabled(): Boolean = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    fun getPairedDevices(): List<BluetoothDevice> = runCatching {
        bluetoothAdapter?.bondedDevices
            ?.sortedWith(compareByDescending<BluetoothDevice> {
                runCatching { it.name }.getOrNull()?.equals(DEFAULT_DEVICE_NAME, true) == true
            }.thenBy { runCatching { it.name }.getOrNull().orEmpty() })
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun lastDeviceAddress(): String? = prefs.getString(PREF_LAST_ADDRESS, null)

    fun connectPreferredOrDiscover(preferredName: String = DEFAULT_DEVICE_NAME) {
        preferredAutoConnectName = preferredName
        _lastError.value = null

        if (!isBluetoothAvailable()) {
            _lastError.value = "이 기기는 Bluetooth를 지원하지 않습니다."
            return
        }
        if (!isBluetoothEnabled()) {
            _lastError.value = "휴대폰 Bluetooth가 꺼져 있습니다. Bluetooth를 켠 뒤 다시 연결하세요."
            return
        }
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        val paired = getPairedDevices()
        val exact = paired.firstOrNull { runCatching { it.name }.getOrNull()?.equals(preferredName, true) == true }
        if (exact != null) {
            connectToDevice(exact)
            return
        }

        val lastAddress = lastDeviceAddress()
        val last = paired.firstOrNull { runCatching { it.address }.getOrNull() == lastAddress }
        if (last != null) {
            connectToDevice(last)
            return
        }

        startDiscovery(preferredName)
    }

    fun startDiscovery(preferredName: String? = preferredAutoConnectName) {
        preferredAutoConnectName = preferredName
        rediscoveryJob?.cancel()
        rediscoveryJob = null

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
                _lastError.value = "Bluetooth 검색을 시작하지 못했습니다. Nearby devices 권한을 확인하고 다시 시도하세요."
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
        val address = runCatching { device.address }.getOrElse {
            _lastError.value = "Bluetooth 기기 주소를 읽지 못했습니다."
            return
        }

        when (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)) {
            BluetoothDevice.BOND_BONDED -> connectToDevice(device)
            BluetoothDevice.BOND_BONDING -> {
                pendingBondAddress = address
                _lastError.value = "Bluetooth 페어링 승인 대기 중입니다."
            }
            else -> {
                pendingBondAddress = address
                val started = runCatching { device.createBond() }.getOrDefault(false)
                if (!started) {
                    pendingBondAddress = null
                    // Some ESP32 SPP stacks allow RFCOMM to trigger pairing themselves.
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

        rediscoveryJob?.cancel()
        rediscoveryJob = null
        stopDiscovery()
        connectJob?.cancel()
        connectJob = null
        listenJob?.cancel()
        listenJob = null
        closeSocketOnly()

        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null
        _connectedDeviceName.value = name
        _connectedDeviceAddress.value = address

        connectJob = scope.launch {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val newSocket = connectRfcomm(device)
                socket = newSocket
                outputStream = newSocket.outputStream
                reader = BufferedReader(InputStreamReader(newSocket.inputStream, Charsets.UTF_8))

                prefs.edit().putString(PREF_LAST_ADDRESS, address).apply()
                preferredAutoConnectName = runCatching { device.name }.getOrNull() ?: preferredAutoConnectName
                _connectionState.value = ConnectionState.CONNECTED
                _lastError.value = null
                startListening()
                sendCommand("STATUS")
            } catch (t: Throwable) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                markDisconnected(clearIdentity = false)
                _lastError.value = "Bluetooth 연결 실패: $reason"
                scheduleRediscovery()
            }
        }
    }

    private fun connectRfcomm(device: BluetoothDevice): BluetoothSocket {
        val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            secure.connect()
            return secure
        } catch (first: Throwable) {
            runCatching { secure.close() }
            val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                insecure.connect()
                return insecure
            } catch (second: Throwable) {
                runCatching { insecure.close() }
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun startListening() {
        listenJob?.cancel()
        listenJob = scope.launch {
            var lostLink = false
            try {
                while (_connectionState.value == ConnectionState.CONNECTED) {
                    val line = reader?.readLine() ?: run {
                        lostLink = true
                        break
                    }
                    processReceivedLine(line.trim())
                }
            } catch (t: Throwable) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    lostLink = true
                    _lastError.value = "Bluetooth 연결이 끊겼습니다: ${t.message ?: t.javaClass.simpleName}"
                }
            } finally {
                if (_connectionState.value == ConnectionState.CONNECTED || lostLink) {
                    markDisconnected(clearIdentity = false)
                    if (lostLink) scheduleRediscovery()
                }
            }
        }
    }

    private fun scheduleRediscovery() {
        val fallback = preferredAutoConnectName ?: DEFAULT_DEVICE_NAME
        rediscoveryJob?.cancel()
        rediscoveryJob = scope.launch {
            delay(500)
            if (_connectionState.value == ConnectionState.DISCONNECTED && isBluetoothEnabled()) {
                startDiscovery(fallback)
            }
        }
    }

    private fun processReceivedLine(line: String) {
        if (line.isBlank()) return

        if (line.startsWith("{")) {
            runCatching { JSONObject(line) }.getOrNull()?.let { json ->
                _btStatusResponse.value = json
                json.optString("ssid").takeIf { it.isNotBlank() }?.let { _wifiProvisionedSsid.value = it }
                json.optString("ip").takeIf { it.isNotBlank() }?.let { _wifiConnectedIp.value = it }
            }
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

    fun sendCommand(command: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val normalized = command.trimEnd('\r', '\n')
        scope.launch {
            try {
                writeMutex.withLock {
                    val out = outputStream ?: error("Bluetooth output stream unavailable")
                    out.write((normalized + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (t: Throwable) {
                _lastError.value = "Bluetooth 전송 실패: ${t.message ?: t.javaClass.simpleName}"
                disconnect(clearIdentity = false)
                scheduleRediscovery()
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun disconnect(clearIdentity: Boolean = true) {
        rediscoveryJob?.cancel()
        rediscoveryJob = null
        connectJob?.cancel()
        connectJob = null
        listenJob?.cancel()
        listenJob = null
        markDisconnected(clearIdentity)
    }

    private fun markDisconnected(clearIdentity: Boolean) {
        closeSocketOnly()
        _connectionState.value = ConnectionState.DISCONNECTED
        if (clearIdentity) {
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
        }
    }

    private fun closeSocketOnly() {
        runCatching { reader?.close() }
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        reader = null
        outputStream = null
        socket = null
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
        stopDiscovery()
        disconnect()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
        scope.cancel()
    }
}
