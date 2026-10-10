package com.sahay.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalPolice
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.distanceMeters
import com.sahay.app.navigate.NavTarget
import com.sahay.core.contracts.GeoPoint
import com.sahay.designsystem.LocalSahayDark
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.ErrorState
import com.sahay.designsystem.components.LoadingState
import com.sahay.engine.map.SahayMap

/** Full-screen map with filter chips, a details sheet and "Go to safety" (DESIGN §6). */
@Composable
fun MapScreen(
    onGoTo: (NavTarget) -> Unit,
    onDownloadPack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state.phase) {
        MapPhase.LOADING -> LoadingState(stringResource(R.string.loading), modifier.padding(SahaySpacing.screenPadding))
        MapPhase.NO_PACK -> EmptyState(
            icon = Icons.Rounded.Map,
            title = stringResource(R.string.map_no_pack_title),
            body = stringResource(R.string.map_no_pack_body),
            modifier = modifier,
            actionLabel = stringResource(R.string.home_no_pack_action),
            onAction = onDownloadPack,
        )
        MapPhase.ERROR -> ErrorState(
            title = stringResource(R.string.map_error_title),
            body = stringResource(R.string.map_error_body),
            actionLabel = stringResource(R.string.action_retry),
            onAction = viewModel::retry,
            modifier = modifier,
        )
        MapPhase.READY -> MapContent(state, viewModel, onGoTo, modifier)
    }
}

@Composable
private fun MapContent(state: MapUiState, viewModel: MapViewModel, onGoTo: (NavTarget) -> Unit, modifier: Modifier) {
    val myPoint = state.view.myLocation?.point
    Box(modifier.fillMaxSize()) {
        SahayMap(
            state = state.view.copy(darkStyle = LocalSahayDark.current),
            modifier = Modifier.fillMaxSize(),
            onPoiClick = { viewModel.select(MapSelection.PoiSelection(it)) },
            onReportClick = { viewModel.select(MapSelection.ReportSelection(it)) },
            onGroupClick = { viewModel.select(MapSelection.GroupSelection(it)) },
        )
        FilterRow(state.filters, viewModel::toggleFilter, Modifier.align(Alignment.TopStart))
        Column(
            Modifier.align(Alignment.BottomEnd).padding(SahaySpacing.md),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
            horizontalAlignment = Alignment.End,
        ) {
            ExtendedFloatingActionButton(
                onClick = viewModel::recenter,
                icon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
                text = { Text(stringResource(R.string.action_recenter)) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            ExtendedFloatingActionButton(
                onClick = { onGoTo(NavTarget.NearestSafe) },
                icon = { Icon(Icons.Rounded.NearMe, contentDescription = null) },
                text = { Text(stringResource(R.string.action_go_safety)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    state.selection?.let { selection ->
        MapSelectionSheet(
            selection = selection,
            distanceM = myPoint?.let { distanceMeters(it, selection.point()) },
            onDismiss = viewModel::clearSelection,
            onGoToPoi = {
                viewModel.clearSelection()
                onGoTo(NavTarget.ToPoi(it.id))
            },
            onGoToGroup = {
                viewModel.clearSelection()
                onGoTo(NavTarget.ToPoint(it.point))
            },
        )
    }
}

private fun MapSelection.point(): GeoPoint = when (this) {
    is MapSelection.PoiSelection -> poi.point
    is MapSelection.ReportSelection -> report.point
    is MapSelection.GroupSelection -> group.point
}

private data class FilterUi(val filter: MapFilter, val labelRes: Int, val icon: ImageVector)

private val filterOptions = listOf(
    FilterUi(MapFilter.SHELTERS, R.string.map_filter_shelters, Icons.Rounded.Home),
    FilterUi(MapFilter.HOSPITALS, R.string.map_filter_hospitals, Icons.Rounded.LocalHospital),
    FilterUi(MapFilter.POLICE, R.string.map_filter_police, Icons.Rounded.LocalPolice),
    FilterUi(MapFilter.REPORTS, R.string.map_filter_reports, Icons.Rounded.Flag),
    FilterUi(MapFilter.GROUPS, R.string.map_filter_groups, Icons.Rounded.Group),
    FilterUi(MapFilter.RISK_AREAS, R.string.map_filter_risk, Icons.Rounded.Warning),
)

@Composable
private fun FilterRow(selected: Set<MapFilter>, onToggle: (MapFilter) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = SahaySpacing.md, vertical = SahaySpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
    ) {
        items(filterOptions, key = { it.filter }) { option ->
            FilterChip(
                selected = option.filter in selected,
                onClick = { onToggle(option.filter) },
                label = { Text(stringResource(option.labelRes)) },
                leadingIcon = { Icon(option.icon, contentDescription = null) },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}
