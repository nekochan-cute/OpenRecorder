# Open Recorder

[![Android CI](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml/badge.svg)](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml)

An open-source voice recorder for Android, modelled on the feature set of the ColorOS Recorder.
Recording, transcription, speaker separation, summaries and audio clean-up all work **on the device**;
online services are optional and only used when you configure them.

> Open Recorder is an independent implementation based on public descriptions of the ColorOS Recorder.
> It is not affiliated with or endorsed by OPPO, and it contains no OPPO code, assets or internal APIs.

## Features

| Area | What you get |
| --- | --- |
| Recording | Pause/resume, keeps recording with the screen off (foreground service with notification controls), 16 kHz or 48 kHz, mono or stereo, built-in / wired / USB / Bluetooth microphones, optional platform noise suppression, crash-safe WAV writing with recovery on next launch |
| Playback | Waveform with tap/drag seeking and mark ticks, ±10 s, speed 0.5×–2×, silence skipping |
| Marks | Time-stamped marks with notes and photos, edit notes later, jump to a mark |
| Editing | Trim (keep a range) or delete a range, selected with a range slider over the waveform; the original is always kept and the edit is saved as a new recording, with marks and fully kept sentences moved to the new timeline |
| Library | Folders, search across titles, transcripts, marks and summaries, favourites, trash with restore, import of any format the device can decode (WAV, M4A/AAC, MP3, OGG/Opus, FLAC, AMR…), export |
| Transcription | Live captions while recording and transcription of saved audio with [Vosk](https://alphacephei.com/vosk/) (offline), or a Whisper-style HTTPS API; edit sentences, tap a sentence to play from there, the sentence being played is highlighted |
| Speakers | Offline speaker separation with the Vosk speaker model (x-vector clustering), or labels returned by an API; rename speakers in bulk, fix a single sentence's speaker |
| Summary | Offline extractive summary with an automatic title and templates for meetings (decisions, action items), lectures (key points) and interviews (questions with answers); optionally an OpenAI-compatible chat completions API |
| Audio clean-up | Noise reduction (spectral gating, adjustable strength), voice enhancement (rumble filter, presence boost, gentle compression) and loudness normalisation, saved as a new recording |
| Sharing | Audio as WAV or M4A, transcript as text, summary as Markdown, captions as SRT or WebVTT, list of marks |

## Privacy

- Audio, photos, transcripts and settings stay in app-private storage and are excluded from cloud backup.
- Nothing is uploaded unless you configure an API and confirm the action. The `INTERNET` permission exists only
  for these optional APIs; API keys are encrypted with the Android Keystore.
- No ads, analytics or trackers.

## Speech models

Models are not bundled. Download a ZIP from the [Vosk model list](https://alphacephei.com/vosk/models),
check its licence, and import it under **Settings → On-device transcription**. The app detects the model type
from the ZIP contents:

- Speech recognition, e.g. `vosk-model-small-ja-0.22` (Japanese) or `vosk-model-small-en-us-0.15` (English).
- Speaker identification: `vosk-model-spk-0.4`. When it is installed, on-device transcription labels speakers
  (話者1, 話者2, …); rename them from the transcript tab.

## Download

Every successful [GitHub Actions run](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml)
publishes a `debug-apks` artifact. Most current phones need the `arm64-v8a` APK. These are debug-signed
development builds; see [docs/CI.md](docs/CI.md).

## Building

Requirements: JDK 17 and the Android SDK (platform 35, build tools 35.0.0).

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # with a device or emulator attached
```

The Gradle Wrapper JAR is stored as checksummed Base64 text and restored on first run
(`scripts/restore-gradle-wrapper.sh`). Minimum Android version: 10 (API 29). Target: API 35.

## Project layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/org/openrecorder/app/core/` | Platform-independent logic: WAV I/O and editing, timeline mapping, audio processing (DSP), speaker clustering, extractive summary, subtitle export. Covered by JVM unit tests. |
| `app/src/main/java/org/openrecorder/app/audio/` | Recording service, Bluetooth SCO routing, MediaCodec import and M4A export |
| `app/src/main/java/org/openrecorder/app/ai/` | Vosk integration, optional HTTPS APIs, encrypted API settings |
| `app/src/main/java/org/openrecorder/app/data/` | Recording store (atomic JSON index, trash, crash recovery) |
| `docs/` | Specification, API contract, validation status, CI notes and clean-room record (in Japanese) |

## Status

This is a development version. CI builds the APKs, runs the JVM unit tests and Android Lint, and runs
instrumented codec tests on an Android 15 emulator. Long recordings, Bluetooth and USB microphones,
vendor power management and real-world recognition quality still need testing on physical devices;
the checklist is in [docs/VALIDATION.md](docs/VALIDATION.md). The app UI is currently Japanese only.

## License

[Apache License 2.0](LICENSE). Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
