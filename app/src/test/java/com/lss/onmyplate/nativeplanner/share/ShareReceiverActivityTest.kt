package com.lss.onmyplate.nativeplanner.share

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lss.onmyplate.nativeplanner.BuildConfig
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ShareReceiverActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearSession() {
        context.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun textShareWithoutSessionDoesNotOpenMainActivity() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "내일 3시 회의")
        }

        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent)
            .create()
            .get()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun unsupportedShareIntentDoesNotOpenMainActivity() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "내일 3시 회의")
        }

        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent)
            .create()
            .get()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
