# RoadPlay

Local video player for Android that puts **your own videos on your car's screen** through
Android Auto. Kotlin, Jetpack Compose, Material 3, Media3 (ExoPlayer + MediaLibraryService),
Room, DataStore.

> **Status:** actively developed — 16 signed releases (v1.0.0 → v2.3.0), tested end-to-end on a
> real head unit (2026 Honda Civic, wired AA 18.1, Android 16 phone). Video on the car screen,
> browsing, the car browser and themes are all confirmed working in the car.

## The idea

Android Auto normally refuses to show video from apps installed outside the Play Store —
Google gates the video UI behind an official listing. RoadPlay is the answer to *"why can't
my car just play my own videos?"*: a self-built player that registers on the head unit,
streams your local library to the car screen while parked, and keeps a full playback
experience — including a browser with YouTube — at your fingertips on the dash.

## On the car screen (Android Auto)

- **Full video library on the head unit**: All Videos, Favourites, Playlists, Recently
  played, and folder browsing, with a **grid/list toggle** (3 thumbnails per row in grid
  mode) and real video thumbnails everywhere.
- **Continue Watching** hero card on Home with resume.
- **Parked-video playback** on the car display: fullscreen with tap-to-reveal controls that
  auto-hide (1–5 s, configurable), ±10 s skip, seek, previous/next, and **VLC-style aspect
  ratio modes** (Best fit, Stretch, 16:9, 4:3, 2.35:1, 1:1).
- **Gear-change-proof**: shifting from drive to reverse (or back) doesn't dump you out of
  your movie or pause it; the screen restores exactly where you were.
- **Mini-player while browsing**: the film keeps playing in a slim bottom bar while you pick
  the next one. Connecting to the car never auto-plays; you choose what starts.
- **Built-in car browser**: URL bar, embedded YouTube with working fullscreen video, back
  navigation, and an on-screen keyboard that types into the page's focused field (the AA host
  never shows the system keyboard; your phone keyboard works too).
- **Five themes**: Navy, Carbon, Slate, Emerald, Wine — picked from the phone app, applied
  across the whole car UI (background, sidebar, cards, hero).
- **Keep video while driving** switch.

## On the phone

- Import videos and folders via the system picker (no storage permission), library, search,
  sort, favourites, playlists.
- Player: seek, ±10 s, next/previous, speed, aspect modes, subtitles (embedded +
  .srt/.vtt/.ass/.ttml), sleep timer, resume, volume/brightness gestures, error recovery.
- Companion settings: car screen text size, car screen theme, control hide timer, browser
  home page, drive-safety switch.

## Folder structure

- `app/src/main/java/com/roadplay/player/` : `data/` (Room, settings, import), `playback/`
  (service, controller), `auto/` (CarPlayerActivity — the whole car-screen UI), `safety/`,
  `ui/` (phone screens, components, theme)
- `app/src/test` unit tests, `app/src/androidTest` device tests
- `.github/workflows/android.yml` — CI: tests, signed release APK, and a GitHub Release on
  every `vX.Y.Z` tag

Key versions: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.10.2, Compose BOM 2024.10.01, Media3 1.5.1,
Room 2.6.1, JDK 17, compileSdk/targetSdk 35, minSdk 26.

## Build locally

1. Install Android Studio (latest stable); it bundles JDK 17 and the SDK manager (install
   "Android SDK Platform 35").
2. File > Open > select this folder; let Gradle sync.
3. Run on a phone (USB debugging on), or `./gradlew testDebugUnitTest assembleDebug`.
4. Signed release: set the `ROADPLAY_KEYSTORE_*` env vars (or GitHub Secrets for CI), then
   `./gradlew assembleRelease`.

## Troubleshooting

- *Not visible in Android Auto:* enable developer mode + "Unknown sources", reconnect; the
  app must be recognized as a media app, and for the video tile the installer record must be
  `com.android.vending` (see Installation).
- *Video tile missing after an update:* re-verify the installer record
  (`adb shell dumpsys package com.roadplay.player | grep installerPackageName`) and
  force-stop the AA host.
- *SDK location not found:* install Platform 35, create `local.properties` with `sdk.dir=...`.
- *Java version errors:* use JDK 17.
- *Video won't play:* try H.264/AAC MP4 — the phone's decoders decide.
- *Notification missing on Android 13+:* allow notifications for the app.

## Privacy

Everything stays on-device: no accounts, no ads, no analytics, and the player itself uses no
network (the optional car browser is the only online surface, and only when you open it).
