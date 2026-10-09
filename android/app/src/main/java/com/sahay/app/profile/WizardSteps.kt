package com.sahay.app.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.sahay.R
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind

// ---------------------------------------------------------------- 1 Essentials

@Composable
internal fun EssentialsStep(
    state: WizardState,
    onName: (String) -> Unit,
    onNationality: (String?) -> Unit,
    onPhone: (String) -> Unit,
) {
    StepHeader(stringResource(R.string.essentials_title), stringResource(R.string.essentials_body))
    OutlinedTextField(
        value = state.name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.essentials_name)) },
        isError = state.showErrors && state.nameError,
        supportingText = { if (state.showErrors && state.nameError) Text(stringResource(R.string.essentials_name_error)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    NationalityField(state.nationality, onNationality)
    OutlinedTextField(
        value = state.phone,
        onValueChange = onPhone,
        label = { Text(stringResource(R.string.essentials_phone)) },
        placeholder = { Text(stringResource(R.string.essentials_phone_hint)) },
        isError = state.showErrors && state.phoneError,
        supportingText = { if (state.showErrors && state.phoneError) Text(stringResource(R.string.essentials_phone_error)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NationalityField(selectedCode: String?, onSelect: (String?) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(locale) { countryList(locale) }
    val selected = countries.firstOrNull { it.code == selectedCode }
    var open by rememberSaveable { mutableStateOf(false) }

    SahayButton(
        text = selected?.let { "${it.flag}  ${it.name}" } ?: stringResource(R.string.essentials_nationality_empty),
        onClick = { open = true },
        variant = ButtonVariant.Secondary,
        icon = Icons.Rounded.Search,
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CountryPicker(countries) { country ->
                onSelect(country.code)
                open = false
            }
        }
    }
}

@Composable
private fun CountryPicker(countries: List<Country>, onPick: (Country) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(countries, query) { countries.search(query) }
    Column(Modifier.padding(horizontal = SahaySpacing.screenPadding), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        Text(stringResource(R.string.essentials_nationality), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.essentials_nationality_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (results.isEmpty()) {
            Text(
                stringResource(R.string.essentials_nationality_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = SahaySpacing.md),
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(results, key = { it.code }) { country ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(country) }.padding(vertical = SahaySpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
                ) {
                    Text(country.flag, style = MaterialTheme.typography.titleLarge)
                    Text(country.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 2 Medical

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicalStep(
    state: WizardState,
    onBloodGroup: (String?) -> Unit,
    onAllergies: (String) -> Unit,
    onMedications: (String) -> Unit,
    onConditions: (String) -> Unit,
    onAddAllergy: (String) -> Unit,
    onAddCondition: (String) -> Unit,
) {
    StepHeader(stringResource(R.string.medical_title), stringResource(R.string.medical_body))
    Text(stringResource(R.string.medical_blood_group), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        BloodGroups.forEach { group ->
            FilterChip(selected = state.bloodGroup == group, onClick = { onBloodGroup(group) }, label = { Text(group) })
        }
        FilterChip(
            selected = state.bloodGroup == BLOOD_GROUP_UNKNOWN,
            onClick = { onBloodGroup(BLOOD_GROUP_UNKNOWN) },
            label = { Text(stringResource(R.string.medical_blood_unknown)) },
        )
    }
    OutlinedTextField(
        value = state.allergies,
        onValueChange = onAllergies,
        label = { Text(stringResource(R.string.medical_allergies)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.medications,
        onValueChange = onMedications,
        label = { Text(stringResource(R.string.medical_medications)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.conditions,
        onValueChange = onConditions,
        label = { Text(stringResource(R.string.medical_conditions)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.medical_quick_add), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        QuickChip(R.string.medical_chip_penicillin, onAddAllergy)
        QuickChip(R.string.medical_chip_nuts, onAddAllergy)
        QuickChip(R.string.medical_chip_asthma, onAddCondition)
        QuickChip(R.string.medical_chip_diabetes, onAddCondition)
    }
}

@Composable
private fun QuickChip(@StringRes label: Int, onAdd: (String) -> Unit) {
    val text = stringResource(label)
    AssistChip(
        onClick = { onAdd(text) },
        label = { Text(text) },
        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
    )
}

// ---------------------------------------------------------------- 3 Emergency contacts

@Composable
internal fun ContactsStep(
    state: WizardState,
    onUpdate: (Int, ContactDraft) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    StepHeader(stringResource(R.string.contacts_title), stringResource(R.string.contacts_body))
    state.contacts.forEachIndexed { index, contact ->
        ContactCard(index, contact, state.showErrors, canRemove = index > 0, onChange = { onUpdate(index, it) }, onRemove = { onRemove(index) })
    }
    if (state.canAddContact) {
        SahayButton(
            text = stringResource(R.string.contacts_add),
            onClick = onAdd,
            variant = ButtonVariant.Secondary,
            icon = Icons.Rounded.Add,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactCard(
    index: Int,
    contact: ContactDraft,
    showErrors: Boolean,
    canRemove: Boolean,
    onChange: (ContactDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val nameError = showErrors && contact.name.isBlank()
    val phoneError = showErrors && !isValidE164(contact.phone)
    val relationError = showErrors && contact.relation == null
    OutlinedCard(shape = SahayShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.contacts_card_title, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    SahayButton(
                        text = stringResource(R.string.action_remove),
                        onClick = onRemove,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.M,
                        icon = Icons.Rounded.Delete,
                        fullWidth = false,
                    )
                }
            }
            OutlinedTextField(
                value = contact.name,
                onValueChange = { onChange(contact.copy(name = it)) },
                label = { Text(stringResource(R.string.contacts_name)) },
                isError = nameError,
                supportingText = { if (nameError) Text(stringResource(R.string.contacts_name_error)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = contact.phone,
                onValueChange = { onChange(contact.copy(phone = it)) },
                label = { Text(stringResource(R.string.contacts_phone)) },
                placeholder = { Text(stringResource(R.string.essentials_phone_hint)) },
                isError = phoneError,
                supportingText = { if (phoneError) Text(stringResource(R.string.contacts_phone_error)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.contacts_relation), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                Relation.entries.forEach { relation ->
                    FilterChip(
                        selected = contact.relation == relation,
                        onClick = { onChange(contact.copy(relation = relation)) },
                        label = { Text(stringResource(relation.label())) },
                    )
                }
            }
            if (relationError) {
                Text(stringResource(R.string.contacts_relation_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@StringRes
private fun Relation.label() = when (this) {
    Relation.PARENT -> R.string.relation_parent
    Relation.PARTNER -> R.string.relation_partner
    Relation.SIBLING -> R.string.relation_sibling
    Relation.FRIEND -> R.string.relation_friend
    Relation.OTHER -> R.string.relation_other
}

// ---------------------------------------------------------------- 4 Where you stay

@Composable
internal fun StayStep(state: WizardState, onHotelName: (String) -> Unit, onHotelAddress: (String) -> Unit) {
    StepHeader(stringResource(R.string.stay_title), stringResource(R.string.stay_body))
    OutlinedTextField(
        value = state.hotelName,
        onValueChange = onHotelName,
        label = { Text(stringResource(R.string.stay_hotel_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.hotelAddress,
        onValueChange = onHotelAddress,
        label = { Text(stringResource(R.string.stay_hotel_address)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- 5 Privacy

@Composable
internal fun PrivacyStep(state: WizardState, onGroupFinder: (Boolean) -> Unit) {
    StepHeader(stringResource(R.string.privacy_title), stringResource(R.string.privacy_group_finder_body))
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = state.groupFinderOptIn, role = Role.Switch, onValueChange = onGroupFinder)
            .padding(vertical = SahaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        Text(stringResource(R.string.privacy_group_finder), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = state.groupFinderOptIn, onCheckedChange = null)
    }
    StatusCard(kind = StatusKind.Info, title = stringResource(R.string.privacy_medical_note))
}
