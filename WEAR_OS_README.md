# Wear OS Watch App for Walkolution Odometer

## Overview

The Walkolution Odometer Android app now includes a Wear OS companion app that displays real-time odometer data on your Pixel Watch or other Wear OS devices.

## Features

The watch screen displays:
- **Current Speed** (large, prominent display)
- **Session Stats** (distance + time)
- **Lifetime Stats** (distance + time)
- **Connection Status** (shows when disconnected from phone app)
- **Metric/Imperial Units** (based on phone app settings)

## Installation

### ⚠️ Important: Distribution Method

**The `wearApp` embedding no longer works in modern AGP versions.** You must install both APKs separately.

### Development/Testing Installation

**Step 1: Build both APKs**
```bash
cd android
./gradlew assembleDebug
```

**Step 2: Install phone app**
```bash
./gradlew :app:installDebug
# Or: adb install app/build/outputs/apk/debug/app-debug.apk
```

**Step 3: Install watch app**

First, find your watch device:
```bash
adb devices
# You'll see something like:
# List of devices attached
# R3CR20...    device          (phone)
# 1C051F...    device          (watch)
```

Then install to the watch:
```bash
# Replace 1C051F... with your watch's serial number
adb -s 1C051F... install wear/build/outputs/apk/debug/wear-debug.apk
```

**Tip:** If you only have one device connected, you can omit the `-s` flag:
```bash
# Disconnect phone, keep only watch connected
adb install wear/build/outputs/apk/debug/wear-debug.apk
```

### Production Distribution (Google Play)

When publishing to Google Play:

1. **Build both APKs:**
   ```bash
   cd android
   ./gradlew assembleRelease
   ```

2. **Upload BOTH APKs separately** to Google Play Console:
   - Phone app: `app/build/outputs/apk/release/app-release.apk`
   - Watch app: `wear/build/outputs/apk/release/wear-release.apk`

3. **Use the same package name** for both (`com.mypeople.walkolutionodometer`)

4. **Use the same Play Store listing** to improve discoverability

5. Each APK updates independently - users get updates for phone/watch separately

### Verifying Installation

- On your Pixel Watch, swipe up from the watch face to open the app drawer
- Scroll to find "Walkolution"
- Tap to launch the watch app

## How It Works

### Data Synchronization

- The phone app sends odometer data to the watch using the **Wearable Data Layer API**
- Data is synced whenever it changes (speed, distance, time updates)
- Updates are marked as "urgent" for immediate delivery
- The watch app shows "Disconnected" if the phone app isn't running

### Architecture

#### Phone App Components

- **WearDataSender.kt** - Sends odometer data to watch
  - Located in: `app/src/main/java/.../WearDataSender.kt`
  - Integrated into `BleService.kt` to send updates automatically

#### Watch App Components

- **WearDataListenerService.kt** - Receives data from phone
  - Listens for data changes on the `/odometer` path
  - Updates shared StateFlow with new data

- **WatchMainActivity.kt** - Main watch screen UI
  - Uses Compose for Wear OS
  - Displays odometer data in a scrollable, watch-optimized layout
  - Supports rotary input for navigation

### Module Structure

```
android/
├── app/              # Phone app (main module)
│   └── build.gradle.kts    # Note: wearApp() is DEPRECATED (AGP 9.0+)
└── wear/             # Watch app module (SEPARATE APK)
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   └── java/.../
    │       ├── WatchMainActivity.kt
    │       ├── WearDataListenerService.kt
    │       └── OdometerData.kt
    └── build.gradle.kts
```

**Architecture Note:** The phone and watch apps are **independent modules** that communicate via the Wearable Data Layer API. They are distributed as **separate APKs** on Google Play, not embedded.

## Building

### Build Phone + Watch APKs

```bash
cd android
./gradlew assembleDebug
```

This builds:
- `app/build/outputs/apk/debug/app-debug.apk` (~12 MB) - Phone app APK
- `wear/build/outputs/apk/debug/wear-debug.apk` (~25 MB) - Watch app APK (separate)

**Note:** For local testing, the phone APK still embeds the watch APK for convenience, but this is deprecated and won't work in AGP 9.0+. For production, always upload both APKs separately to Google Play.

### Build Watch APK Only

```bash
./gradlew :wear:assembleDebug
```

## Development

### Testing the Watch App

1. **With Android Studio:**
   - Run the wear configuration on a Wear OS emulator or physical device
   - Or run the app configuration on your phone (automatically pushes to watch)

2. **With ADB:**
   ```bash
   # Install on phone (automatically installs on paired watch)
   adb -d install app/build/outputs/apk/debug/app-debug.apk

   # Or install directly on watch
   adb -s <watch-device-id> install wear/build/outputs/apk/debug/wear-debug.apk
   ```

### Debugging Data Sync

Check logs for data transmission:

```bash
# Phone app logs
adb logcat -s WearDataSender

# Watch app logs
adb -s <watch-device-id> logcat -s WearDataListener
```

## Customization

### Changing Watch UI

Edit [android/wear/src/main/java/com/mypeople/walkolutionodometer/WatchMainActivity.kt](android/wear/src/main/java/com/mypeople/walkolutionodometer/WatchMainActivity.kt) to:
- Adjust font sizes
- Change layout/colors
- Add/remove data fields
- Modify scrolling behavior

### Changing Data Sync

Edit [android/app/src/main/java/com/mypeople/walkolutionodometer/WearDataSender.kt](android/app/src/main/java/com/mypeople/walkolutionodometer/WearDataSender.kt) to:
- Send additional data fields
- Change sync frequency
- Modify data path

## Troubleshooting

### Watch App Doesn't Install

1. Ensure your watch is paired with your phone via the Wear OS app
2. Check that both devices have a stable connection
3. Try reinstalling the phone app
4. Wait a few minutes - installation can be delayed

### No Data on Watch

1. Open the phone app to ensure BLE service is running
2. Check that the phone app is connected to the Pico device
3. Verify in logs that data is being sent:
   ```bash
   adb logcat -s WearDataSender
   ```

### Watch Shows "Disconnected"

This is normal when:
- Phone app is not running
- Phone BLE service is stopped
- Phone and watch are out of Bluetooth range

## Technical Details

### Wear OS Versions

- **Minimum SDK:** 30 (Wear OS 3.0+)
- **Target SDK:** 35
- **Pixel Watch Compatibility:** Full support for all Pixel Watch models

### Dependencies

- `androidx.wear.compose:compose-material` - Wear OS UI components
- `androidx.wear.compose:compose-foundation` - Wear OS foundation
- `com.google.android.gms:play-services-wearable` - Data Layer API
- `com.google.android.horologist` - Enhanced Wear OS UX components

### Data Format

Data sent to watch (via DataMap):
```kotlin
{
  "currentSpeed": Float,        // Current speed (mph or km/h)
  "sessionDistance": Float,     // Session distance (miles or km)
  "sessionTime": String,        // Session time ("H:MM:SS")
  "lifetimeDistance": Float,    // Lifetime distance
  "lifetimeTime": String,       // Lifetime time
  "metric": Boolean,            // true = km/km/h, false = mi/mph
  "isConnected": Boolean,       // Phone app connection status
  "timestamp": Long             // Update timestamp
}
```

## Future Enhancements

Possible improvements:
- Watch complications (show speed on watch face)
- Watch tiles (quick glance widget)
- Ambient mode optimizations (always-on display)
- Haptic feedback for milestones
- Voice commands to start/stop tracking
