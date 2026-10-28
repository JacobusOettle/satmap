package com.jacobusoettle.satmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jacobusoettle.satmap.app.AppModel
import com.jacobusoettle.satmap.offline.OfflineManager
import java.util.UUID

/** An offline area: show it on the map, its size and depth, mobile data, and a deeper (or shallower) level. */
@Composable
fun AreaDetailView(
    model: AppModel,
    offline: OfflineManager,
    areaID: UUID,
    state: AreaDetailState = remember(areaID) { AreaDetailState() },
    close: () -> Unit,
) {
    // M4: UI spec §3.8.
}
