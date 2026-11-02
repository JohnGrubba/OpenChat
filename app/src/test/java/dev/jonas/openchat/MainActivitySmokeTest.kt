package dev.jonas.openchat

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenChatApplication::class)
class MainActivitySmokeTest {
    @Test
    fun activityLaunchesWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ApplicationProvider.getApplicationContext<OpenChatApplication>().container)
    }
}
