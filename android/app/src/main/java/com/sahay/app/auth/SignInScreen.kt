package com.sahay.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind

@Composable
fun SignInScreen(onSignedIn: (SignInNext) -> Unit, viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Signed in (earlier run, or just now): move on once.
    LaunchedEffect(state.next) {
        state.next?.let { next ->
            onSignedIn(next)
            viewModel.consumeNext()
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.md, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.signin_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.signin_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        state.problem?.let { problem -> ProblemCard(problem) }
        SahayButton(
            text = stringResource(R.string.signin_google),
            onClick = { viewModel.signInWithGoogle(context) },
            icon = Icons.Rounded.AccountCircle,
            loading = state.loading,
            modifier = Modifier.padding(top = SahaySpacing.xs),
        )
        SahayButton(
            text = stringResource(R.string.signin_guest),
            onClick = viewModel::continueAsGuest,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.M,
            icon = Icons.Rounded.Person,
            enabled = !state.loading,
            fullWidth = false,
        )
    }
}

@Composable
private fun ProblemCard(problem: SignInProblem) {
    val (title, body) = when (problem) {
        SignInProblem.NO_INTERNET -> R.string.signin_error_no_internet_title to R.string.signin_error_no_internet_body
        SignInProblem.NO_GOOGLE_ACCOUNT -> R.string.signin_error_no_account_title to R.string.signin_error_no_account_body
        SignInProblem.FAILED -> R.string.signin_error_failed_title to R.string.signin_error_failed_body
    }
    StatusCard(
        kind = if (problem == SignInProblem.NO_INTERNET) StatusKind.Offline else StatusKind.Warning,
        title = stringResource(title),
        body = stringResource(body),
    )
}
