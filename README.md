# Speed Meter (Jetpack Compose)

A GPS speed meter for Android: live speed, max/avg, distance, trip timer,
km/h or mph, a speed-limit alarm, and saved trip history.

## What's inside
- `MainActivity.kt` — the full Compose UI
- `ui/SpeedViewModel.kt` — GPS tracking (fused location), all the math, the timer, persistence
- `data/Trip.kt` — trip model + SharedPreferences storage
- `ui/theme/Theme.kt` — colors and theme
- Gradle config, manifest, launcher icon

## Build the APK (Android Studio — recommended)
1. Install Android Studio (free): https://developer.android.com/studio
2. Open Android Studio > "Open" > select this `SpeedMeter` folder.
3. Wait for Gradle sync. It will auto-generate the Gradle wrapper and download
   dependencies the first time (needs internet).
4. Menu: Build > Build App Bundle(s) / APK(s) > Build APK(s).
5. A popup says "APK(s) generated successfully" — click "locate".
   Your file: app/build/outputs/apk/debug/app-debug.apk

## Install on your phone
- Easiest: plug the phone in by USB with USB debugging on, and click the green
  Run button in Android Studio — it installs and launches directly.
- Or copy app-debug.apk to the phone, tap it, allow "install from unknown
  sources" when asked, install, open.

When the app starts, tap Start / Grant Location Permissions. For real speed
readings, use it outdoors with GPS on.

## Notes
- minSdk 24 -> Android 7.0 and up.
- Package id is com.example.speedmeter (fine for personal/sideload; change it
  in app/build.gradle.kts if you ever publish).
