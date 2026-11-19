# Privacy Policy — Ghost BLE Tracking (native Android)

Last updated: 2026-11-19

## The short version

Ghost BLE Tracking collects nothing, sends nothing, and tracks nobody.
All scanning happens on your phone, and all results stay on your phone.

## What the app does with scan data

The app uses your phone's Bluetooth and Wi-Fi radios to list devices and
networks broadcasting around you — the same broadcasts any nearby phone
can hear. That data (device names, MAC/BSSID addresses, signal strength,
advertised services, network names and security types):

- is shown to you in the app, on your device;
- is kept only in the app's memory while you use it (tracked devices and
  distance history exist for the current session);
- is **never** uploaded, synced, backed up by the app, sold, or shared.

There is no server, no account, no cloud sync, no analytics, no
advertising, and no third-party tracking code of any kind in this app.

## Permissions, in plain language

- **Bluetooth Scan / Connect** — to run Bluetooth LE scans and read device
  names. Declared with Android's `neverForLocation` flag: the app does
  not use Bluetooth to work out where you are.
- **Location (fine)** — Android requires this permission before it will
  return Bluetooth (older Android) and Wi-Fi scan results. The app never
  reads your GPS position.
- **Nearby Wi-Fi Devices (Android 13+)** — to run Wi-Fi scans without
  location.
- **Notifications** — only for proximity alerts on devices you chose to
  track. Optional; scanning works without it.
- **Wi-Fi state** — to check Wi-Fi is on and to trigger Wi-Fi scans.

## How to delete all of your data

Close the app and its in-memory session data is gone. Uninstalling the
app removes everything it stored. There is nothing stored anywhere else
to delete.

## Children

The app contains no advertising, no purchases and no data collection, and
is not directed at children.

## Changes

If a future version ever changed any of the above, this policy and the
Google Play Data Safety declarations would be updated **before** that
version is published.

## Contact

Questions about this policy: via the GitHub repository
`eye4gotteverything-bot/ghost-ble-tracking` (open an issue).
