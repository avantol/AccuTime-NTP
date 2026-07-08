# GPS-NTP-Hotspot (AccuTime)

Turn an Android phone into a **GPS time source** for other computers, with two
output modes:

1. **Bluetooth → ChronoGPS** — forwards raw NMEA over Bluetooth SPP to a Windows
   virtual COM port for ChronoGPS (the original AccuTime NMEA Bridge). See
   [connecting-chronogps.md](connecting-chronogps.md).
2. **WiFi NTP → hotspot** *(new)* — the phone runs a GPS-disciplined SNTP server
   over its own **WiFi hotspot**, so any PC on that hotspot can set its clock
   with **no cable and no router**. Aimed at an offline **Linux Lite 3.4** PC,
   but works with any NTP client. See [wifi-ntp-hotspot.md](wifi-ntp-hotspot.md).

This is a detached fork of the AccuTime NMEA Bridge project; see
[feasibility-study.md](feasibility-study.md) for the full background.

## WiFi NTP mode in one minute

- Phone: open **AccuTime**, tap **WiFi NTP → hotspot**, turn on your phone's WiFi
  hotspot, press **START**. The app shows the address to point the PC at
  (usually `192.168.43.1:10123`) and the GPS time source once it has a fix.
- PC: connect to the phone's hotspot, then run the bundled client — no IP needed,
  it auto-detects the phone (your default gateway):
  ```sh
  sudo python accutime-sync.py
  ```
  The client and its boot-time service live in [linux-client/](linux-client/) and
  need **nothing installed** (Python stdlib only). Expected accuracy ±2–10 ms.

## Why a high port (10123) instead of 123

An unrooted Android app cannot bind privileged ports (< 1024), so the SNTP
server uses UDP **10123**. The bundled Linux client targets that port; `chrony`
can too (`server <ip> port 10123`). Standard `ntpdate` cannot, which is why the
client is included.

## App modules

| File | Role |
|------|------|
| `MainActivity.kt` | UI, mode selector (Bluetooth / WiFi NTP), status |
| `NmeaBluetoothService.kt` | Bluetooth SPP NMEA bridge (mode 1) |
| `NtpServerService.kt` | GPS-disciplined SNTP server on UDP 10123 (mode 2) |
| `PermissionHelper.kt` | Runtime permissions (Bluetooth only when needed) |

## Build

Standard Android Gradle project (min SDK 24, target SDK 35):

```sh
./gradlew assembleDebug        # or assembleRelease
```

Output APK: `app/build/outputs/apk/.../AccuTime.apk`.
