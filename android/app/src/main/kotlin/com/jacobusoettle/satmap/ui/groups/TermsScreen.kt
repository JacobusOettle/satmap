package com.jacobusoettle.satmap.ui.groups

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.groups.GroupsRoute
import com.jacobusoettle.satmap.ui.Accent
import com.jacobusoettle.satmap.ui.Footnote
import com.jacobusoettle.satmap.ui.FormRow
import com.jacobusoettle.satmap.ui.FormSection
import com.jacobusoettle.satmap.ui.Headline
import com.jacobusoettle.satmap.ui.LabelSecondary
import com.jacobusoettle.satmap.ui.MultiLineRowPadding
import com.jacobusoettle.satmap.ui.SatIcons
import com.jacobusoettle.satmap.ui.ScreenChrome
import com.jacobusoettle.satmap.ui.SheetScreen
import com.jacobusoettle.satmap.ui.pressable

/** S1 Terms (docs/groups/SPEC.md §9.3, §8.13): what groups are, the rules, and "Agree and continue", which also
 *  confirms the person is 18 or older or has a parent's or guardian's permission. Accepted once per install
 *  (`groupsTermsAccepted`); then `agreed` (S2, or S8 in the join sheet). The caller gives the leading button. */
@Composable
fun TermsScreen(model: AppModel, below: GroupsRoute?, leading: ScreenChrome.Leading, agreed: () -> Unit) {
    SheetScreen(ScreenChrome("Groups", leading)) {
        item {
            FormSection(first = true) {
                HeadlineRow("See your group on the map")
                IconTextRow("Share your position, trail and pins with friends, family or your drone team.", SatIcons.Person3, iconSize = Person3RowSize)
                IconTextRow("Only people you invite can join. Each invite works once, for 7 days.", GroupSymbols.link)
                IconTextRow(
                    "Everything your group shares is encrypted on your phone. Our server can't read names, places or notes.",
                    GroupSymbols.lock,
                )
                IconTextRow("Don't post anything illegal, hateful or dangerous. You can report and block anyone.", GroupSymbols.flag)
                IconTextRow("Creating a group needs SatMap Pro. Joining is free.", GroupSymbols.star)
            }
        }
        item {
            FormSection {
                FormRow(verticalPadding = MultiLineRowPadding) {
                    Text(
                        "By tapping Agree and continue you accept the terms of use and confirm that you're 18 or older, or have a " +
                            "parent's or guardian's permission.",
                        style = Footnote,
                        color = LabelSecondary,
                    )
                }
                FormRow(
                    Modifier.testTag("agreeTerms").pressable {
                        model.groups.acceptTerms()
                        agreed()
                    },
                ) {
                    Text("Agree and continue", style = Headline, color = Accent, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            FormSection(footer = "Questions or a problem? ${GroupConfig.contactEmail}") {
                LinkRow("Terms of use", GroupConfig.termsURL)
                LinkRow("Privacy notice", GroupConfig.privacyURL)
            }
        }
    }
}
