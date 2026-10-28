package com.jacobusoettle.satmap.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import com.jacobusoettle.satmap.app.AppModel

/** "Go to coordinates": paste or type a position in any format, see how it was read, then show and measure from it.
 *  `sheetState`: the field takes the focus once the sheet is expanded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateEntryView(model: AppModel, close: () -> Unit, sheetState: SheetState? = null) {
    // M3: UI spec §3.10.
}
