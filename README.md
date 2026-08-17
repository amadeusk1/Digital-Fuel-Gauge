# Digital Fuel Gauge

Android app that estimates remaining fuel and range from your odometer, consumption, and tank capacity.

**Download:** [Latest release](https://github.com/amadeusk1/FuelCheck/releases/latest)

## Features

- **Fuel gauge** — remaining liters, percent, and estimated range
- **Add fuel** — log a partial fill or a full tank
- **Fuel log** — history of fills with date, liters, and odometer
- **GPS trip tracking** — record distance in the background, then add it to the odometer
- **Discard confirmation** — choose to apply or discard an active GPS trip
- **Multi-car** — set up and switch between vehicles
- **GasBuddy** — quick shortcut to find nearby stations

## Requirements

- Android 7.0+ (API 24)
- Location permission for GPS tracking
- Notification permission on Android 13+ (for the trip tracking notification)

## Install

1. Open the [latest release](https://github.com/amadeusk1/FuelCheck/releases/latest)
2. Download the APK
3. Install on your phone (allow install from unknown sources if asked)

> Note: the app ID is now `com.digitalfuelgauge`. Installing this build next to an older FuelCheck install will appear as a separate app.

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

1. On first launch, set up your car (name, consumption, tank capacity, odometer)
2. Enter your **current odometer** and tap **Calculate** to refresh remaining fuel and range
3. Log fuel updates as you fill up
4. Optionally tap the GPS icon to track a trip; stop when done to add kilometres (or discard)

## Tech

- Kotlin, Material Components, View Binding
- Package / application ID: `com.digitalfuelgauge`
- Google Play Services Location
- Foreground service for background GPS trips
- Local storage via SharedPreferences

## License

Personal project by [amadeusk1](https://github.com/amadeusk1).
