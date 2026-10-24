# Third-party notices

Open Recorder original code: Apache License 2.0. See LICENSE.

## Libraries in the APK

| Component | Version | License / source |
| --- | --- | --- |
| AndroidX / Jetpack Compose / Media3 | See app/build.gradle.kts and BOM | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ and https://github.com/androidx/media |
| Material icons (subset, in `ui/icons`) | 1.7.8 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / kotlinx.coroutines | 2.4.21 / 1.10.2 | Apache-2.0, https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| sherpa-onnx (static ONNX Runtime build) | 1.12.21 | Apache-2.0, https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (inside sherpa-onnx) | bundled | MIT, https://github.com/microsoft/onnxruntime |
| LiteRT-LM | 0.18.1 | Apache-2.0, https://github.com/google-ai-edge/LiteRT-LM |
| Gson | transitive | Apache-2.0, https://github.com/google/gson |
| jump3r (Java port of LAME) | 1.0.5 | LGPL-2.1-or-later, https://github.com/Sciss/jump3r — full text in `app/src/main/assets/licenses/LGPL-2.1.txt` |
| OkHttp / Okio | 4.12.0 / transitive | Apache-2.0, https://github.com/square/okhttp and https://github.com/square/okio |
| Gradle wrapper | 8.11.1 | Apache-2.0, https://github.com/gradle/gradle |
| JUnit (test only) | 4.13.2 | EPL-1.0, https://github.com/junit-team/junit4 |
| Robolectric (test only) | 4.14.1 | MIT, https://github.com/robolectric/robolectric |
| Hamcrest (test only) | transitive | BSD-3-Clause, https://github.com/hamcrest/JavaHamcrest |

jump3r is used unmodified as a separate library. Its source is available from Maven Central
(`de.sciss:jump3r:1.0.5`, sources JAR) and the project page above.

## Models downloaded on request

No model weights are included in the APK. These are downloaded from Hugging Face when the user asks.

| Model | License | Source |
| --- | --- | --- |
| ReazonSpeech v2 (k2) | Apache-2.0 | https://huggingface.co/reazon-research/reazonspeech-k2-v2 (via csukuangfj/reazonspeech-k2-v2) |
| SenseVoice Small | FunASR Model License | https://github.com/FunAudioLLM/SenseVoice (via csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17) |
| Silero VAD | MIT | https://github.com/snakers4/silero-vad (via csukuangfj/vad) |
| 3D-Speaker CAM++ | Apache-2.0 | https://github.com/modelscope/3D-Speaker (via csukuangfj/speaker-embedding-models) |
| Gemma 4 E2B | Apache-2.0 | https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm |
| TinySwallow 1.5B Instruct | Apache-2.0 | https://huggingface.co/litert-community/TinySwallow-1.5B-Instruct |

The bundled Apache License text applies to components using that license.
Upstream notices included in dependency AAR/JAR resources are preserved.
