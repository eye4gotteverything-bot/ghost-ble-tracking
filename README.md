# Ghost BLE Tracking

A Bluetooth Low Energy (BLE) and Wi-Fi proximity scanner: it shows the devices
around your phone, sorted by **estimated distance in feet**, across the
2.4 GHz BLE band and the 5 GHz and 6 GHz Wi-Fi bands — with MAC/BSSID address,
device type, signal strength, and a “targeting” view that flags radio patterns
that can be aimed at mobile devices.

Display name: **Ghost BLE Tracking** · Repo: `ghost-ble-tracking` under
`eye4gotteverything-bot` · Android only (for the future native build) — no Apple version is planned.

---

## ⚠️ Read this first — honesty about the web version

**A web page cannot power a phone's Bluetooth or Wi-Fi radios for scanning.**
This repository is the web version of the app, and it **cannot scan real
surrounding devices** — no browser page can.

The only scan data in this version comes from a clearly labeled **Demo Mode**:

- Demo Mode is **OFF by default**. The app starts completely empty: no
  results, no tracked devices, no history.
- Demo Mode simulates scans with **fictional** sample devices and networks.
  Every demo entry is labeled “Demo”, uses fictional names and clearly
  fictional addresses, and is never a real device.
- Turning Demo Mode off clears all demo results, flags and demo history.
- Live Updates, tracking, proximity alerts and targeting flags all work
  inside Demo Mode, so you can see exactly how they behave.

