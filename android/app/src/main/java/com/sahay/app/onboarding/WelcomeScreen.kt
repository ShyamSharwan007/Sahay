package com.sahay.app.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sahay.R
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.SahayButton

private data class Slide(@DrawableRes val art: Int, @StringRes val title: Int, @StringRes val body: Int)

private val slides = listOf(
    Slide(R.drawable.ill_offline, R.string.welcome_slide1_title, R.string.welcome_slide1_body),
    Slide(R.drawable.ill_alerts, R.string.welcome_slide2_title, R.string.welcome_slide2_body),
    Slide(R.drawable.ill_help, R.string.welcome_slide3_title, R.string.welcome_slide3_body),
)

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val pager = rememberPagerState(pageCount = { slides.size })
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = SahaySpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            SlideContent(slides[page])
        }
        PageDots(
            count = slides.size,
            current = pager.currentPage,
            description = stringResource(R.string.welcome_page_indicator, pager.currentPage + 1, slides.size),
        )
        SahayButton(
            text = stringResource(R.string.welcome_get_started),
            onClick = onGetStarted,
            icon = Icons.Rounded.ArrowForward,
            modifier = Modifier.padding(vertical = SahaySpacing.xl),
        )
    }
}

@Composable
private fun SlideContent(slide: Slide) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.xl, Alignment.CenterVertically),
    ) {
        // Decorative: the title below says the same thing.
        Image(painterResource(slide.art), contentDescription = null, modifier = Modifier.size(220.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
            Text(stringResource(slide.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(slide.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, description: String) {
    Row(
        Modifier.padding(top = SahaySpacing.md).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
    ) {
        repeat(count) { index ->
            val active = index == current
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .size(width = if (active) 24.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            ) {}
        }
    }
}
