package io.github.miniontoby.rokidapkuploader

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData

class MainActivity : AppCompatActivity() {
    private lateinit var serialInput: EditText
    private lateinit var filePathText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var uploadButton: Button
    private lateinit var statusText: TextView
    private lateinit var bluetoothHelper: BluetoothHelper

    private var selectedApkUri: Uri? = null
    private var device1: BluetoothDevice? = null


    private val apkPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedApkUri = uri
                filePathText.text = uri.path ?: "Selected"
            }
        }
    }

    companion object {
        const val TAG = "MainActivity"
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
        }.toTypedArray()
    }
    // Permission
    private val permissionGrantedResult = MutableLiveData<Boolean?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serialInput = findViewById(R.id.serialInput)
        filePathText = findViewById(R.id.filePathText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        uploadButton = findViewById(R.id.uploadButton)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.selectFileButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/vnd.android.package-archive"
            }
            apkPicker.launch(intent)
        }

        findViewById<Button>(R.id.scanDevicesButton).setOnClickListener {
            scanForDevices()
        }

        uploadButton.setOnClickListener {
            startUpload()
        }

        // Request Permissions
        permissionGrantedResult.postValue(null)
        requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        bluetoothHelper = BluetoothHelper(
            this,
            statusText,
            {
            },
            { device ->
                device1 = device
                val devices = arrayOf(device)
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, devices)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                deviceSpinner.adapter = adapter
                statusText.text = "Found ${devices.size} device(s)"
            }
        )

        // Observe Permission Result
        permissionGrantedResult.observe(this) {
            bluetoothHelper.permissionResult.postValue(it)
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS.hashCode()){
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED}
            permissionGrantedResult.postValue(allGranted)
        }
    }

    private fun scanForDevices() {
        statusText.text = "Scanning for devices..."
        bluetoothHelper.startScan()
    }

    private fun startUpload() {
        val serialNumber = serialInput.text.toString()
        val apkUri = selectedApkUri
        var device = deviceSpinner.selectedItem as? BluetoothDevice
        if (device == null) device = device1

        if (serialNumber.isEmpty() || apkUri == null || device == null) {
            Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        bluetoothHelper.stopScan()

        uploadButton.isEnabled = false
        statusText.text = "Starting upload..."

        try {
            val uuid = bluetoothHelper.mSocketUuid
            val mac = bluetoothHelper.mMacAddress
            if (uuid != null && mac != null) {
                bluetoothHelper.connect(uuid, mac, serialNumber, apkUri);
            } else {
                bluetoothHelper.initDevice(device, serialNumber, apkUri);
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            uploadButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothHelper.release()
    }
}
