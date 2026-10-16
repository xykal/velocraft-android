# ⚡ VeloCraft Direct (Android)

> **Direct-to-Game, Ultra-Lightweight Minecraft: Java Edition (1.20.4+ Fabric) Engine & Pro PvP HUD for Android**  
> *Tanpa Launcher Bloat • Lock-Free Native C++20 Input Bridge (<0.5ms) • Pre-Tuned Sodium/Lithium/FerriteCore Stack • Full Custom PvP Controls & Gyro*

---

## 🎯 Mengapa VeloCraft Dibuat? (Masalah yang Diselesaikan)

Kebanyakan port Minecraft Java di Android saat ini berbentuk **Launcher serba-guna** (seperti PojavLauncher / FCL) yang mengharuskan pengguna mengatur versi, memasang Fabric manual, mencari mod optimasi satu per satu, dan menggunakan emulasi kursor mouse yang kaku saat bermain.

**VeloCraft Direct** mengambil pendekatan berbeda yang jauh lebih diminati pemain kompetitif & kasual:
1. **Tanpa Launcher (*Direct-to-Game*):** Klik ikon APK langsung masuk ke layar permainan (`GameActivity`). Konfigurasi runtime dilakukan otomatis di balik layar hanya pada saat pertama kali dibuka.
2. **Optimasi Super Ringan (*Out-of-the-Box*):** Dikunci dan dioptimalkan khusus untuk **Minecraft 1.20.4 + Fabric Loader** dengan paket mod performa terbaik (**Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling, ModernFix**) serta *auto-tuning* JVM G1GC + *CPU Big/Prime Core Pinning*.
3. **Kontrol Penuh Pro PvP (*Zero-Latency HUD*):**
   - **Lock-Free SPSC C++20 Input Queue (`lwjgl_input_bridge.h`):** Menyalurkan input layar sentuh 10 jari & sensor *Gyroscope* langsung ke thread render LWJGL/GLFW dengan latensi **< 0.5 milidetik**.
   - **Smart GUI Detection (`FabricStateBridge`):** Mod pendamping (`:fabric-bridge-mod`) di dalam Minecraft memberi tahu APK Android secara *real-time* (via UDP `127.0.0.1:28465`) saat pemain membuka *Inventory/Chest*, sehingga layar otomatis beralih dari **Mode Crosshair PvP** ke **Mode Sentuh Langsung (GUI Cursor)** tanpa perlu memencet tombol "Toggle Mouse" manual!
   - **In-Game HUD Layout Editor:** Geser, ubah ukuran, atur transparansi tombol, *Sprint-Lock*, *Sneak-Toggle*, *Direct 9-Slot Hotbar*, dan penghitung **CPS (Clicks Per Second)** langsung di dalam game.

---

## 🏗️ Arsitektur Sistem

```text
+-----------------------------------------------------------------------------------+
|                            VELOCRAFT ANDROID APK (:app)                           |
|                                                                                   |
|  +-----------------------------+       +---------------------------------------+  |
|  |   ProHudOverlayView.kt      |       |       FabricStateBridge.kt            |  |
|  | - 10-Finger Multi-Touch     |<------| - Local UDP Listener (127.0.0.1:28465)|  |
|  | - Left/Right CPS Counter    |       | - Auto Crosshair <-> GUI Mode Switch  |  |
|  | - Direct 9-Slot Hotbar      |       +---------------------------------------+  |
|  | - In-Game Drag Layout Edit  |                           ^                      |
|  +--------------+--------------+                           | (0.2ms Local Packet) |
|                 |                                          |                      |
|  +--------------v--------------+       +-------------------+-------------------+  |
|  |   velocraft_jni.cpp (C++20) |       |  Embedded OpenJDK 21 ARM64 + Fabric   |  |
|  | - Lock-Free SPSC RingBuffer |------>|  - Minecraft 1.20.4                   |  |
|  | - Big/Prime Core Affinity   |       |  - :fabric-bridge-mod (Mixin Hook)    |  |
|  | - ANativeWindow -> GLES 3.2 |       |  - Sodium + Lithium + FerriteCore     |  |
|  +-----------------------------+       +---------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

---

## 📂 Struktur Repository

```text
velocraft-android/
├── .github/workflows/
│   └── build-apk.yml                # CI/CD GitHub Actions otomatis build Mod + NDK APK
├── app/                             # Modul Utama Aplikasi Android (Kotlin + C++20 NDK)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml      # Direct-Boot GameActivity (Tanpa Activity Launcher)
│       ├── assets/config/
│       │   └── engine-profile.json  # Konfigurasi Modrinth Stack & Pre-tuned Sodium/Options
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   ├── lwjgl_input_bridge.h # Lock-Free SPSC Ring Buffer & Tabel Keycode GLFW
│       │   └── velocraft_jni.cpp    # JLI_Launch JVM Loader, Core Pinning, & JNI Bridge
│       └── java/io/velocraft/android/
│           ├── GameActivity.kt      # Entrypoint Utama Langsung ke Game + 120Hz Mode
│           ├── bridge/
│           │   ├── NativeEngineBridge.kt
│           │   └── FabricStateBridge.kt
│           ├── controls/
│           │   ├── ControlProfile.kt    # Layout Default Pro PvP & JSON Serializer
│           │   ├── GyroAimController.kt # Sensor Gyroscope 200Hz+ untuk Aiming Presisi
│           │   └── ProHudOverlayView.kt # Custom View Multi-Touch + CPS + HUD Editor
│           └── engine/
│               ├── DirectBootProvisioner.kt # Auto-setup DMCA-safe saat pertama dibuka
│               └── JvmOptimizer.kt          # Deteksi RAM/SoC & Tuning G1GC ARM64
├── fabric-bridge-mod/               # Companion Mod Fabric di dalam Minecraft Java
│   ├── build.gradle.kts
│   └── src/main/
│       ├── resources/
│       │   ├── fabric.mod.json
│       │   └── velocraft.mixins.json
│       └── java/io/velocraft/mod/
│           ├── VeloCraftBridgeMod.java
│           └── mixin/MinecraftClientMixin.java
└── docs/
    └── hud-preview.html             # Simulator Interaktif Pro PvP HUD & Layout Editor
```

---

## 🛡️ Keamanan Lisensi & Anti-DMCA untuk Open-Source GitHub

Repository ini dirancang **100% legal dan aman dari takedown DMCA Mojang/Microsoft**:
- Repository **tidak menyimpan** file `client.jar` atau aset hak cipta milik Mojang.
- Saat pengguna membuka aplikasi pertama kali, `DirectBootProvisioner.kt` mengunduh *libraries* resmi dari CDN Mojang (`piston-meta.mojang.com`), Fabric (`meta.fabricmc.net`), dan paket optimasi *open-source* dari Modrinth (`api.modrinth.com`).

---

## 🚀 Cara Build & Pengembangan Selanjutnya

1. **Via GitHub Actions (Otomatis di Cloud):**
   Setiap *push* ke branch `main` atau `develop` akan memicu `.github/workflows/build-apk.yml` yang meng-compile `:fabric-bridge-mod`, menyalinnya ke `assets/mods/`, meng-compile C++ NDK (`arm64-v8a`), dan menghasilkan file APK di tab **Actions -> Artifacts**.
2. **Via Lokal (Android Studio / Command Line):**
   ```bash
   ./gradlew :fabric-bridge-mod:build
   ./gradlew :app:assembleDebug
   ```

---

## 📜 Lisensi
Didistribusikan di bawah lisensi **GNU General Public License v3.0 (GPL-3.0)**.
