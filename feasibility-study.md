# Feasibility Study: Android GPS Time to Windows via ChronoGPS/Decodium

## Overview

This document examines the feasibility of an Android app reading GPS time and transmitting it to a Windows PC for use with ChronoGPS (bundled with Decodium 3.0). Target accuracy: ±50ms.

**Verdict: Highly feasible. ±50ms is conservative; ±2–10ms is achievable with WiFi UDP/NTP.**

---

## 1. What Are Decodium and ChronoGPS?

### Decodium 3.0 "Codename Raptor"
- A WSJT-X fork by Martino Merola (IU8LMC) implementing the **FT2 ultra-fast digital mode** (3.8-second TX/RX cycles)
- FT2 requires clock accuracy of **±50ms** (4x tighter than FT8's ±200ms)
- Retains support for FT8, FT4, JT65, and other WSJT-X modes
- Has its own built-in NTP client (25+ servers, RTT filtering, IQR outlier removal, EMA smoothing)
- License: GPL v3
- Platform: Windows x64 (primary)
- Repository: https://github.com/iu8lmc/Decodium-3.0-Codename-Raptor

### ChronoGPS
- Standalone high-precision Windows time sync tool by Yoshiharu Tsukuura (JP1LRT)
- **Bundled with Decodium 3.0** (v2.4.6 included in installer, launchable from Decodium's UI)
- Also usable independently of Decodium
- Written in Python 3.11+, distributed as standalone .exe via PyInstaller
- License: **MIT** (open source, auditable)
- Repository: https://github.com/jp1lrt/ChronoGPS

### ChronoGPS Module Architecture

| Module | Purpose |
|--------|---------|
| `main.py` | Application entry point |
| `gui.py` | Primary GUI interface |
| `nmea_parser.py` | Processes GPS NMEA data streams from COM ports |
| `ntp_client.py` | NTP server communication |
| `time_sync.py` | Central hub for OS time modification |
| `weak_sync_logic.py` | Periodic synchronization algorithms |
| `config.py` | JSON configuration file management |
| `admin.py` | Admin privilege checking and UAC elevation |
| `startup.py` | Boot argument parsing, mode selection, Mutex management |
| `shutdown_manager.py` | Graceful shutdown sequencing |
| `autostart.py` | Windows startup automation |
| `tray_icon.py` | System tray integration |
| `locales.py` | 16-language support |

---

## 2. ChronoGPS Input Interfaces

ChronoGPS accepts time data through exactly two interfaces:

### GPS/GNSS via COM/Serial Port
- Reads NMEA-0183 sentence streams from a GPS receiver connected to a Windows COM port
- Primarily uses **RMC (Recommended Minimum)** sentences for UTC time extraction
- Supports GPS, GLONASS, BeiDou, Galileo, QZSS, and SBAS (WAAS/MSAS/EGNOS)
- Standard baud rate: 9600
- Works with physical serial ports, USB-to-serial adapters, and **Bluetooth virtual COM ports**

### NTP via Network
- RFC 5905-compliant NTP client
- Default server: `pool.ntp.org`, configurable to any server IP
- 64-bit timestamp precision with offset/delay calculations to millisecond accuracy

### GPS Synchronization Modes
1. **Off** — GPS reception active for monitoring only, no time correction applied
2. **Instant Sync** — Uses GPS as absolute UTC reference, calibrates OS time once, then monitors drift
3. **Weak Sync / Interval Sync** — Accumulates time-offset samples every second; at configurable intervals (default 30s), applies corrections only when drift exceeds threshold (default ±0.2s)

---

## 3. GPS Time Accuracy Available on Android

| API | Accuracy | Notes |
|-----|----------|-------|
| `Location.getTime()` | ±1s to ±10ms | Milliseconds often truncated to `.000` on many devices |
| `Location.getElapsedRealtimeNanos()` | ±1–5ms | Timestamped at HAL level, monotonic, nanosecond resolution |
| `GnssClock` via `GnssMeasurementsEvent` (API 24+) | ±10–50 **nanoseconds** | Raw hardware clock — the gold standard |
| `OnNmeaMessageListener` (API 24+) | Raw NMEA | Direct access to NMEA sentences from the GPS hardware |

### GnssClock Time Calculation
```
GPS_time_ns = TimeNanos - (FullBiasNanos + BiasNanos)
UTC_time_ns = GPS_time_ns - (LeapSeconds * 1_000_000_000)
```

The chipset self-reports uncertainty (typically 1–50ns). GPS time itself is not the bottleneck — transport latency is.

---

## 4. Transport Options

### WiFi UDP
| Metric | Value |
|--------|-------|
| Median one-way latency | 1–5ms |
| P95 latency | 5–10ms |
| Jitter | Low with NTP-style RTT compensation |

### Bluetooth SPP (Serial Port Profile)
| Metric | Value |
|--------|-------|
| Typical latency | 5–30ms one-way |
| Worst case | 50–100ms |
| Jitter | Moderate |

### Bluetooth LE
| Metric | Value |
|--------|-------|
| Latency | Tied to connection interval (7.5–30ms typical) |
| Jitter | Less predictable than SPP |

---

## 5. Two Viable Paths to ChronoGPS

### Path 1: NMEA over Bluetooth SPP → Virtual COM Port

```
┌──────────────┐  Bluetooth SPP   ┌─────────────────┐   COM Port    ┌────────────┐
│ Android App  │ ──────────────►  │ Windows BT Stack │ ───────────► │ ChronoGPS  │
│              │  $GPRMC sentences │ (Virtual COM port│  NMEA stream  │            │
│ Reads GPS    │  at 1 Hz          │  e.g. COM5)      │              │ Reads COM5 │
│ Sends NMEA   │                  │                  │              │ Sets clock │
└──────────────┘                  └─────────────────┘              └────────────┘
```

**Android side — existing apps:**
- **Bluetooth GPS Output** (meowsbox.com) — commercial, actively maintained, sends NMEA over BT SPP
- **ShareGPS** (jillybunch.com) — sends NMEA over Bluetooth SPP, TCP/IP, and USB

**Android side — custom app:**
- Read GPS via `OnNmeaMessageListener` (raw NMEA from hardware) or construct `$GPRMC` from `GnssClock`
- Write NMEA to Bluetooth RFCOMM socket (SPP UUID `00001101-0000-1000-8000-00805f9b34fb`)

**Windows side — zero custom work:**
1. Pair phone via Bluetooth
2. Windows auto-creates virtual COM port (visible in Device Manager > Ports)
3. In ChronoGPS, select that COM port as GPS source
4. ChronoGPS reads NMEA and syncs clock

**Expected accuracy: ±30–100ms** — Bluetooth jitter is the limiting factor.

**Pros:** Minimal development effort; ChronoGPS already supports this. No WiFi/network needed.
**Cons:** Bluetooth jitter may occasionally exceed ±50ms. No RTT compensation in NMEA protocol.

### Path 2: Android as NTP Server → ChronoGPS NTP Client

```
┌──────────────┐    WiFi UDP/123    ┌────────────┐
│ Android App  │ ◄────────────────► │ ChronoGPS  │
│              │   NTP protocol     │            │
│ SNTP Server  │                    │ NTP Client │
│ GPS-derived  │                    │ Syncs clock│
└──────────────┘                    └────────────┘
```

**Android side — custom app that:**
1. Reads `GnssClock` for nanosecond-accurate GPS time
2. Runs an SNTP server on UDP port 123
3. Responds to NTP queries with GPS-derived timestamps

**Windows/ChronoGPS side — configuration only:**
1. Change ChronoGPS NTP server from `pool.ntp.org` to phone's local IP (e.g. `192.168.1.42`)
2. ChronoGPS handles RTT compensation automatically via NTP protocol

**Expected accuracy: ±2–10ms** — well within ±50ms target.

**Pros:** Best accuracy; standard protocol; ChronoGPS already has an NTP client; large accuracy margin.
**Cons:** Phone and PC must be on same WiFi network. Custom Android app required.

---

## 6. NMEA $GPRMC Sentence Format

The key sentence ChronoGPS uses for time extraction:

```
$GPRMC,hhmmss.ss,A,llll.llll,a,yyyyy.yyyy,a,x.x,x.x,ddmmyy,x.x,a*hh<CR><LF>
```

Example:
```
$GPRMC,225446.00,A,4916.4500,N,12311.1200,W,000.5,054.7,191124,020.3,E*6A
```

| Field | Example | Meaning |
|-------|---------|---------|
| UTC Time | `225446.00` | 22:54:46.00 UTC |
| Status | `A` | A=Active/Valid, V=Void |
| Latitude | `4916.4500,N` | 49°16.45'N |
| Longitude | `12311.1200,W` | 123°11.12'W |
| Speed | `000.5` | Knots over ground |
| Course | `054.7` | Degrees true |
| Date | `191124` | 19 Nov 2024 |
| Mag Var | `020.3,E` | Magnetic variation |
| Checksum | `*6A` | XOR of chars between `$` and `*` |

---

## 7. Accuracy Summary by Configuration

| Setup | Expected Accuracy | Meets ±50ms? |
|-------|-------------------|--------------|
| GnssClock + WiFi NTP (Path 2) | ±2–10ms | Yes (10x margin) |
| ElapsedRealtimeNanos + WiFi NTP | ±5–15ms | Yes (5x margin) |
| Raw NMEA + Bluetooth SPP (Path 1) | ±30–100ms | Borderline |
| Location.getTime() + Bluetooth | ±100–1000ms | No |

---

## 8. Windows Time-Setting Requirements

All methods require **administrator privileges** on Windows:
- `SetSystemTime()` / `SetLocalTime()` Win32 API requires `SE_SYSTEMTIME_NAME` privilege
- ChronoGPS handles UAC elevation automatically via its `admin.py` module
- v2.5+ supports a "Monitor-Only" mode that launches without UAC, with sync features unlockable on demand

---

## 9. Alternative Windows GPS Time Sync Software

If ChronoGPS is insufficient or you want alternatives:

| Software | Cost | NMEA Needed | Notes |
|----------|------|-------------|-------|
| **GPSTime** (COAA) | Free | `$GPRMC` only | Simple, works with BT COM ports |
| **NMEATime2** (VisualGPS) | $20 | Multiple sentences | Advanced jitter filtering |
| **Meinberg NTP** | Free | `$GPRMC` | Complex config, acts as NTP server |
| **BktTimeSync** | Free | NMEA 0183 | Has companion Android app (BktTimeSyncGPS) |
| **GPS2Time** (VK4ADC) | Free | General NMEA | Basic but functional |

---

## 10. Existing Android Apps (Status as of March 2026)

**No off-the-shelf Android app is currently viable.** Google Play's August 2025 API 34 target requirement caused most older GPS/NMEA apps to be delisted:

| App | Status |
|-----|--------|
| **Bluetooth GPS Output** (meowsbox) | Reportedly unavailable / incompatible |
| **ShareGPS** (jillybunch) | Delisted from Play (Feb 2024), last updated 2016 |
| **GPS NMEA Bluetooth Transmitter** (LishSoft) | Play status unclear, last APK Apr 2025 |
| **BktTimeSyncGPS** | Available but uses proprietary protocol (not NMEA) |

---

## 11. Solution: NMEA Bridge (Custom Android App)

A custom Android app ("NMEA Bridge") was built to fill this gap. It reads raw NMEA sentences from the phone's GPS chipset via `OnNmeaMessageListener` and forwards them over Bluetooth SPP to ChronoGPS.

### App Details
- **Package**: `com.gpstobt.nmeabridge`
- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0) — covers ~98% of active devices
- **Target SDK**: 35 (Android 15) — meets Google Play 2025+ requirements
- **License**: Custom (not published to Play Store)

### How It Works
1. App registers an `OnNmeaMessageListener` to receive raw NMEA from the GPS hardware
2. Filters to only RMC, GGA, GSA, GSV sentences (what ChronoGPS needs)
3. Opens a Bluetooth RFCOMM server socket (SPP UUID `00001101-0000-1000-8000-00805f9b34fb`)
4. Waits for Windows to connect (Windows pairs with phone, sees virtual COM port)
5. Forwards filtered NMEA sentences to the Bluetooth output stream
6. Runs as a foreground service so GPS stays active when screen is off

### UI Features
- Start/Stop toggle
- Bluetooth connection status (disconnected / waiting / connected + device name)
- GPS fix status (no fix / 2D / 3D)
- Satellite count (in use / in view)
- Lat/Lon position display
- UTC time from GPS
- Sentence counter
- Scrollable NMEA log (last 50 sentences)

### Setup on Windows Side
1. Pair phone with Windows PC via Bluetooth
2. Note the virtual COM port in Device Manager > Ports (COM & LPT) > "Standard Serial over Bluetooth link (COMx)"
3. Open ChronoGPS, select that COM port, set baud rate to 9600
4. ChronoGPS reads NMEA and syncs the Windows clock

### Source Files
```
app/src/main/
├── AndroidManifest.xml          — permissions, service declaration
├── java/com/gpstobt/nmeabridge/
│   ├── MainActivity.kt          — UI, service lifecycle, status display
│   ├── NmeaBluetoothService.kt  — foreground service, NMEA listener, BT SPP server
│   └── PermissionHelper.kt      — runtime permission handling (API 24-35)
└── res/
    ├── layout/activity_main.xml  — Material Design layout
    └── values/
        ├── strings.xml
        └── colors.xml
```

---

## 12. Recommendation

**Primary approach**: Use NMEA Bridge (custom app) with Bluetooth SPP → ChronoGPS COM port. Expected accuracy ±30–100ms. Adequate for FT8 (±200ms), borderline for FT2 (±50ms).

**If higher accuracy is needed for FT2**: Build a companion WiFi NTP mode into the app that uses `GnssClock` for nanosecond GPS time and serves it via SNTP. Point ChronoGPS NTP client at the phone's IP. Expected ±2–10ms.

**Hardware alternative**: A USB GPS dongle (u-blox based, ~$15) plugged directly into the Windows PC eliminates all wireless latency. ChronoGPS reads it natively.

---

## References

- Decodium 3.0 GitHub: https://github.com/iu8lmc/Decodium-3.0-Codename-Raptor
- ChronoGPS GitHub: https://github.com/jp1lrt/ChronoGPS
- FT2 Technical Info: https://www.ft2.it/
- Adventures in FT2 (Mid Sussex ARS): https://midsussexars.org.uk/news/518-adventures-in-ft2-with-decodium-3
- NMEATime2 / VisualGPS: https://www.visualgps.net/
- GPSTime (COAA): https://www.coaa.co.uk/gpstime.htm
- BktTimeSync: https://www.maniaradio.it/en/bkttimesync.html
- Bluetooth GPS Output: http://www.meowsbox.com/en/btgps
- ShareGPS: http://jillybunch.com/sharegps/
- Windows SetSystemTime API: https://learn.microsoft.com/en-us/windows/win32/api/sysinfoapi/nf-sysinfoapi-setsystemtime
