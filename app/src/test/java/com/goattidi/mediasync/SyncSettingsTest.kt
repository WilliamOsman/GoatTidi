package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.repo.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncSettingsTest {

    @Test
    fun `fresh install uses the device-derived default`() {
        assertEquals("GoatTidi_Pixel", SyncSettings.effectiveFolderName(null, hasCachedFolder = false, "GoatTidi_Pixel"))
    }

    @Test
    fun `install that resolved a folder under the old default keeps Phone Media`() {
        // Otherwise Settings would show the new default while uploads kept going to the old folder
        assertEquals("Phone Media", SyncSettings.effectiveFolderName(null, hasCachedFolder = true, "GoatTidi_Pixel"))
    }

    @Test
    fun `a saved name always wins`() {
        assertEquals("Trips", SyncSettings.effectiveFolderName("Trips", hasCachedFolder = true, "GoatTidi_Pixel"))
    }

    @Test
    fun `default folder name is GoatTidi_ plus a device name`() {
        val name = SyncSettings(ApplicationProvider.getApplicationContext<Context>()).defaultFolderName
        assertTrue(name, name.startsWith("GoatTidi_") && name.length > "GoatTidi_".length)
        assertFalse(name, name.contains('/'))
    }
}
