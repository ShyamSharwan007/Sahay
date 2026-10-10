package com.sahay.app.common

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalPolice
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Water
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sahay.R
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.ShelterStatus
import com.sahay.core.contracts.TrustLabel
import com.sahay.designsystem.components.StatusChip
import com.sahay.designsystem.components.StatusKind

// ---- places

fun PoiType.icon(): ImageVector = when (this) {
    PoiType.SHELTER -> Icons.Rounded.Home
    PoiType.CANDIDATE_SHELTER -> Icons.Rounded.HomeWork
    PoiType.HOSPITAL -> Icons.Rounded.LocalHospital
    PoiType.POLICE -> Icons.Rounded.LocalPolice
}

/** DESIGN §1.6: candidate shelters are always called "Possible shelter". */
@StringRes
fun PoiType.labelRes(): Int = when (this) {
    PoiType.SHELTER -> R.string.poi_type_shelter
    PoiType.CANDIDATE_SHELTER -> R.string.poi_type_possible_shelter
    PoiType.HOSPITAL -> R.string.poi_type_hospital
    PoiType.POLICE -> R.string.poi_type_police
}

fun PoiType.isShelter(): Boolean = this == PoiType.SHELTER || this == PoiType.CANDIDATE_SHELTER

/** Open / Full / Closed / Status unknown, each with an icon and a word. */
@Composable
fun ShelterStatusChip(status: ShelterStatus, modifier: Modifier = Modifier) {
    when (status) {
        ShelterStatus.OPEN -> StatusChip(StatusKind.Safe, stringResource(R.string.shelter_open), modifier)
        ShelterStatus.FULL -> StatusChip(StatusKind.Danger, stringResource(R.string.shelter_full), modifier)
        ShelterStatus.CLOSED -> StatusChip(StatusKind.Danger, stringResource(R.string.shelter_closed), modifier)
        ShelterStatus.UNKNOWN -> StatusChip(
            StatusKind.Offline, stringResource(R.string.shelter_unknown), modifier, icon = Icons.AutoMirrored.Rounded.HelpOutline,
        )
    }
}

// ---- hazard reports

fun HazardType.icon(): ImageVector = when (this) {
    HazardType.FLOOD -> Icons.Rounded.Water
    HazardType.ROAD_BLOCKED -> Icons.Rounded.Block
    HazardType.SHELTER_FULL -> Icons.Rounded.Group
    HazardType.SHELTER_OPEN -> Icons.Rounded.Home
    HazardType.POWER_LINE -> Icons.Rounded.ElectricalServices
    HazardType.LANDSLIDE -> Icons.Rounded.Terrain
    HazardType.OTHER -> Icons.Rounded.MoreHoriz
}

@StringRes
fun HazardType.labelRes(): Int = when (this) {
    HazardType.FLOOD -> R.string.hazard_flood
    HazardType.ROAD_BLOCKED -> R.string.hazard_road_blocked
    HazardType.SHELTER_FULL -> R.string.hazard_shelter_full
    HazardType.SHELTER_OPEN -> R.string.hazard_shelter_open
    HazardType.POWER_LINE -> R.string.hazard_power_line
    HazardType.LANDSLIDE -> R.string.hazard_landslide
    HazardType.OTHER -> R.string.hazard_other
}

@StringRes
fun TrustLabel.labelRes(): Int = when (this) {
    TrustLabel.VERIFIED -> R.string.trust_verified
    TrustLabel.LIKELY -> R.string.trust_likely
    TrustLabel.UNCONFIRMED -> R.string.trust_unconfirmed
}

// ---- groups

@StringRes
fun GroupStatus.labelRes(): Int = when (this) {
    GroupStatus.AT_SHELTER -> R.string.group_at_shelter
    GroupStatus.SAFE_AREA -> R.string.group_safe_area
    GroupStatus.RISK_ZONE -> R.string.group_risk
}

/** Only groups that are not in a flood-risk zone may be offered as a destination. */
fun PeopleGroup.canGoTo(): Boolean = status != GroupStatus.RISK_ZONE

@Composable
fun GroupStatusChip(status: GroupStatus, modifier: Modifier = Modifier) {
    val kind = when (status) {
        GroupStatus.AT_SHELTER -> StatusKind.Safe
        GroupStatus.SAFE_AREA -> StatusKind.Info
        GroupStatus.RISK_ZONE -> StatusKind.Danger
    }
    StatusChip(kind, stringResource(status.labelRes()), modifier)
}

@Composable
fun groupPeopleText(size: Int): String = pluralStringResource(R.plurals.group_people, size, size)

/** "7 people · At a shelter · 400 m"; the distance part is dropped when there is no location fix. */
@Composable
fun groupSummaryText(group: PeopleGroup, distanceM: Double?): String {
    val people = groupPeopleText(group.size)
    val status = stringResource(group.status.labelRes())
    return if (distanceM == null) {
        "$people · $status"
    } else {
        stringResource(R.string.group_summary, people, status, distanceText(distanceM))
    }
}
