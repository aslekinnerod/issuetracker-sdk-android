package no.issuetracker.sdk.internal

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FormFactorTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun phoneIsNotTelevision() {
        assertFalse(FormFactor.isTelevision(context()))
    }

    @Test
    @Config(qualifiers = "television")
    fun televisionUiModeIsTelevision() {
        assertTrue(FormFactor.isTelevision(context()))
    }

    @Test
    fun leanbackFeatureIsTelevision() {
        shadowOf(context().packageManager)
            .setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertTrue(FormFactor.isTelevision(context()))
    }

    @Test
    fun fireTvFeatureIsTelevision() {
        shadowOf(context().packageManager)
            .setSystemFeature("amazon.hardware.fire_tv", true)
        assertTrue(FormFactor.isTelevision(context()))
    }
}
