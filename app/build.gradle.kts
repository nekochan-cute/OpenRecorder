import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// sherpa-onnx is published as an AAR by its author on Hugging Face, not on Maven Central.
// It is downloaded once and verified against a pinned SHA-256 before use.
val sherpaVersion = "1.12.21"
val sherpaSha256 = "6d1bf9a2ede218d9f33115f494058b83ae5c2af8a8275e64353c3de8c83a2603"
val sherpaAar = layout.buildDirectory.file("deps/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val downloadSherpa by tasks.registering {
    val target = sherpaAar
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (file.isFile && sha(file) == sherpaSha256) return@doLast
        file.parentFile.mkdirs()
        val temp = File(file.path + ".part")
        URI("https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
            .toURL().openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
        check(sha(temp) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
        check(temp.renameTo(file))
    }
}
android {
    namespace = "org.openrecorder.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.openrecorder.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device speech: VAD, recognition (ReazonSpeech / SenseVoice) and speaker embeddings (Apache-2.0).
    implementation(files(sherpaAar).builtBy(downloadSherpa))
    // On-device LLM for summaries (Apache-2.0).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // Screen previews rendered on the JVM (ScreenshotTest); run with -Pscreenshots.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("screenshots")) systemProperty("screenshots.dir", rootProject.file("build/screenshots").path)
}