**Real scanning** on all bands (BLE + 5 GHz + 6 GHz Wi-Fi) requires a native
Android build with Bluetooth Scan, location, and Wi-Fi scanning permissions.
That native build is the Google Play / APK path — see
[Android / Google Play](#android--google-play) below.

---

## What it does

- **Scanner** — Start / Pause / Stop controls, a live scan-status bar
  (state, elapsed time, the band + channel being swept right now, and live
  counters: total devices, per-band counts, targeting flags), a radar-style
  proximity view with band-colored blips, and a results list sorted
  closest-first. Each result shows name (or “Unknown”), MAC/BSSID, device
  type, RSSI in dBm with a signal bar, first seen / last seen, and an
  estimated distance in feet (meters in parentheses).
- **Bands & Channels** — three bands, each with its own on/off toggle and
  per-channel “heard” counts. Filter the list and radar by All / 2.4 / 5 / 6.
- **Device detail** — tap any result for everything the (demo) advertisement
  carries: MAC/BSSID, name, type, band, channel, frequency in MHz, RSSI,
  distance estimate, connectable yes/no (BLE), advertised service UUIDs with
  friendly names (Heart Rate 0x180D, Battery 0x180F, Device Information
  0x180A, …), manufacturer, Wi-Fi security type, a distance history for the
  session, and a Track button.
- **Targeting** — a dedicated scan that flags **suspected patterns** aimed at
  mobile devices: an evil-twin pattern (same network name, different BSSID),
  a generic open hotspot soliciting phones, channel hopping / presence on
  many channels, BLE advertisement flooding, a possible follower signal
  (distance stays roughly constant), repeated pairing/connection attempts,
  and a persistent unknown transmitter. Each flag shows the source's
  MAC/BSSID, band/channel/frequency, RSSI, distance estimate in feet,
  device-type guess, a plain-language reason, a risk level (Low / Watch /
  High), first/last seen and times seen, plus Track and Dismiss buttons.
  **Flags are patterns to be aware of, NOT proof of an attack** — most have
  innocent explanations, and even a native phone build can only see
  patterns, never prove a device is an IMSI catcher or similar.
- **Tracking** — pin devices or targeting sources from any band (targeting
  sources are marked ⚠). Distances update during scans, with a proximity
  alert (default: within 10 feet, editable) and a lost-device alert (after a
  set number of unseen minutes, editable), plus a distance-over-time log per
  tracked device. Starts empty.
- **History** — every finished scan saves a session: date, duration, device
  count, targeting flag count, the device list, and that scan's full
  Live Updates timeline. Starts empty.
- **Live Updates feed** — timestamped event lines as they happen: scan
  started, new device found (name/type, distance in feet, band), targeting
  pattern flagged (reason, distance), tracked device entered alert range,
  tracked device lost, and a scan-finished summary.
- **Settings** — environment setting for the distance estimate (see below),
  a Demo Mode toggle, and a “Send updates to Muse chat” toggle (default on)
  with an honest label: in this web version updates appear in the app's
  Live Updates feed only; chat delivery is a native-build feature.

## How the distance estimate works

Distance is **always an estimate**, never a measurement. It is calculated
from signal strength (RSSI) with the standard log-distance path-loss formula:

```
distance = 10 ^ ((RSSI_at_1m − RSSI) / (10 × n))
```

with `RSSI_at_1m = −59 dBm` (typical at 1 meter) and a path-loss exponent `n`
set by the environment setting:

| Environment | n |
|---|---|
| Outdoor / open | 2.0 |
| Indoor (default) | 2.7 |
| Crowded / interference | 3.3 |

Results are shown in feet, with meters in parentheses. Walls, human bodies,
pockets, device orientation and interference can shift the real distance a
lot — treat the number as “roughly this far”, not “exactly this far”.

## Bands & channels

| Band | Use | Channels shown |
|---|---|---|
| 2.4 GHz | Bluetooth LE | Advertising ch 37 · 2402 MHz, 38 · 2426 MHz, 39 · 2480 MHz, plus all 40 BLE channels (2402–2480 MHz) |
| 5 GHz | Wi-Fi | 36 · 5180, 44 · 5220, 149 · 5745, 157 · 5785, 165 · 5825 MHz (representative) |
| 6 GHz | Wi-Fi 6E | 1 · 5955, 33 · 6115, 69 · 6295, 149 · 6695, 233 · 7115 MHz (representative) |

Device types the app can label: phone, headphones/earbuds, smartwatch/
wearable, fitness tracker, smart TV, speaker, keyboard, mouse, game
controller, beacon, heart-rate monitor, Wi-Fi access point/network, and
other/unknown.

## Run it locally

No build step, no dependencies — plain HTML/CSS/JS.

Option 1 — just open the file:

```
open index.html        # or double-click it in your file manager
```

Option 2 — serve it (recommended, closest to how it will be hosted):

```sh
cd ghost-ble-tracking-repo
python3 -m http.server 8000
# then visit http://localhost:8000
```

Then: open **Settings**, turn on **Demo Mode**, go to **Scanner** and press
**Start Scan**. Try the Targeting tab and its **Start Targeting Scan** too.

## Upload to GitHub

This code is ready to upload as-is to the repository
**`eye4gotteverything-bot/ghost-ble-tracking`** (create it as a public repo
named `ghost-ble-tracking`, then add these files at the repo root:
`index.html`, `styles.css`, `app.js`, `README.md`, `PRIVACY.md`, `LICENSE`,
`.gitignore`).

Per the owner's rule: changes made while testing go on a branch / pull
request and are approved by the owner before merging to `main` — nothing
goes live without that approval.

The repo contains no real device data, no real names, no phone numbers and
no keys or secrets — demo data is fictional and generated in the browser.

## Privacy summary

- All app data (settings, tracked devices, scan history) stays in **your
  browser's localStorage on your device**. There is no server, no account,
  and nothing is sent anywhere.
- There is **no tracking, analytics or advertising** of any kind.
- Demo data is fictional and lives only in memory while you scan.
- Delete everything any time from **Settings → Delete all app data**, or by
  clearing the site's data in your browser.
- Full policy: see [PRIVACY.md](PRIVACY.md).

## Android / Google Play

This web version is the interface and logic prototype. A **native Android
rebuild** is required for real scanning, because only a native app can use
the phone's radios. That build will need:

- **Bluetooth Scan** permission (BLE scanning), **location** permission
  (required by Android for BLE scan results), and **Wi-Fi scanning**
  permission for the 5 GHz / 6 GHz bands.
- A signed Android App Bundle (`.aab`), a store listing, a hosted privacy
  policy, and Google Play's Data Safety and content-rating forms.
- If the developer account is a new personal account, Google requires a
  **closed test with at least 12 testers for 14 days** before production.
- Nothing is promoted to production without the owner's approval.

The owner's other app, **GhosŧŦasker**, follows exactly the same path
(hosted web version first, then the native Android build for Google Play),
so the process — and its permission cautions — is already proven there.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 eye4gotteverything-bot.
