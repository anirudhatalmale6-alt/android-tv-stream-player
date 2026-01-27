# Stream Player — Android TV Streaming Player

A lightweight, rock-solid streaming player built for Android TV and Android-based set-top boxes (STBs). Power on the device, and the selected stream plays automatically — no user intervention required.

## Features

- **Protocol Support**: SRT, RTMP, RTMPS, HLS (HTTP/HTTPS), and progressive HTTP streams
- **Auto-Start on Boot**: Launches automatically when the device powers on
- **Auto-Play**: Begins playback immediately on launch
- **Automatic Reconnection**: Exponential backoff reconnection (2s → 4s → 8s → … → 30s max) on stream drops
- **24/7 Continuous Playback**: Foreground service + partial wake lock prevents the OS from killing playback
- **Minimal UI**: Full-screen playback with a discreet settings overlay for URL configuration
- **D-pad Navigation**: Fully navigable with Android TV remote / D-pad

## Quick Start

### Install the APK

```bash
adb install StreamPlayer-release.apk
```

### Configure the Stream URL

1. Launch the app (or let it auto-launch on boot)
2. Press **OK/Enter** or **Menu** on the remote to open Settings
3. Enter your stream URL (e.g., `rtmp://server/live/stream` or `https://example.com/stream.m3u8`)
4. Press **Save & Play** — the app returns to full-screen playback

### Supported URL Formats

| Protocol | Example URL |
|----------|-------------|
| RTMP | `rtmp://server:1935/live/stream` |
| RTMPS | `rtmps://server:443/live/stream` |
| HLS | `https://server/path/stream.m3u8` |
| HTTP/HTTPS | `https://server/path/stream.ts` |
| SRT | `srt://server:9000` |
| SRT (with options) | `srt://server:9000?streamid=mystream&latency=200` |

## Build from Source

### Prerequisites

- Android Studio Hedgehog (2023.1+) or later
- JDK 17
- Android SDK with API 34

### Steps

1. Clone the repository
2. Open the `StreamPlayer` folder in Android Studio
3. Sync Gradle (automatic on open)
4. Build → Make Project or run `./gradlew assembleDebug`

### Command-line Build

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Signed release APK
```

Output APKs:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## Key Libraries

| Library | Version | Purpose |
|---------|---------|---------|
| [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer) | 1.2.1 | Core playback engine |
| Media3 HLS | 1.2.1 | HLS stream support |
| Media3 RTMP DataSource | 1.2.1 | RTMP/RTMPS stream support |
| Media3 UI | 1.2.1 | Player view component |
| [srtdroid](https://github.com/ThibaultBee/srtdroid) | 1.8.0 | Native SRT protocol (Haivision libsrt) |
| AndroidX Leanback | 1.0.0 | Android TV launcher integration |

## Architecture

```
com.streamplayer.tv/
├── PlaybackActivity.kt   — Full-screen playback, ExoPlayer setup, reconnection logic
├── SettingsActivity.kt   — Stream URL input, auto-play toggle
├── PlaybackService.kt    — Foreground service + wake lock for 24/7 operation
├── SrtDataSource.kt      — Custom Media3 DataSource for SRT protocol via libsrt
├── BootReceiver.kt       — BOOT_COMPLETED receiver for auto-launch
└── StreamPrefs.kt        — SharedPreferences wrapper for URL storage
```

## Changing the Default Stream URL

The stream URL is stored in SharedPreferences and configured via the in-app settings screen. To change it:

1. **Via the app**: Press OK/Enter → change the URL → Save & Play
2. **Via ADB** (for remote deployment):
   ```bash
   adb shell am start -n com.streamplayer.tv/.SettingsActivity
   ```

## SRT Support

SRT is natively supported via [srtdroid](https://github.com/ThibaultBee/srtdroid), which bundles pre-built Haivision `libsrt` binaries for all Android ABIs (armeabi-v7a, arm64-v8a, x86, x86_64).

**SRT URL format:**
```
srt://host:port[?param=value&...]
```

**Supported URL parameters:**
| Parameter | Description | Example |
|-----------|-------------|---------|
| `streamid` | SRT stream ID | `streamid=mystream` |
| `latency` | Latency in ms (default: 200) | `latency=500` |
| `passphrase` | Encryption passphrase | `passphrase=secret` |
| `mode` | Connection mode (caller) | `mode=caller` |
| `transtype` | Transport type (live) | `transtype=live` |

**Testing with FFmpeg:**
```bash
# Start an SRT listener on your machine:
ffmpeg -re -i input.mp4 -c copy -f mpegts "srt://0.0.0.0:9000?mode=listener"

# Then enter in the app: srt://<your-ip>:9000
```

## Signing

The release build uses a bundled keystore (`streamplayer-release.keystore`). For production deployment, replace it with your own keystore:

1. Generate your keystore: `keytool -genkeypair -v -keystore my-release.keystore -alias myalias -keyalg RSA -keysize 2048 -validity 10000`
2. Update `app/build.gradle.kts` signing config with your keystore path, alias, and passwords

## License

Proprietary — built for client use.
