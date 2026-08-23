<img src="./logooriginal.jpg" width="150" height="auto" alt="GooglePhotosMod logo" />

<p align="left">
  <a href="https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FGooglePhotosMod">
    <img src="https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FGooglePhotosMod&label=repo%20views&countColor=%230e75b6&style=flat" alt="Repo Views" />
  </a>
  &nbsp;
  <a href="https://github.com/pbzin/GooglePhotosMod/releases">
    <img src="https://img.shields.io/github/downloads/pbzin/GooglePhotosMod/total?style=flat&color=0e75b6&label=downloads" alt="Downloads" />
  </a>
</p>

# GooglePhotosMod

Mods and enhancements for the Google Photos app via LSPosed. This module adds useful features and technical fixes to improve the media management experience.

## Features (Hooks)

### 🎥 Real Filename Display
Displays the original video filename (e.g., `video_01.mp4`, `vacation.mkv`) directly on the main Google Photos grid.
*   **How it works**: Hooks into `PhotoCellView.draw` and dynamically resolves the media object title, allowing for quick file identification without opening details.

### ⏳ Backup Optimization (Smart Hold)
Prevents the system from prematurely terminating Google Photos backup tasks while data transfer is still active.
*   **How it works**: Monitors network traffic for the app's UID and delays the `jobFinished` call in specific backup services if an upload is still in progress.

## Requirements

*   Android 8.0 (Oreo) or higher.
*   **LSPosed** environment configured and active.
*   Google Photos installed (`com.google.android.apps.photos`).

## Installation

1.  Download the latest APK from the [Releases](https://github.com/pbzin/GooglePhotosMod/releases) tab.
2.  Install the module and enable it in the LSPosed manager, selecting Google Photos as the scope.
3.  Restart Google Photos (Force Stop) for changes to take effect.
4.  Access the module settings to enable or disable the available mods.

## Development and Build

The project uses **Android Gradle Plugin 9.3.1** with native Kotlin support and **Gradle 9.5.0**.

```bash
./gradlew app:assembleDebug
```

The final APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`.

---
*Note: This project is an Xposed module and has no official affiliation with Google.*
