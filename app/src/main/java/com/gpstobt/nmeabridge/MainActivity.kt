package com.gpstobt.nmeabridge

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MODE_BT = 0
        const val MODE_NTP = 1
    }

    private lateinit var btnModeBt: Button
    private lateinit var btnModeNtp: Button
    private lateinit var btnToggle: Button
    private lateinit var llBtPanel: LinearLayout
    private lateinit var llNtpPanel: LinearLayout
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var tvNtpStatus: TextView
    private lateinit var tvNtpAddress: TextView
    private lateinit var tvNtpClients: TextView
    private lateinit var tvTimeSource: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvSatellites: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvUtcTime: TextView
    private lateinit var tvSentenceCount: TextView
    private lateinit var tvNmeaLogLabel: TextView
    private lateinit var tvNmeaLog: TextView
    private lateinit var svNmeaLog: ScrollView

    private var mode = MODE_BT
    private var serviceRunning = false
    private val nmeaLogLines = mutableListOf<String>()
    private val maxLogLines = 50

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                NmeaBluetoothService.ACTION_STATUS_UPDATE -> updateBtUi(intent)
                NtpServerService.ACTION_STATUS_UPDATE -> updateNtpUi(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnModeBt = findViewById(R.id.btnModeBt)
        btnModeNtp = findViewById(R.id.btnModeNtp)
        btnToggle = findViewById(R.id.btnToggle)
        llBtPanel = findViewById(R.id.llBtPanel)
        llNtpPanel = findViewById(R.id.llNtpPanel)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        tvNtpStatus = findViewById(R.id.tvNtpStatus)
        tvNtpAddress = findViewById(R.id.tvNtpAddress)
        tvNtpClients = findViewById(R.id.tvNtpClients)
        tvTimeSource = findViewById(R.id.tvTimeSource)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvSatellites = findViewById(R.id.tvSatellites)
        tvPosition = findViewById(R.id.tvPosition)
        tvUtcTime = findViewById(R.id.tvUtcTime)
        tvSentenceCount = findViewById(R.id.tvSentenceCount)
        tvNmeaLogLabel = findViewById(R.id.tvNmeaLogLabel)
        tvNmeaLog = findViewById(R.id.tvNmeaLog)
        svNmeaLog = findViewById(R.id.svNmeaLog)

        btnModeBt.setOnClickListener { setMode(MODE_BT) }
        btnModeNtp.setOnClickListener { setMode(MODE_NTP) }

        btnToggle.setOnClickListener {
            if (!serviceRunning) startSelectedMode() else stopSelectedMode()
        }

        findViewById<TextView>(R.id.tvSupportLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/avantol/AccuTime/releases/latest")))
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener {
            if (serviceRunning) stopSelectedMode()
            finishAndRemoveTask()
        }

        setMode(MODE_BT)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(NmeaBluetoothService.ACTION_STATUS_UPDATE)
            addAction(NtpServerService.ACTION_STATUS_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    // --- Mode selection ---

    private fun setMode(newMode: Int) {
        if (serviceRunning) {
            Toast.makeText(this, "Stop before switching mode", Toast.LENGTH_SHORT).show()
            return
        }
        mode = newMode
        val active = 0xFF4CAF50.toInt()
        val inactive = 0xFF555555.toInt()
        btnModeBt.backgroundTintList = ColorStateList.valueOf(if (mode == MODE_BT) active else inactive)
        btnModeNtp.backgroundTintList = ColorStateList.valueOf(if (mode == MODE_NTP) active else inactive)

        val btMode = mode == MODE_BT
        llBtPanel.visibility = if (btMode) View.VISIBLE else View.GONE
        llNtpPanel.visibility = if (btMode) View.GONE else View.VISIBLE
        // The NMEA log is only meaningful in Bluetooth mode.
        tvSentenceCount.visibility = if (btMode) View.VISIBLE else View.GONE
        tvNmeaLogLabel.visibility = if (btMode) View.VISIBLE else View.GONE
        svNmeaLog.visibility = if (btMode) View.VISIBLE else View.GONE
    }

    // --- Start / stop ---

    private fun startSelectedMode() {
        val needBluetooth = mode == MODE_BT
        if (!PermissionHelper.hasAllPermissions(this, needBluetooth)) {
            PermissionHelper.requestPermissions(this, needBluetooth)
            return
        }

        if (needBluetooth) {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter
            if (btAdapter == null) {
                Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_LONG).show()
                return
            }
            if (!btAdapter.isEnabled) {
                Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            Toast.makeText(this,
                "Turn on your phone's WiFi hotspot, then connect the PC to it",
                Toast.LENGTH_LONG).show()
        }

        val serviceClass = if (needBluetooth) NmeaBluetoothService::class.java
                           else NtpServerService::class.java
        ContextCompat.startForegroundService(this, Intent(this, serviceClass))
        serviceRunning = true
        btnToggle.text = getString(R.string.btn_stop)
        styleStopButton()

        nmeaLogLines.clear()
        tvNmeaLog.text = ""
    }

    private fun stopSelectedMode() {
        val serviceClass = if (mode == MODE_BT) NmeaBluetoothService::class.java
                           else NtpServerService::class.java
        stopService(Intent(this, serviceClass))
        serviceRunning = false
        btnToggle.text = getString(R.string.btn_start)
        styleStartButton()

        tvBluetoothStatus.text = getString(R.string.status_disconnected)
        tvBluetoothStatus.setTextColor(getColor(R.color.status_disconnected))
        tvNtpStatus.text = getString(R.string.ntp_stopped)
        tvNtpStatus.setTextColor(getColor(R.color.status_disconnected))
    }

    private fun styleStartButton() {
        btnToggle.layoutParams = (btnToggle.layoutParams as LinearLayout.LayoutParams).apply {
            height = (64 * resources.displayMetrics.density).toInt()
        }
        btnToggle.textSize = 22f
        btnToggle.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
        btnToggle.setTextColor(0xFFFFFFFF.toInt())
        // Re-enable mode switching when stopped.
        btnModeBt.isEnabled = true
        btnModeNtp.isEnabled = true
    }

    private fun styleStopButton() {
        btnToggle.layoutParams = (btnToggle.layoutParams as LinearLayout.LayoutParams).apply {
            height = (40 * resources.displayMetrics.density).toInt()
        }
        btnToggle.textSize = 14f
        btnToggle.backgroundTintList = ColorStateList.valueOf(0xFF666666.toInt())
        btnToggle.setTextColor(0xFFCCCCCC.toInt())
        btnModeBt.isEnabled = false
        btnModeNtp.isEnabled = false
    }

    // --- Bluetooth mode UI ---

    private fun updateBtUi(intent: Intent) {
        when (intent.getIntExtra(NmeaBluetoothService.EXTRA_BT_STATUS, 0)) {
            NmeaBluetoothService.BT_DISCONNECTED -> {
                tvBluetoothStatus.text = getString(R.string.status_disconnected)
                tvBluetoothStatus.setTextColor(getColor(R.color.status_disconnected))
            }
            NmeaBluetoothService.BT_WAITING -> {
                tvBluetoothStatus.text = getString(R.string.status_waiting)
                tvBluetoothStatus.setTextColor(getColor(R.color.status_waiting))
            }
            NmeaBluetoothService.BT_CONNECTED -> {
                val deviceName = intent.getStringExtra(NmeaBluetoothService.EXTRA_DEVICE_NAME) ?: ""
                tvBluetoothStatus.text = getString(R.string.status_connected) +
                    if (deviceName.isNotEmpty()) " ($deviceName)" else ""
                tvBluetoothStatus.setTextColor(getColor(R.color.status_connected))
            }
        }

        updateGpsUi(
            intent.getIntExtra(NmeaBluetoothService.EXTRA_GPS_STATUS, 0),
            intent.getIntExtra(NmeaBluetoothService.EXTRA_SATELLITES_USED, 0),
            intent.getIntExtra(NmeaBluetoothService.EXTRA_SATELLITES_VIEW, 0),
            intent.getDoubleExtra(NmeaBluetoothService.EXTRA_LATITUDE, 0.0),
            intent.getDoubleExtra(NmeaBluetoothService.EXTRA_LONGITUDE, 0.0),
            intent.getStringExtra(NmeaBluetoothService.EXTRA_UTC_TIME) ?: ""
        )

        val count = intent.getLongExtra(NmeaBluetoothService.EXTRA_SENTENCE_COUNT, -1)
        if (count >= 0) tvSentenceCount.text = "Sentences sent: $count"

        val sentence = intent.getStringExtra(NmeaBluetoothService.EXTRA_NMEA_SENTENCE)
        if (sentence != null) {
            nmeaLogLines.add(sentence.trim())
            while (nmeaLogLines.size > maxLogLines) nmeaLogLines.removeAt(0)
            tvNmeaLog.text = nmeaLogLines.joinToString("\n")
            svNmeaLog.post { svNmeaLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // --- WiFi NTP mode UI ---

    private fun updateNtpUi(intent: Intent) {
        when (intent.getIntExtra(NtpServerService.EXTRA_SERVER_STATUS, 0)) {
            NtpServerService.SERVER_STARTING -> {
                tvNtpStatus.text = getString(R.string.ntp_starting)
                tvNtpStatus.setTextColor(getColor(R.color.status_waiting))
            }
            NtpServerService.SERVER_SERVING -> {
                tvNtpStatus.text = getString(R.string.ntp_serving)
                tvNtpStatus.setTextColor(getColor(R.color.status_connected))
            }
            NtpServerService.SERVER_ERROR -> {
                tvNtpStatus.text = getString(R.string.ntp_error)
                tvNtpStatus.setTextColor(getColor(R.color.status_disconnected))
            }
        }

        val addr = intent.getStringExtra(NtpServerService.EXTRA_SERVER_ADDR) ?: ""
        if (addr.isNotEmpty()) {
            tvNtpAddress.text = "Point the PC at: $addr:${NtpServerService.NTP_PORT}"
        }

        val served = intent.getLongExtra(NtpServerService.EXTRA_REQUESTS_SERVED, 0)
        val lastClient = intent.getStringExtra(NtpServerService.EXTRA_LAST_CLIENT) ?: ""
        tvNtpClients.text = "Requests served: $served" +
            if (lastClient.isNotEmpty()) "  (last: $lastClient)" else ""

        when (intent.getIntExtra(NtpServerService.EXTRA_TIME_SOURCE, 0)) {
            NtpServerService.TIME_SRC_GNSS -> {
                tvTimeSource.text = "Time source: GNSS raw (best)"
                tvTimeSource.setTextColor(getColor(R.color.status_connected))
            }
            NtpServerService.TIME_SRC_LOCATION -> {
                tvTimeSource.text = "Time source: Location fallback"
                tvTimeSource.setTextColor(getColor(R.color.status_waiting))
            }
            else -> {
                tvTimeSource.text = "Time source: waiting for GPS…"
                tvTimeSource.setTextColor(getColor(R.color.status_waiting))
            }
        }

        updateGpsUi(
            intent.getIntExtra(NtpServerService.EXTRA_GPS_STATUS, 0),
            intent.getIntExtra(NtpServerService.EXTRA_SATELLITES_USED, 0),
            intent.getIntExtra(NtpServerService.EXTRA_SATELLITES_VIEW, 0),
            intent.getDoubleExtra(NtpServerService.EXTRA_LATITUDE, 0.0),
            intent.getDoubleExtra(NtpServerService.EXTRA_LONGITUDE, 0.0),
            intent.getStringExtra(NtpServerService.EXTRA_UTC_TIME) ?: ""
        )
    }

    // --- Shared GPS UI ---

    private fun updateGpsUi(
        gpsStatus: Int, satsUsed: Int, satsView: Int,
        lat: Double, lon: Double, utcTime: String
    ) {
        when (gpsStatus) {
            NmeaBluetoothService.GPS_NO_FIX -> {
                tvGpsStatus.text = getString(R.string.gps_no_fix)
                tvGpsStatus.setTextColor(getColor(R.color.gps_no_fix))
            }
            NmeaBluetoothService.GPS_FIX_2D -> {
                tvGpsStatus.text = getString(R.string.gps_fix_2d)
                tvGpsStatus.setTextColor(getColor(R.color.gps_fix))
            }
            NmeaBluetoothService.GPS_FIX_3D -> {
                tvGpsStatus.text = getString(R.string.gps_fix_3d)
                tvGpsStatus.setTextColor(getColor(R.color.gps_fix))
            }
        }
        tvSatellites.text = "Satellites: $satsUsed in use / $satsView in view"
        if (lat != 0.0 || lon != 0.0) {
            tvPosition.text = "Position: %.6f, %.6f".format(lat, lon)
        }
        if (utcTime.isNotEmpty()) {
            tvUtcTime.text = "UTC Time: $utcTime"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQUEST_CODE) {
            if (PermissionHelper.allGranted(grantResults)) {
                startSelectedMode()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to read GPS time",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
