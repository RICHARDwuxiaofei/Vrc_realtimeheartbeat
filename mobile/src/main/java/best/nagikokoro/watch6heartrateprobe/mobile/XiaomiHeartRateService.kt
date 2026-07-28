package best.nagikokoro.watch6heartrateprobe.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import best.nagikokoro.watch6heartrateprobe.R
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * On-demand BLE bridge for Xiaomi Band's built-in "Share HR" mode.
 *
 * This service is never started in Galaxy Watch mode. In Xiaomi mode it scans
 * only for the Bluetooth SIG Heart Rate Service, connects to one selected
 * device, and subscribes to Heart Rate Measurement notifications.
 */
class XiaomiHeartRateService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private var active = true
    private var reconnectAddress: String? = null
    private var reconnectName: String? = null
    private var reconnectTicket = 0L
    private val intentionallyClosedGatts = Collections.newSetFromMap(
        ConcurrentHashMap<BluetoothGatt, Boolean>(),
    )

    private val scanner
        get() = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    override fun onCreate() {
        super.onCreate()
        PhoneRelayRepository.initialize(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待小米手环"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_STOP && !PhoneRelayRepository.isXiaomiMode()) {
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> {
                active = false
                stopScan()
                closeGatt()
                PhoneRelayRepository.updateXiaomiConnection(
                    status = "已切回 Galaxy Watch",
                    connected = false,
                    scanning = false,
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SCAN -> {
                active = true
                closeGatt()
                startScan()
            }
            ACTION_CONNECT -> {
                active = true
                val address = intent?.getStringExtra(EXTRA_ADDRESS)
                val name = intent?.getStringExtra(EXTRA_NAME)
                if (address.isNullOrBlank()) {
                    PhoneRelayRepository.reportXiaomiError("没有可连接的小米手环地址")
                } else {
                    connect(address, name)
                }
            }
            else -> {
                active = true
                if (bluetoothGatt == null && !scanning) {
                    val saved = PhoneRelayRepository.savedXiaomiDevice()
                    if (saved != null) connect(saved.first, saved.second) else startScan()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        stopScan()
        closeGatt()
        mainHandler.removeCallbacksAndMessages(null)
        intentionallyClosedGatts.clear()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        invalidateReconnect()
        if (!hasBlePermissions()) {
            PhoneRelayRepository.updateXiaomiConnection("需要蓝牙/附近设备权限", connected = false, scanning = false)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            PhoneRelayRepository.updateXiaomiConnection("请先打开手机蓝牙", connected = false, scanning = false)
            return
        }
        val bleScanner = scanner
        if (bleScanner == null) {
            PhoneRelayRepository.reportXiaomiError("这台手机不支持 BLE 扫描")
            return
        }
        stopScan()
        PhoneRelayRepository.clearXiaomiCandidates()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        PhoneRelayRepository.updateXiaomiConnection("正在扫描心率广播…", connected = false, scanning = true)
        updateNotification("正在扫描小米手环")
        bleScanner.startScan(listOf(filter), settings, scanCallback)
        mainHandler.postDelayed({
            if (scanning) {
                stopScan()
                PhoneRelayRepository.updateXiaomiConnection(
                    "扫描结束；请确认手环已开启“设置 → 共享心率”",
                    connected = false,
                    scanning = false,
                )
                updateNotification("未找到心率广播")
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        if (hasBlePermissions()) runCatching { scanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String, name: String?) {
        invalidateReconnect()
        if (!hasBlePermissions()) {
            PhoneRelayRepository.updateXiaomiConnection("需要蓝牙/附近设备权限", connected = false, scanning = false)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            PhoneRelayRepository.reportXiaomiError("无效的蓝牙设备地址")
            return
        }
        stopScan()
        closeGatt()
        reconnectAddress = address
        reconnectName = name?.takeIf { it.isNotBlank() } ?: "小米手环"
        PhoneRelayRepository.saveXiaomiDevice(address, reconnectName!!)
        PhoneRelayRepository.updateXiaomiConnection(
            "正在连接 ${reconnectName}…",
            connected = false,
            scanning = false,
            address = address,
            name = reconnectName,
        )
        updateNotification("正在连接 ${reconnectName}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDeviceTransport.LE)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = if (hasConnectPermission()) {
                result.device.name ?: result.scanRecord?.deviceName ?: "BLE 心率设备"
            } else {
                result.scanRecord?.deviceName ?: "BLE 心率设备"
            }
            PhoneRelayRepository.addXiaomiCandidate(
                XiaomiBandCandidate(result.device.address, name, result.rssi),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            PhoneRelayRepository.reportXiaomiError("BLE 扫描失败（代码 $errorCode）")
            updateNotification("扫描失败")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!active) {
                gatt.close()
                return
            }
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    PhoneRelayRepository.updateXiaomiConnection(
                        "已连接，正在读取心率服务…",
                        connected = true,
                        scanning = false,
                        address = gatt.device.address,
                        name = reconnectName,
                    )
                    updateNotification("已连接 ${reconnectName ?: "小米手环"}")
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    if (intentionallyClosedGatts.remove(gatt)) {
                        gatt.close()
                        return
                    }
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    PhoneRelayRepository.updateXiaomiConnection(
                        "连接已断开，准备重连…",
                        connected = false,
                        scanning = false,
                    )
                    updateNotification("连接断开")
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                PhoneRelayRepository.reportXiaomiError("读取 BLE 服务失败（代码 $status）")
                return
            }
            val measurement = gatt.getService(HEART_RATE_SERVICE)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (measurement == null) {
                PhoneRelayRepository.reportXiaomiError("设备没有标准心率测量特征（0x2A37）")
                return
            }
            if (!gatt.setCharacteristicNotification(measurement, true)) {
                PhoneRelayRepository.reportXiaomiError("无法订阅心率通知")
                return
            }
            val descriptor = measurement.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                PhoneRelayRepository.reportXiaomiError("设备缺少心率通知配置")
                return
            }
            val enableValue = if (measurement.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, enableValue) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!started) PhoneRelayRepository.reportXiaomiError("启动心率通知失败")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                PhoneRelayRepository.updateXiaomiConnection(
                    "正在接收实时心率",
                    connected = true,
                    scanning = false,
                    address = gatt.device.address,
                    name = reconnectName,
                )
                updateNotification("正在接收实时心率")
            } else {
                PhoneRelayRepository.reportXiaomiError("订阅心率通知失败（代码 $status）")
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleMeasurement(gatt, characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleMeasurement(gatt, characteristic.uuid, value)
        }
    }

    private fun handleMeasurement(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (uuid != HEART_RATE_MEASUREMENT) return
        val bpm = HeartRateMeasurementParser.parseBpm(value)
        if (bpm == null) {
            PhoneRelayRepository.reportXiaomiError("收到无效的 BLE 心率数据")
            return
        }
        PhoneRelayRepository.handleXiaomiHeartRate(
            address = gatt.device.address,
            name = reconnectName ?: "小米手环",
            bpm = bpm,
        )
        updateNotification("实时心率 $bpm BPM")
    }

    private fun scheduleReconnect() {
        val address = reconnectAddress ?: return
        if (!active) return
        val ticket = ++reconnectTicket
        mainHandler.postDelayed({
            if (ticket == reconnectTicket && active && PhoneRelayRepository.isXiaomiMode()) {
                connect(address, reconnectName)
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun invalidateReconnect() {
        reconnectTicket += 1
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            intentionallyClosedGatts.add(gatt)
            runCatching { gatt.close() }
            mainHandler.postDelayed(
                { intentionallyClosedGatts.remove(gatt) },
                CLOSED_GATT_GUARD_MS,
            )
        }
    }

    private fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                hasConnectPermission()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            AppLocale.text(this, "小米手环心率连接"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = AppLocale.text(this@XiaomiHeartRateService, "仅在小米手环模式开启时保持 BLE 心率连接")
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(AppLocale.text(this, "VRChat 心率桥 · 小米手环"))
        .setContentText(AppLocale.text(this, text))
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private object BluetoothDeviceTransport {
        const val LE = 2
    }

    companion object {
        const val ACTION_START = "xiaomi.hr.START"
        const val ACTION_SCAN = "xiaomi.hr.SCAN"
        const val ACTION_CONNECT = "xiaomi.hr.CONNECT"
        const val ACTION_STOP = "xiaomi.hr.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        private const val NOTIFICATION_CHANNEL = "xiaomi_heart_rate"
        private const val NOTIFICATION_ID = 42
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val CLOSED_GATT_GUARD_MS = 10_000L
        private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
