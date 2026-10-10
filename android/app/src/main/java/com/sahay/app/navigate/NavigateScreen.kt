package com.sahay.app.navigate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.distanceText
import com.sahay.core.contracts.MapViewState
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.RouteWarning
import com.sahay.designsystem.LocalSahayDark
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.ErrorState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusChip
import com.sahay.designsystem.components.StatusKind
import com.sahay.engine.map.SahayMap
import kotlinx.serialization.Serializable

/** [target] is an encoded [NavTarget]; null means "nearest safe place". */
@Serializable
data class NavigateRoute(val target: String? = null)

@Composable
fun NavigateScreen(
    onBack: () -> Unit,
    /** Opens the "Show to a local" card for the current destination (null = a bare spot or the nearest shelter). */
    onShowLocal: (Poi?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NavigateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.action_go_safety), onBack)
        when (val s = state) {
            NavUiState.Finding -> Column(Modifier.padding(horizontal = SahaySpacing.screenPadding)) {
                LoadingState(stringResource(R.string.nav_finding), rows = 1)
                Text(
                    stringResource(R.string.nav_finding),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = SahaySpacing.sm),
                )
            }
            is NavUiState.NoFix -> ErrorState(
                title = stringResource(R.string.nav_no_fix_title),
                body = stringResource(if (s.permissionMissing) R.string.nav_no_permission_body else R.string.nav_no_fix_body),
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::retry,
                icon = Icons.Rounded.LocationOff,
            )
            NavUiState.NoRoute -> ErrorState(
                title = stringResource(R.string.nav_no_route_title),
                body = stringResource(R.string.nav_no_route_body),
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::retry,
            )
            is NavUiState.Active -> ActiveNavigation(s, onShowLocal, viewModel::routeTo)
        }
    }
}

/** The map fills the screen; the route card floats at the bottom. "Full map" hides the card and shows every place. */
@Composable
private fun ActiveNavigation(state: NavUiState.Active, onShowLocal: (Poi?) -> Unit, onRouteTo: (Poi) -> Unit) {
    val route = state.route
    var fullScreen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SahayMap(
            state = MapViewState(
                center = state.myLocation.point,
                followUser = true,
                darkStyle = LocalSahayDark.current,
                myLocation = state.myLocation,
                pois = if (fullScreen) (state.allPois + listOfNotNull(route.destination)).distinctBy { it.id } else listOfNotNull(route.destination),
                route = route,
                riskZones = state.riskZones,
            ),
            modifier = Modifier.fillMaxSize(),
        )
        SahayButton(
            text = stringResource(if (fullScreen) R.string.nav_exit_full_map else R.string.nav_full_map),
            onClick = { fullScreen = !fullScreen },
            icon = if (fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.M,
            fullWidth = false,
            compact = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(SahaySpacing.sm),
        )
        if (!fullScreen) {
            RouteCard(state, onShowLocal, onRouteTo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Compact bottom card: distance + time, destination, notices as chips, other safe places. */
@Composable
private fun RouteCard(state: NavUiState.Active, onShowLocal: (Poi?) -> Unit, onRouteTo: (Poi) -> Unit, modifier: Modifier) {
    val route = state.route
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
    ) {
        if (state.arrived) {
            StatusCard(
                kind = StatusKind.Safe,
                title = stringResource(R.string.nav_arrived_title),
                body = stringResource(R.string.nav_arrived_body),
            )
        } else {
            Header(state.remainingM)
        }
        Destination(route)
        RouteNotices(route)
        NearbyPlaces(state.nearby, onRouteTo)
        SahayButton(
            text = stringResource(R.string.nav_show_local),
            onClick = { onShowLocal(route.destination) },
            icon = Icons.Rounded.Translate,
            variant = ButtonVariant.Secondary,
        )
    }
}

/** "650 m · 9 min walk" (DESIGN §6). */
@Composable
private fun Header(remainingM: Double) {
    val text = stringResource(
        R.string.nav_header,
        distanceText(remainingM),
        stringResource(R.string.nav_eta_walk, walkingMinutes(remainingM)),
    )
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

/** Destination name in the user's language, with the Tamil name below; each at most two lines. */
@Composable
private fun Destination(route: Route) {
    val poi = route.destination
    Column {
        Text(
            poi?.name ?: stringResource(R.string.nav_destination_point),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        poi?.nameTa?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Small chips: "avoids flood-prone roads", plain-words warnings, or ONE message for a straight-line fallback. */
@Composable
private fun RouteNotices(route: Route) {
    if (route.isStraightLine) {
        // Without path data the other warnings add nothing; say it once.
        StatusChip(StatusKind.Watch, stringResource(R.string.nav_straight_line), icon = Icons.Rounded.Flag)
        return
    }
    // A route that doesn't avoid risk zones always says so, even if the engine didn't add the warning.
    val warnings = if (!route.avoidsRiskZones && RouteWarning.PASSES_RISK_AREA !in route.warnings) {
        route.warnings + RouteWarning.PASSES_RISK_AREA
    } else {
        route.warnings
    }
    if (route.avoidsRiskZones && warnings.isEmpty()) {
        StatusChip(StatusKind.Safe, stringResource(R.string.nav_avoids_risk), icon = Icons.Rounded.Verified)
        return
    }
    warnings.forEach { warning -> StatusChip(StatusKind.Warning, stringResource(warning.messageRes())) }
}

/** The nearest other safe places; tapping one re-routes there. */
@Composable
private fun NearbyPlaces(nearby: List<Pair<Poi, Double>>, onRouteTo: (Poi) -> Unit) {
    if (nearby.isEmpty()) return
    Text(
        stringResource(R.string.nav_other_places),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
    nearby.forEach { (poi, meters) ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onRouteTo(poi) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Icon(Icons.Rounded.NearMe, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                poi.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(distanceText(meters), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun RouteWarning.messageRes(): Int = when (this) {
    RouteWarning.START_FAR_FROM_ROAD -> R.string.nav_warn_far_from_road
    RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL -> R.string.nav_warn_hospital
    RouteWarning.PASSES_RISK_AREA -> R.string.nav_warn_risk_area
    RouteWarning.NO_PACK -> R.string.nav_warn_no_pack
}
