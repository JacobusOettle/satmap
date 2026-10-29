package com.jacobusoettle.satmap.ui

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/** The system colours as the iPhone screenshots show them: iOS 26's dark ones (the crosshair's red dot, the active
 *  buttons and the download bar in yellow, "Ready offline" in green). */
class ThemeTest {
    @Test
    fun theSystemColoursAreIos26sDarkOnes() {
        assertEquals(0xFFFF4245.toInt(), SystemRed.toArgb()) // iOS 18: #FF453A
        assertEquals(0xFFFFD600.toInt(), SystemYellow.toArgb()) // iOS 18: #FFD60A
        assertEquals(0xFFFF9230.toInt(), SystemOrange.toArgb()) // iOS 18: #FF9F0A
        assertEquals(0xFF30D158.toInt(), SystemGreen.toArgb()) // the same in both
    }
}
