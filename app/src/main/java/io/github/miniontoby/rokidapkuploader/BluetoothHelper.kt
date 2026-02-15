package io.github.miniontoby.rokidapkuploader;

import android.Manifest;
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import java.io.File
import kotlin.Unit;
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


/**
 * Bluetooth Helper
 * @author rokid
 * @date 2025/04/27
 * @param context Activity Register Context
 * @param initStatus Init Status
 * @param deviceFound Device Found
 */
class BluetoothHelper(
    val context: AppCompatActivity,
    val statusText: TextView,
    val initStatus: (INIT_STATUS) -> Unit,
    val deviceFound: (BluetoothDevice) -> Unit
) {
    companion object {
        const val TAG = "Rokid Glasses CXR-M"
 
        // Request Code
        const val REQUEST_CODE_PERMISSIONS = 100
 
        // Required Permissions
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
 
        // Init Status
        enum class INIT_STATUS {
            NotStart,
            INITING,
            INIT_END
        }
    }

    // Scan Results
    val scanResultMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()
 
    // Bonded Devices
    val bondedDeviceMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()
 
    // Scanner
    private val scanner by lazy {
        adapter?.bluetoothLeScanner ?: run {
            Log.d(TAG, "scanner doesnt have perms?")
            Toast.makeText(context, "Bluetooth is not supported", Toast.LENGTH_SHORT).show()
            showRequestPermissionDialog()
            throw Exception("Bluetooth is not supported!!")
        }
    }
 
    // Bluetooth Enabled
    @SuppressLint("MissingPermission")
    private val bluetoothEnabled: MutableLiveData<Boolean> = MutableLiveData<Boolean>().apply {
        this.observe(context) {
            if (this.value == true) {
                initStatus.invoke(INIT_STATUS.INIT_END)
                startScan()
            } else {
                showRequestBluetoothEnableDialog()
            }
        }
    }
 
    //  Bluetooth State Listener
    private val requestBluetoothEnable = context.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            adapter = manager?.adapter
        } else {
            showRequestBluetoothEnableDialog()
        }
    }
 
    // Bluetooth Adapter
    private var adapter: BluetoothAdapter? = null
        set(value) {
            field = value
            value?.let {
                if (!it.isEnabled) {
                    //to Enable it
                    requestBluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    bluetoothEnabled.postValue(true)
                }
            }
        }

    // Bluetooth Manager
    private var manager: BluetoothManager? = null
        set(value) {
            field = value
            initStatus.invoke(INIT_STATUS.INITING)
            value?.let {
                adapter = it.adapter
            } ?: run {
                Log.d(TAG, "manager doesnt have perms?")
                Toast.makeText(context, "Bluetooth is not supported", Toast.LENGTH_SHORT).show()
                showRequestPermissionDialog()
            }
        }

    // Permission Result
    val permissionResult: MutableLiveData<Boolean> = MutableLiveData<Boolean>().apply {
        this.observe(context) {
            if (it == true) {
                manager =
                    context.getSystemService(AppCompatActivity.BLUETOOTH_SERVICE) as BluetoothManager
            } else {
                Log.d(TAG, "permissionResult is false")
                showRequestPermissionDialog()
            }
        }
    }
 
    // Scan Listener
    val scanListener = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { r ->
                r.device.name?.let {
                    scanResultMap[it] = r.device
                    deviceFound.invoke(r.device)
                }
            }
        }
 
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Toast.makeText(
                context,
                "Scan Failed $errorCode",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    // check permissions
    fun checkPermissions() {
        initStatus.invoke(INIT_STATUS.NotStart)
        context.requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        context.registerReceiver(
            bluetoothStateListener,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }
 
    // Release
    @SuppressLint("MissingPermission")
    fun release() {
        context.unregisterReceiver(bluetoothStateListener)
        stopScan()
        permissionResult.postValue(false)
        bluetoothEnabled.postValue(false)
    }
 
 
    // Show Request Permission Dialog
    private fun showRequestPermissionDialog() {
        Log.d(TAG, "showRequestPermissionDialog")
        AlertDialog.Builder(context)
            .setTitle("Permission")
            .setMessage("Please grant the permission")
            .setPositiveButton("OK") { _, _ ->
                Log.d(TAG, "Requesting perms")
                context.requestPermissions(
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
                )
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(
                    context,
                    "Permission does not granted, FINISH",
                    Toast.LENGTH_SHORT
                ).show()
                context.finish()
            }
            .show()
    }
 
    // Show Request Bluetooth Enable Dialog
    private fun showRequestBluetoothEnableDialog() {
        Log.d(TAG, "showRequestBluetoothEnableDialog")
        AlertDialog.Builder(context)
            .setTitle("Bluetooth")
            .setMessage("Please enable the bluetooth")
            .setPositiveButton("OK") { _, _ ->
                Log.d(TAG, "Enabling bluetooth")
                requestBluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(
                    context,
                    "Bluetooth does not enabled, FINISH",
                    Toast.LENGTH_SHORT
                ).show()
                context.finish()
            }
            .show()
    }
 
    // Start Scan
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        scanResultMap.clear()
        Log.d(TAG, "startScan")
        val connectedList = getConnectedDevices()
        for (device in connectedList) {
            device.name?.let {
                if (it.contains("Glasses", false)) {
                    bondedDeviceMap[it] = device
                    deviceFound.invoke(device)
                }
            }
        }
 
        adapter?.bondedDevices?.forEach { d ->
            d.name?.let {
                if (it.contains("Glasses", false)) {
                    if (bondedDeviceMap[it] == null) {
                        bondedDeviceMap[it] = d
                        deviceFound.invoke(d)
                    }
                }
            }
        }
 
        try {
            scanner.startScan(
                listOf<ScanFilter>(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString("00009100-0000-1000-8000-00805f9b34fb"))//Rokid Glasses Service
                        .build()
                ), ScanSettings.Builder().build(),
                scanListener
            )
        } catch (e: Exception) {
            Log.d(TAG, Log.getStackTraceString(e))
            statusText.text = "Scan Failed: ${e.message}"
            Toast.makeText(context, "Scan Failed ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
 
    // Stop Scan
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanner.stopScan(scanListener)
    }
 
    //  Get Connected Devices
    @SuppressLint("MissingPermission")
    private fun getConnectedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.filter { device ->
            try {
                val isConnected =
                    device::class.java.getMethod("isConnected").invoke(device) as Boolean
                isConnected
            } catch (_: Exception) {
                Toast.makeText(context, "Get Connected Devices Failed", Toast.LENGTH_SHORT).show()
                false
            }
        } ?: emptyList()
    }
 
    // Bluetooth State Listener
    val bluetoothStateListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        initStatus.invoke(INIT_STATUS.NotStart)
                        bluetoothEnabled.postValue(false)
                    }
                }
            }
        }
    }

    var isDone: Boolean = false
    var mSocketUuid: String? = null
    var mMacAddress: String? = null
    private var tSocketUuid: String? = null
    private var tMacAddress: String? = null
    private var aesKey = "6b4b588923c84fb6b0a337c0ed3419d4"
    private fun encryptSerialNumber(serialNumber: String): ByteArray {
        val secretKeySpec = SecretKeySpec(aesKey.toByteArray(), "AES")
        val ivParameterSpec = IvParameterSpec(aesKey.toByteArray(), 0, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        return cipher.doFinal(serialNumber.toByteArray())
    }

    /**
     * Init Bluetooth
     *
     * @param device     Bluetooth Device
     */
    fun initDevice(device: BluetoothDevice, serialNumber: String, apkUri: Uri){
        try { statusText.text = "Init Device connection..." } catch (_: Exception) {}
        /**
         * Init Bluetooth
         *
         * @param context   Application Context
         * @param device     Bluetooth Device
         * @param callback   Bluetooth Status Callback
         */
        CxrApi.getInstance().initBluetooth(context, device,  object : BluetoothStatusCallback {
            /**
             * Connection Info
             *
             * @param socketUuid   Socket UUID
             * @param macAddress   Classic Bluetooth MAC Address
             * @param rokidAccount Rokid Account
             * @param glassesType  Device Type, 0-no display, 1-have display
             */
            override fun onConnectionInfo(
                socketUuid: String?,
                macAddress: String?,
                rokidAccount: String?,
                glassesType: Int
            ) {
                socketUuid?.let { uuid ->
                    macAddress?.let { address->
                        tSocketUuid = uuid
                        tMacAddress = address
                        connect(uuid, address, serialNumber, apkUri)
                    }?:run {
                        Log.e(TAG, "macAddress is")
                    }
                }?:run{
                    Log.e(TAG, "socketUuid is")
                }
            }

            override fun onConnected() {}
            override fun onDisconnected() {}

            /**
             * Failed
             *
             * @param errorCode   Error Code:
             * @see ValueUtil.CxrBluetoothErrorCode
             * @see ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID  Parameter Invalid
             * @see ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED BLE Connect Failed
             * @see ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED Socket Connect Failed
             * @see ValueUtil.CxrBluetoothErrorCode.UNKNOWN Unknown
             */
            override fun onFailed(p0: ValueUtil.CxrBluetoothErrorCode?) {
                Log.d(TAG, "Failed to init the connection... $p0")
                try { statusText.text = "Failed to init the connection... $p0" } catch (_: Exception) {}
            }

        })
    }

    /**
     *  Connect
     *
     *  @param socketUuid   Socket UUID
     *  @param macAddress   Classic Bluetooth MAC Address
     */
    fun connect(socketUuid: String, macAddress: String, serialNumber: String, apkUri: Uri) {
        isDone = false
        try { statusText.text = "Connecting to bluetooth..." } catch (_: Exception) {}
        // Encrypt serial number
        val snEncryptedContent = encryptSerialNumber(serialNumber)
        /**
         * Connect
         */
        CxrApi.getInstance().connectBluetooth(context, socketUuid, macAddress, object : BluetoothStatusCallback{
            /**
             * Connection Info
             *
             * @param socketUuid   Socket UUID
             * @param macAddress   Classic Bluetooth MAC Address
             * @param rokidAccount Rokid Account
             * @param glassesType  Device Type, 0-no display, 1-have display
             */
            override fun onConnectionInfo(
                socketUuid: String?,
                macAddress: String?,
                rokidAccount: String?,
                glassesType: Int
            ) { }

            /**
             * Connected
             */
            override fun onConnected() {
                Log.d(TAG, "Connected to bluetooth")
                mSocketUuid = tSocketUuid
                mMacAddress = tMacAddress
                CxrApi.getInstance().sendGlobalToastContent(2, "APK Uploader is connected", false)
                CxrApi.getInstance().sendGlobalTtsContent("A P K Up Loader is connected") // Text To Speech needs to be able to say it correctly
                initWifi(apkUri)
            }

            /**
             * Disconnected
             */
            override fun onDisconnected() {
                Log.d(TAG, "Disconnected from bluetooth")
                if (isDone) {
                    try { statusText.text = "Disconnected from bluetooth" } catch (_: Exception) {}
                }
            }

            /**
             * Failed
             *
             * @param errorCode   Error Code:
             * @see ValueUtil.CxrBluetoothErrorCode
             * @see ValueUtil.CxrBluetoothErrorCode.PARAM_INVALID  Parameter Invalid
             * @see ValueUtil.CxrBluetoothErrorCode.BLE_CONNECT_FAILED BLE Connect Failed
             * @see ValueUtil.CxrBluetoothErrorCode.SOCKET_CONNECT_FAILED Socket Connect Failed
             * @see ValueUtil.CxrBluetoothErrorCode.UNKNOWN Unknown
             */
            override fun onFailed(p0: ValueUtil.CxrBluetoothErrorCode?) {
                Log.e(TAG, "Failed to connect to bluetooth $p0")
                try { statusText.text = "Failed to connect to bluetooth $p0" } catch (_: Exception) {}
            }

        }, snEncryptedContent, aesKey)
    }

    /**
     * Init Wifi
     *
     * @return  Status:
     * @see ValueUtil.CxrStatus
     * @see ValueUtil.CxrStatus.REQUEST_SUCCEED Success
     * @see ValueUtil.CxrStatus.REQUEST_WAITING Waiting
     * @see ValueUtil.CxrStatus.REQUEST_FAILED Failed
     */
    fun initWifi(apkUri: Uri): ValueUtil.CxrStatus? {
        try { statusText.text = "Starting wifi..." } catch (_: Exception) {}
        return CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
            /**
             * Connected
             */
            override fun onConnected() {
                Log.d(TAG, "Connected to wifi")
                uploadApk(apkUri)
            }

            /**
             * Disconnected
             */
            override fun onDisconnected() {
                Log.d(TAG, "Disconnected from wifi")
                if (isDone) {
                    try { statusText.text = "Disconnected from wifi" } catch (_: Exception) {}
                }
            }

            /**
             * Failed
             *
             * @param errorCode   Error Code:
             * @see ValueUtil.CxrWifiErrorCode
             * @see ValueUtil.CxrWifiErrorCode.WIFI_DISABLED Mobile Phone Wi-Fi disabled
             * @see ValueUtil.CxrWifiErrorCode.WIFI_CONNECT_FAILED Wi-Fi P2P Connect Failed
             * @see ValueUtil.CxrWifiErrorCode.UNKNOWN Unknown
             */
            override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                Log.e(TAG, "Failed to connect to wifi $errorCode")
                try { statusText.text = "Failed to connect to wifi $errorCode" } catch (_: Exception) {}
                CxrApi.getInstance().deinitBluetooth()
            }

        })
    }

    fun uploadApk(apkUri: Uri): Boolean {
        try { statusText.text = "Starting upload APK..." } catch (_: Exception) {}

        // Read APK file
        val inputStream = context.contentResolver.openInputStream(apkUri)
        val cacheDir = context.cacheDir
        val apkFile = File(cacheDir, "temp.apk")
        inputStream?.use { input ->
            apkFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Upload APK
        return CxrApi.getInstance().startUploadApk(apkFile.absolutePath, object : ApkStatusCallback {
            override fun onUploadApkSucceed() {
                apkFile.delete()
                isDone = true
                CxrApi.getInstance().deinitWifiP2P()
                // Toast.makeText(context, "APK uploaded successfully", Toast.LENGTH_SHORT).show()
                try { statusText.text = "APK uploaded successfully" } catch (_: Exception) {}
            }

            override fun onUploadApkFailed() {
                apkFile.delete()
                isDone = true
                CxrApi.getInstance().deinitWifiP2P()
                // Toast.makeText(context, "APK upload failed", Toast.LENGTH_SHORT).show()
                try { statusText.text = "APK upload failed" } catch (_: Exception) {}
            }

            override fun onInstallApkSucceed() {
                isDone = true
                CxrApi.getInstance().deinitBluetooth()
                // Toast.makeText(context, "APK installed successfully", Toast.LENGTH_SHORT).show()
                try { statusText.text = "APK installed successfully" } catch (_: Exception) {}
            }

            override fun onInstallApkFailed() {
                isDone = true
                CxrApi.getInstance().deinitBluetooth()
                // Toast.makeText(context, "APK install failed", Toast.LENGTH_SHORT).show()
                try { statusText.text = "APK install failed" } catch (_: Exception) {}
            }

            override fun onUninstallApkSucceed() {
                TODO("Not yet implemented")
            }

            override fun onUninstallApkFailed() {
                TODO("Not yet implemented")
            }

            override fun onOpenAppSucceed() {
                TODO("Not yet implemented")
            }

            override fun onOpenAppFailed() {
                TODO("Not yet implemented")
            }
        })
    }
}