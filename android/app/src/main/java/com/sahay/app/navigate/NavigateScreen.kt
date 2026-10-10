package com.sahay.app.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.distanceText
import com.sahay.core.contracts.MapViewState
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.RouteWarning
import com.sahay.designsystem.LocalSahayDark
import com.sahay.designsystem.SahaySpacing
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
    onShowLocal: () -> Unit,
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
            is NavUiState.Active -> ActiveNavigation(s, onShowLocal)
        }
    }
}

@Composable
private fun ActiveNavigation(state: NavUiState.Active, onShowLocal: () -> Unit) {
    val route = state.route
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
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
        }
        SahayMap(
            state = MapViewState(
                center = state.myLocation.point,
                followUser = true,
                darkStyle = LocalSahayDark.current,
                myLocation = state.myLocation,
                pois = listOfNotNull(route.destination),
                route = route,
                riskZones = state.riskZones,
            ),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = SahaySpacing.screenPadding),
        )
        SahayButton(
            text = stringResource(R.string.nav_show_local),
            onClick = onShowLocal,
            icon = Icons.Rounded.Translate,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.padding(SahaySpacing.screenPadding),
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

/** Destination name in the user's language, with the Tamil name below for showing to a local. */
@Composable
private fun Destination(route: Route) {
    val poi = route.destination
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        Column {
            Text(poi?.name ?: stringResource(R.string.nav_destination_point), style = MaterialTheme.typography.titleLarge)
            poi?.nameTa?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Either the "avoids flood-prone roads" badge or plain-words warnings. Never raw enum names. */
@Composable
private fun RouteNotices(route: Route) {
    // A route that doesn't avoid risk zones always says so, even if the engine didn't add the warning.
    val warnings = if (!route.avoidsRiskZones && RouteWarning.PASSES_RISK_AREA !in route.warnings) {
        route.warnings + RouteWarning.PASSES_RISK_AREA
    } else {
        route.warnings
    }
    if (route.avoidsRiskZones && warnings.isEmpty() && !route.isStraightLine) {
        StatusChip(StatusKind.Safe, stringResource(R.string.nav_avoids_risk), icon = Icons.Rounded.Verified)
        return
    }
    warnings.forEach { warning ->
        StatusCard(kind = StatusKind.Warning, title = stringResource(warning.messageRes()))
    }
    if (route.isStraightLine && RouteWarning.NO_PACK !in warnings) {
        StatusCard(kind = StatusKind.Watch, title = stringResource(R.string.nav_straight_line), icon = Icons.Rounded.Flag)
    }
}

private fun RouteWarning.messageRes(): Int = when (this) {
    RouteWarning.START_FAR_FROM_ROAD -> R.string.nav_warn_far_from_road
    RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL -> R.string.nav_warn_hospital
    RouteWarning.PASSES_RISK_AREA -> R.string.nav_warn_risk_area
    RouteWarning.NO_PACK -> R.string.nav_warn_no_pack
}
