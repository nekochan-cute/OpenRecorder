# Open Recorder

[![Android CI](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml/badge.svg)](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml)

Android voice recorder. Recording, transcription, speaker labels, summaries and audio clean-up run on the device;
online APIs are optional.

## Features

| Area | |
| --- | --- |
| Recording | Pause and resume, screen-off recording, 16 or 48 kHz, mono or stereo, built-in / wired / USB / Bluetooth mics, noise suppression, crash recovery |
| Playback | Waveform seeking with marks, ±10 s, 0.5×–2× speed, silence skipping |
| Marks | Notes and photos, jump to a mark |
| Editing | Trim or delete a range, saved as a new recording |
| Library | Folders, search, favourites, trash, import of any format the phone decodes, export |
| Transcription | Live captions and saved audio with [Vosk](https://alphacephei.com/vosk/) or a Whisper-style API; editable, tap to play |
| Speakers | Speaker labels with the Vosk speaker model or an API; rename and fix |
| Summary | Extractive summary with a title; meeting, lecture and interview templates; or an OpenAI-compatible API |
| Audio clean-up | Noise reduction, voice enhancement and loudness normalisation, saved as a new recording |
| Sharing | WAV or M4A, transcript, Markdown summary, SRT or WebVTT, marks |

## Privacy

Data stays in app storage and out of cloud backup. Nothing is uploaded unless you set up an API; API keys are
encrypted with the Android Keystore.

## Speech models

Import a ZIP from the [Vosk model list](https://alphacephei.com/vosk/models) under **Settings → On-device transcription**,
e.g. `vosk-model-small-ja-0.22` (speech) or `vosk-model-spk-0.4` (speakers).

## Download

APKs are in the `debug-apks` artifact of each successful [CI run](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml). Most phones need `arm64-v8a`.

## Building

JDK 17, Android SDK platform 35 and build tools 35.0.0. Android 10 or later.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

## License

[Apache License 2.0](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
