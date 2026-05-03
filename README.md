# YTD - YouTube & Spotify Downloader

A lightweight app for downloading music and videos from YouTube and Spotify. Available on **Windows**, **macOS**, and **Android**.

![YTD Screenshot](screenshot.png)

## Features

- **YouTube Downloads** — Video (MP4), Audio (MP3), or Audio (WAV)
- **Spotify Downloads** — Tracks, albums, and playlists (downloaded as MP3 via YouTube search)
- **Album Art & Metadata** — Spotify downloads are tagged with title, artist, album name, and cover art (ID3 tags)
- **YouTube Thumbnail Embedding** — MP3 downloads from YouTube include the video thumbnail
- **Quality Selection** — Video: Best / 1080p / 720p / 480p | Audio: 320 / 192 / 128 kbps
- **Download History** — View and open previously downloaded files
- **Paste from Clipboard** — One-click URL pasting
- **Dark Theme** — Easy on the eyes
- **No Spotify API Key Required** — Uses native web scraping
- **Share Intent Support (Android)** — Share URLs directly from YouTube or Spotify apps

## Download

| Platform | Download |
|----------|----------|
| Windows  | [YTD-Windows.exe](https://github.com/ElmoTuisk/YTD/releases/tag/windows-latest) |
| macOS    | [YTD.dmg](https://github.com/ElmoTuisk/YTD/releases/tag/V1) |
| Android  | [YTD-Android.apk](https://github.com/ElmoTuisk/YTD/releases/tag/android-v1) |

> Head to the [Releases](https://github.com/ElmoTuisk/YTD/releases) page and grab the latest build for your platform.

## Run from Source

### Desktop (Windows / macOS)

**Requirements:** Python 3.11+, FFmpeg

```bash
pip install PyQt6 yt-dlp requests mutagen
python main.py
```

Platform-specific source code is in the `windows/` and `macos/` directories.

### Android

**Requirements:** Android Studio or Android SDK with Java 17+

1. Open the `android/` directory in Android Studio
2. Sync Gradle and run on a device or emulator (API 24+)

Or build from the command line:
```bash
cd android
./gradlew assembleDebug
# APK will be at android/app/build/outputs/apk/debug/app-debug.apk
```

## Build

### Windows

1. Place `ffmpeg.exe` and `ffprobe.exe` in the `windows/` directory
2. Run:
```bash
cd windows
pyinstaller main.spec
```

### macOS

```bash
cd macos
chmod +x build_mac.sh
./build_mac.sh
```

### Android

```bash
cd android
./gradlew assembleRelease
```

## Tech Stack

### Desktop
- **GUI:** PyQt6
- **Downloader:** yt-dlp
- **Audio Processing:** FFmpeg (bundled)
- **Spotify Integration:** Pathfinder GraphQL API (reverse-engineered)
- **Metadata:** mutagen (ID3 tag embedding)

### Android
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM (ViewModel + StateFlow)
- **DI:** Hilt
- **Database:** Room (download history)
- **Preferences:** Jetpack DataStore
- **Downloader:** youtubedl-android (yt-dlp + FFmpeg)
- **Spotify Integration:** Pathfinder GraphQL API (same as desktop)
- **Metadata:** mp3agic (ID3 tag embedding)
- **HTTP:** OkHttp

## Project Structure

```
YTD/
├── windows/          # Windows desktop source (Python/PyQt6)
├── macos/            # macOS desktop source (Python/PyQt6)
├── android/          # Android source (Kotlin/Compose)
│   └── app/src/main/java/com/elmotuisk/ytd/
│       ├── ui/           # Compose screens, theme
│       ├── download/     # Download engine
│       ├── spotify/      # Spotify client
│       ├── data/         # Room DB, DataStore, models
│       ├── service/      # Foreground download service
│       └── di/           # Hilt dependency injection
├── icon.ico
├── screenshot.png
└── requirements.txt
```

## License

This project is provided as-is for personal use.
