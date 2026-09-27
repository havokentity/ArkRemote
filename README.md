# Ark Remote

Android remote for Samsung Odyssey Ark monitors (gen 1 and Ark 2). It controls each monitor over
Wi-Fi instead of IR, so one remote no longer turns the other monitors off.

- **All on / All off.** These check each monitor's power state first, so they never toggle one the wrong way.
- **Switch input on all monitors.** HDMI 1–4 buttons that you can rename (for example "Gaming PC" or "Work PC").
- **Per-monitor control.** On, off, input, and a full remote (source, D-pad, OK, back, home, volume).
- **Quick Settings tile.** It turns the whole group off if any monitor is on, otherwise it turns them all on.
- **Network scan.** Finds Samsung displays on your network.

## How it works

| Action | Mechanism |
|---|---|
| Status | `GET http://<ip>:8001/api/v2/` → `device.PowerState` |
| Keys (power off, inputs, remote) | `wss://<ip>:8002/api/v2/channels/samsung.remote.control` (token issued on first approval) |
| Power on | Wake-on-LAN magic packet to the monitor's MAC, plus `KEY_POWER` if it is in network standby |

## Monitor setup (once per monitor)

1. Settings › General › Network › Expert Settings › **Power On with Mobile**: turn **On**.
2. If your firmware lists **IP Remote** there, turn that on as well.
3. Give each monitor a DHCP reservation on your router so its IP address stays the same.
4. In the app, scan (or add by IP), then tap **Pair** on each monitor and accept the prompt on its screen.

## Install

Every push to `main` builds a signed APK and attaches it to a GitHub Release (see the
[Releases](../../releases) page). Download it on your phone and install it (you need to allow
installs from your browser). The builds are signed with the same key every time, so each new APK
installs as an update over the previous one.

## Building locally

Open the project in Android Studio (it provides Gradle), or run `gradle assembleRelease` with
JDK 17 and the Android SDK installed. Release signing reads `ARK_KEYSTORE_PATH`,
`ARK_KEYSTORE_PASSWORD` and `ARK_KEY_ALIAS`. If they are not set, the build falls back to the
debug key.
