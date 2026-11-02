package com.jacobusoettle.satmap

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.core.Base64URL
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.InviteLink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The link check of docs/groups/SPEC.md §5.6 (walkthrough step 41 on the iPhone): MainActivity, launched in demo mode
 *  with the invite link of "Braai club" (not joined), shows its join confirmation. Once with the App Link itself, once
 *  as the landing page's `intent://` link arrives (`satmap://join/<token>` with the key in the extra `groupKey`).
 *  Whether a real browser tap keeps the fragment is a device check after deployment (§14 U1). */
@RunWith(AndroidJUnit4::class)
class InviteIntentTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun demo(intent: Intent): Intent = intent.setClass(context, MainActivity::class.java).putExtra("groupsDemo", "YES")

    @Test
    fun anAppLinkOpensTheJoinConfirmation() {
        val intent = demo(Intent(Intent.ACTION_VIEW, Uri.parse(GroupDemo.inviteLink2)))
        ActivityScenario.launch<MainActivity>(intent).use { expectJoinSheet() }
    }

    @Test
    fun theLandingPagesIntentLinkOpensItToo() {
        val invite = InviteLink.parse(GroupDemo.inviteLink2, GroupConfig.domain) as InviteLink.Parsed.Invite
        val intent = demo(Intent(Intent.ACTION_VIEW, Uri.parse("satmap://join/${invite.link.token}")))
            .putExtra("groupKey", Base64URL.encode(invite.link.groupKey))
        ActivityScenario.launch<MainActivity>(intent).use { expectJoinSheet() }
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
