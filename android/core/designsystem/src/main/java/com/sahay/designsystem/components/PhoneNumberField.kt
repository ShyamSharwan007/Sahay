package com.sahay.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.phone.PhoneCountry
import com.sahay.designsystem.phone.PhoneFieldValue
import com.sahay.designsystem.phone.PhoneNumbers
import java.util.Locale

/** All user-visible words of [PhoneNumberField], supplied by the caller from string resources. */
class PhoneFieldTexts(
    /** "Country code: India +91" */
    val countryButton: (country: String, dialCode: String) -> String,
    val sheetTitle: String,
    val searchLabel: String,
    val popular: String,
    val allCountries: String,
    val noResults: String,
    /** "This number doesn't look right for India" */
    val invalid: (country: String) -> String,
    val required: String,
)

/**
 * Phone input: a country-code button (flag + dial code, opens a searchable list) and the national number.
 * Digits only, formatted while typing. Pasting or typing a full number that starts with "+" or "00" picks the
 * country automatically. [onValueChange] reports the country, the digits and whether the user chose the country.
 *
 * Errors show once the user has left the field, or when [showErrors] is true.
 */
@Composable
fun PhoneNumberField(
    value: PhoneFieldValue,
    onValueChange: (PhoneFieldValue) -> Unit,
    label: String,
    texts: PhoneFieldTexts,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    showErrors: Boolean = false,
) {
    val context = LocalContext.current
    remember { PhoneNumbers.init(context) }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val countries = remember(locale) { PhoneNumbers.countries(locale) }
    val country = countries.firstOrNull { it.region == value.region }
        ?: PhoneCountry(value.region, PhoneNumbers.regionName(value.region, locale), PhoneNumbers.dialCode(value.region) ?: 0)

    // A full number is being typed ("+91 9..."): shown as typed until it can be split into country and digits.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var hadFocus by remember { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    val raw = PhoneNumbers.toRawPhone(value.region, value.national)
    val problem = when {
        pending != null -> null // still typing a full number
        value.national.isEmpty() -> if (required && showErrors) texts.required else null
        !PhoneNumbers.isValid(raw, value.region) && (touched || showErrors) -> texts.invalid(country.name)
        else -> null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalAlignment = Alignment.Top) {
            CountryButton(country, texts.countryButton(country.name, country.dialText)) { sheetOpen = true }
            OutlinedTextField(
                value = pending ?: value.national,
                onValueChange = { text ->
                    when {
                        PhoneNumbers.looksInternational(text) -> {
                            val found = PhoneNumbers.detect(text)
                            if (found != null) {
                                pending = null
                                onValueChange(PhoneFieldValue(found.region, found.national.take(PhoneNumbers.MAX_NATIONAL_DIGITS), manual = true))
                            } else {
                                pending = text.filter { it == '+' || it == ' ' || Character.digit(it, 10) >= 0 }.take(MAX_PENDING_CHARS)
                            }
                        }
                        else -> {
                            pending = null
                            onValueChange(value.copy(national = PhoneNumbers.sanitize(text).take(PhoneNumbers.MAX_NATIONAL_DIGITS)))
                        }
                    }
                },
                label = { Text(label) },
                isError = problem != null,
                singleLine = true,
                // Digits always read left to right, also in Arabic.
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                visualTransformation = if (pending == null) NationalFormat(value.region) else VisualTransformation.None,
                modifier = Modifier.weight(1f).onFocusChanged { focus ->
                    if (focus.isFocused) hadFocus = true else if (hadFocus) touched = true
                },
            )
        }
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = SahaySpacing.md),
            )
        }
    }

    if (sheetOpen) {
        CountrySheet(
            countries = countries,
            selectedRegion = value.region,
            texts = texts,
            onPick = { picked ->
                sheetOpen = false
                pending = null
                onValueChange(PhoneFieldValue(picked.region, value.national, manual = true))
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

private const val MAX_PENDING_CHARS = 24

/** Flag + dial code, at least 56 dp tall like the text field next to it. */
@Composable
private fun CountryButton(country: PhoneCountry, description: String, onClick: () -> Unit) {
    Surface(
        shape = SahayShapes.button,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .heightIn(min = 56.dp)
            .semantics { contentDescription = description; role = Role.Button }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = SahaySpacing.sm, end = SahaySpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${country.flag} ${country.dialText}".trim(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountrySheet(
    countries: List<PhoneCountry>,
    selectedRegion: String,
    texts: PhoneFieldTexts,
    onPick: (PhoneCountry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(countries, query) { countries.matching(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = SahaySpacing.screenPadding).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
        ) {
            Text(texts.sheetTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(texts.searchLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (results.isEmpty()) {
                Text(
                    texts.noResults,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = SahaySpacing.md),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                val showSections = query.isBlank()
                val popularCount = if (showSections) results.count { it.region in PhoneNumbers.POPULAR_REGIONS } else 0
                if (showSections && popularCount > 0) item(key = "popular") { SectionLabel(texts.popular) }
                items(results, key = { it.region }) { country ->
                    Column {
                        if (showSections && country === results.getOrNull(popularCount)) SectionLabel(texts.allCountries)
                        CountryRow(country, country.region == selectedRegion) { onPick(country) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = SahaySpacing.xs).semantics { heading() },
    )
}

@Composable
private fun CountryRow(country: PhoneCountry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
    ) {
        Text(country.flag, style = MaterialTheme.typography.titleLarge)
        Text(country.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(country.dialText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Case-insensitive match on the country name, its ISO code or its dial code ("+49", "49"). A blank query keeps everything. */
fun List<PhoneCountry>.matching(query: String): List<PhoneCountry> {
    val q = query.trim()
    if (q.isEmpty()) return this
    val codeQuery = q.removePrefix("+").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    return filter { c ->
        c.name.contains(q, ignoreCase = true) ||
            c.region.equals(q, ignoreCase = true) ||
            (codeQuery != null && c.dialCode.toString().startsWith(codeQuery))
    }
}

/** Shows the typed digits in the country's style while the stored text stays plain digits. */
private class NationalFormat(private val region: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = PhoneNumbers.formatNational(region, text.text)
        return TransformedText(AnnotatedString(formatted), DigitOffsets(formatted, text.text.length))
    }

    override fun equals(other: Any?) = other is NationalFormat && other.region == region
    override fun hashCode() = region.hashCode()
}

/** Maps cursor positions between the plain digits and their formatted text by counting digits. */
private class DigitOffsets(private val formatted: String, private val digitCount: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= digitCount) return formatted.length
        var seen = 0
        formatted.forEachIndexed { index, c ->
            if (c.isDigit() && ++seen == offset) return index + 1
        }
        return formatted.length
    }

    override fun transformedToOriginal(offset: Int): Int =
        formatted.take(offset.coerceIn(0, formatted.length)).count { it.isDigit() }.coerceAtMost(digitCount)
}
