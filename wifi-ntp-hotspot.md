# WiFi NTP over the phone's hotspot

This mode makes the phone a **stratum-1 NTP server** disciplined by its GPS, served
over the phone's own **WiFi hotspot**. A PC joins that hotspot and sets its clock
from the phone. No cable, no router, no internet.

```
┌─────────────────────────┐   WiFi hotspot    ┌──────────────────────┐
│ Android phone (AccuTime) │  UDP :10123 (NTP) │ Linux Lite 3.4 PC     │
│                          │ ◄───────────────► │                       │
│ GPS → GnssClock          │   SNTP exchange   │ accutime-sync.py      │
│ SNTP server (this app)   │                   │ → settimeofday()      │
│ = hotspot gateway        │                   │ = hotspot client      │
│   (192.168.43.1)         │                   │   (192.168.43.x)      │
└─────────────────────────┘                   └──────────────────────┘
```

## How the phone keeps accurate time

The app disciplines a clock from GPS, best source first:

1. **GnssClock** (raw GNSS measurements, API 24+) — hardware GPS time with a
   nanosecond-scale reference. Computed as:
   ```
   gps_ns  = TimeNanos − (FullBiasNanos + BiasNanos)
   utc_ns  = gps_ns + GPS_EPOCH_UNIX_NS − leapSeconds·1e9
   ```
   paired with the measurement's `elapsedRealtimeNanos` so it can be projected
   forward precisely for each NTP request.
2. **Location fallback** — `Location.getTime()` paired with the fix's
   `elapsedRealtimeNanos`, for phones without raw-GNSS support. Coarser (often
   truncated to whole seconds); the app shows when it's using this.

Until a fix exists, the server answers with **LI=3 (unsynchronized)** and stratum
16, and correct clients (including the bundled one) refuse to set the clock.

## Why UDP port 10123, not 123

Ports below 1024 are privileged; an **unrooted Android app cannot bind port 123**.
The server uses **10123** instead. Clients that can target a custom port:

- The bundled `linux-client/accutime-sync.py` (`--port`, default 10123).
- `chrony`: `server 192.168.43.1 port 10123 iburst`.

Plain `ntpdate` / classic `ntpd` cannot set a per-server port — hence the
included client.

## Phone setup

1. Open **AccuTime**, tap **WiFi NTP → hotspot**.
2. Turn on the phone's WiFi hotspot (Android Settings → Network → Hotspot).
   Leave mobile data off if you like; the hotspot's local network is all that's
   needed.
3. Press **START**. Grant location permission if asked (needed to read GPS).
4. The app shows:
   - `Point the PC at: 192.168.43.1:10123` — the address/port for the client.
   - `Time source: GNSS raw` (best) or `Location fallback`.
   - GPS fix, satellites, position, live UTC, and requests served.

> Tip: keep the AccuTime screen on or the phone plugged in. The server runs as a
> foreground service so GPS stays active with the screen off, but some phones
> throttle the hotspot aggressively when idle.

## PC setup (Linux Lite 3.4 / any Linux)

1. Connect the PC to the phone's hotspot WiFi.
2. Copy `linux-client/accutime-sync.py` to the PC (one file, USB stick is fine).
3. Run it as root:
   ```sh
   sudo python accutime-sync.py 192.168.43.1
   ```
   See [linux-client/README.md](linux-client/README.md) for `--loop`, `--hwclock`,
   the boot-time systemd service, and troubleshooting.

## Accuracy

WiFi round-trip is a few ms and the NTP offset calculation cancels most of the
symmetric delay, so expect **±2–10 ms** with `GNSS raw`. That is far inside
FT8's ~1 s window and comfortably meets FT2's ±50 ms requirement.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Point the PC at: (hotspot off?)` | Turn the phone's WiFi hotspot on, then STOP/START. |
| Client: `query failed: timed out` | PC not on the hotspot, wrong IP, or wrong port. Confirm the IP shown in the app; ping `192.168.43.1`. |
| Client: `server reports NOT synchronized` | Phone has no GPS fix yet — wait for `Time source: GNSS raw`. |
| Client: `need root to set the clock` | Re-run with `sudo` (only `--dry-run` works without root). |
| Offset is large but stable each run | Normal on first sync; it steps the clock. Use `--loop` to keep it disciplined. |
| `Time source: Location fallback` | Phone lacks raw-GNSS support; accuracy is coarser but usually still sub-second. |
