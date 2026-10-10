package com.sahay.app.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.sahay.R
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.PhoneFieldTexts
import com.sahay.designsystem.components.PhoneNumberField
import com.sahay.designsystem.components.StatusKind
import com.sahay.designsystem.phone.PhoneFieldValue
import com.sahay.designsystem.phone.PhoneNumbers

// ---------------------------------------------------------------- 1 Essentials

@Composable
internal fun EssentialsStep(
    state: WizardState,
    onName: (String) -> Unit,
    onNationality: (String?) -> Unit,
    onPhone: (PhoneFieldValue) -> Unit,
) {
    StepHeader(stringResource(R.string.essentials_title), stringResource(R.string.essentials_body))
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    val nameError = (state.showErrors || nameTouched) && state.nameError
    OutlinedTextField(
        value = state.name,
        onValueChange = { nameTouched = true; onName(it) },
        label = { Text(stringResource(R.string.essentials_name)) },
        isError = nameError,
        supportingText = { if (nameError) Text(stringResource(R.string.essentials_name_error)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    NationalityField(state.nationality, onNationality)
    PhoneNumberField(
        value = state.phoneValue,
        onValueChange = onPhone,
        label = stringResource(R.string.essentials_phone),
        texts = phoneFieldTexts(),
        showErrors = state.showErrors,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The words of the phone field, from string resources. */
@Composable
internal fun phoneFieldTexts(): PhoneFieldTexts {
    val button = stringResource(R.string.phone_country_button)
    val invalid = stringResource(R.string.phone_invalid_for_country)
    return PhoneFieldTexts(
        countryButton = { country, dial -> String.format(button, country, dial) },
        sheetTitle = stringResource(R.string.phone_country_title),
        searchLabel = stringResource(R.string.phone_country_search),
        popular = stringResource(R.string.phone_popular),
        allCountries = stringResource(R.string.phone_all_countries),
        noResults = stringResource(R.string.phone_no_results),
        invalid = { country -> String.format(invalid, country) },
        required = stringResource(R.string.phone_required),
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
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(country) }.padding(vertical = SahaySpacing.sm),
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
        ContactCard(index, contact, state.contactPhoneValue(contact), state.showErrors, canRemove = index > 0, onChange = { onUpdate(index, it) }, onRemove = { onRemove(index) })
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
    phoneValue: PhoneFieldValue,
    showErrors: Boolean,
    canRemove: Boolean,
    onChange: (ContactDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    val nameError = (showErrors || nameTouched) && contact.name.isBlank()
    // With Next disabled, say what is missing once the rest of the card is filled in.
    val relationError = contact.relation == null && (showErrors || (contact.name.isNotBlank() && PhoneNumbers.isValid(contact.phone)))
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
                onValueChange = { nameTouched = true; onChange(contact.copy(name = it)) },
                label = { Text(stringResource(R.string.contacts_name)) },
                isError = nameError,
                supportingText = { if (nameError) Text(stringResource(R.string.contacts_name_error)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            PhoneNumberField(
                value = phoneValue,
                onValueChange = { onChange(contact.copy(phone = it.rawPhone(), phoneRegion = it.manualRegion())) },
                label = stringResource(R.string.contacts_phone),
                texts = phoneFieldTexts(),
                required = true,
                showErrors = showErrors,
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
internal fun Relation.label() = when (this) {
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
