package com.sahay.app.local

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.common.safely
import com.sahay.app.navigate.NavTarget
import com.sahay.app.navigate.parseNavTarget
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.Phrase
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.SahayConfig
import com.sahay.core.contracts.ShelterStatus
import com.sahay.core.contracts.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val HEADLINE_PHRASE_ID = "tourist_need_help"
private const val FIX_TIMEOUT_MS = 3_000L

/** One phrasebook entry: Tamil to show, the user's own language for them to read. */
data class PhraseRow(val id: String, val category: String, val tamil: String, val own: String, val icon: String?)

data class LocalCardUi(
    val loading: Boolean = true,
    /** Null when the trip pack (and so the Tamil text) isn't on the phone. */
    val tamilHeadline: String? = null,
    val ownHeadline: String? = null,
    val destination: Poi? = null,
    val hotelName: String? = null,
    val hotelAddress: String? = null,
    val bloodGroup: String? = null,
    val allergies: String? = null,
    val phrases: List<PhraseRow> = emptyList(),
)

/** Joins the Tamil phrases with the user's language by id. A phrase missing in the user's language keeps an empty line. */
fun joinPhrases(tamil: List<Phrase>, own: List<Phrase>): List<PhraseRow> {
    val ownById = own.associateBy { it.id }
    return tamil.map { PhraseRow(it.id, it.category, it.text, ownById[it.id]?.text.orEmpty(), it.icon) }
}

@HiltViewModel
class ShowLocalViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val packs: PackRepository,
    profiles: ProfileStore,
    private val location: LocationProvider,
) : ViewModel() {

    // Type-safe routes store their arguments under the property name.
    private val target: NavTarget = parseNavTarget(savedState.get<String>("target"))

    private val _state = MutableStateFlow(LocalCardUi())
    val state: StateFlow<LocalCardUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Reload when the profile is edited or the pack is downloaded or deleted.
            combine(profiles.profile, packs.activePack) { profile, _ -> profile }
                .collectLatest { profile ->
                    _state.value = load(profile)
                    // Finding the nearest shelter can wait for a GPS fix, so the card shows first.
                    val destination = safely { resolveDestination() }
                    _state.update { it.copy(destination = destination) }
                }
        }
    }

    private suspend fun load(profile: UserProfile?): LocalCardUi {
        val language = profile?.language ?: "en"
        val tamil = safely { packs.phrases(SahayConfig.LOCAL_LANGUAGE) }.orEmpty()
        val own = safely { packs.phrases(language).ifEmpty { packs.phrases("en") } }.orEmpty()
        return LocalCardUi(
            loading = false,
            tamilHeadline = tamil.firstOrNull { it.id == HEADLINE_PHRASE_ID }?.text,
            ownHeadline = own.firstOrNull { it.id == HEADLINE_PHRASE_ID }?.text,
            hotelName = profile?.hotelName,
            hotelAddress = profile?.hotelAddress,
            bloodGroup = profile?.bloodGroup,
            allergies = profile?.allergies,
            phrases = joinPhrases(tamil, own),
        )
    }

    /** The place from the route, or the nearest shelter that isn't full or closed. */
    private suspend fun resolveDestination(): Poi? = when (val t = target) {
        is NavTarget.ToPoi -> packs.pois().firstOrNull { it.id == t.poiId }
        is NavTarget.ToPoint -> null // a bare spot has no name to show
        NavTarget.NearestSafe -> {
            val fix = location.lastFix.value ?: location.currentFix(FIX_TIMEOUT_MS)
            fix?.let { nearestOpenShelter(it.point) }
        }
    }

    private suspend fun nearestOpenShelter(from: GeoPoint): Poi? =
        listOf(PoiType.SHELTER, PoiType.CANDIDATE_SHELTER)
            .flatMap { packs.nearestPois(from, it, limit = 3) }
            .filter { (poi, _) -> poi.status != ShelterStatus.FULL && poi.status != ShelterStatus.CLOSED }
            .minByOrNull { (_, metres) -> metres }
            ?.first
}
