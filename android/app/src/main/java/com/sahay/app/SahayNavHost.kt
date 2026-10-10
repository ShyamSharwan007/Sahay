package com.sahay.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import com.sahay.designsystem.LocalReduceMotion
import com.sahay.designsystem.SahayMotion
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sahay.R
import com.sahay.app.auth.SignInNext
import com.sahay.app.auth.SignInScreen
import com.sahay.app.main.MainScaffold
import com.sahay.app.onboarding.LanguageScreen
import com.sahay.app.onboarding.StartDestination
import com.sahay.app.onboarding.StartViewModel
import com.sahay.app.onboarding.WelcomeScreen
import com.sahay.app.profile.ProfileWizardScreen
import com.sahay.app.trip.TripSetupScreen
import com.sahay.designsystem.components.LoadingState
import kotlinx.serialization.Serializable

// Type-safe routes (Navigation 2.8+).
@Serializable data object LanguageRoute
@Serializable data object WelcomeRoute
@Serializable data object SignInRoute
@Serializable data object WizardRoute
/** [fromOnboarding] = first-run setup (offers Skip, continues to Main); otherwise opened from Home/Me and returns there. */
@Serializable data class TripSetupRoute(val fromOnboarding: Boolean = false)
@Serializable data object MainRoute

/** First launch: Language → Welcome → Sign in → Wizard → Trip setup. With a complete profile: straight to Main. */
@Composable
fun SahayNavHost(startViewModel: StartViewModel = hiltViewModel()) {
    val destination by startViewModel.destination.collectAsStateWithLifecycle()
    when (destination) {
        StartDestination.LOADING -> LoadingState(stringResource(R.string.loading), Modifier.systemBarsPadding().padding(20.dp))
        StartDestination.LANGUAGE -> OnboardingGraph(start = LanguageRoute)
        StartDestination.MAIN -> OnboardingGraph(start = MainRoute)
    }
}

@Composable
private fun OnboardingGraph(start: Any, navController: NavHostController = rememberNavController()) {
    val reduceMotion = LocalReduceMotion.current
    NavHost(
        navController,
        startDestination = start,
        enterTransition = { SahayMotion.enter(reduceMotion) },
        exitTransition = { SahayMotion.exit(reduceMotion) },
        popEnterTransition = { SahayMotion.enter(reduceMotion) },
        popExitTransition = { SahayMotion.exit(reduceMotion) },
    ) {
        composable<LanguageRoute> {
            LanguageScreen(onContinue = { navController.navigate(WelcomeRoute) })
        }
        composable<WelcomeRoute> {
            WelcomeScreen(onGetStarted = { navController.navigate(SignInRoute) })
        }
        composable<SignInRoute> {
            SignInScreen(
                // Sign-in stays on the stack: Back from the first wizard step returns here (and signs out).
                onSignedIn = { next ->
                    when (next) {
                        SignInNext.PROFILE_SETUP -> navController.navigate(WizardRoute)
                        SignInNext.HOME -> navController.navigate(MainRoute) { popUpTo<LanguageRoute> { inclusive = true } }
                        SignInNext.TRIP_SETUP ->
                            navController.navigate(TripSetupRoute(fromOnboarding = true)) { popUpTo<LanguageRoute> { inclusive = true } }
                    }
                },
            )
        }
        composable<WizardRoute> {
            ProfileWizardScreen(
                onExit = { navController.popBackStack() },
                onFinished = { navController.navigate(TripSetupRoute(fromOnboarding = true)) { popUpTo<LanguageRoute> { inclusive = true } } },
            )
        }
        composable<TripSetupRoute> { entry ->
            val fromOnboarding = entry.toRoute<TripSetupRoute>().fromOnboarding
            TripSetupScreen(
                fromOnboarding = fromOnboarding,
                onDone = {
                    if (fromOnboarding) navController.navigate(MainRoute) { popUpTo<TripSetupRoute> { inclusive = true } }
                    else navController.popBackStack()
                },
                onExit = { navController.popBackStack() },
            )
        }
        composable<MainRoute> {
            MainScaffold(onOpenTripSetup = { navController.navigate(TripSetupRoute()) })
        }
    }
}
