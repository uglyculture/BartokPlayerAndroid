# BartokPlayerAndroid

Android app that streams and plays [Bartók Rádió](https://www.mr3-bartok.hu/) (Hungarian classical music radio) with background playback, media notification controls, and automatic reconnection.

## Features

- Streams MP3, OGG Vorbis, OGG Opus, AAC, and HLS audio
- Background playback with media notification (play/pause from lock screen)
- Live program schedule from mediaklikk.hu
- Automatic reconnection and stream failover
- Dark theme UI

## Requirements

- Android Studio (includes JDK 17, Android SDK, Gradle)
- Android 8.0+ (API 26) device or emulator

## Build & Run

Open the project in Android Studio, sync Gradle, and run on a device/emulator.

Or from the command line:

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Stream URLs

Edit `app/src/main/assets/streams.txt` to add or change streams (one URL per line, `#` for comments).
