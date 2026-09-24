# SpeedSync — Internet Speed Meter ⚡

[![Website](https://img.shields.io/badge/Website-Showcase-00e5ff?style=flat)](https://memamun.github.io/speedsync/)
[![Download APK](https://img.shields.io/badge/Download-APK%20v2.0.0-success?style=for-the-badge&logo=android)](https://github.com/memamun/speedsync/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg?style=flat&logo=android)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![MinSdk](https://img.shields.io/badge/Min%20SDK-24-orange.svg?style=flat)](https://developer.android.com)
[![Privacy Policy](https://img.shields.io/badge/Privacy-Policy-purple.svg?style=flat)](https://memamun.github.io/speedsync/privacy.html)

A high-performance, battery-efficient real-time internet speed meter and network data monitor for Android, built with modern Jetpack Compose, Material 3 Expressive, and Kotlin Coroutines.

> 📲 **Instant Install**: Download the ready-to-run [SpeedSync_v2.0.0_Release.apk](https://github.com/memamun/speedsync/releases/latest) directly onto your Android device.

---

## ✨ Features

- **Live Status Bar Speed Indicator**:
  - Displays real-time download and upload speeds directly in the Android status bar.
  - Pixel-perfect typography and sizing calibrated to match classic speed meter utilities.
  - Uses 1,290+ pre-rendered hdpi bitmaps (0–999 KB/s, 1.0–29.1 MB/s) with a dynamic canvas generator fallback using *Liberation Sans Bold*.
  - Explicit resource shrinking protection via `res/raw/keep.xml` preserving all dynamic drawables and font assets in release builds.
  - Leftmost status bar placement using `PRIORITY_MAX`, alphabetical sort keys, and prioritized timestamps.

- **Intelligent Battery & Resource Conservation**:
  - **Display-Based Visual Deduplication**: Compares formatted notification title, body, expanded text, and active icon identifier; completely eliminates redundant notification dispatches when visual metrics remain unchanged without arbitrary wakeups.
  - **Screen-Off Quiescence**: Stops 1-second sampling loops immediately when the screen turns off. Sleep traffic delta is reconciled upon wake, avoiding battery drain during sleep.
  - **Sequential Lifecycle Channel**: Processes screen-on, screen-off, start, and stop events in strict FIFO order through an unbounded actor channel to prevent race conditions.
  - **Callback-Driven Connectivity Caching**: Leverages `ConnectivityManager.NetworkCallback` to cache active network metadata and bandwidth, eliminating per-second binder IPC capability queries.
  - **Storage Architecture**: In-memory metric accumulation with periodic 15-second background flushes and lifecycle flushes (`ScreenOff`, `Stop`) to prevent flash storage wear, maintaining a 30-day historical usage record.

- **Modern Jetpack Compose Material 3 UI**:
  - Edge-to-edge layout with full dynamic system bar contrast adaptation for Light and Dark themes.
  - Smooth animated speedometer gauge optimized with `derivedStateOf` to eliminate redundant recompositions.
  - 2×2 diagnostic metric cards displaying upload speed, ping, jitter, and packet loss.

- **Hardened Network Speed Test**:
  - Multi-phase diagnostic suite (Ping latency, Jitter, Packet Loss, Download throughput, and Upload throughput).
  - **Sustained Streaming**: Sustained transfer loops with bounded concurrency (`concurrency = 2`) over 512 KB chunks and 50 MB download streams.
  - **Enforced Phase Deadlines**: Actively aborts in-flight socket requests upon deadline expiry or user cancellation.
  - **Session Isolation**: Each test runs with an independent `TestSession`, cancellation scope, and request registry, preventing superseded runs from modifying state.
  - **Monotonic Calculations**: Uses monotonic nanosecond timing (`System.nanoTime()`) for all throughput math, immune to system clock shifts or NTP syncs.

- **Accurate Traffic Accounting**:
  - Accounts for accumulated Wi-Fi and Cellular data metrics independently of whichever transport is active at the moment of wake or sampling.
  - **Proportional Sleep Attribution**: Unobserved sleep delta spanning one or more calendar midnights is distributed proportionally across each crossed calendar day based on elapsed duration, mathematically preserving 100% of total bytes down to the last byte.

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
- **Concurrency & Reactivity**: Kotlin Coroutines, StateFlow (`collectAsStateWithLifecycle`), Channels
- **Networking**: OkHttp 4, Android `TrafficStats`, `ConnectivityManager` event callbacks
- **Testing**: JUnit 4, Robolectric, Kotlin Coroutines Test, Roborazzi Screenshot Testing (34 unit & integration test cases)

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

# Assemble release APK and App Bundle
./gradlew assembleRelease bundleRelease
```

The compiled APK will be located at `app/build/outputs/apk/release/app-release.apk`.

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
