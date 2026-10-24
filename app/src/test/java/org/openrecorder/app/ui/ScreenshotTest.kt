package org.openrecorder.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openrecorder.app.ai.AiConfig
import org.openrecorder.app.ai.ModelCatalog
import org.openrecorder.app.ai.Provider
import org.openrecorder.app.audio.CaptureState
import org.openrecorder.app.core.LocalSummary
import org.openrecorder.app.core.Mark
import org.openrecorder.app.core.Recording
import org.openrecorder.app.core.Segment
import org.openrecorder.app.data.RecordingOptions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** Renders screens with sample data to PNG for review. Runs only with `-Pscreenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = System.getProperty("screenshots.dir")?.let(::File)

    private fun snap(name: String, content: @Composable () -> Unit) {
        assumeTrue(dir != null)
        var theme by mutableStateOf(false)
        rule.setContent { RecorderTheme(theme) { content() } }
        for (dark in listOf(false, true)) {
            theme = dark
            rule.waitForIdle()
            val view = rule.activity.window.decorView.rootView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            dir!!.mkdirs()
            File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val now = System.currentTimeMillis()
    private val segments = listOf(
        Segment(0, 4000, "今日は新製品の発売スケジュールと予算について話します", "話者1"),
        Segment(4200, 8000, "発売スケジュールは来月末を目標にしています", "話者1"),
        Segment(8300, 12000, "予算は先月の見積もりより少し増えそうです", "話者2"),
        Segment(12100, 16000, "それでは発売日は十一月三十日に決定します", "話者1"),
        Segment(16200, 20000, "田中さんは金曜日までに予算の見積もりを作成してください。お願いします", "話者1"),
        Segment(20400, 24000, "わかりました、見積もりを作成して共有します", "話者2"),
        Segment(24200, 28000, "広告の予算は別途検討することにしましょう", "話者3"),
    )
    private val meeting = Recording(id = "a", title = "新製品・予算（会議）", createdAt = now - 3_600_000, fileName = "a.wav", durationMs = 95_000,
        favorite = true, folder = "仕事", segments = segments, summary = LocalSummary.summarize(segments, "会議").body,
        marks = listOf(Mark("m1", 12_000, "発売日の決定"), Mark("m2", 41_000, ""), Mark("m3", 63_500, "広告費は来週")))
    private val library = listOf(meeting,
        Recording(id = "b", title = "録音 10/24 9:12", createdAt = now - 7_200_000, fileName = "b.wav", durationMs = 312_000),
        Recording(id = "c", title = "統計学 第5回", createdAt = now - 86_400_000, fileName = "c.wav", durationMs = 5_412_000, folder = "講義", segments = segments.take(1)),
        Recording(id = "d", title = "インタビュー 佐藤さん", createdAt = now - 4 * 86_400_000L, fileName = "d.wav", durationMs = 1_860_000))
    private val levels = ByteArray(95_000 / 50) { i ->
        val speech = abs(sin(i / 7.0)) * (0.55 + 0.45 * abs(sin(i / 53.0)))
        (if ((i / 40) % 5 == 4) 25 else (60 + 180 * speech).toInt()).toByte()
    }

    @Test fun library() = snap("01-library") { LibraryScreen(library, CaptureState(), LibraryActions()) }
    @Test fun libraryEmpty() = snap("02-library-empty") { LibraryScreen(emptyList(), CaptureState(), LibraryActions()) }
    @Test fun recording() = snap("03-recording") {
        val recent = List(400) { i -> (0.25 + 0.6 * abs(sin(i / 6.0)) * abs(sin(i / 31.0))).toFloat() }
        RecordScreen(CaptureState(id = "a", durationMs = 754_300, levels = recent, input = "内蔵マイク", speaking = true,
            live = segments.take(4)),
            meeting.copy(marks = listOf(Mark("m", 740_000, ""))), RecordActions())
    }
    @Test fun detailAudio() = snap("04-detail-audio") { DetailScreen(DetailState(meeting, levels, 12_400, false, localModel = true), DetailActions()) }
    @Test fun detailTranscript() = snap("05-detail-transcript") { DetailScreen(DetailState(meeting, levels, 9_000, true, localModel = true), DetailActions(), initialTab = 1) }
    @Test fun detailSummary() = snap("06-detail-summary") { DetailScreen(DetailState(meeting, levels, 0, false, aiSummary = true), DetailActions(), initialTab = 2) }
    @Test fun detailNoTranscript() = snap("07-detail-empty-transcript") {
        DetailScreen(DetailState(meeting.copy(segments = emptyList(), summary = ""), levels, 0, false, localModel = true, aiTranscription = true), DetailActions(), initialTab = 1)
    }
    @Test fun editor() = snap("08-editor") { EditorScreen(meeting, levels, 30_000, false, false, EditorActions()) }
    @Test fun settings() = snap("09-settings") {
        SettingsScreen(RecordingOptions(), listOf(1 to "内蔵マイク", 7 to "Pixel Buds（Bluetooth）"), true, "ReazonSpeech v2",
            AiConfig(summaryProvider = Provider.OpenRouter), "0.1.0-dev", SettingsActions())
    }
    @Test fun ai() = snap("10-ai") {
        AiScreen(AiConfig(summaryProvider = Provider.Google, summaryModel = "gemini-2.5-flash", keys = mapOf(Provider.Google to "key"),
            transcriptionProvider = Provider.Groq, transcriptionModel = "whisper-large-v3-turbo"), AiActions())
    }
    @Test fun models() = snap("12-models") {
        val store = listOf(ModelCatalog.reazon to true, ModelCatalog.senseVoice to false, ModelCatalog.vad to true, ModelCatalog.speaker to true,
            ModelCatalog.gemma to false, ModelCatalog.swallow to true)
        ModelsScreen(store.map { (spec, installed) ->
            ModelRow(spec, installed, spec == ModelCatalog.reazon || spec == ModelCatalog.swallow, if (spec == ModelCatalog.gemma) org.openrecorder.app.ai.DownloadState(0.42f) else null)
        }, true, ModelActions())
    }
    @Test fun translated() = snap("13-detail-translated") {
        val english = listOf("Today we'll talk about the launch schedule and budget for the new product", "We are aiming for the end of next month",
            "The budget looks a little higher than last month's estimate", "Then the release date is decided as November 30")
        val r = meeting.copy(segments = segments.mapIndexed { i, s -> s.copy(translation = english.getOrElse(i) { "" }) }, translationLanguage = "en")
        DetailScreen(DetailState(r, levels, 9_000, false, llm = true), DetailActions(), initialTab = 1)
    }
    @Test fun trash() = snap("11-trash") { TrashScreen(library.take(2).map { it.copy(deletedAt = now) }, {}, {}, {}, {}) }
}
