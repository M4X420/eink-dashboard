# E-Ink Dashboard

Turn an old e-reader into a fast, interactive **Home Assistant control panel**.

The app runs natively on the e-reader (Android 2.3+), talks directly to Home Assistant over the
WebSocket API and draws a few large, high-contrast tiles. Tap a tile to toggle a light or run a
script, and see state changes live without page reloads and without screenshots. What the
device shows is configured **in Home Assistant**, using a normal dashboard.

Developed and tested on a **tolino shine (1st generation, 2013, Android 2.3.4)**.

> 🇩🇪 **Deutsch:** siehe [unten](#deutsch).

## Features

- **Live:** state changes arrive over WebSocket (`subscribe_entities`) within a fraction of a second.
- **Interactive:** tap to toggle, press buttons, run scripts and scenes, or any `perform-action`.
- **Configured in Home Assistant:** pick any dashboard; edit and save it, and the device updates immediately.
- **Easy pairing:** Home Assistant discovers the device (zeroconf). Enter the code shown on the display and you're done. No tokens to type.
- **Device in Home Assistant:** battery, charging, Wi-Fi signal and connectivity sensors; buttons for *Refresh display* and *Reload dashboard*.
- **Made for e-ink:** black/white tiles, batched redraws, a real hardware full refresh (GC16) against ghosting on Freescale i.MX devices, and a fallback flash on others.
- **Kiosk mode (root):** the app replaces the stock home screen and starts on boot.
- **Tiny:** no dependencies, around 50 KB APK, runs on 256 MB RAM.

## Requirements

| | |
|---|---|
| E-reader | Android 2.3 (API 9) or newer with Wi-Fi. Tested: tolino shine 1. Root is needed only for kiosk mode. |
| Home Assistant | 2025.2 or newer |
| Network | The device must reach Home Assistant via **plain HTTP in your LAN**, e.g. `http://192.168.1.10:8123`. Old Android cannot do modern TLS. A reverse proxy for external HTTPS access is fine, as long as port 8123 is reachable via HTTP inside the LAN. |

## Installation

### 1. Home Assistant integration

**HACS (recommended):** HACS → ⋮ → *Custom repositories* → add `https://github.com/M4X420/eink-dashboard`
with type *Integration* → install **E-Ink Dashboard** → restart Home Assistant.

**Manual:** copy `custom_components/eink_dashboard` into your Home Assistant `config/custom_components/`
folder and restart Home Assistant.

### 2. App on the e-reader

Download `eink-dashboard-<version>.apk` from the [latest release](https://github.com/M4X420/eink-dashboard/releases/latest)
and install it, for example with [ADB](https://developer.android.com/tools/adb):

```bash
adb install eink-dashboard-0.1.0.apk
adb shell am start -n io.github.m4x420.einkdashboard/.DashboardActivity
```

The app shows its IP address and a 6-digit pairing code.

### 3. Pair

Home Assistant shows **"Discovered: E-Ink Dashboard"** under *Settings → Devices & services*.
Click *Add* and enter the pairing code. If nothing is discovered, use *Add integration → E-Ink Dashboard*
and enter IP address and code manually.

During pairing, the integration creates a dedicated Home Assistant user (no admin rights, local network
only) and hands its token to the device. Deleting the integration unpairs the device and removes that user.

### 4. Choose what to show

Create a dashboard for the device (*Settings → Dashboards → Add dashboard*), then select it under
*E-Ink Dashboard → Configure*. Example:

```yaml
views:
  - title: E-Ink
    cards:
      - type: grid
        columns: 2              # becomes the column count on the device (1–4)
        cards:
          - type: button
            entity: light.living_room
            name: Living room
          - type: button
            entity: script.good_night
          - type: tile
            entity: sensor.outdoor_temperature
            name: Outside
          - type: tile
            entity: switch.coffee_maker
            tap_action:
              action: none       # display only
```

**Supported cards:** `button`, `tile`, `entity`, rows of `entities` cards, and any card with an
`entity`. These can be nested in `grid`, `vertical-stack` and `horizontal-stack`, and the *sections*
view layout works too. Only the first view is used, with up to 12 tiles.

**Tap actions:**

| Configuration | On tap |
|---|---|
| no `tap_action`, `toggle` or `more-info` | toggleable domains (light, switch, fan, cover, input_boolean, …) toggle; script/scene turn on; button/input_button press; everything else is display only |
| `perform-action` / `call-service` | the configured action including `data` and `target` |
| `none`, `navigate`, `url`, … | display only |

### 5. Kiosk mode (optional, root)

Hold the status line at the bottom for 5 seconds to open the menu, then choose
**"Als Startbildschirm einrichten (Root)"** and confirm the Superuser prompt. The stock home screen is
disabled, not removed, and the app starts on boot. **"Original-Startbildschirm wiederherstellen"** in the
same menu undoes it.

## Security notes

- The device talks to Home Assistant over **unencrypted HTTP**. Anyone who can sniff your LAN or
  Wi-Fi can read its token. That's why the integration uses a dedicated non-admin, local-only user:
  the token can switch devices but cannot change your Home Assistant configuration.
- Pairing requires the code shown on the display. Wrong codes are slowed down, and the code changes after
  10 failed attempts.
- The app opens a small HTTP server on port **8124** for pairing. Without the token it only reveals basic
  device info (model, app version, paired yes/no).

## Troubleshooting

- **"Kein Dashboard ausgewählt" (no dashboard selected):** pick one under *E-Ink Dashboard → Configure*.
- **Device not discovered:** both must be in the same network or VLAN, because mDNS does not cross routers.
  Use the manual setup with IP address and code.
- **Ghosting:** press *Refresh display* on the device page in Home Assistant. The app also does a full
  refresh after every 10 redraws.
- **Logs on the device:** `adb logcat -s eink-dashboard`

## Building from source

The app is in `android/`. It is a plain Gradle project with no dependencies.

```bash
cd android
./gradlew assembleDebug
```

For a signed release build, create `android/keystore.properties` (see the comment in
`android/app/build.gradle`). The file is git-ignored and must never be committed.

## Disclaimer

Not affiliated with tolino, Rakuten Kobo, Deutsche Telekom or Home Assistant. Kiosk mode uses root to
disable system components. Use at your own risk.

## License

[MIT](LICENSE)

---

## Deutsch

**E-Ink Dashboard** macht aus einem alten E-Reader, zum Beispiel einem **tolino shine**, ein
interaktives Bedienfeld für Home Assistant. Die App zeigt große Kacheln, die sich live aktualisieren.
Ein Tipp schaltet Licht, Schalter, Skripte usw.

**Kurzanleitung:**

1. Integration über HACS installieren: *Benutzerdefinierte Repositories* →
   `https://github.com/M4X420/eink-dashboard`, Typ *Integration*. Danach HA neu starten.
2. APK aus den [Releases](https://github.com/M4X420/eink-dashboard/releases/latest) auf den E-Reader
   installieren und starten.
3. Home Assistant meldet „Neu entdeckt: E-Ink Dashboard“. Auf *Hinzufügen* klicken und den Code vom
   Display eingeben.
4. Ein eigenes Dashboard mit wenigen Buttons/Kacheln anlegen und unter *E-Ink Dashboard → Konfigurieren*
   auswählen.
5. Optional: Statuszeile 5 Sekunden gedrückt halten → „Als Startbildschirm einrichten (Root)“.

**Wichtig:** Der E-Reader braucht im Heimnetz Zugriff auf Home Assistant per **HTTP**
(z.B. `http://192.168.2.10:8123`), weil alte Android-Versionen kein modernes HTTPS können.
