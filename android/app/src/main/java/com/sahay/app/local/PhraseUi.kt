package com.sahay.app.local

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalPolice
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PersonalInjury
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WrongLocation
import androidx.compose.ui.graphics.vector.ImageVector
import com.sahay.R

/** Icon names come from the trip pack (Material Symbol names). Unknown names get a neutral icon. */
fun phraseIcon(name: String?): ImageVector = when (name) {
    "pan_tool" -> Icons.Rounded.PanTool
    "home" -> Icons.Rounded.Home
    "location_on" -> Icons.Rounded.LocationOn
    "local_hospital" -> Icons.Rounded.LocalHospital
    "local_police" -> Icons.Rounded.LocalPolice
    "emergency" -> Icons.Rounded.Emergency
    "personal_injury" -> Icons.Rounded.PersonalInjury
    "medication" -> Icons.Rounded.Medication
    "water_drop" -> Icons.Rounded.WaterDrop
    "battery_charging_full" -> Icons.Rounded.BatteryChargingFull
    "warning" -> Icons.Rounded.Warning
    "terrain" -> Icons.Rounded.Terrain
    "hotel" -> Icons.Rounded.Hotel
    "wrong_location" -> Icons.Rounded.WrongLocation
    "favorite" -> Icons.Rounded.Favorite
    "check", "check_circle" -> Icons.Rounded.CheckCircle
    "cancel" -> Icons.Rounded.Cancel
    else -> Icons.Rounded.Translate
}

/** Label for a phrase category (CONTRACTS §8.2); null for categories this build doesn't know. */
@StringRes
fun categoryLabel(category: String): Int? = when (category) {
    "emergency" -> R.string.phrase_cat_emergency
    "directions" -> R.string.phrase_cat_directions
    "medical" -> R.string.phrase_cat_medical
    "basic" -> R.string.phrase_cat_basic
    else -> null
}
