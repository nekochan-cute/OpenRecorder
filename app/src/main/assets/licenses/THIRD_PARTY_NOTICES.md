# Third-party notices

Open Recorder original code: Apache License 2.0. See LICENSE.

Dependencies retain their own notices and licenses. The APK includes native
Vosk and JNA libraries, but no speech model weights. Distribution of a chosen
speech model requires checking that model's license independently.

| Component | Version used | License / source |
| --- | --- | --- |
| AndroidX / Jetpack Compose / Media3 | See app/build.gradle.kts and BOM | Apache-2.0, https://android.googlesource.com/platform/frameworks/support/ and https://github.com/androidx/media |
| Kotlin / kotlinx.coroutines | 2.1.20 / 1.10.2 | Apache-2.0, https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| Vosk Android / Kaldi | 0.3.75 | Apache-2.0, https://github.com/alphacep/vosk-api and https://github.com/kaldi-asr/kaldi |
| JNA | 5.18.1 | Dual Apache-2.0 / LGPL-2.1-or-later; Apache-2.0 is selected. https://github.com/java-native-access/jna |
| OkHttp / Okio | 4.12.0 / transitive | Apache-2.0, https://github.com/square/okhttp and https://github.com/square/okio |
| Gradle wrapper | 8.11.1 | Apache-2.0, https://github.com/gradle/gradle |
| JUnit (test only) | 4.13.2 | EPL-1.0, https://github.com/junit-team/junit4 |
| Robolectric (test only) | 4.14.1 | MIT, https://github.com/robolectric/robolectric |
| Hamcrest (test only) | transitive | BSD-3-Clause, https://github.com/hamcrest/JavaHamcrest |

The bundled Apache License text applies to components using that license.
Upstream notices included in dependency AAR/JAR resources must be preserved.
