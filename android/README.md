# Ghost BLE Tracking — Native Android (REAL scanning)

A **real-time Bluetooth Low Energy + Wi-Fi scanner** for Android. This is the
native Android build of Ghost BLE Tracking, created because the earlier web
version could only run a demo: a web page cannot power a phone's Bluetooth or
Wi-Fi radios. **This app can, and does. There is no demo mode and no fake
data anywhere in this project** — the results list starts empty and only
fills with devices and networks your phone actually hears.

## What it really does

- **Real BLE scanning** with Android's `BluetoothLeScanner`. Continuous
  low-latency scanning across the BLE advertising channels (37 / 38 / 39 —
  the radio rotates through them automatically; Android does not let apps
  pick a single channel). For every advertiser heard it pulls:
  - MAC address, advertised name (or "Unknown")
  - RSSI (signal strength) and a **distance estimate in feet**
  - Advertised service UUIDs with friendly names (Heart Rate `0x180D`,
    Battery `0x180F`, Device Information `0x180A`, HID, audio, beacons…)
  - Manufacturer data + company name (Apple, Samsung, Google, Sony…)
  - Connectable flag, GAP appearance, advertised TX power
  - A best-effort **device type** guess (phone, headphones/earbuds,
    smartwatch/wearable, fitness tracker, heart-rate monitor, smart TV,
    speaker, keyboard, mouse, game controller, beacon, unknown) from
    services → appearance → name, in that order of reliability
- **Real Wi-Fi scanning** with `WifiManager`: SSID, BSSID, frequency (MHz),
  channel derived from frequency, band (**2.4 / 5 / 6 GHz**), security
  (WPA3 / WPA2 / WPA / WEP / Open), RSSI and a distance estimate in feet.
- **Distance in feet** from RSSI using the log-distance path-loss model
  `distance = 10 ^ ((txPowerAt1m − RSSI) / (10 · n))`, txPowerAt1m = −59 dBm,
  with a selectable environment: Outdoor n = 2.0, Indoor n = 2.7,
  Crowded/obstructed n = 3.3. It is an **estimate** — walls, pockets and
  body blocking can shift it by several feet.
- **Targeting-pattern flags** (heuristics — see "Honest limits"):
  - *Evil-twin candidate*: same SSID on multiple BSSIDs **with different
    security** (e.g. an open copy next to the real WPA2 network)
  - *Open generic hotspot*: open network with a generic lure-style name
  - *BLE advertisement flooding*: one address advertising abnormally fast
  - *Follower candidate*: same address seen across multiple scan sessions
    at a similar distance
- **Tracking**: tap Track (or long-press a result) to pin a device. If a
  tracked device comes within your chosen distance (default **10 ft**),
  you get a **notification**. Tap any result for full detail, including its
  distance history for the session.
- **Live status**: scanning state, elapsed time, and per-band counts
  (BLE / Wi-Fi 2.4 / 5 / 6 GHz), with the list sorted **closest first** and
  refreshed about once a second.

## Build and install

### Option A — Android Studio (easiest)

1. Install [Android Studio](https://developer.android.com/studio) (it
   bundles the Android SDK and a JDK — both are required; this project
   cannot be built with just a text editor).
2. Open this folder (`ghost-ble-android`) as a project and let Gradle sync.
   Android Studio will offer to generate the Gradle wrapper if needed.
3. Plug in your phone with USB debugging on (or use an emulator with
   Bluetooth support — note most emulators have limited/no real BLE, so a
   **real phone is strongly recommended** for this app).
4. Press **Run ▶**. The app installs and launches on the phone.

### Option B — Command line

```bash
# Requires: JDK 17, Android SDK (platform 34), Gradle 8.7+
# If the wrapper is not present yet, generate it once:
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
# APK lands at:
#   app/build/outputs/apk/debug/app-debug.apk
```

Install that APK on the phone (sideload):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

…or copy the APK to the phone and open it (you may need to allow
"Install unknown apps" for your file manager — only do this for APKs you
built yourself, like this one).

