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
import androidx.compose.material.icons.rounded.Emergency
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
import com.sahay.designsystem.LocalReduceMotion
import com.sahay.designsystem.SahayMotion
import androidx.compose.runtime.LaunchedEffect
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
import com.sahay.R
import com.sahay.app.alerts.AlertDetailRoute
import com.sahay.app.alerts.AlertDetailScreen
import com.sahay.app.alerts.AlertsScreen
import com.sahay.app.alerts.PasteAlertRoute
import com.sahay.app.alerts.PasteAlertScreen
import com.sahay.app.deeplink.DeepLinkTarget
import com.sahay.app.emergency.EmergencyScreen
import com.sahay.app.home.HomeScreen
import com.sahay.app.local.ShowLocalRoute
import com.sahay.app.local.ShowLocalScreen
import com.sahay.app.map.MapScreen
import com.sahay.app.me.AboutRoute
import com.sahay.app.me.AboutScreen
import com.sahay.app.me.GalleryRoute
import com.sahay.designsystem.DesignGalleryScreen
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.me.EditProfileRoute
import com.sahay.app.me.MeScreen
import com.sahay.app.me.MedicalCardRoute
import com.sahay.app.me.MedicalCardScreen
import com.sahay.app.me.PrivacyRoute
import com.sahay.app.me.PrivacyScreen
import com.sahay.app.me.TripPackRoute
import com.sahay.app.me.TripPackScreen
import com.sahay.app.navigate.NavTarget
import com.sahay.app.navigate.NavigateRoute
import com.sahay.app.navigate.NavigateScreen
import com.sahay.app.navigate.encode
import com.sahay.app.people.PeopleRoute
import com.sahay.app.people.PeopleScreen
import com.sahay.app.profile.ProfileWizardScreen
import com.sahay.app.profile.WizardStep
import com.sahay.app.report.ReportRoute
import com.sahay.app.report.ReportScreen
import com.sahay.app.sos.SosRoute
import com.sahay.app.sos.SosScreen
import androidx.navigation.NavHostController
import com.sahay.core.contracts.ConnectivityState
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.BottomBarItem
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.ConnectivityChip
import com.sahay.designsystem.components.OfflineBanner
import com.sahay.designsystem.components.SahayBottomBar
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayTopBar
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date

