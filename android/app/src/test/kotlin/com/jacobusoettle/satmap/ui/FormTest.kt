package com.jacobusoettle.satmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** iOS's inset-grouped list pieces (UI spec §1.5): upper-case headers, the rows' taps, switches and deletes. */
@RunWith(AndroidJUnit4::class)
class FormTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSectionShowsItsHeaderUpperCasedAndItsFooter() {
        compose.setContent {
            SatMapTheme {
                FormSection(header = "Offline areas", footer = "Switch a file off.", first = true) {
                    FormValueRow("Saved", "68.4 MB")
                }
            }
        }
        compose.onNodeWithText("OFFLINE AREAS").assertExists()
        compose.onNodeWithText("Offline areas").assertDoesNotExist()
        compose.onNodeWithText("Switch a file off.").assertExists()
        compose.onNodeWithText("68.4 MB").assertExists()
    }

    @Test
    fun linkAndActionRowsTapAndADisabledActionDoesnt() {
        var links = 0
        var actions = 0
        compose.setContent {
            SatMapTheme {
                FormSection {
                    FormLinkRow("Find a park, reserve or town", Icons.Filled.Search) { links++ }
                    FormActionRow("Export all offline maps") { actions++ }
                    FormActionRow("Restore from a backup", enabled = false) { actions++ }
                }
            }
        }
        compose.onNodeWithText("Find a park, reserve or town").performClick()
        compose.onNodeWithText("Export all offline maps").performClick()
        compose.onNodeWithText("Restore from a backup").assert(
            SemanticsMatcher("disabled") { it.config.contains(SemanticsProperties.Disabled) },
        )
        assertEquals(1, links)
        assertEquals(1, actions)
    }

    @Test
    fun theWholeToggleRowSwitches() {
        var on by mutableStateOf(false)
        compose.setContent {
            SatMapTheme { FormSection { FormToggleRow("Allow mobile data", on) { on = it } } }
        }
        compose.onNodeWithText("Allow mobile data")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertEquals(true, on)
        compose.onNodeWithText("Allow mobile data")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun aRowCanBeDeletedWithTheAccessibilityAction() {
        var deleted = 0
        compose.setContent {
            SatMapTheme {
                FormSection {
                    FormSwipeToDeleteRow(onDelete = { deleted++ }) { FormValueRow("Kruger", "2 GB") }
                }
            }
        }
        val row = compose.onNode(
            SemanticsMatcher("offers Delete") { n ->
                n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Delete" } == true
            },
        )
        row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Delete" }.action()
        assertEquals(1, deleted)
    }

    @Test
    fun aTextFieldShowsItsPlaceholderUntilTypedInto() {
        var text by mutableStateOf("")
        compose.setContent {
            SatMapTheme { Column { FormTextFieldRow(text, { text = it }, placeholder = "Area name") } }
        }
        compose.onNodeWithText("Area name").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Kruger")
        assertEquals("Kruger", text)
        compose.onNodeWithText("Area name").assertDoesNotExist()
    }
}
