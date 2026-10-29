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
import androidx.lifecycle.Lifecycle
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
        // "Save to a folder" (API 34 and later: a custom action that opens SaveToFolderActivity, which tells
        // SharePresenter and opens SatMap with ACTION_SAVE_TO_FOLDER).
        @Suppress("DEPRECATION")
        val actions = chooser.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS)!!.map { it as ChooserAction }
        assertEquals(listOf("Save to a folder"), actions.map { it.label.toString() })
        val target = shadowOf(actions.single().action)
        assertTrue(target.isActivity)
        assertEquals(SaveToFolderActivity::class.java.name, target.savedIntent.component!!.className)
        assertEquals(0, closed)

        // The chooser closes (whatever was chosen): the share ends.
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(1, closed)
        assertNull(SharePresenter.request.value)

        // "Save to a folder" asked for after the chooser closed (it ended the share): the folder picker, then the copy
        // into the folder picked.
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
        assertEquals(1, closed)
    }

    @Test
    fun saveToAFolderChosenInTheChooserEndsTheShareWhenTheFolderPickerCloses() {
        compose.setContent { ShareHost() }
        compose.waitForIdle()
        val events = mutableListOf<String>()
        SharePresenter.present(ShareRequest(files, saveToFolder = { events += "copy $it" }) { events += "closed" })
        compose.waitForIdle()
        val chooser = shadowOf(activity).nextStartedActivityForResult

        // "Save to a folder" is chosen: SaveToFolderActivity tells SharePresenter before the chooser's result comes.
        val forward = Robolectric.buildActivity(SaveToFolderActivity::class.java).create().get()
        assertTrue(forward.isFinishing)
        assertTrue(SharePresenter.folderWanted.value)
        assertEquals(BackupSharing.ACTION_SAVE_TO_FOLDER, shadowOf(forward).nextStartedActivityForResult.intent.action)
        compose.waitForIdle()
        val picker = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.action)
        // MainActivity's ACTION_SAVE_TO_FOLDER asks again: nothing more opens.
        SharePresenter.requestFolder()
        compose.waitForIdle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)

        // The chooser closes as the folder picker opens: the share goes on.
        shadowOf(activity).receiveResult(chooser.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertNull(SharePresenter.request.value)
        assertEquals(emptyList<String>(), events)

        // A folder is picked: the copy starts, then the share ends.
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.usb", "root")
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(tree))
        compose.waitForIdle()
        assertEquals(listOf("copy $tree", "closed"), events)

        // Again, with the folder picker cancelled: the share ends, nothing is copied.
        events.clear()
        SharePresenter.present(ShareRequest(files, saveToFolder = { events += "copy $it" }) { events += "closed" })
        compose.waitForIdle()
        val chooser2 = shadowOf(activity).nextStartedActivityForResult
        SharePresenter.requestFolder()
        compose.waitForIdle()
        val picker2 = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(chooser2.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), events)
        shadowOf(activity).receiveResult(picker2.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(listOf("closed"), events)
        assertFalse(SharePresenter.folderWanted.value)
    }

    @Test
    fun eachShareEndsOnce() {
        compose.setContent { ShareHost() }
        compose.waitForIdle()
        var first = 0
        var second = 0
        SharePresenter.present(ShareRequest(files) { first += 1 })
        compose.waitForIdle()
        val chooser = shadowOf(activity).nextStartedActivityForResult
        // A second share while the first is still open ends the first.
        SharePresenter.present(ShareRequest(files) { second += 1 })
        assertEquals(1, first)
        compose.waitForIdle()
        val chooser2 = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CHOOSER, chooser2.intent.action)
        // The first chooser's result (late) ends only what is open now; a second result changes nothing.
        shadowOf(activity).receiveResult(chooser.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(1, first)
        assertEquals(1, second)
        SharePresenter.finished()
        assertEquals(1, second)
    }

    @Test
    fun aShareOpenedFromAnActivityThatIsGoneEndsWhenSatMapOpensAgain() {
        // The chooser and the folder picker were opened from a MainActivity that has gone (SatMap was removed from
        // Recents, after Android had ended the activity in the background), and their results never came.
        SharePresenter.attach()
        var closed = 0
        val folders = mutableListOf<Uri>()
        val request = ShareRequest(files, saveToFolder = { folders += it }) { closed += 1 }
        SharePresenter.present(request)
        request.launchedBy = "gone"
        SharePresenter.requestFolder()
        SharePresenter.folderLaunchedBy = "gone"
        SharePresenter.detach()

        // A new MainActivity: the share ends, and nothing is opened again.
        compose.setContent { ShareHost() }
        compose.waitForIdle()
        assertEquals(1, closed)
        assertNull(SharePresenter.request.value)
        assertFalse(SharePresenter.folderWanted.value)
        assertEquals(emptyList<Uri>(), folders)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun aShareEndsWhenItsActivityFinishes() {
        compose.setContent { ShareHost() }
        compose.waitForIdle()
        var closed = 0
        SharePresenter.present(ShareRequest(files) { closed += 1 })
        compose.waitForIdle()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivityForResult.intent.action)

        // SatMap is removed from Recents while the chooser is open: no result will come.
        compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        assertEquals(1, closed)
        assertNull(SharePresenter.request.value)
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
