# WiFiQueue

WiFiQueue is an Android download queue that only downloads over Wi-Fi, runs one
download at a time, and keeps working in the background. Add a direct file URL
on the Home tab, queue it, and the app starts it when Wi-Fi is available,
stopping the active download and re-queuing it when the connection drops.

Built with Kotlin, Jetpack Compose, Room, WorkManager and the system
`DownloadManager`.

## Features

- **Wi-Fi only** — requests are restricted to `NETWORK_WIFI`, so queued
  downloads cannot consume mobile data.
- **Sequential queue** — at most one download is active; the next item starts
  only when no download is active (finished, failed, paused or cancelled).
- **Automatic start on Wi-Fi** — while the app is alive, a connectivity
  callback triggers a queue pass when Wi-Fi returns.
- **Background safety net** — a periodic WorkManager job reconciles records with
  the real `DownloadManager` state when the UI is not open, subject to system
  scheduling.
- **Honest status reporting** — the queue shows progress, real failure reasons
  ("not enough space", "too many redirects", etc.) and what happens next.
- **History and settings** — completed downloads are separated into History;
  Settings is currently UI-only, with toggles that are not persisted or applied.

## How it works

```
Home / Queue UI ──> DownloadViewModel ──> QueueSequencer ──> QueueManager ──> DownloadManager
                              ▲                 │
                              │                 └── Room (download_items)
                              │
Wi-Fi callback ───────────────┘
DownloadReceiver (system broadcast) ──> QueueSequencer
Periodic WorkManager job ─────────────> QueueSequencer
```

- `WifiConnectionMonitor` reports Wi-Fi connectivity via `callbackFlow`.
- `QueueSequencer` serializes every engine operation with a process-wide
  `Mutex`, reconciles active records, and starts the next queued item only when
  the queue is idle.
- `QueueManager` owns all direct `DownloadManager` calls: enqueue, progress
  queries, pause, resume and cancel.
- `DownloadReceiver` receives `ACTION_DOWNLOAD_COMPLETE`, looks the record up by
  job id, and lets the sequencer reconcile success or failure.
- `QueueProcessorWorker` runs a reconciliation pass at WorkManager's 15-minute
  minimum interval (deferrable by the system) and once immediately at app
  startup.
- `DownloadViewModel` ticks every 2.5 seconds while the UI is alive, and
  reconciles only while Wi-Fi is connected.

### Download states

`DownloadStateMachine` defines the legal transitions for
`DownloadStatus`: `WAITING`, `QUEUED`, `DOWNLOADING`, `PAUSED`, `COMPLETED`,
`FAILED`, `CANCELLED`.

Typical flow:

```
WAITING -> QUEUED -> DOWNLOADING -> COMPLETED
                          │  │
                          │  ├── FAILED  -> QUEUED (user retry)
                          │  └── PAUSED  -> QUEUED (user resume)
                          └──── QUEUED   (Wi-Fi lost, restarts automatically)
```

### Pause / resume behaviour

`DownloadManager` has no public API to pause an individual download. Pausing
therefore removes the active request and its partial data, and resuming
enqueues a fresh request that starts from 0 bytes. The UI states this instead of
implying a byte-level resume that does not exist.

### Storage

Files are written to the app-specific external Downloads directory
(`Context.getExternalFilesDir(DIRECTORY_DOWNLOADS)`), so no broad storage
permission is needed. Destination names are de-duplicated (`Movie (1).mp4`) so
two downloads never target the same path.

## Project structure

```
app/src/main/java/com/wifiqueue/app/
├── MainActivity.kt            # Compose scaffold, tabs, notification permission
├── WifiQueueApp.kt            # Application: schedules queue workers
├── data/                      # Room entity, DAO, converters, state machine
├── service/                   # DownloadManager engine, sequencer, workers,
│                              #   receiver, notifications, storage
├── ui/screens/                # Home, Queue, History, Settings
├── ui/theme/                  # Colors, typography, Material 3 theme
└── viewmodel/                 # DownloadViewModel
```

## Requirements

- JDK 17
- Android SDK Platform 34, Build-Tools 34.0.0
- Android Gradle Plugin 8.5.2, which needs Gradle 8.7 or newer
- `minSdk 24` (Android 7.0), `targetSdk 34`

This repository does **not** currently include a Gradle wrapper. Open the
project in Android Studio (which supplies Gradle and the SDK), or generate a
wrapper with an installed Gradle 8.7+:

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and can
be installed with `adb install -r <path>`.

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Download files |
| `ACCESS_NETWORK_STATE` | Detect Wi-Fi connectivity |
| `POST_NOTIFICATIONS` | Show download progress/completion notifications (Android 13+, requested at launch) |
| `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` | Declared in the manifest but never requested at runtime; app-specific external storage needs no broad permission |

If the notification permission is declined, downloads still work; only status
notifications are hidden.

## Known limitations

- Only direct file URLs are supported; HTML pages and authentication flows are
  not resolved.
- Downloads stopped by pause or Wi-Fi loss restart from the beginning (see above); a DownloadManager job survives app process death and keeps running.
- Failed downloads stay in `FAILED` until the user taps Retry.
- The Settings toggles are UI placeholders; they are not persisted or applied.
- There are no automated tests in the repository yet.
