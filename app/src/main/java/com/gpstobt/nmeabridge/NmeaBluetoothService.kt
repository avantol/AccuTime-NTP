package com.gpstobt.nmeabridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NmeaBluetoothService : Service() {

    companion object {
        private const val TAG = "NmeaBluetoothService"
        private const val CHANNEL_ID = "nmea_bridge_channel"
        private const val NOTIFICATION_ID = 1
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        // Broadcast actions
        const val ACTION_STATUS_UPDATE = "com.gpstobt.nmeabridge.STATUS_UPDATE"
        const val EXTRA_BT_STATUS = "bt_status"
        const val EXTRA_GPS_STATUS = "gps_status"
        const val EXTRA_NMEA_SENTENCE = "nmea_sentence"
        const val EXTRA_SENTENCE_COUNT = "sentence_count"
        const val EXTRA_SATELLITES_USED = "satellites_used"
        const val EXTRA_SATELLITES_VIEW = "satellites_view"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_UTC_TIME = "utc_time"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val BT_DISCONNECTED = 0
        const val BT_WAITING = 1
        const val BT_CONNECTED = 2

        const val GPS_NO_FIX = 0
        const val GPS_FIX_2D = 1
        const val GPS_FIX_3D = 2

        // NMEA sentence types to forward to ChronoGPS
        private val ALLOWED_SENTENCES = setOf("RMC", "GGA", "GSA", "GSV")
    }

    inner class LocalBinder : Binder() {
        val service: NmeaBluetoothService get() = this@NmeaBluetoothService
    }

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)
    private val sentenceCount = AtomicLong(0)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var acceptThread: Thread? = null

    private var locationManager: LocationManager? = null
    private var nmeaListener: OnNmeaMessageListener? = null

    private var currentBtStatus = BT_DISCONNECTED
    private var currentGpsStatus = GPS_NO_FIX
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentUtcTime = ""
    private var satellitesUsed = 0
    private var satellitesInView = 0
    private var connectedDeviceName = ""

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Waiting for Bluetooth connection"))
            startNmeaListener()
            startBluetoothServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            stopNmeaListener()
            closeBluetoothServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- NMEA Listener ---

    @SuppressLint("MissingPermission")
    private fun startNmeaListener() {
        nmeaListener = OnNmeaMessageListener { message, _ ->
            processNmea(message)
        }
        locationManager?.apply {
            requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,  // 1 second minimum interval
                0f,     // no distance filter
                { },    // empty location listener — we only need NMEA
                mainLooper
            )
            addNmeaListener(nmeaListener!!, null)
        }
    }

    private fun stopNmeaListener() {
        nmeaListener?.let { locationManager?.removeNmeaListener(it) }
        locationManager?.removeUpdates { }
        nmeaListener = null
    }

    private fun processNmea(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isEmpty() || !message.startsWith("$")) return

        // Extract sentence type (e.g., "RMC" from "$GPRMC" or "$GNRMC")
        val sentenceId = extractSentenceType(message) ?: return
        if (sentenceId !in ALLOWED_SENTENCES) return

        // Parse status info for UI
        when (sentenceId) {
            "RMC" -> parseRmcForDisplay(message)
            "GGA" -> parseGgaForDisplay(message)
            "GSA" -> parseGsaForDisplay(message)
            "GSV" -> parseGsvForDisplay(message)
        }

        // Forward to Bluetooth
        sendToClient(message)

        // Broadcast update
        val count = sentenceCount.incrementAndGet()
        broadcastStatus(nmeaSentence = message, count = count)
    }

    private fun extractSentenceType(sentence: String): String? {
        // Handles $GPRMC, $GNRMC, $GARMC, etc.
        if (sentence.length < 6) return null
        val commaIdx = sentence.indexOf(',')
        if (commaIdx < 4) return null
        // Sentence type is the last 3 chars before the first comma
        return sentence.substring(commaIdx - 3, commaIdx)
    }

    private fun parseRmcForDisplay(sentence: String) {
        val parts = sentence.split(",")
        if (parts.size < 10) return
        val timeStr = parts[1]
        if (timeStr.length >= 6) {
            val hh = timeStr.substring(0, 2)
            val mm = timeStr.substring(2, 4)
            val ss = timeStr.substring(4, 6)
            val frac = if (timeStr.length > 7) timeStr.substring(6) else ""
            currentUtcTime = "$hh:$mm:$ss$frac"
        }
        if (parts[2] == "A" && parts.size >= 6) {
            parseCoordinates(parts[3], parts[4], parts[5], parts[6])
        }
    }

    private fun parseGgaForDisplay(sentence: String) {
        val parts = sentence.split(",")
        if (parts.size < 8) return
        val fixQuality = parts[6].toIntOrNull() ?: 0
        if (fixQuality > 0 && parts.size >= 5) {
            parseCoordinates(parts[2], parts[3], parts[4], parts[5])
        }
        satellitesUsed = parts[7].toIntOrNull() ?: satellitesUsed
    }

    private fun parseGsaForDisplay(sentence: String) {
        val parts = sentence.split(",")
        if (parts.size < 3) return
        val fixType = parts[2].toIntOrNull() ?: 1
        currentGpsStatus = when (fixType) {
            2 -> GPS_FIX_2D
            3 -> GPS_FIX_3D
            else -> GPS_NO_FIX
        }
    }

    private fun parseGsvForDisplay(sentence: String) {
        val parts = sentence.split(",")
        if (parts.size < 4) return
        satellitesInView = parts[3].toIntOrNull() ?: satellitesInView
    }

    private fun parseCoordinates(lat: String, latDir: String, lon: String, lonDir: String) {
        try {
            if (lat.length >= 4) {
                val latDeg = lat.substring(0, 2).toDouble()
                val latMin = lat.substring(2).toDouble()
                currentLat = latDeg + latMin / 60.0
                if (latDir == "S") currentLat = -currentLat
            }
            if (lon.length >= 5) {
                val lonDeg = lon.substring(0, 3).toDouble()
                val lonMin = lon.substring(3).toDouble()
                currentLon = lonDeg + lonMin / 60.0
                if (lonDir == "W") currentLon = -currentLon
            }
        } catch (_: NumberFormatException) {
            // Ignore malformed coordinates
        }
    }

    // --- Bluetooth SPP Server ---

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        acceptThread = Thread({
            while (running.get()) {
                try {
                    updateBtStatus(BT_WAITING)
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                        "AccuTime", SPP_UUID
                    )
                    Log.i(TAG, "Waiting for Bluetooth SPP connection...")

                    // Blocking call — waits for a client
                    clientSocket = serverSocket?.accept()
                    serverSocket?.close() // Only accept one connection at a time

                    clientSocket?.let { socket ->
                        outputStream = socket.outputStream
                        connectedDeviceName = socket.remoteDevice?.name ?: "Unknown"
                        updateBtStatus(BT_CONNECTED)
                        updateNotification("Sending NMEA to $connectedDeviceName")
                        Log.i(TAG, "Client connected: $connectedDeviceName")

                        // Keep alive — wait for disconnect
                        while (running.get() && socket.isConnected) {
                            Thread.sleep(500)
                        }
                    }
                } catch (e: IOException) {
                    if (running.get()) {
                        Log.w(TAG, "Bluetooth connection error, will retry", e)
                    }
                } finally {
                    closeClientConnection()
                    updateBtStatus(BT_DISCONNECTED)
                    updateNotification("Waiting for Bluetooth connection")
                }

                // Brief pause before re-listening
                if (running.get()) {
                    Thread.sleep(1000)
                }
            }
        }, "BT-Accept").apply { isDaemon = true; start() }
    }

    private fun closeClientConnection() {
        try { outputStream?.close() } catch (_: IOException) { }
        try { clientSocket?.close() } catch (_: IOException) { }
        outputStream = null
        clientSocket = null
        connectedDeviceName = ""
    }

    private fun closeBluetoothServer() {
        try { serverSocket?.close() } catch (_: IOException) { }
        closeClientConnection()
        acceptThread?.interrupt()
        acceptThread = null
    }

    @Synchronized
    private fun sendToClient(nmeaSentence: String) {
        val stream = outputStream ?: return
        try {
            // Ensure proper NMEA line termination
            val data = if (nmeaSentence.endsWith("\r\n")) {
                nmeaSentence
            } else {
                nmeaSentence.trimEnd() + "\r\n"
            }
            stream.write(data.toByteArray(Charsets.US_ASCII))
            stream.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Write failed, client likely disconnected", e)
            closeClientConnection()
            updateBtStatus(BT_DISCONNECTED)
        }
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // --- Status Broadcasting ---

    private fun updateBtStatus(status: Int) {
        currentBtStatus = status
        broadcastStatus()
    }

    private fun broadcastStatus(nmeaSentence: String? = null, count: Long? = null) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_BT_STATUS, currentBtStatus)
            putExtra(EXTRA_GPS_STATUS, currentGpsStatus)
            putExtra(EXTRA_SATELLITES_USED, satellitesUsed)
            putExtra(EXTRA_SATELLITES_VIEW, satellitesInView)
            putExtra(EXTRA_LATITUDE, currentLat)
            putExtra(EXTRA_LONGITUDE, currentLon)
            putExtra(EXTRA_UTC_TIME, currentUtcTime)
            putExtra(EXTRA_DEVICE_NAME, connectedDeviceName)
            count?.let { putExtra(EXTRA_SENTENCE_COUNT, it) }
            nmeaSentence?.let { putExtra(EXTRA_NMEA_SENTENCE, it) }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
