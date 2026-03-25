# BartokPlayerAndroid

## Project Overview
Android app that streams and plays Bartók Rádió (Hungarian classical music radio) with background playback, media notification controls, and automatic reconnection.

## Tech Stack
- Kotlin / Jetpack Compose for UI
- Media3 ExoPlayer for audio streaming (MP3, OGG Vorbis, OGG Opus, AAC, HLS)
- Media3 MediaSessionService for background playback with notification
- Material 3 dark theme
- Gradle 8.9 / AGP 8.7.3

## Stream URLs
- Stored in `app/src/main/assets/streams.txt` (one URL per line, `#` for comments)
- Player tries each URL in order; falls back to the next on failure

## Project Structure
- `app/src/main/java/com/bartokplayer/` — Kotlin source
  - `MainActivity.kt` — Compose UI, MediaController connection, schedule display
  - `PlaybackService.kt` — MediaSessionService with ExoPlayer, stream failover
  - `ProgramSchedule.kt` — Fetches program schedule XML from mediaklikk.hu
- `app/src/main/assets/streams.txt` — Stream URLs
- `app/src/main/res/` — Android resources (icons, themes, strings)
- `BartokPlayer/` — Original Windows C#/.NET version (reference only)

## Build
```bash
./gradlew assembleDebug    # Debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease  # Release APK
```
Or open in Android Studio and run directly.

## Notes
- Requires Android SDK with API 35 and JDK 17
- minSdk 26 (Android 8.0+)
- Not self-contained — uses standard Gradle wrapper
