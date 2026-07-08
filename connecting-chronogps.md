# Connecting ChronoGPS to the Bluetooth COM Port

## How the Connection Works

The phone (running AccuTime) acts as a Bluetooth SPP **server** — it listens and waits. **Windows initiates the connection** when an application on the PC opens the Bluetooth COM port. You do not connect from the Windows Bluetooth settings screen; that screen is only for pairing.

In short: pair the devices once, then the connection happens automatically each time a Windows app (PuTTY, ChronoGPS, etc.) opens the COM port.

## Step 1: Pair the Phone with the PC

**Important**: AccuTime must be running (with START tapped) on the phone **before** you pair. Windows discovers the phone's SPP serial service during pairing. If AccuTime isn't running at that time, Windows won't know the phone offers a serial port and won't create the COM port.

1. On the phone, open AccuTime and tap **START** — it should show **"Bluetooth: Waiting for connection..."**
2. On the phone, make Bluetooth discoverable (Settings > Bluetooth)
3. On Windows, go to **Settings > Bluetooth & devices > Add device**
4. Select the phone and complete pairing
5. Windows may show "Couldn't connect" for some profiles — this is normal; it's trying audio/HID profiles that don't apply. The SPP serial service is still registered.

If you already paired **without** AccuTime running: remove the device on Windows, start AccuTime on the phone, then re-pair. This is the most common fix for connection problems.

## Step 2: Find the COM Port Number on Windows

1. Open **Device Manager** (right-click Start > Device Manager)
2. Expand **Ports (COM & LPT)**
3. Look for something like **"Standard Serial over Bluetooth link (COM5)"** — there may be two entries; you want the **outgoing** one
4. Note the COM port number (e.g., COM5)

**Alternative**: Settings > Bluetooth & devices > Devices > More Bluetooth settings > COM Ports tab. Look for the **Outgoing** port associated with your phone's name. If no port is listed, click **Add > Outgoing**, select your phone, and click **OK** to create one.

## Step 3: Start AccuTime on the Phone

If AccuTime isn't already running from the pairing step:

1. Open AccuTime on the phone
2. Tap **START**
3. The app should show **"Bluetooth: Waiting for connection..."**
4. The phone is now listening — it will connect as soon as Windows opens the COM port

## Step 4: (Optional) Verify Data with PuTTY First

Before pointing ChronoGPS at it, you can confirm NMEA is flowing:

1. Open **PuTTY** (or any serial terminal) on Windows
2. Select **Serial**, enter the COM port (e.g., COM5), speed **9600**
3. Click **Open** — this is the moment Windows initiates the Bluetooth SPP connection
4. AccuTime on the phone should switch to **"Bluetooth: Connected"**
5. You should see NMEA sentences scrolling:
   ```
   $GPRMC,123456.00,A,4807.038,N,01131.000,E,...*XX
   $GPGGA,123456.00,4807.038,N,01131.000,E,...*XX
   ```
6. If data flows, close PuTTY (important — only one app can use the COM port at a time)

## Step 5: Configure ChronoGPS via Decodium

1. Open **Decodium 3.0**
2. Select **View > Time synchronization**
   - **Deselect** "NTP enabled"
   - **Select** "Launch ChronoGPS"
3. Check the **Windows notification area** (system tray, lower-right corner of the screen) for the ChronoGPS icon
4. Right-click the ChronoGPS icon and select **Show**
5. In ChronoGPS:
   - **Deselect** "NTP Auto-sync" (we're using GPS, not NTP)
   - Enter the correct **COM port** (e.g., COM5)
   - Set **Baud Rate** to **9600**
   - Set **Interval Monitoring** to **5 minutes**
   - Check **"Run with Administrator privileges"** (required to set the Windows system clock)
   - Click **Start**
6. Confirm on the ChronoGPS status line: **"GPS reception started: COMxx @ 9600bps"**
7. This opens the COM port, which initiates the Bluetooth connection — AccuTime on the phone should switch to "Connected"
8. You should see:
   - NMEA sentences appearing in the ChronoGPS log
   - Time extracted from RMC sentences
   - Clock offset displayed (target: within ±50ms)

## Troubleshooting

- **Windows won't connect / no COM port appears**: The most common cause is that the phone was paired **without AccuTime running**. Fix: remove the device on Windows, start AccuTime on the phone (tap START, confirm "Waiting for connection"), then re-pair from Windows. Windows must discover the SPP service during pairing.
- **No COM port listed after pairing**: Check More Bluetooth settings > COM Ports tab; add an Outgoing port if needed.
- **COM port won't open / "Access denied"**: Another application is holding the port. Close PuTTY, HyperTerminal, or any other serial app. If nothing obvious is running, use **Process Explorer** (free from Microsoft) to find the process: Ctrl+F > search for "serial". Alternatively, restart the **Bluetooth Support Service** (Win+R > `services.msc` > find it > Restart).
- **Two COM ports listed**: Try the **Outgoing** one first; if that doesn't work, try the other.
- **AccuTime stays on "Waiting"**: The COM port hasn't been opened on Windows yet. Open it from PuTTY or ChronoGPS to trigger the connection. Remember: the phone does not initiate — Windows does, by opening the COM port.
- **Phone says "Couldn't connect" during pairing**: This is normal. Android's pairing UI tries to connect audio/HID profiles that don't apply. The SPP serial service is still registered successfully.
- **Data flows but no time sync**: Make sure the phone has a GPS fix (AccuTime should show "GPS: 3D Fix" and satellite count > 0). RMC sentences need status='A' (active fix) for ChronoGPS to accept the time.
- **Connection drops**: If the phone screen turns off or AccuTime is killed by Android's battery optimizer, the connection will drop. Keep AccuTime in the foreground or disable battery optimization for it.
