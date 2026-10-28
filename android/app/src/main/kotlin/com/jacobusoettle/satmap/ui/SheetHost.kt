package com.jacobusoettle.satmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

// Android only: every iOS sheet is a ModalBottomSheet (always fully expanded, the map dimmed above it), with an
// in-sheet stack of routes in place of a NavigationStack (DESIGN.md §4.2).

/** What a screen in a sheet shows in the bar: its title, the leading button and an optional trailing one. */
class ScreenChrome(
    val title: String,
    val leading: Leading,
    val trailing: (@Composable () -> Unit)? = null,
) {
    sealed interface Leading {
        class Done(val onClick: () -> Unit) : Leading
        class Cancel(val onClick: () -> Unit) : Leading

        /** The back button, labelled with the previous screen's title. */
        class Back(val previousTitle: String, val onClick: () -> Unit) : Leading
    }
}

/** A sheet's screens: the root at the bottom. A route holds its screen's state, so a screen under another keeps it. */
class RouteStack<R : Any>(root: R) {
    val routes: SnapshotStateList<R> = mutableStateListOf(root)
    val top: R get() = routes.last()

    fun push(r: R) {
        routes.add(r)
    }

    fun pop() {
        if (routes.size > 1) routes.removeAt(routes.lastIndex)
    }
}

/** Shows `item` in a bottom sheet while it isn't null; a swipe down calls `onDismiss`. When the model clears the item
 *  itself, the last content stays while the sheet hides. */
@Composable
fun <T : Any> SheetHost(item: T?, onDismiss: () -> Unit, content: @Composable (T) -> Unit) {
    // M3: the ModalBottomSheet (DESIGN.md §4.2), with testTagsAsResourceId on its content.
}

/** The sheet's bar: the title (tagged `screenTitle`), the leading Done / Cancel / back button (tagged `back`), the
 *  trailing button, and a hairline once the list has scrolled. */
@Composable
fun SheetTopBar(chrome: ScreenChrome, scrolled: Boolean) {
    // M3: CenterAlignedTopAppBar (DESIGN.md §4.2).
}
