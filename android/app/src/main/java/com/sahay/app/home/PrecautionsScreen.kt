package com.sahay.app.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahay.R
import com.sahay.app.common.PrecautionCards
import com.sahay.app.common.SubScreenHeader
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.Precaution
import com.sahay.core.contracts.ProfileStore
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object PrecautionsRoute

data class PrecautionsUiState(val precautions: List<Precaution> = emptyList(), val language: String = "en")

@HiltViewModel
class PrecautionsViewModel @Inject constructor(packs: PackRepository, profiles: ProfileStore) : ViewModel() {
    val state: StateFlow<PrecautionsUiState> = combine(packs.activePack, profiles.profile) { pack, profile ->
        PrecautionsUiState(pack?.precautions.orEmpty(), profile?.language ?: "en")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrecautionsUiState())
}

/** Every precaution of the trip pack, most severe first. */
@Composable
fun PrecautionsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: PrecautionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.home_precautions), onBack)
        if (state.precautions.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Shield,
                title = stringResource(R.string.precautions_empty_title),
                body = stringResource(R.string.precautions_empty_body),
                modifier = Modifier.padding(SahaySpacing.screenPadding),
            )
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            ) {
                PrecautionCards(state.precautions, state.language)
            }
        }
    }
}
