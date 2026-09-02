package io.github.hoonex.esp32car.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
    fun isBluetoothEnabled(): Boolean = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    fun getPairedDevices(): List<BluetoothDevice> = runCatching {
        bluetoothAdapter?.bondedDevices
            ?.sortedWith(compareBy({ it.name ?: "" }, { it.address }))
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun lastDeviceAddress(): String? = prefs.getString(PREF_LAST_ADDRESS, null)

    fun reconnectLastDevice(): Boolean {
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

        connectJob?.cancel()
        listenJob?.cancel()
        closeSocketOnly()

        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null
        _connectedDeviceName.value = name
        _connectedDeviceAddress.value = address

        connectJob = scope.launch {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = newSocket
                newSocket.connect()
                outputStream = newSocket.outputStream
                reader = BufferedReader(InputStreamReader(newSocket.inputStream, Charsets.UTF_8))

                prefs.edit().putString(PREF_LAST_ADDRESS, address).apply()
                _connectionState.value = ConnectionState.CONNECTED
                startListening()
                sendCommand("STATUS")
            } catch (t: Throwable) {
                _lastError.value = t.message ?: t.javaClass.simpleName
                disconnect(clearIdentity = false)
            }
        }
    }

    private fun startListening() {
        listenJob?.cancel()
        listenJob = scope.launch {
            try {
                while (_connectionState.value == ConnectionState.CONNECTED) {
                    val line = reader?.readLine() ?: break
                    processReceivedLine(line.trim())
                }
            } catch (t: Throwable) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    _lastError.value = t.message ?: "Bluetooth link lost"
                }
            } finally {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    disconnect(clearIdentity = false)
                }
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
                    outputStream?.write((normalized + "\n").toByteArray(Charsets.UTF_8))
                    outputStream?.flush()
                }
            } catch (t: Throwable) {
                _lastError.value = t.message ?: "Bluetooth write failed"
                disconnect(clearIdentity = false)
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun disconnect(clearIdentity: Boolean = true) {
        connectJob?.cancel()
        connectJob = null
        listenJob?.cancel()
        listenJob = null
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

    fun close() {
        disconnect()
        scope.cancel()
    }
}
