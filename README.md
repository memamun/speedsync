# SpeedSync — Internet Speed Meter ⚡

[![Website](https://img.shields.io/badge/Website-Showcase-00e5ff?style=flat)](https://memamun.github.io/speedsync/)
[![Download APK](https://img.shields.io/badge/Download-APK%20v1.1.1-success?style=for-the-badge&logo=android)](https://github.com/memamun/speedsync/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg?style=flat&logo=android)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![MinSdk](https://img.shields.io/badge/Min%20SDK-24-orange.svg?style=flat)](https://developer.android.com)
[![Privacy Policy](https://img.shields.io/badge/Privacy-Policy-purple.svg?style=flat)](https://memamun.github.io/speedsync/privacy.html)

A high-performance, battery-efficient real-time internet speed meter and network data monitor for Android, built with modern Jetpack Compose, Material 3 Expressive, and Kotlin Coroutines.

> 📲 **Instant Install**: Download the ready-to-run [SpeedSync_v1.1.1_Release.apk](https://github.com/memamun/speedsync/releases/latest) directly onto your Android device.

---

## ✨ Features

- **Live Status Bar Speed Indicator**:
  - Displays real-time download and upload speeds directly in the Android status bar.
  - Pixel-perfect typography and sizing calibrated to match classic speed meter utilities.
  - Uses 1,290+ pre-rendered hdpi bitmaps (0–999 KB/s, 1.0–29.1 MB/s) with a dynamic canvas generator fallback using *Liberation Sans Bold*.
  - Leftmost status bar placement using `PRIORITY_MAX`, alphabetical sort keys, and prioritized timestamps.

- **Intelligent Battery & Resource Conservation**:
  - **Smart IPC Deduplication**: Throttles notification updates to a 5-second heartbeat when idle or when network throughput is 0 B/s, slashing Binder IPC transactions to `system_server` by over 80%.
  - **Screen-Off State Throttling**: Monitors screen state via broadcast receiver, gracefully releasing wake locks and slowing polling to 8 seconds when the screen is off.
  - **Storage Hygiene**: Automatically prunes daily historical metrics older than 60 days to prevent unbounded `SharedPreferences` growth.

- **Modern Jetpack Compose Material 3 UI**:
  - Edge-to-edge layout with full dynamic system bar contrast adaptation for Light and Dark themes.
  - Smooth animated speedometer gauge with live download/upload activity indicators.
  - 2×2 diagnostic metric cards displaying upload speed, ping, jitter, and packet loss.

- **Integrated Network Speed Test**:
  - Multi-phase network diagnostic suite (Ping latency, Jitter, Packet Loss, Download throughput, and Upload throughput).
  - **Cooperative Cancellation**: Instantly abort in-flight network streams with responsive UI state reset.

- **Traffic Tracking & History**:
  - Separates Wi-Fi vs Cellular data metrics automatically based on the active network transport.
  - In-memory metric accumulation with 15-second disk flush intervals to eliminate flash storage wear.
  - 30-day historical usage view.

- **Hardened System Lifecycle**:
  - Foreground Service with `dataSync` service type compliant with Android 14+ (API 34/35/36).
  - `BootReceiver` handles `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, and device quickboot intents.
  - Protected `onTaskRemoved` lifecycle handling preventing `ForegroundServiceStartNotAllowedException` on Android 12+.

- **Accessibility**:
  - Full TalkBack support with merged semantic descriptions across metric cards, speed gauge, and navigation elements.

---

## 🛠 Tech Stack & Architecture

- **UI Framework**: Jetpack Compose (BOM), Material 3 Design
- **Language**: Kotlin 2.x
- **Concurrency & Reactivity**: Kotlin Coroutines, StateFlow (`collectAsStateWithLifecycle`)
- **Networking**: OkHttp 4, Android `TrafficStats`, `ConnectivityManager`
- **Testing**: JUnit 4, Robolectric, Roborazzi Screenshot Testing

---

## 📱 Permissions Used

| Permission | Purpose |
|---|---|
| `INTERNET` | Performing speed tests and ping diagnostics |
| `ACCESS_NETWORK_STATE` | Identifying active network interfaces (Wi-Fi vs Cellular) |
| `FOREGROUND_SERVICE` | Running the live background speed monitor |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ requirement for foreground sync services |
| `POST_NOTIFICATIONS` | Android 13+ status bar notification permission |
| `RECEIVE_BOOT_COMPLETED` | Automatically restoring the meter on system reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Allowing uninterrupted background operation on aggressive OEM ROMs |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug | 2024.2.1 or newer
- JDK 17 or JDK 21
- Android SDK (API 36 compile SDK, API 24 minimum SDK)

### Building from Source

```bash
# Clone the repository
git clone https://github.com/memamun/speedsync.git
cd speedsync

# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License

```
Copyright 2026 SpeedSync Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
