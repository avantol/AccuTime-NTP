#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
accutime-sync.py -- set a Linux PC's clock from the AccuTime phone app's
GPS-disciplined SNTP server, over the phone's WiFi hotspot.

No packages to install: uses only the Python standard library, so it runs on an
offline box (Python 2.7 or 3.x, e.g. Linux Lite 3.4 / Ubuntu 16.04).

Why not ntpdate/ntpd? An unrooted Android app can't bind the privileged NTP
port 123, so AccuTime serves on a high port (default 10123). Standard ntpdate
can't target a custom port; this client can.

Typical use (as root, so it can set the clock):

    sudo python accutime-sync.py                 # one-shot, default host/port
    sudo python accutime-sync.py 192.168.43.1    # explicit phone IP
    sudo python accutime-sync.py --loop --interval 60   # keep it disciplined
    python accutime-sync.py --dry-run            # just show the offset

The phone's hotspot gateway is usually 192.168.43.1 (shown in the AccuTime app).
"""

from __future__ import print_function

import argparse
import os
import socket
import struct
import sys
import time

# Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01).
NTP_UNIX_OFFSET = 2208988800

DEFAULT_HOST = "192.168.43.1"
DEFAULT_PORT = 10123


def detect_gateway():
    """
    Return the default-route gateway IPv4, or None. On a phone's hotspot the PC's
    default gateway IS the phone, so this finds the server with no configuration —
    handy for field/mountaintop use. Reads /proc/net/route directly (stdlib only).
    """
    try:
        best = None  # (metric, ip)
        with open("/proc/net/route") as f:
            f.readline()  # skip header
            for line in f:
                parts = line.split()
                if len(parts) < 11:
                    continue
                dest, gw, flags, metric = parts[1], parts[2], parts[3], parts[6]
                if dest != "00000000":          # 0.0.0.0 == default route
                    continue
                flags_i = int(flags, 16)
                if not (flags_i & 0x1) or not (flags_i & 0x2):   # RTF_UP | RTF_GATEWAY
                    continue
                ip = socket.inet_ntoa(struct.pack("<L", int(gw, 16)))
                m = int(metric)
                if best is None or m < best[0]:
                    best = (m, ip)
        return best[1] if best else None
    except Exception:
        return None


def ntp_to_unix(sec, frac):
    """Convert a 64-bit NTP timestamp (two 32-bit words) to Unix seconds (float)."""
    return (sec - NTP_UNIX_OFFSET) + float(frac) / 2 ** 32


def unix_to_ntp(unix_time):
    """Convert Unix seconds (float) to a 64-bit NTP timestamp (sec, frac)."""
    sec = int(unix_time) + NTP_UNIX_OFFSET
    frac = int((unix_time - int(unix_time)) * (2 ** 32)) & 0xFFFFFFFF
    return sec & 0xFFFFFFFF, frac


def query(host, port, timeout):
    """
    Send one SNTP request and return (offset, delay, stratum, leap_indicator).

    offset = amount to add to the local clock to correct it (seconds).
    Raises socket.timeout / socket.error on network failure.
    """
    # LI=0, VN=3, Mode=3 (client). Rest of the 48-byte packet is zero except our
    # transmit timestamp, which a well-behaved server echoes back as originate.
    packet = bytearray(48)
    packet[0] = 0x1B
    t1 = time.time()
    ts_sec, ts_frac = unix_to_ntp(t1)
    struct.pack_into("!II", packet, 40, ts_sec, ts_frac)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(bytes(packet), (host, port))
        data, _ = sock.recvfrom(48)
        t4 = time.time()
    finally:
        sock.close()

    if len(data) < 48:
        raise ValueError("short NTP reply (%d bytes)" % len(data))

    li = (struct.unpack("!B", data[0:1])[0] >> 6) & 0x3
    stratum = struct.unpack("!B", data[1:2])[0]
    # Receive timestamp (t2) at bytes 32..39, transmit timestamp (t3) at 40..47.
    rx_sec, rx_frac = struct.unpack("!II", data[32:40])
    tx_sec, tx_frac = struct.unpack("!II", data[40:48])
    t2 = ntp_to_unix(rx_sec, rx_frac)
    t3 = ntp_to_unix(tx_sec, tx_frac)

    offset = ((t2 - t1) + (t3 - t4)) / 2.0
    delay = (t4 - t1) - (t3 - t2)
    return offset, delay, stratum, li, t4


def set_clock(unix_time):
    """Step the system clock to unix_time (UTC seconds). Requires root."""
    try:
        import ctypes
        import ctypes.util

        class Timeval(ctypes.Structure):
            _fields_ = [("tv_sec", ctypes.c_long), ("tv_usec", ctypes.c_long)]

        libc_name = ctypes.util.find_library("c") or "libc.so.6"
        libc = ctypes.CDLL(libc_name, use_errno=True)
        sec = int(unix_time)
        usec = int(round((unix_time - sec) * 1e6))
        if usec >= 1000000:
            sec += 1
            usec -= 1000000
        tv = Timeval(sec, usec)
        if libc.settimeofday(ctypes.byref(tv), None) != 0:
            err = ctypes.get_errno()
            raise OSError(err, os.strerror(err))
    except Exception as exc:
        # Fall back to GNU date if settimeofday isn't usable for some reason.
        sys.stderr.write("settimeofday failed (%s); trying `date`\n" % exc)
        if os.system('date -u -s "@%0.6f" >/dev/null' % unix_time) != 0:
            raise


def sync_once(args):
    try:
        offset, delay, stratum, li, t4 = query(args.host, args.port, args.timeout)
    except Exception as exc:
        print("query failed: %s" % exc, file=sys.stderr)
        return False

    print("stratum=%d  round-trip delay=%.1f ms  clock offset=%+.1f ms"
          % (stratum, delay * 1000.0, offset * 1000.0))

    if li == 3:
        print("server reports NOT synchronized (no GPS lock yet) -- not setting clock",
              file=sys.stderr)
        return False

    if args.dry_run:
        print("dry-run: would set clock (offset %+.3f s)" % offset)
        return True

    if abs(offset) < args.min_step:
        print("offset within %.0f ms threshold -- clock left unchanged"
              % (args.min_step * 1000.0))
        return True

    if os.geteuid() != 0:
        print("need root to set the clock (re-run with sudo)", file=sys.stderr)
        return False

    corrected = t4 + offset
    set_clock(corrected)
    print("clock set to %s UTC" % time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(corrected)))

    if args.hwclock:
        if os.system("hwclock --systohc >/dev/null 2>&1") == 0:
            print("hardware clock updated")
        else:
            print("hwclock update failed (non-fatal)", file=sys.stderr)
    return True


def main():
    parser = argparse.ArgumentParser(
        description="Set this PC's clock from the AccuTime phone SNTP server.")
    parser.add_argument("host", nargs="?", default="auto",
                        help="phone IP or host:port; default 'auto' detects the "
                             "hotspot gateway (the phone). Fallback: %s" % DEFAULT_HOST)
    parser.add_argument("-p", "--port", type=int, default=DEFAULT_PORT,
                        help="UDP port (default %d)" % DEFAULT_PORT)
    parser.add_argument("-t", "--timeout", type=float, default=5.0,
                        help="socket timeout in seconds (default 5)")
    parser.add_argument("--loop", action="store_true",
                        help="keep syncing on --interval instead of once")
    parser.add_argument("--interval", type=float, default=60.0,
                        help="seconds between syncs in --loop mode (default 60)")
    parser.add_argument("--min-step", type=float, default=0.0,
                        help="skip setting if |offset| below this many seconds")
    parser.add_argument("--hwclock", action="store_true",
                        help="also write the corrected time to the RTC via hwclock")
    parser.add_argument("--dry-run", action="store_true",
                        help="measure and print the offset but don't set the clock")
    args = parser.parse_args()

    args.host = args.host.strip().rstrip(",")
    if args.host.lower() in ("", "auto"):
        # Field default: the phone is the PC's default gateway on its hotspot.
        gw = detect_gateway()
        if gw:
            print("auto-detected phone at gateway %s" % gw)
            args.host = gw
        else:
            print("auto-detect failed; using %s (or pass an explicit IP)"
                  % DEFAULT_HOST, file=sys.stderr)
            args.host = DEFAULT_HOST
    elif args.host.count(":") == 1:
        # Accept "host:port" pasted straight from the app. One colon only, so
        # IPv6 is left alone (this client is IPv4-only). Embedded port wins.
        host_part, _, port_part = args.host.partition(":")
        if port_part.isdigit():
            args.host = host_part
            args.port = int(port_part)

    print("AccuTime sync -> %s:%d" % (args.host, args.port))
    if not args.loop:
        sys.exit(0 if sync_once(args) else 1)

    while True:
        sync_once(args)
        try:
            time.sleep(args.interval)
        except KeyboardInterrupt:
            print("\nstopped")
            break


if __name__ == "__main__":
    main()
