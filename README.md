# Open Recorder

[![Android CI](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml/badge.svg)](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml)

A voice recorder for Android that transcribes, separates speakers and summarizes on the device.
AI services are optional: connect OpenRouter, Google AI Studio, OpenAI, Groq or any OpenAI-compatible API when you want them.

<p>
<img src="docs/screenshots/library-light.png" width="180" alt="Recordings">
<img src="docs/screenshots/recording-light.png" width="180" alt="Recording">
<img src="docs/screenshots/detail-audio-light.png" width="180" alt="Playback and marks">
<img src="docs/screenshots/detail-transcript-dark.png" width="180" alt="Transcript with speakers">
</p>

## Features

| Area | |
| --- | --- |
| Recording | One tap to record, pause and resume, keeps recording with the screen off, 16 or 48 kHz, mono or stereo, built-in / wired / USB / Bluetooth microphones, crash-safe WAV with recovery |
| Playback | Scrolling waveform with marks, drag to scrub, ±10 s, 0.5×–3× speed, silence skipping |
| Marks | Flag a moment while recording or playing, add a note or a photo, tap to jump |
| Editing | Keep or remove a selected range; the original is kept and the result is saved as a new recording |
| Library | Folders, favourites, search across titles, transcripts, marks and summaries, trash, import of any format the phone can decode |
| Transcription | Live captions while recording and transcription of saved audio with [Vosk](https://alphacephei.com/vosk/) (offline) or an AI service; edit text, tap a sentence to play from there |
| Speakers | Offline speaker separation with the Vosk speaker model; rename speakers, fix a sentence's speaker |
| Summary | Offline summary with an automatic title and templates for meetings (decisions, action items), lectures (key points) and interviews (questions and answers), or a summary from an AI service |
| Audio clean-up | Noise reduction, voice enhancement and loudness levelling, saved as a new recording |
| Sharing | Audio as M4A or WAV, transcript, SRT or WebVTT captions, summary as Markdown, list of marks |

## Privacy

Recordings, photos and transcripts stay in app storage and are excluded from cloud backup. Nothing is sent
anywhere unless you choose an AI service; API keys are encrypted with the Android Keystore. No ads or analytics.

## Speech models

Download a ZIP from the [Vosk model list](https://alphacephei.com/vosk/models) and add it under
**Settings → Add model**. The app tells speech and speaker models apart by their contents.

- Speech: e.g. `vosk-model-small-ja-0.22` (Japanese) or `vosk-model-small-en-us-0.15` (English)
- Speakers: `vosk-model-spk-0.4`

## AI services

**Settings → AI services** picks a service for summaries and one for transcription. OpenRouter supports
signing in through the browser (OAuth PKCE); other services take an API key, with a button that opens the
provider's key page. Models are listed from the service. Details: [docs/API_CONTRACT.md](docs/API_CONTRACT.md).

## Download

Each successful [CI run](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml) publishes
`debug-apks`. Most phones need the `arm64-v8a` APK.

## Building

JDK 17 and the Android SDK (platform 35, build tools 35.0.0).

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest                                   # device or emulator
./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots  # render screens to build/screenshots
```

Android 10 (API 29) or later.

## Documentation

Japanese: [specification](docs/SPECIFICATION.md), [API contract](docs/API_CONTRACT.md),
[testing and device checklist](docs/VALIDATION.md), [CI](docs/CI.md).

## License

[Apache License 2.0](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
