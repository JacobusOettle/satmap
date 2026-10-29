package com.jacobusoettle.satmap

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.util.regex.Pattern

/** Android's "<app> isn't responding" and "<app> keeps stopping" dialogs. On the slow emulator the system's own apps
 *  (Messages) bring one up now and then: it covers SatMap and takes the walk's taps. */
object SystemDialogs {
    private val title = Pattern.compile(".*(isn.t responding|keeps stopping|has stopped).*")
    private var lastCheck = 0L

    /** Closes such a dialog, looking at most every 2 s; returns its title, or null when there was none. SatMap's own is
     *  answered with "Wait" (the caller reports it), another app's with "Close app", so it doesn't come back at once. */
    fun dismiss(device: UiDevice): String? {
        val now = SystemClock.uptimeMillis()
        if (now - lastCheck < 2_000) return null
        lastCheck = now
        return try {
            val dialog = device.findObject(By.text(title)) ?: return null
            val text = dialog.text ?: ""
            val answer = if (text.contains("SatMap")) "Wait" else "Close app"
            (device.findObject(By.text(answer)) ?: device.findObject(By.text("Wait")) ?: device.findObject(By.text("OK")))
                ?.click()
            text
        } catch (e: Exception) {
            null // the dialog went while it was read
        }
    }
}