// Routes inside the main area (the outer graph in SahayNavHost owns onboarding and trip setup).
@Serializable data object HomeTab
@Serializable data object MapTab
@Serializable data object AlertsTab
@Serializable data object MeTab
@Serializable data object EmergencyRoute

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
    val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()

    // Emergency Mode on (from a tile, the top bar or a notification) shows its screen; off removes it.
    LaunchedEffect(state.emergency) {
        if (state.emergency) navController.showEmergency() else navController.popBackStack<EmergencyRoute>(inclusive = true)
    }
    // Notification links open once the main screens exist; unknown links were already dropped.
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        when (link) {
            DeepLinkTarget.NavigateSafe -> navController.navigate(NavigateRoute()) { launchSingleTop = true }
            DeepLinkTarget.Groups -> navController.navigate(PeopleRoute) { launchSingleTop = true }
            is DeepLinkTarget.Alert -> navController.navigate(AlertDetailRoute(link.id)) { launchSingleTop = true }
            DeepLinkTarget.Emergency -> if (state.emergency) navController.showEmergency() else viewModel.activateEmergency()
        }
        viewModel.consumeLink()
    }
    val onEmergencyScreen = destination?.hasRoute(EmergencyRoute::class) == true
    // These full-screen screens pad the system bars themselves, so the normal bars step aside.
    val selfInsets = destination?.let { it.hasRoute(ShowLocalRoute::class) || it.hasRoute(EditProfileRoute::class) } == true

    val selectedTab = tabRoutes.indexOfFirst { route -> destination?.hierarchy?.any { it.hasRoute(route::class) } == true }
    val title = when (selectedTab) {
        1 -> stringResource(R.string.nav_map)
        2 -> stringResource(R.string.nav_alerts)
        3 -> stringResource(R.string.nav_me)
        else -> stringResource(R.string.main_title_home)
    }

    Column(Modifier.fillMaxSize()) {
        // The Emergency screen has its own header (chip and battery), so the top bar steps aside.
        if (!onEmergencyScreen && !selfInsets) {
            SahayTopBar(
                title = title,
                actions = {
                    if (!state.emergency) {
                        SahayButton(
                            text = stringResource(R.string.action_emergency_short),
                            onClick = viewModel::activateEmergency,
                            variant = ButtonVariant.Danger,
                            size = ButtonSize.M,
                            icon = Icons.Rounded.Emergency,
                            fullWidth = false,
                            compact = true,
                        )
                    }
                    ConnectivityChip(
                        compact = true,
                        level = connectivityLevel(state.connectivity),
                        label = connectivityLabel(state.connectivity),
                        onClick = { showSheet = true },
                    )
                },
            )
            if (!state.connectivity.internet) {
                OfflineBanner(offlineBannerText(state.savedDataSinceEpochSec), Modifier.fillMaxWidth())
            }
        }

        // The bottom bar hides itself in Emergency Mode, so give the content the nav-bar inset back.
        val contentInsets = if (state.emergency && !selfInsets) Modifier.navigationBarsPadding() else Modifier
        val reduceMotion = LocalReduceMotion.current
        NavHost(
            navController = navController,
            startDestination = HomeTab,
            modifier = Modifier.weight(1f).then(contentInsets),
            enterTransition = { SahayMotion.enter(reduceMotion) },
            exitTransition = { SahayMotion.exit(reduceMotion) },
            popEnterTransition = { SahayMotion.enter(reduceMotion) },
            popExitTransition = { SahayMotion.exit(reduceMotion) },
        ) {
            composable<HomeTab> {
                HomeScreen(
                    onOpenAction = { titleRes ->
                        if (titleRes == R.string.action_emergency_mode) viewModel.activateEmergency()
                        else navController.openHomeAction(titleRes)
                    },
                    onDownloadPack = onOpenTripSetup,
                )
            }
            composable<EmergencyRoute> {
                EmergencyScreen(
                    connectivity = state.connectivity,
                    onConnectivityClick = { showSheet = true },
                    onGoToSafety = { navController.navigate(NavigateRoute()) },
                    onSos = { navController.navigate(SosRoute) },
                    onShowLocal = { navController.navigate(ShowLocalRoute()) },
                    onFindPeople = { navController.navigate(PeopleRoute) },
                    onExit = viewModel::deactivateEmergency,
                )
            }
            composable<SosRoute> {
                SosScreen(
                    onBack = { navController.popBackStack() },
                    onEditContacts = { navController.navigate(EditProfileRoute(WizardStep.CONTACTS.ordinal)) },
                )
            }
            composable<ShowLocalRoute> {
                ShowLocalScreen(
                    onBack = { navController.popBackStack() },
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
                    onShowLocal = { destination ->
                        val target = destination?.let { NavTarget.ToPoi(it.id).encode() }
                        navController.navigate(ShowLocalRoute(target))
                    },
                )
            }
            composable<ReportRoute> { ReportScreen(onBack = { navController.popBackStack() }) }
            composable<PeopleRoute> {
                PeopleScreen(
                    onBack = { navController.popBackStack() },
                    onGoToGroup = { group -> navController.navigate(NavigateRoute(NavTarget.ToPoint(group.point).encode())) },
                )
            }
            composable<MeTab> {
                MeScreen(
                    onEditProfile = { navController.navigate(EditProfileRoute()) },
                    onMedicalCard = { navController.navigate(MedicalCardRoute) },
                    onTripPack = { navController.navigate(TripPackRoute) },
                    onPrivacy = { navController.navigate(PrivacyRoute) },
                    onAbout = { navController.navigate(AboutRoute) },
                )
            }
            composable<EditProfileRoute> {
                ProfileWizardScreen(
                    onExit = { navController.popBackStack() },
                    onFinished = { navController.popBackStack() },
                )
            }
            composable<MedicalCardRoute> {
                MedicalCardScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(EditProfileRoute(WizardStep.MEDICAL.ordinal)) },
                )
            }
            composable<TripPackRoute> {
                TripPackScreen(onBack = { navController.popBackStack() }, onUpdate = onOpenTripSetup)
            }
            composable<PrivacyRoute> { PrivacyScreen(onBack = { navController.popBackStack() }) }
            composable<AboutRoute> {
                AboutScreen(onBack = { navController.popBackStack() }, onOpenGallery = { navController.navigate(GalleryRoute) })
            }
            // Hidden developer screen, reached by tapping the version in About 7 times.
            composable<GalleryRoute> {
                Column(Modifier.fillMaxSize()) {
                    SubScreenHeader("Design gallery", onBack = { navController.popBackStack() })
                    DesignGalleryScreen(Modifier.weight(1f))
                }
            }
        }

        if (!selfInsets) {
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
    }

    if (showSheet) WhatWorksSheet(state.connectivity, onDismiss = { showSheet = false })
}

/** Opens the screen behind a Home tile (Emergency Mode is handled by the caller). */
private fun NavHostController.openHomeAction(titleRes: Int) {
    when (titleRes) {
        R.string.action_go_safety -> navigate(NavigateRoute())
        R.string.action_sos -> navigate(SosRoute)
        R.string.action_show_local -> navigate(ShowLocalRoute())
        R.string.action_report_hazard -> navigate(ReportRoute)
        R.string.action_find_people -> navigate(PeopleRoute)
    }
}

/** Goes to the Emergency screen: back to it if it is already in the stack, otherwise on top. */
private fun NavHostController.showEmergency() {
    if (!popBackStack<EmergencyRoute>(inclusive = false)) navigate(EmergencyRoute) { launchSingleTop = true }
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
