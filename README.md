# 🎙️ Smart Mic — Android Application

**Author:** Ahmed El-aref  
**All rights reserved Ahmed El-aref © 2025**

---

## Overview

Smart Mic is a professional-grade Android audio application that lets you:

- Capture audio from **multiple input sources** (built-in mic, Bluetooth headset, wired AUX, USB mic)
- Play audio in **real time** through any output device with minimum latency
- **Record to MP3** using the industry-standard LAME encoder (via FFmpegKit)
- **Playback** and manage your recordings directly in the app

---

## Features

| Feature | Details |
|---|---|
| Audio Sources | Built-in mic, Bluetooth SCO, Wired headset, USB microphone |
| Audio Output | Phone speaker, Earpiece, Bluetooth headset, Wired headset |
| Live Monitoring | `AudioRecord` → `AudioTrack`, `VOICE_COMMUNICATION` mode |
| Recording Format | **MP3** (128 kbps, via libmp3lame through FFmpegKit) |
| Enhancements | Noise Suppression, Auto Gain Control, Push-to-Talk |
| Level Meter | Animated gradient VU meter with dB display |
| Error Handling | WAV fallback if MP3 encoding fails; user-friendly dialogs |
| Min Android | **API 26 (Android 8.0 Oreo)** |

---

## Project Structure

```
SmartMic/
├── app/src/main/java/com/ahmedalaref/smartmic/
│   ├── MainActivity.kt          ← UI + permission management
│   ├── AudioEngine.kt           ← AudioRecord + AudioTrack streaming
│   ├── BluetoothAudioHandler.kt ← Bluetooth SCO lifecycle
│   ├── Mp3Recorder.kt           ← PCM capture + MP3 encoding
│   ├── RecordingsAdapter.kt     ← RecyclerView for recordings
│   └── AudioLevelView.kt        ← Custom animated VU meter View
├── app/src/main/res/
│   ├── layout/activity_main.xml
│   ├── layout/item_recording.xml
│   ├── values/colors.xml
│   ├── values/strings.xml
│   └── values/themes.xml
```

---

## Setup in Android Studio

### 1 · Open the project
```
File → Open → select the SmartMic/ folder
```

### 2 · Sync Gradle
Android Studio will auto-sync. If it fails, check your internet
connection — the FFmpegKit `.aar` is ~30 MB and is downloaded from
Maven Central on first build.

### 3 · Build & Run
```
Build → Make Project   (Ctrl+F9)
Run   → Run 'app'      (Shift+F10)
```

---

## Key Dependencies

```groovy
// Real-time audio streaming
// (built into Android SDK – no additional dependency)

// MP3 encoding — audio package includes libmp3lame
implementation 'com.arthenica:ffmpeg-kit-android-audio:6.0-2'

// UI
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.recyclerview:recyclerview:1.3.2'
```

> **Note on FFmpegKit version:** If `6.0-2` is not resolved by Gradle,
> check the latest release at https://github.com/arthenica/ffmpeg-kit
> and update the version string in `app/build.gradle`.

---

## Permissions

| Permission | When needed |
|---|---|
| `RECORD_AUDIO` | Always — for microphone capture |
| `BLUETOOTH_CONNECT` | Android 12+ — for Bluetooth headset access |
| `BLUETOOTH` | Android ≤ 11 — legacy Bluetooth |
| `MODIFY_AUDIO_SETTINGS` | Always — for audio mode / routing |
| `WRITE_EXTERNAL_STORAGE` | Android ≤ 9 — saving recordings |

---

## Recording Storage Location

Recordings are saved to:
```
/Android/data/com.ahmedalaref.smartmic/files/SmartMic/Recordings/
```

You can access them via a file manager app or Android Debug Bridge:
```bash
adb pull /sdcard/Android/data/com.ahmedalaref.smartmic/files/SmartMic/Recordings/
```

---

## Architecture Notes

### Low-latency Streaming
- Uses `AudioRecord.PERFORMANCE_MODE_LOW_LATENCY` where supported
- Buffer size = 2 × `AudioRecord.getMinBufferSize()` for stability
- `AudioTrack` in `MODE_STREAM` for real-time push
- `VOICE_COMMUNICATION` audio mode to suppress echo

### MP3 Encoding Flow
```
AudioRecord (PCM) ──▶ temp .pcm file
                             │
                    FFmpegKit (libmp3lame)
                             │
                       SmartMic_DATE.mp3
```

### Bluetooth SCO
1. `audioManager.startBluetoothSco()` initiates the SCO link
2. `ACTION_SCO_AUDIO_STATE_UPDATED` broadcast confirms connection
3. `MediaRecorder.AudioSource.VOICE_COMMUNICATION` is used for capture
4. 6-second timeout guard prevents hanging on failed connections

---

## Building a Release APK

```
Build → Generate Signed Bundle / APK → APK
```

Ensure `proguard-rules.pro` contains:
```
-keep class com.arthenica.ffmpegkit.** { *; }
```
(already included in the project)

---

## All rights reserved Ahmed El-aref © 2025
