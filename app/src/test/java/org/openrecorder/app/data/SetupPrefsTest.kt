package org.openrecorder.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupPrefsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aNewInstallSeesTheSetupUntilItIsFinished() {
        assertTrue(SetupPrefs(context).needed(used = false))
        assertTrue(SetupPrefs(context).needed(used = false))
        SetupPrefs(context).finish()
        assertFalse(SetupPrefs(context).needed(used = false))
    }

    @Test fun anInstallInUseNeverSeesItEvenAfterEverythingIsDeleted() {
        assertFalse(SetupPrefs(context).needed(used = true))
        assertFalse(SetupPrefs(context).needed(used = false))
    }
}
