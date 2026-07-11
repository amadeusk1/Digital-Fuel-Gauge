# FuelCheck

Android app that estimates remaining fuel and range from your odometer, consumption, and tank capacity.

**Download:** [FuelCheck 1.0 APK](https://github.com/amadeusk1/FuelCheck/releases/tag/v1.0)

## Features

- **Fuel gauge** — remaining liters, percent, and estimated range
- **Add fuel** — log a partial fill or a full tank
- **Fuel log** — history of fills with date, liters, and odometer
- **GPS trip tracking** — record distance in the background, then add it to the odometer
- **Discard confirmation** — choose to apply or discard an active GPS trip
- **GasBuddy** — quick shortcut to find nearby stations

## Requirements

- Android 7.0+ (API 24)
- Location permission for GPS tracking
- Notification permission on Android 13+ (for the trip tracking notification)

## Install

1. Open the [latest release](https://github.com/amadeusk1/FuelCheck/releases/latest)
2. Download `FuelCheck-1.0.apk`
3. Install on your phone (allow install from unknown sources if asked)

## Build

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and run on a device or emulator.

Release APK:

```bash
./gradlew assembleRelease
```

## How to use

1. Set **average consumption** (L/100 km) and **tank capacity**
2. Log a fuel update (partial or full tank) so the app has a starting point
3. Enter your **current odometer** and tap **Calculate** to refresh remaining fuel and range
4. Optionally tap the GPS icon to track a trip; stop when done to add kilometres (or discard)

## Tech

- Kotlin, Material Components, View Binding
- Google Play Services Location
- Foreground service for background GPS trips
- Local storage via SharedPreferences

## License

Personal project by [amadeusk1](https://github.com/amadeusk1).
