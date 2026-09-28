package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window behind Compose must match the surface Compose draws, or launch flashes a different color. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeColorsTest {

    private fun windowBackground(): Int =
        ApplicationProvider.getApplicationContext<Context>().getColor(R.color.window_background)

    @Test
    @Config(qualifiers = "notnight")
    fun `light window background matches the light Compose surface`() {
        assertEquals(Integer.toHexString(lightColorScheme().surface.toArgb()), Integer.toHexString(windowBackground()))
    }

    @Test
    @Config(qualifiers = "night")
    fun `dark window background matches the dark Compose surface`() {
        assertEquals(Integer.toHexString(darkColorScheme().surface.toArgb()), Integer.toHexString(windowBackground()))
    }
}
