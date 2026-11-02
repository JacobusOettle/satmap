package com.jacobusoettle.satmap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.core.Base64URL
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.InviteLink
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The link check of docs/groups/SPEC.md §5.6 (walkthrough step 41 on the iPhone): MainActivity, opened in demo mode
 *  with the invite link of "Braai club" (not joined), shows its join confirmation. Once with the App Link itself, once
 *  as the landing page's `intent://` link arrives (`satmap://join/<token>` with the key in the extra `groupKey`).
 *  Whether a real browser tap keeps the fragment is a device check after deployment (§14 U1).
 *
 *  The link is sent as another app sends it (`startActivity`), not with `ActivityScenario`: MainActivity clears a
 *  handled intent's action (so a recreated activity doesn't open it again), which `ActivityScenario` takes for another
 *  activity; and MainActivity is `singleTask`, so a link may also arrive by `onNewIntent`. */
@RunWith(AndroidJUnit4::class)
class InviteIntentTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Sends the link to SatMap, in demo mode. */
    private fun send(intent: Intent) {
        context.startActivity(
            intent.setClass(context, MainActivity::class.java)
                .putExtra("groupsDemo", "YES")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Closes SatMap's activities, so the next test starts afresh. */
    @After
    fun closeActivities() {
        val stages = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
        var running = emptyList<Activity>()
        instrumentation.runOnMainSync {
            running = stages.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            running.forEach { it.finish() }
        }
        val end = System.currentTimeMillis() + 10_000
        while (running.any { !it.isDestroyed } && System.currentTimeMillis() < end) Thread.sleep(100)
    }

    @Test
    fun anAppLinkOpensTheJoinConfirmation() {
        send(Intent(Intent.ACTION_VIEW, Uri.parse(GroupDemo.inviteLink2)))
        expectJoinSheet()
    }

    @Test
    fun theLandingPagesIntentLinkOpensItToo() {
        val invite = InviteLink.parse(GroupDemo.inviteLink2, GroupConfig.domain) as InviteLink.Parsed.Invite
        send(
            Intent(Intent.ACTION_VIEW, Uri.parse("satmap://join/${invite.link.token}"))
                .putExtra("groupKey", Base64URL.encode(invite.link.groupKey)),
        )
        expectJoinSheet()
    }

    /** The join sheet for "Braai club", past the terms if they weren't accepted yet (S8 shows S1 first). */
    private fun expectJoinSheet() {
        compose.waitUntil(30_000) { found(hasTestTag("joinGroup")) || found(hasText("Agree and continue") and hasClickAction()) }
        if (!found(hasTestTag("joinGroup"))) {
            compose.onAllNodes(hasText("Agree and continue") and hasClickAction()).onFirst().performClick()
        }
        compose.waitUntil(30_000) { found(hasTestTag("joinGroup")) }
        compose.waitUntil(10_000) { found(hasText("Braai club")) }
    }

    private fun found(m: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
}
