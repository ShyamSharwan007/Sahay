package com.sahay.engine.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.MapViewState
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi

/**
 * PLACEHOLDER for the MapLibre map (Person B replaces the body, never the signature).
 * Draws a rounded box listing the POIs and the route distance so screens can be built against it.
 */
@Composable
fun SahayMap(
    state: MapViewState,
    modifier: Modifier = Modifier,
    onPoiClick: (Poi) -> Unit = {},
    onReportClick: (HazardReport) -> Unit = {},
    onGroupClick: (PeopleGroup) -> Unit = {},
    onLongPress: (GeoPoint) -> Unit = {},
) {
    Surface(
        modifier = modifier.heightIn(min = 160.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Map preview", style = MaterialTheme.typography.titleMedium)
            state.route?.let {
                Text("Route: ${it.distanceM.toInt()} m · ${it.etaMin} min walk", style = MaterialTheme.typography.bodyMedium)
            }
            state.pois.forEach { poi ->
                Text(
                    text = poi.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { onPoiClick(poi) }.padding(vertical = 2.dp),
                )
            }
        }
    }
}
