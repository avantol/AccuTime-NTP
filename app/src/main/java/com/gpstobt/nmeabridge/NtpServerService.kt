package com.gpstobt.nmeabridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves GPS-derived UTC over SNTP so a PC on the phone's WiFi hotspot can set
 * its clock with no cable and no router.
 *
 * Why a high port (10123) instead of the standard NTP port 123: an unrooted
 * Android app cannot bind privileged ports (< 1024). The bundled Linux client
 * (linux-client/accutime-sync.py) targets this port explicitly. chrony can also
 * reach it with `server <ip> port 10123`.
 *
 * Time source, best first:
 *   1. GnssClock (raw GNSS measurements) — hardware GPS time, ~ns reference.
 *   2. Location.getTime() paired with the fix's elapsedRealtime — fallback for
 *      devices without raw-measurement support; less precise (getTime() is often
 *      truncated to whole seconds) but keeps the server usable.
 * The active source is reported to the UI so the operator knows the accuracy tier.
 */
class NtpServerService : Service() {

    companion object {
        private const val TAG = "NtpServerService"
        private const val CHANNEL_ID = "accutime_ntp_channel"
        private const val NOTIFICATION_ID = 2

        /** Non-privileged UDP port the SNTP server binds. See class docs. */
        const val NTP_PORT = 10123

        // GPS epoch (1980-01-06 00:00:00 UTC) expressed in Unix nanoseconds.
        private const val GPS_EPOCH_UNIX_NANOS = 315964800L * 1_000_000_000L
        // Seconds between the NTP epoch (1900) and the Unix epoch (1970).
        private const val NTP_UNIX_OFFSET_SECS = 2208988800L
        // Current GPS-UTC leap-second offset, used only if the chipset doesn't report one.
        private const val DEFAULT_LEAP_SECONDS = 18

        // Broadcast actions / extras (distinct from the Bluetooth service's).
        const val ACTION_STATUS_UPDATE = "com.gpstobt.nmeabridge.NTP_STATUS_UPDATE"
        const val EXTRA_SERVER_STATUS = "server_status"
        const val EXTRA_SERVER_ADDR = "server_addr"
        const val EXTRA_REQUESTS_SERVED = "requests_served"
        const val EXTRA_LAST_CLIENT = "last_client"
        const val EXTRA_TIME_SOURCE = "time_source"
        const val EXTRA_GPS_STATUS = "gps_status"
        const val EXTRA_SATELLITES_USED = "satellites_used"
        const val EXTRA_SATELLITES_VIEW = "satellites_view"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_UTC_TIME = "utc_time"

        const val SERVER_STARTING = 0
        const val SERVER_SERVING = 1
        const val SERVER_ERROR = 2

        const val TIME_SRC_NONE = 0
        const val TIME_SRC_GNSS = 1
        const val TIME_SRC_LOCATION = 2

        const val GPS_NO_FIX = 0
        const val GPS_FIX_2D = 1
        const val GPS_FIX_3D = 2
    }