> **Build note:** this project was authored and statically reviewed
> (IDs, imports, manifest, packages all cross-checked) in an environment
> with no JDK/Android SDK installed, so it has not been compiled there.
> The first Gradle sync in Android Studio is the real first build; if it
> reports a small error, it will point at the exact file and line.

## Permissions — what and why

Android will not return Bluetooth or Wi-Fi scan results without these.
The app asks at runtime and explains itself if you say no.

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` (Android 12+) | The core permission to run BLE scans. Declared with `neverForLocation` — this app does **not** derive location from scans. |
| `BLUETOOTH_CONNECT` (Android 12+) | Needed to read a scanned device's name on Android 12+. |
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi scan results, and for all scan results on Android 11 and below. The app never reads GPS. On Android 11 and below, the system **Location toggle must also be ON** or results come back empty (Android's rule, not the app's). |
| `NEARBY_WIFI_DEVICES` (Android 13+) | The newer, location-free permission for Wi-Fi scanning. |
| `POST_NOTIFICATIONS` (Android 13+) | For proximity alerts on tracked devices. Denying it never blocks scanning. |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Read Wi-Fi state and trigger Wi-Fi scans. |
| Legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (≤ Android 11 only) | Pre-12 scanning permissions, capped with `maxSdkVersion=30`. |

## Honest limits — read this

- **Wi-Fi scan throttling is real.** Android allows a foreground app only
  **4 Wi-Fi scans per 2 minutes**. When throttled, the app says so on
  screen and shows the most recent results Android has. BLE scanning is
  **not** throttled and is the real-time part of the app.
- **6 GHz needs 6 GHz hardware.** 6 GHz (Wi-Fi 6E/7) results only appear on
  phones with a 6 GHz radio, near a 6 GHz network. On other phones that
  band simply stays at zero — that is the phone, not a bug.
- **Distances are estimates.** RSSI bounces around with orientation,
  walls, and bodies. Treat feet as "roughly this close", never exact.
- **MAC addresses can be randomized.** Many phones rotate the Bluetooth /
  Wi-Fi MAC addresses they advertise. The same physical device may appear
  under a new address later; a "follower" flag can also just be a
  coworker's phone, your own other device, or a neighbour's TV.
- **Targeting flags are patterns, not proof.** Same-SSID networks, open
  hotspots and fast advertisers all happen in normal places. This app
  **cannot** prove an attack, and it **cannot detect or prove an IMSI
  catcher / "stingray"** — no phone app can from scan data alone. Flags
  are labelled as patterns in the UI for exactly this reason.
- **Battery:** continuous low-latency BLE scanning uses noticeably more
  battery. Stop the scan when you're done.
- **Android only.** There is no iOS version; iOS does not allow this kind
  of scanning for App Store apps.

## Google Play path

Same path as GhosŧŦasker:

1. Build a signed release **.aab** (Android Studio → Build → Generate
   Signed Bundle) — Play does not accept plain APKs for new apps.
2. Play Console: create the app, use `PRIVACY.md` in this repo as the
   privacy policy (publish it at a public URL, e.g. GitHub Pages), and
   complete the Data Safety section honestly: the app processes Bluetooth
   and Wi-Fi scan data **on-device only, nothing is collected or shared**.
   Note that Google treats scan-derived identifiers seriously — declare
   the Bluetooth/Wi-Fi permissions and their purpose exactly as prompted.
3. Bluetooth/Wi-Fi scanning permissions get extra review on Play; be ready
   to explain the app's core scanning purpose in the permission
   declaration forms.
4. If the developer account is a **new personal account** (created after
   2023-11-13), Play requires a **closed test with at least 12 testers
   opted in continuously for 14 days** before production access.
5. Promote internal → closed → production **only with the owner's
   approval at each step** — nothing in this repo changes that rule.

## Privacy

See [PRIVACY.md](PRIVACY.md). Short version: everything stays on the
device. No servers, no accounts, no analytics, no ads, no data collection.

## License

MIT — see [LICENSE](LICENSE). © 2026 eye4gotteverything-bot
