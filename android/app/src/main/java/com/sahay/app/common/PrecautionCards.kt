package com.sahay.app.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sahay.core.contracts.Precaution
import com.sahay.core.contracts.pick
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.statusKindForSeverity

/** Precautions as status cards, most severe first. Text falls back to English via [pick]. */
@Composable
fun PrecautionCards(precautions: List<Precaution>, language: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        precautions.sortedByDescending { it.severity }.forEach { p ->
            StatusCard(
                kind = statusKindForSeverity(p.severity),
                title = p.title.pick(language),
                body = p.body.pick(language).ifBlank { null },
            )
        }
    }
}
