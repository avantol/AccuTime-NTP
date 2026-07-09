# AccuTime Linux client (WiFi NTP)

Set an offline Linux PC's clock from the **AccuTime** phone app's GPS-disciplined
SNTP server, over the **phone's WiFi hotspot** — no cable, no router, no internet.

Built for **Linux Lite 3.4** (Ubuntu 16.04 base, i386) but portable to any Linux.
Uses only the Python standard library (Python 2.7 or 3.x), so there is **nothing
to install** on the target machine.

## Why this and not `ntpdate`

An unrooted Android app can't bind the privileged NTP port 123, so AccuTime
serves on a high port (default **10123**). `ntpdate` can't target a custom port;
this client can. (If you prefer `chrony`, it also works: `server 192.168.43.1
port 10123 iburst`.)

## One-time setup

1. On the phone: open **AccuTime**, choose **WiFi NTP → hotspot**, and turn on
   the phone's WiFi hotspot (Settings → Hotspot). Press **START**. The app shows
   `Point the PC at: 192.168.43.1:10123` (your IP may differ) and, once it has a
   fix, `Time source: GNSS raw`.
2. On the PC: connect to the phone's hotspot WiFi network.
3. Copy `accutime-sync.py` to the PC (USB stick is fine — it's one file).

## Usage

```sh
sudo python accutime-sync.py                    # zero-config: auto-detects the phone
sudo python accutime-sync.py 10.254.59.85       # explicit phone IP
sudo python accutime-sync.py 10.254.59.85:10123 # host:port pasted from the app
     python accutime-sync.py --dry-run          # measure offset, don't set clock (no root)
sudo python accutime-sync.py --loop --interval 60   # keep the clock disciplined
sudo python accutime-sync.py --hwclock          # also write the hardware RTC (UTC)
```

**Auto-detect (default):** with no host argument (or `auto`), the client uses the
PC's **default gateway** as the phone address. On the phone's hotspot the gateway
*is* the phone, so there's nothing to look up or type — ideal for field use. Pass
an explicit IP only if you're on a more complex network with another gateway.

Setting the clock requires **root** (or `CAP_SYS_TIME`); `--dry-run` does not.

| Option | Default | Meaning |
|--------|---------|---------|
| `host` (positional) | `auto` | Phone IP or `host:port`; `auto` = detect the hotspot gateway |
| `-p, --port N`   | `10123` | UDP port the app serves on |
| `-t, --timeout N`| `5`     | Socket timeout, seconds |
| `--loop`         | off     | Re-sync forever instead of once |
| `--interval N`   | `60`    | Seconds between syncs in `--loop` mode |
| `--min-step N`   | `0`     | Skip setting if the offset is under N seconds |
| `--hwclock`      | off     | Also write the corrected time to the RTC (UTC) |
| `--dry-run`      | off     | Measure & print offset; don't touch the clock |

The client prints the measured round-trip delay and clock offset each sync, e.g.:

```
AccuTime sync -> 192.168.43.1:10123
stratum=1  round-trip delay=3.2 ms  clock offset=+412.6 ms
clock set to 2026-07-08 14:31:07 UTC
```

If the phone has no GPS lock yet, the server replies "not synchronized" (NTP
LI=3) and the client refuses to set the clock — so you never set a wrong time.

## Run at boot (optional)

`accutime-sync.service` (systemd) keeps the clock disciplined while the PC is on
the hotspot. Edit the phone IP if needed, then:

```sh
sudo cp accutime-sync.py /usr/local/bin/
sudo cp accutime-sync.service /etc/systemd/system/
sudo systemctl enable --now accutime-sync.service
```

Because the phone is your time source, stop the system NTP daemon from fighting
it: `sudo timedatectl set-ntp false` (harmless offline, but explicit).

## Desktop icon on XFCE / Linux Lite (field-tested gotchas)

Getting a working double-click launcher on old XFCE (Linux Lite 3.4) took some
fighting. Use `AccuTime-Sync.desktop` as the template and mind these:

1. **Edit the two machine-specific lines:** set `Exec=`'s path to where
   `launch-accutime.sh` actually lives on that PC (put `accutime-sync.py` in the
   **same folder** — the launcher looks for it next to itself).
2. **`Terminal=false` is mandatory here.** The `Exec` line opens the terminal
   itself (`x-terminal-emulator -e ...`). If you set `Terminal=true`, XFCE also
   tries to open one and the icon **silently does nothing**.
3. **Use `x-terminal-emulator`, not `xfce4-terminal`.** The latter isn't always
   installed; `x-terminal-emulator` is the standard alias to whatever terminal
   the machine has.
4. **Executable bit:** files copied off a FAT/USB stick often lose it, and the
   GUI "Allow this file to run" tick doesn't always set it. From a terminal:
   `chmod +x ~/Desktop/"AccuTime Sync.desktop"`.
5. **Trust:** right-click the icon → Properties → Permissions → tick "Allow this
   file to run as a program". If a double-click opens the file in a text editor,
   it's untrusted/non-executable (see 4).

If you'd rather not hand-edit terminal args, point `Exec=` at
`run-accutime-gui.sh` instead (still `Terminal=false`) — it auto-detects an
installed terminal (xfce4-terminal, xterm, lxterminal, …) and runs the sync
inside it.

## Accuracy

WiFi UDP round-trip is typically a few milliseconds, and the NTP offset math
cancels most of the symmetric portion, so expect **±2–10 ms** once the phone
reports `GNSS raw` as its time source. That's well inside FT8's ~1 s window and
FT2's ±50 ms window. On phones without raw-GNSS support the app falls back to
`Location` time (coarser — the app shows which is active).
