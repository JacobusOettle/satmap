package com.jacobusoettle.satmap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    /** A route on the stack, with a key of its own under which its screen keeps what it remembers with
     *  `rememberSaveable` (its list's scroll position) while another screen is over it. */
    class Entry<R : Any> internal constructor(val key: Int, val route: R)

    private var nextKey = 0
    val entries: SnapshotStateList<Entry<R>> = mutableStateListOf(Entry(nextKey++, root))
    val routes: List<R> get() = entries.map { it.route }
    val top: R get() = entries.last().route
    val size: Int get() = entries.size

    fun push(r: R) {
        entries.add(Entry(nextKey++, r))
    }

    fun pop() {
        if (entries.size > 1) entries.removeAt(entries.lastIndex)
    }

    /** The route under `r` (whose title labels r's back button), or null for the root. */
    fun below(r: R): R? {
        val i = entries.indexOfFirst { it.route === r }
        return if (i > 0) entries[i - 1].route else null
    }
}

/** The sheet being shown. `close` (Done, Cancel, a choice that ends the sheet) slides it down, then clears what it
 *  shows; `sheetState` tells a screen when the sheet is fully up (Go to coordinates then focuses its field). */
@OptIn(ExperimentalMaterial3Api::class)
class SheetScope internal constructor(val sheetState: SheetState, val close: () -> Unit)

/** Shows `item` in a bottom sheet while it isn't null, as iOS's `.sheet(item:)`: always fully up, with the dimmed map
 *  above it. A swipe down, a tap on the map above or system back slide it away and call `onDismiss`. When the model
 *  clears the item itself, the last content stays while the sheet slides away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> SheetHost(item: T?, onDismiss: () -> Unit, content: @Composable SheetScope.(T) -> Unit) {
    // What the sheet shows: kept while it slides away after the item was cleared.
    var shown by remember { mutableStateOf(item) }
    if (item != null) shown = item
    val current = shown ?: return
    // From here on, one presentation: a new sheet state each time the sheet is shown again.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestItem by rememberUpdatedState(item)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val hiding = remember { booleanArrayOf(false) }
    val gone = item == null
    LaunchedEffect(gone) {
        if (gone) {
            hiding[0] = true
            try {
                sheetState.hide()
            } finally {
                if (latestItem == null) shown = null
            }
        } else if (hiding[0]) {
            // Shown again while it was sliding away.
            hiding[0] = false
            sheetState.show()
        }
    }
    val sheet = remember(sheetState) {
        SheetScope(sheetState) { scope.launch { sheetState.hide() }.invokeOnCompletion { latestOnDismiss() } }
    }
    ModalBottomSheet(
        onDismissRequest = { latestOnDismiss() },
        // The map shows above it, dimmed, as above an iOS page sheet.
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp),
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        containerColor = SheetBackground,
        contentColor = Color.White,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        // iOS shows no grabber on these sheets.
        dragHandle = null,
        // Insets are handled inside (the lists' bottom padding, the keyboard).
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            sheet.content(current)
        }
    }
}

/** The sheet's bar: the title (tagged `screenTitle`), the leading Done / Cancel / back button (tagged `back`), the
 *  trailing button, and a hairline once the list has scrolled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetTopBar(chrome: ScreenChrome, scrolled: Boolean) {
    Box {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    chrome.title,
                    style = Headline,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }.testTag("screenTitle"),
                )
            },
            navigationIcon = {
                when (val leading = chrome.leading) {
                    is ScreenChrome.Leading.Done -> BarTextButton("Done", leading.onClick)
                    is ScreenChrome.Leading.Cancel -> BarTextButton("Cancel", leading.onClick)
                    is ScreenChrome.Leading.Back -> BackButton(leading.previousTitle, leading.onClick)
                }
            },
            actions = { chrome.trailing?.invoke() },
            expandedHeight = 56.dp,
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = SheetBackground,
                scrolledContainerColor = SheetBackground,
            ),
        )
        if (scrolled) {
            HorizontalDivider(Modifier.align(Alignment.BottomStart), thickness = 0.5.dp, color = Separator)
        }
    }
}

/** "Done" or "Cancel": body text in the accent colour. */
@Composable
private fun BarTextButton(title: String, onClick: () -> Unit) {
    TextButton(onClick) { Text(title, style = Body, color = Accent) }
}

/** iOS's back button: a chevron and the previous screen's title, in the accent colour. */
@Composable
private fun BackButton(previousTitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Back"
                role = Role.Button
                testTag = "back"
                onClick {
                    onClick()
                    true
                }
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Text(previousTitle, style = Body, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A screen in a sheet: its bar, then its list (iOS's inset-grouped list: sections 32 dp apart), which scrolls under
 *  the bar, ends clear of the navigation bar and shrinks above the keyboard. */
@Composable
fun SheetScreen(
    chrome: ScreenChrome,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        SheetTopBar(chrome, scrolled = state.canScrollBackward)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).imePadding(),
            state = state,
            contentPadding = PaddingValues(top = 16.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            content = content,
        )
    }
}

/** Shows the stack's top screen, sliding in from the right when pushed and back when popped (iOS
 *  `NavigationStack`). System back pops it; at the root it closes the sheet. A screen under another keeps what its
 *  route holds and what it remembers with `rememberSaveable` (its list's scroll position). `screen` gets the route
 *  and the one under it (null for the root), whose title labels its back button: kept while a popped screen slides
 *  away. */
@Composable
fun <R : Any> SheetStack(stack: RouteStack<R>, screen: @Composable (route: R, below: R?) -> Unit) {
    val saved = rememberSaveableStateHolder()
    // Registered after the sheet's own back handling, so it wins while there is a screen to go back to.
    BackHandler(enabled = stack.size > 1) { stack.pop() }
    AnimatedContent(
        targetState = stack.entries.last(),
        transitionSpec = {
            if (targetState.key > initialState.key) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        contentKey = { it.key },
        label = "sheet screens",
    ) { entry ->
        // What is under a screen never changes while it is on the stack (only the top is pushed or popped).
        val below = remember(entry) { stack.below(entry.route) }
        saved.SaveableStateProvider(entry.key) { screen(entry.route, below) }
    }
    // A popped screen's remembered state goes with it.
    val known = remember { HashSet<Int>() }
    SideEffect {
        val keys = stack.entries.map { it.key }.toSet()
        for (k in known - keys) saved.removeState(k)
        known.clear()
        known.addAll(keys)
    }
}
