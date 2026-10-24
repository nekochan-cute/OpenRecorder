package org.openrecorder.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WaveformTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Time labels near the right edge used to get a negative width and crash while playing. */
    @Test fun rulerDrawsAtEveryPlaybackPosition() {
        var position by mutableLongStateOf(0L)
        rule.setContent {
            RecorderTheme(false) {
                ScrollingWaveform(LevelTrack(20 * 60) { 0.5f }, position, 60_000, emptyList(), Modifier.fillMaxWidth().height(200.dp))
            }
        }
        val view = rule.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        for (ms in 0L..60_000L step 50) {
            position = ms
            rule.waitForIdle()
            view.draw(Canvas(bitmap))
        }
    }
}
