package com.sahay.app.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sahay.R
import com.sahay.app.alerts.AlertDetailRoute
import com.sahay.app.alerts.AlertDetailScreen
import com.sahay.app.alerts.AlertsScreen
import com.sahay.app.alerts.PasteAlertRoute
import com.sahay.app.alerts.PasteAlertScreen
import com.sahay.app.home.HomeScreen
import com.sahay.app.map.MapScreen
import com.sahay.app.navigate.NavTarget
import com.sahay.app.navigate.NavigateRoute
import com.sahay.app.navigate.NavigateScreen
import com.sahay.app.navigate.encode
import com.sahay.app.people.PeopleRoute
import com.sahay.app.people.PeopleScreen
import com.sahay.app.report.ReportRoute
import com.sahay.app.report.ReportScreen
import androidx.navigation.NavHostController
import com.sahay.core.contracts.ConnectivityState
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.BottomBarItem
import com.sahay.designsystem.components.ConnectivityChip
import com.sahay.designsystem.components.ConnectivityLevel
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.OfflineBanner
import com.sahay.designsystem.components.SahayBottomBar
import com.sahay.designsystem.components.SahayTopBar
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date

// Routes inside the main area (the outer graph in SahayNavHost owns onboarding and trip setup).
@Serializable data object HomeTab
@Serializable data object MapTab
@Serializable data object AlertsTab
@Serializable data object MeTab
@Serializable data class ComingSoonRoute(val titleRes: Int)

private val tabRoutes: List<Any> = listOf(HomeTab, MapTab, AlertsTab, MeTab)