    inner class LocalBinder : Binder() {
        val service: NtpServerService get() = this@NtpServerService
    }

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)
    private val requestsServed = AtomicLong(0)

    private var locationManager: LocationManager? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var locationListener: LocationListener? = null
    private var gnssCallback: GnssMeasurementsEvent.Callback? = null

    private var socket: DatagramSocket? = null
    private var serverThread: Thread? = null

    // GPS-disciplined clock reference. refUtcNanos is true UTC (Unix ns) captured
    // at the instant SystemClock.elapsedRealtimeNanos() read refElapsedNanos.
    @Volatile private var refUtcNanos = 0L
    @Volatile private var refElapsedNanos = 0L
    @Volatile private var timeSource = TIME_SRC_NONE

    private var serverStatus = SERVER_STARTING
    private var serverAddr = ""
    private var lastClient = ""
    private var gpsStatus = GPS_NO_FIX
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var satellitesUsed = 0
    private var satellitesInView = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Starting NTP server…"))
            startGpsListeners()
            startNtpServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            stopGpsListeners()
            stopNtpServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- GPS time discipline ---

    @SuppressLint("MissingPermission")
    private fun startGpsListeners() {
        val lm = locationManager ?: return

        // Fallback source + keeps the GPS engine active (also drives NMEA).
        locationListener = LocationListener { location -> onLocation(location) }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener!!, mainLooper)

        // UI-only: reuse NMEA for fix/sats/position.
        nmeaListener = OnNmeaMessageListener { message, _ -> processNmeaForDisplay(message) }
        lm.addNmeaListener(nmeaListener!!, null)

        // Preferred source: raw GNSS measurements → GnssClock.
        gnssCallback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                onGnssClock(event)
            }
        }
        try {
            lm.registerGnssMeasurementsCallback(gnssCallback!!, Handler(mainLooper))
        } catch (e: Exception) {
            Log.w(TAG, "Raw GNSS measurements unavailable; using Location fallback", e)
        }
    }

    private fun stopGpsListeners() {
        val lm = locationManager
        nmeaListener?.let { lm?.removeNmeaListener(it) }
        locationListener?.let { lm?.removeUpdates(it) }
        gnssCallback?.let { try { lm?.unregisterGnssMeasurementsCallback(it) } catch (_: Exception) {} }
        nmeaListener = null
        locationListener = null
        gnssCallback = null
    }

    private fun onGnssClock(event: GnssMeasurementsEvent) {
        val elapsedNow = SystemClock.elapsedRealtimeNanos()
        val clock = event.clock
        // Without FullBiasNanos the receiver hasn't locked GPS time yet.
        if (!clock.hasFullBiasNanos()) return

        val bias = if (clock.hasBiasNanos()) Math.round(clock.biasNanos) else 0L
        val gpsNanos = clock.timeNanos - (clock.fullBiasNanos + bias)
        val leap = if (clock.hasLeapSecond()) clock.leapSecond else DEFAULT_LEAP_SECONDS
        val utcNanos = gpsNanos + GPS_EPOCH_UNIX_NANOS - leap * 1_000_000_000L

        val elapsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && clock.hasElapsedRealtimeNanos()) {
            clock.elapsedRealtimeNanos
        } else {
            elapsedNow
        }
        setReference(utcNanos, elapsed, TIME_SRC_GNSS)
    }

    private fun onLocation(location: Location) {
        // Don't override the better GNSS source once it's active.
        if (timeSource == TIME_SRC_GNSS) return
        val elapsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }
        setReference(location.time * 1_000_000L, elapsed, TIME_SRC_LOCATION)
    }

    private fun setReference(utcNanos: Long, elapsedNanos: Long, source: Int) {
        refUtcNanos = utcNanos
        refElapsedNanos = elapsedNanos
        timeSource = source
    }

    private fun hasTime() = timeSource != TIME_SRC_NONE

    /** Current best estimate of true UTC in Unix nanoseconds. */
    private fun nowUtcNanos(): Long {
        val baseUtc = refUtcNanos
        val baseElapsed = refElapsedNanos
        return baseUtc + (SystemClock.elapsedRealtimeNanos() - baseElapsed)
    }

    // --- SNTP server ---

    private fun startNtpServer() {
        serverThread = Thread({
            try {
                socket = DatagramSocket(NTP_PORT)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind UDP $NTP_PORT", e)
                serverStatus = SERVER_ERROR
                updateNotification("Error: could not bind port $NTP_PORT")
                broadcastStatus()
                return@Thread
            }

            serverStatus = SERVER_SERVING
            serverAddr = resolveServerAddresses()
            updateNotification("Serving NTP on $serverAddr:$NTP_PORT")
            broadcastStatus()

            val reqBuf = ByteArray(48)
            while (running.get()) {
                try {
                    val request = DatagramPacket(reqBuf, reqBuf.size)
                    socket!!.receive(request)
                    val recvNanos = nowUtcNanos()
                    val response = buildResponse(request.data, recvNanos)
                    socket!!.send(DatagramPacket(response, response.size, request.address, request.port))

                    lastClient = request.address?.hostAddress ?: "?"
                    requestsServed.incrementAndGet()
                    broadcastStatus()
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "NTP request handling error", e)
                }
            }
        }, "NTP-Server").apply { isDaemon = true; start() }
    }

    private fun stopNtpServer() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        serverThread?.interrupt()
        serverThread = null
    }

    /**
     * Build a 48-byte SNTP server response. When we have no GPS time yet we reply
     * with LI=3 (unsynchronized) and stratum 16 so a correct client refuses it
     * rather than setting the clock from garbage.
     */
    private fun buildResponse(request: ByteArray, receiveNanos: Long): ByteArray {
        val buf = ByteArray(48)
        val synced = hasTime()

        val clientVn = (request[0].toInt() shr 3) and 0x07
        val vn = if (clientVn in 1..4) clientVn else 4
        val li = if (synced) 0 else 3           // 3 = clock not synchronized
        buf[0] = ((li shl 6) or (vn shl 3) or 4).toByte()  // mode 4 = server
        buf[1] = (if (synced) 1 else 16).toByte()          // stratum: 1 = primary (GPS)
        buf[2] = if (request[2].toInt() != 0) request[2] else 4  // poll
        buf[3] = (-20).toByte()                            // precision ~1µs clock resolution

        // Root delay (4..7) and root dispersion (8..11) left zero.

        // Reference identifier "GPS\0"
        buf[12] = 'G'.code.toByte()
        buf[13] = 'P'.code.toByte()
        buf[14] = 'S'.code.toByte()
        buf[15] = 0

        if (synced) {
            // Reference timestamp: instant of our last GPS sync.
            putTimestamp(buf, 16, refUtcNanos)
        }
        // Originate timestamp: echo the client's transmit timestamp verbatim.
        System.arraycopy(request, 40, buf, 24, 8)
        // Receive timestamp: when the request arrived.
        putTimestamp(buf, 32, receiveNanos)
        // Transmit timestamp: as late as possible before send.
        putTimestamp(buf, 40, nowUtcNanos())

        return buf
    }

    private fun putTimestamp(buf: ByteArray, off: Int, utcNanos: Long) {
        if (utcNanos <= 0) return
        val unixSec = utcNanos / 1_000_000_000L
        val nanoRem = utcNanos % 1_000_000_000L
        val ntpSec = unixSec + NTP_UNIX_OFFSET_SECS
        // Fraction of a second scaled to 2^32. nanoRem < 1e9, so (nanoRem << 32) fits in Long.
        val frac = (nanoRem shl 32) / 1_000_000_000L
        writeUInt32(buf, off, ntpSec)
        writeUInt32(buf, off + 4, frac)
    }

    private fun writeUInt32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = ((value ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
    }

    /**
     * Build the "point the PC at" string: the phone's hotspot IPv4 with the port,
     * best guess first. Cellular/virtual interfaces are dropped because their IPs
     * (e.g. a carrier 10.x address) aren't reachable from a hotspot client.
     */
    private fun resolveServerAddresses(): String {
        return try {
            val ranked = mutableListOf<Pair<String, Int>>()
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = (ni.name ?: "").lowercase()
                // Skip mobile-data / transitional / virtual interfaces.
                if (listOf("rmnet", "pdp", "ccmni", "clat", "v4-rmnet", "dummy", "rev_")
                        .any { name.startsWith(it) }) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    var score = 0
                    // Tethering/hotspot interfaces are usually named ap*, softap, swlan,
                    // wlan*, rndis* (USB), bt-pan, or bridge — prefer those.
                    if (name.startsWith("ap") || name.contains("softap") || name.contains("swlan") ||
                        name.contains("tether") || name.startsWith("wlan") || name.startsWith("rndis") ||
                        name.contains("bt-pan") || name.startsWith("bridge")) score += 10
                    if (ip.startsWith("192.168.")) score += 2
                    ranked.add(ip to score)
                }
            }
            if (ranked.isEmpty()) return "(hotspot off?)"
            ranked.sortByDescending { it.second }
            val ips = ranked.map { it.first }
            val primary = "${ips.first()}:$NTP_PORT"
            if (ips.size > 1) "$primary   (or ${ips.drop(1).joinToString(", ")})" else primary
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate interfaces", e)
            "?"
        }
    }

    // --- NMEA parsing for UI display only ---

    private fun processNmeaForDisplay(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.length < 6 || !message.startsWith("$")) return
        val commaIdx = message.indexOf(',')
        if (commaIdx < 4) return
        when (message.substring(commaIdx - 3, commaIdx)) {
            "GGA" -> {
                val p = message.split(",")
                if (p.size >= 8) {
                    if ((p[6].toIntOrNull() ?: 0) > 0) parseCoordinates(p[2], p[3], p[4], p[5])
                    satellitesUsed = p[7].toIntOrNull() ?: satellitesUsed
                }
            }
            "GSA" -> {
                val p = message.split(",")
                if (p.size >= 3) {
                    gpsStatus = when (p[2].toIntOrNull() ?: 1) {
                        2 -> GPS_FIX_2D
                        3 -> GPS_FIX_3D
                        else -> GPS_NO_FIX
                    }
                }
            }
            "GSV" -> {
                val p = message.split(",")
                if (p.size >= 4) satellitesInView = p[3].toIntOrNull() ?: satellitesInView
            }
            "RMC" -> broadcastStatus()  // ~1 Hz UI refresh
        }
    }

    private fun parseCoordinates(lat: String, latDir: String, lon: String, lonDir: String) {
        try {
            if (lat.length >= 4) {
                currentLat = lat.substring(0, 2).toDouble() + lat.substring(2).toDouble() / 60.0
                if (latDir == "S") currentLat = -currentLat
            }
            if (lon.length >= 5) {
                currentLon = lon.substring(0, 3).toDouble() + lon.substring(3).toDouble() / 60.0
                if (lonDir == "W") currentLon = -currentLon
            }
        } catch (_: NumberFormatException) {
        }
    }

    private fun formatUtc(): String {
        if (!hasTime()) return ""
        val ms = nowUtcNanos() / 1_000_000L
        val totalSecs = ms / 1000
        val hh = (totalSecs / 3600) % 24
        val mm = (totalSecs / 60) % 60
        val ss = totalSecs % 60
        return String.format("%02d:%02d:%02d.%03d", hh, mm, ss, ms % 1000)
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // --- Status broadcasting ---

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_SERVER_STATUS, serverStatus)
            putExtra(EXTRA_SERVER_ADDR, serverAddr)
            putExtra(EXTRA_REQUESTS_SERVED, requestsServed.get())
            putExtra(EXTRA_LAST_CLIENT, lastClient)
            putExtra(EXTRA_TIME_SOURCE, timeSource)
            putExtra(EXTRA_GPS_STATUS, gpsStatus)
            putExtra(EXTRA_SATELLITES_USED, satellitesUsed)
            putExtra(EXTRA_SATELLITES_VIEW, satellitesInView)
            putExtra(EXTRA_LATITUDE, currentLat)
            putExtra(EXTRA_LONGITUDE, currentLon)
            putExtra(EXTRA_UTC_TIME, formatUtc())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
