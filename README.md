# SensorsOff

   <p align="center">
  <img src="https://github.com/user-attachments/assets/5e387daf-d6c9-4e86-a6a8-661a8d81b0c4" width="200" alt="Your Logo"/>
</p>


[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?style=flat&logo=android)](https://www.android.com)
[![Release](https://img.shields.io/badge/Release-v2.8.3-brightgreen.svg?style=flat)](https://github.com/LinerSRT/SensorsOff)
[![minSdk](https://img.shields.io/badge/minSdk-24%20(Android%207.0)-blue.svg?style=flat)](https://developer.android.com/about/versions/nougat)
[![Target API](https://img.shields.io/badge/Privacy%20API-29%2B%20(Android%2010%2B)-purple.svg?style=flat)](https://developer.android.com/about/versions/10)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4.svg?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)

**SensorsOff** is an open-source Android privacy utility and system service management application designed to control hardware sensor privacy states (`SensorPrivacyManager`) across Android 10+ (API 29 to API 34+). 

It delivers fast on-demand hardware sensor toggling, real-time diagnostic telemetry, and persistent Quick Settings tile synchronization with multiple privileged backend execution pipelines.

---

## Android Version Compatibility

- **Application Installation (`minSdk = 24`)**: The app installs and runs its telemetry dashboards and sensor diagnostic tools on Android 7.0 Nougat (API 24) and higher.
- **Hardware Sensor Privacy Subsystem (`API 29+`)**: System-wide hardware sensor privacy (`ISensorPrivacyManager`) was introduced in Android 10 (API 29). Elevated hardware toggling via Shizuku/Root is supported on Android 10+ (API 29 to API 34+). On Android 7.0–9.0 (API 24–28), the underlying Android OS does not include `ISensorPrivacyManager`, and sensor privacy operations return `UNKNOWN` / unsupported.

---

## Architecture & System Overview

```
+-----------------------------------------------------------------------+
|                         SensorsOff Client UI                          |
|             (Jetpack Compose • Material 3 • MVVM Architecture)        |
+------------------------------------+----------------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------+
|                    Privileged Execution Pipeline                      |
|  +---------------------------------+  +----------------------------+  |
|  |           Shizuku API           |  |       Root Shell (su)      |  |
|  |     (Direct AIDL Binder IPC)    |  |     (Privileged Fallback)  |  |
|  +----------------+----------------+  +--------------+-------------+  |
+-------------------|----------------------------------|----------------+
                    +----------------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------+
|                   Android OS Subsystems (AOSP)                        |
|  • ISensorPrivacyManager (Direct Binder Transaction & Read-back)      |
|  • SystemUI Quick Settings (`ACTIVE_TILE` TileService)                |
|  • Hardware Sensor HAL (Camera, Microphone, Gyro, Accelerometer)      |
+-----------------------------------------------------------------------+
```

---

## Key Capabilities

- **Fast Quick Settings Integration**:
  - Implements `ACTIVE_TILE` metadata to ensure the Quick Settings tile operates with minimal latency in Android `SystemUI`.
  - Event-driven state synchronization with authoritative asynchronous read-back from `ISensorPrivacyManager`.
  - Supports configurable tile operation modes (Global Sensors Off, Selective Camera + Microphone privacy, or Individual toggles).

- **Privileged Execution & Authoritative Verification**:
  - **Shizuku API Integration**: Executes elevated sensor privacy transactions via Shizuku's privileged AIDL Binder IPC.
  - **Direct Root (su)**: Fallback compound process batching for hardware state switching.
  - **Authoritative Read-Back Verification**: Every toggle operation performs an asynchronous read-back from the Android sensor privacy service before reporting success.
  - **Zero-Daemon Architecture**: No background services, daemons, polling loops, or battery-draining listeners.

- **Real-Time Sensor Telemetry & Diagnostics**:
  - Live hardware monitoring for Accelerometer, Gyroscope, Magnetometer, Proximity, Ambient Light, Camera, and Audio subsystems to visually verify privacy isolation.

- **Enterprise Reliability**:
  - Immediate state refresh on user unlocking and tile interaction to survive OEM power management cycles.

---

## Attribution & Acknowledgments

This project is built upon foundational concepts and designs established by the open-source community.

We gratefully acknowledge and credit:

* **Original Creator & Author**: [LinerSRT](https://github.com/LinerSRT)
* **Upstream Project Repository**: [https://github.com/LinerSRT/SensorsOff](https://github.com/LinerSRT/SensorsOff)

Their initial work on system sensor privacy controls on Android provided the baseline architecture and inspiration for this implementation.

---

## Documentation & Changelog

- **[CHANGELOG.md](CHANGELOG.md)**: Full release notes, problem analyses, root causes, and verification metrics following Keep a Changelog format.
- **[CONVENTIONAL_COMMITS.md](CONVENTIONAL_COMMITS.md)**: Complete ledger of standardized Conventional Commit messages for all releases, formatted for git commit workflows.
- **[PROBLEM_ANALYSIS_ROOT_CAUSE.md](PROBLEM_ANALYSIS_ROOT_CAUSE.md)**: Comprehensive repository of all deep technical problem analyses, operating system root causes, and engineered resolutions.

---

## Tile Resilience

SensorsOff implements a robust, on-demand Quick Settings tile architecture designed to work in harmony with Android's process lifecycle:

- **Zero Permanent Background Process**: SensorsOff does not run a foreground service, background daemon, keep-alive loop, or persistent wake lock. 
- **SystemUI Lifecycle Management**: Android `SystemUI` owns the lifecycle of the Quick Settings tile, instantiating `SensorsOffTileService` on demand when the shade is opened or when the tile is tapped.
- **On-Demand Dependency Reconstruction**: When Android recreates `SensorsOffTileService` after process death, memory reclamation, or Doze, the service reconstructs its runtime dependencies and visual caches cleanly without assuming persistent state from previous instances.
- **On-Demand Shizuku & Root Connection**: The Shizuku IPC binder and superuser paths are queried and acquired on demand. If the Shizuku binder dies or restarts, the tile recovers on its next invocation cycle.
- **Authoritative State Refresh**: Authoritative global sensor privacy state is asynchronously refreshed via direct Binder IPC upon lifecycle reactivation (`onStartListening()` / `onClick()`), ensuring the tile always reflects real hardware state.
- **No Foreground Service Required**: The app operates with 0% idle battery consumption, requiring no persistent notifications or battery-saver exemptions.
- **OEM & Battery Saver Boundaries**: While SensorsOff maximizes recovery within the standard Android `TileService` lifecycle, third-party apps cannot override aggressive vendor-specific battery killers or OEM-level restrictions that prohibit tile service instantiation.

---

### Option 1: Shizuku (Recommended)
1. Install and launch **Shizuku** on the device.
2. Pair via Wireless Debugging or start via Root / ADB.
3. Grant permission to **SensorsOff** when prompted in the app.

### Option 2: Direct Root (su)
If your device is rooted (e.g., via Magisk or KernelSU), SensorsOff automatically detects `su` and requests superuser authorization for direct privileged shell execution.

---

## License

Distributed under the Apache License, Version 2.0. See `LICENSE` for more information.