@Composable
fun MainScaffold(
    onOpenTripSetup: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val destination = navController.currentBackStackEntryAsState().value?.destination
    var showSheet by rememberSaveable { mutableStateOf(false) }

    val selectedTab = tabRoutes.indexOfFirst { route -> destination?.hierarchy?.any { it.hasRoute(route::class) } == true }
    val title = when (selectedTab) {
        1 -> stringResource(R.string.nav_map)
        2 -> stringResource(R.string.nav_alerts)
        3 -> stringResource(R.string.nav_me)
        else -> stringResource(R.string.main_title_home)
    }

    Column(Modifier.fillMaxSize()) {
        SahayTopBar(
            title = title,
            actions = {
                ConnectivityChip(
                    level = connectivityLevel(state.connectivity),
                    label = connectivityLabel(state.connectivity),
                    onClick = { showSheet = true },
                )
            },
        )
        if (!state.connectivity.internet) {
            OfflineBanner(offlineBannerText(state.savedDataSinceEpochSec), Modifier.fillMaxWidth())
        }

        // The bottom bar hides itself in Emergency Mode, so give the content the nav-bar inset back.
        val contentInsets = if (state.emergency) Modifier.navigationBarsPadding() else Modifier
        NavHost(
            navController = navController,
            startDestination = HomeTab,
            modifier = Modifier.weight(1f).then(contentInsets),
        ) {
            composable<HomeTab> {
                HomeScreen(
                    onOpenAction = { titleRes -> navController.openHomeAction(titleRes) },
                    onDownloadPack = onOpenTripSetup,
                )
            }
            composable<MapTab> {
                MapScreen(
                    onGoTo = { target -> navController.navigate(NavigateRoute(target.encode())) },
                    onDownloadPack = onOpenTripSetup,
                )
            }
            composable<AlertsTab> {
                AlertsScreen(
                    onOpenAlert = { id -> navController.navigate(AlertDetailRoute(id)) },
                    onPaste = { navController.navigate(PasteAlertRoute) },
                )
            }
            composable<AlertDetailRoute> {
                AlertDetailScreen(
                    onBack = { navController.popBackStack() },
                    onGoToSafety = { navController.navigate(NavigateRoute()) },
                )
            }
            composable<PasteAlertRoute> {
                PasteAlertScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAlert = { id ->
                        navController.navigate(AlertDetailRoute(id)) { popUpTo<PasteAlertRoute> { inclusive = true } }
                    },
                )
            }
            composable<NavigateRoute> {
                NavigateScreen(
                    onBack = { navController.popBackStack() },
                    onShowLocal = { navController.navigate(ComingSoonRoute(R.string.action_show_local)) },
                )
            }
            composable<ReportRoute> { ReportScreen(onBack = { navController.popBackStack() }) }
            composable<PeopleRoute> {
                PeopleScreen(
                    onBack = { navController.popBackStack() },
                    onGoToGroup = { group -> navController.navigate(NavigateRoute(NavTarget.ToPoint(group.point).encode())) },
                )
            }
            composable<MeTab> { ComingSoon(R.string.nav_me, onBack = null) }
            composable<ComingSoonRoute> { entry ->
                ComingSoon(entry.toRoute<ComingSoonRoute>().titleRes, onBack = { navController.popBackStack() })
            }
        }

        SahayBottomBar(
            items = bottomBarItems(state.unreadAlerts),
            selectedIndex = selectedTab.coerceAtLeast(0),
            onSelect = { index ->
                navController.navigate(tabRoutes[index]) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }

    if (showSheet) WhatWorksSheet(state.connectivity, onDismiss = { showSheet = false })
}

/** Home tiles that have a real screen open it; the rest still show the placeholder. */
private fun NavHostController.openHomeAction(titleRes: Int) {
    when (titleRes) {
        R.string.action_go_safety -> navigate(NavigateRoute())
        R.string.action_report_hazard -> navigate(ReportRoute)
        R.string.action_find_people -> navigate(PeopleRoute)
        else -> navigate(ComingSoonRoute(titleRes))
    }
}

@Composable
private fun bottomBarItems(unread: Int): List<BottomBarItem> = listOf(
    BottomBarItem(stringResource(R.string.nav_home), Icons.Rounded.Home),
    BottomBarItem(stringResource(R.string.nav_map), Icons.Rounded.Map),
    BottomBarItem(
        label = stringResource(R.string.nav_alerts),
        icon = Icons.Rounded.Notifications,
        badgeCount = unread,
        contentDescription = if (unread > 0) pluralStringResource(R.plurals.nav_alerts_unread, unread, unread) else null,
    ),
    BottomBarItem(stringResource(R.string.nav_me), Icons.Rounded.Person),
)

@Composable
private fun connectivityLabel(state: ConnectivityState): String = when (connectivityLevel(state)) {
    ConnectivityLevel.Online -> stringResource(R.string.conn_online)
    ConnectivityLevel.SmsOnly -> stringResource(R.string.conn_sms_only)
    ConnectivityLevel.Offline ->
        if (state.meshPeers > 0) pluralStringResource(R.plurals.conn_offline_phones, state.meshPeers, state.meshPeers)
        else stringResource(R.string.conn_offline)
}

@Composable
private fun offlineBannerText(sinceEpochSec: Long?): String =
    if (sinceEpochSec == null) {
        stringResource(R.string.offline_banner)
    } else {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sinceEpochSec * 1000))
        stringResource(R.string.offline_banner_since, time)
    }

/** "What works right now": every capability with a word and an icon, never color alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatWorksSheet(connectivity: ConnectivityState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = SahaySpacing.screenPadding).padding(bottom = SahaySpacing.xl).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Text(
                stringResource(R.string.works_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Capability.entries.forEach { capability ->
                CapabilityRow(stringResource(capability.labelRes()), capabilityAvailable(capability, connectivity))
            }
        }
    }
}

private fun Capability.labelRes(): Int = when (this) {
    Capability.SAVED_MAP -> R.string.works_saved_map
    Capability.ALERTS_ONLINE -> R.string.works_alerts_online
    Capability.SMS -> R.string.works_sms
    Capability.NEARBY_PHONES -> R.string.works_nearby
}

@Composable
private fun CapabilityRow(label: String, available: Boolean) {
    val safe = LocalSahayColors.current.safe
    val tint = if (available) safe else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
    ) {
        Icon(if (available) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, contentDescription = null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (available) R.string.works_yes else R.string.works_no),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

/** Stand-in for screens other tasks build. Never a dead end: sub-screens offer a way back. */
@Composable
private fun ComingSoon(titleRes: Int, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Rounded.Construction,
            title = stringResource(titleRes),
            body = stringResource(R.string.soon_body),
            actionLabel = onBack?.let { stringResource(R.string.soon_back) },
            onAction = onBack,
        )
    }
}
