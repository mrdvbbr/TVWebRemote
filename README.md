# TVWebRemote 📺

> **Zero-latency local web remote dashboard for Android TV & TV Boxes with an Apple TV Siri Remote aesthetic.**

![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Web%20PWA-3b82f6?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-10b981?style=flat-square)
![Latency](https://img.shields.io/badge/Latency-%3C2ms%20(UDP%20%2B%20uinput)-emerald?style=flat-square)

**TVWebRemote** is a lightweight Android service that hosts an embedded, mobile-first Web Remote Dashboard directly on your Android TV box (port `8080`). Anyone connected to your local Wi-Fi can open the IP address in Safari, Chrome, or any browser to instantly control the TV—**no App Store downloads, TestFlight, or client installs required**.

---

## ✨ Features

- 📱 **Zero-Friction Client**: Open `http://<tv-ip>:8080` on any device (iPhone, Android, Mac, iPad, Windows).
- 📲 **Add to Home Screen (PWA)**: Full-screen iOS / Android web app with custom app icon, viewport lock, and haptics.
- ⚡ **Ultra-Low Latency (<2ms)**: Native C `/dev/uinput` daemon with UDP transport bypasses slow Android Runtime startup.
- 🎛️ **Apple TV Aesthetic**:
  - Precision **Clickpad**: Circular directional navigation ring with concave center OK button.
  - **Unified Volume Rocker**: Vertical pill rocker for Volume Up and Volume Down.
  - Dedicated **Back**, **TV/Home**, **Menu**, **Power**, and **Pause** controls.
  - **Tactile Audio & Haptic Feedback**: Optional mechanical click audio synthesizer and tight haptics (`navigator.vibrate`).
- 📑 **iOS 18 Style Bottom Sheet**:
  - Dedicated **Quick Channels** drawer with category filter tabs (*Popular, National, Entertainment*).
  - Real-time instant search filter.
  - Pre-configured channel tuning with instant sequence dialing.
- 🚀 **Quick Apps Row**: One-tap instant launchers for **TelecomTV**, **YouTube TV**, and **Settings**.
- 🔄 **Boot Persistence**: Starts automatically in the background on TV power-on via `RECEIVE_BOOT_COMPLETED`.

---

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────┐
│               Any Device on Local Wi-Fi                │
│         (iPhone Safari, Android Chrome, Mac)           │
│                                                        │
│            [http://192.168.100.101:8080]               │
└───────────────────────────┬────────────────────────────┘
                            │ HTTP POST (Instant API)
                            ▼
┌────────────────────────────────────────────────────────┐
│            Android TV Box (Port 8080)                  │
│                                                        │
│   com.bbr.tvwebremote (Foreground Service)             │
│   ├── Embedded Multi-threaded HTTP Server              │
│   ├── KeyDispatcher & Package Launcher                 │
│   └── BootReceiver (Starts on Boot)                    │
└───────────────────────────┬────────────────────────────┘
                            │ Local UDP (Port 7777)
                            ▼
┌────────────────────────────────────────────────────────┐
│          Native tvkey Driver (C / uinput)              │
│   Injects hardware events directly into Linux kernel   │
│   Latency: <1-2ms                                      │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites
- Android TV box or Android TV device (Android 7.0+ / API 24+)
- Computer with `adb` installed

### Quick Installation
1. Connect to your TV box via ADB:
   ```bash
   adb connect <TV_IP>:5555  # or custom port, e.g. 6060
   ```
2. Build or download `TVWebRemote.apk`:
   ```bash
   ./build_apk.sh
   ```
3. Install the APK:
   ```bash
   adb install -r -d build/TVWebRemote.apk
   ```
4. Start the service (or launch TVWebRemote once from the TV app drawer):
   ```bash
   adb shell am start-foreground-service com.bbr.tvwebremote/.WebRemoteService
   ```
5. Open your browser:
   ```
   http://<TV_IP>:8080
   ```

---

## 🔌 HTTP API

The embedded server exposes a lightweight REST API for automation or external integration:

| Method | Endpoint | Description | Example |
|---|---|---|---|
| `GET` | `/` | Web Remote UI (HTML/CSS/JS) | `curl http://tv:8080/` |
| `GET` | `/api/status` | Server status | `curl http://tv:8080/api/status` |
| `POST` | `/api/key?name=<key>` | Injects D-pad or function key | `curl -X POST "http://tv:8080/api/key?name=up"` |
| `POST` | `/api/tune?num=<digits>` | Tunes to channel number | `curl -X POST "http://tv:8080/api/tune?num=010"` |
| `POST` | `/api/launch?pkg=<id>` | Launches Android application | `curl -X POST "http://tv:8080/api/launch?pkg=uz.telecom.telecomtv"` |

### Supported Key Names
`up`, `down`, `left`, `right`, `ok`, `back`, `home`, `menu`, `volup`, `voldown`, `mute`, `power`, `playpause`

---

## 🛠️ Building from Source

No Gradle or Android Studio required—a fast, standalone build script uses standard Android SDK build-tools:

```bash
chmod +x build_apk.sh
./build_apk.sh
```

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
