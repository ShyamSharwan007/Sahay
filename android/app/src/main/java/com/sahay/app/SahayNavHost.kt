package com.sahay.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sahay.R
import com.sahay.app.auth.SignInScreen
import com.sahay.app.onboarding.LanguageScreen
import com.sahay.app.onboarding.StartDestination
import com.sahay.app.onboarding.StartViewModel
import com.sahay.app.onboarding.WelcomeScreen
import com.sahay.app.profile.ProfileWizardScreen
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.LoadingState
import kotlinx.serialization.Serializable

// Type-safe routes (Navigation 2.8+).
@Serializable data object LanguageRoute
@Serializable data object WelcomeRoute
@Serializable data object SignInRoute
@Serializable data object WizardRoute
@Serializable data object TripSetupRoute
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
    NavHost(navController, startDestination = start) {
        composable<LanguageRoute> {
            LanguageScreen(onContinue = { navController.navigate(WelcomeRoute) })
        }
        composable<WelcomeRoute> {
            WelcomeScreen(onGetStarted = { navController.navigate(SignInRoute) })
        }
        composable<SignInRoute> {
            SignInScreen(
                // Pop sign-in so Back from the wizard returns to Welcome instead of bouncing forward again.
                onSignedIn = { navController.navigate(WizardRoute) { popUpTo<SignInRoute> { inclusive = true } } },
            )
        }
        composable<WizardRoute> {
            ProfileWizardScreen(
                onExit = { navController.popBackStack() },
                onFinished = { navController.navigate(TripSetupRoute) { popUpTo<LanguageRoute> { inclusive = true } } },
            )
        }
        composable<TripSetupRoute> {
            Placeholder(R.string.placeholder_trip_title, R.string.placeholder_trip_body)
        }
        composable<MainRoute> {
            Placeholder(R.string.placeholder_main_title, R.string.placeholder_main_body)
        }
    }
}

/** Temporary screen for routes other tasks will build. */
@Composable
private fun Placeholder(titleRes: Int, bodyRes: Int) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(icon = Icons.Rounded.Construction, title = stringResource(titleRes), body = stringResource(bodyRes))
    }
}
