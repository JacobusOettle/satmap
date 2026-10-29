package com.jacobusoettle.satmap.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.LabeledIntent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.service.chooser.ChooserAction
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.MainActivity
import com.jacobusoettle.satmap.offline.BackupSharing
import com.jacobusoettle.satmap.offline.SaveToFolderActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** The share flow (DESIGN.md §5.9, offline spec §2.8): `SharePresenter` asked from outside any screen, `ShareHost`
 *  opening the chooser with "Save to a folder" and the folder picker, and the completion when the chooser closes. */
@RunWith(AndroidJUnit4::class)
class ShareSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val files = listOf(
        Uri.parse("content://com.jacobusoettle.satmap.backup/offline/A/imagery.mbtiles?displayName=Farm%20-%20satellite.mbtiles"),
        Uri.parse("content://com.jacobusoettle.satmap.backup/offline/A/terrain.mbtiles?displayName=Farm%20-%20elevation.mbtiles"),
    )

    @Before
    fun setUp() {
        SharePresenter.finished()
    }

    @After
    fun tearDown() {
        SharePresenter.finished()
    }

    private val activity get() = compose.activity

    @Test
    fun withNoScreenTheShareEndsAtOnce() {
        var closed = 0
        SharePresenter.present(ShareRequest(files) { closed += 1 })
        assertEquals(1, closed)
        assertNull(SharePresenter.request.value)
    }

    @Test
    fun theChooserOpensAndItsClosingEndsTheShare() {
        compose.setContent { ShareHost() }
        compose.waitForIdle()
        var closed = 0
        val folders = mutableListOf<Uri>()
        val request = ShareRequest(files, saveToFolder = { folders += it }) { closed += 1 }
        SharePresenter.present(request)
        assertSame(request, SharePresenter.request.value)
        compose.waitForIdle()
        val started = shadowOf(activity).nextStartedActivityForResult
        val chooser = started.intent
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("application/octet-stream", send.type)
        @Suppress("DEPRECATION")
        assertEquals(files, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(2, send.clipData!!.itemCount)
        assertEquals(files[1], send.clipData!!.getItemAt(1).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // "Save to a folder" (API 34 and later: a custom action that opens SatMap with ACTION_SAVE_TO_FOLDER).
        @Suppress("DEPRECATION")
        val actions = chooser.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS)!!.map { it as ChooserAction }
        assertEquals(listOf("Save to a folder"), actions.map { it.label.toString() })
        val forwarded = shadowOf(actions.single().action).savedIntent
        assertEquals(BackupSharing.ACTION_SAVE_TO_FOLDER, forwarded.action)
        assertEquals(MainActivity::class.java.name, forwarded.component!!.className)
        assertEquals(0, closed)

        // The chooser closes (whatever was chosen): the share ends.
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(1, closed)
        assertNull(SharePresenter.request.value)

        // "Save to a folder" was chosen: the folder picker, then the copy into the folder picked.
        SharePresenter.requestFolder()
        assertTrue(SharePresenter.folderWanted.value)
        compose.waitForIdle()
        val picker = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.action)
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.usb", "root")
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(tree))
        compose.waitForIdle()
        assertEquals(listOf(tree), folders)
        assertFalse(SharePresenter.folderWanted.value)

        // Cancelled: nothing is copied.
        SharePresenter.requestFolder()
        compose.waitForIdle()
        val again = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(again.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(listOf(tree), folders)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun beforeAPI34SaveToAFolderIsAnExtraTarget() {
        // (Robolectric runs Android 15: the version the chooser reads is set to 33 for it.)
        val real = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        val chooser = try {
            BackupSharing.chooser(activity, files)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", real)
        }
        assertFalse(chooser.hasExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS))
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.single() as LabeledIntent
        assertEquals("Save to a folder", target.nonLocalizedLabel.toString())
        // SaveToFolderActivity forwards it to MainActivity.
        assertEquals(SaveToFolderActivity::class.java.name, target.component!!.className)
        val forward = Robolectric.buildActivity(SaveToFolderActivity::class.java, target).create().get()
        val forwarded = shadowOf(forward).nextStartedActivity
        assertEquals(BackupSharing.ACTION_SAVE_TO_FOLDER, forwarded.action)
        assertEquals(MainActivity::class.java.name, forwarded.component!!.className)
        assertTrue(forward.isFinishing)
    }
}
