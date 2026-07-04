/*
 *   Copyright 2020–2026 PhotoZ
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package onlasdan.gallery.onboarding.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import onlasdan.gallery.R
import onlasdan.gallery.ui.theme.AppTheme

/**
 * Sprint 10+ / M1 — Compose migration of the onboarding screen.
 *
 * Replaces fragment_onboarding.xml + ViewPager + 3 slide layouts with a
 * pure Compose [HorizontalPager]. Each slide is a composable — no Fragment
 * per slide, no ViewPagerAdapter.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@Composable
fun OnBoardingScreen(
    onFinish: () -> Unit,
) {
    val pages = listOf(
        OnBoardingPage(
            titleRes = R.string.onboarding_slide1_title,
            descriptionRes = R.string.onboarding_slide1_description,
            iconRes = null, // Slide 1 has no icon (welcome text only)
        ),
        OnBoardingPage(
            titleRes = R.string.onboarding_slide2_title,
            descriptionRes = R.string.onboarding_slide2_description,
            iconRes = R.drawable.ic_password_colored,
        ),
        OnBoardingPage(
            titleRes = R.string.onboarding_slide3_title,
            descriptionRes = R.string.onboarding_slide3_description,
            iconRes = R.drawable.ic_photo_colored,
        ),
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    val isLastPage = pagerState.currentPage == pages.lastIndex
    val buttonText = if (isLastPage) {
        stringResource(R.string.onboarding_finish)
    } else {
        stringResource(R.string.onboarding_next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // Pager fills the top area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                OnBoardingSlide(pages[page])
            }
        }

        // Dot indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { index ->
                val isSelected = pagerState.currentPage == index
                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 10.dp else 8.dp),
                ) {}
            }
        }

        // Next / Finish button
        Button(
            onClick = {
                if (isLastPage) {
                    onFinish()
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(buttonText)
        }
    }
}

private data class OnBoardingPage(
    val titleRes: Int,
    val descriptionRes: Int,
    val iconRes: Int?,
)

@Composable
private fun OnBoardingSlide(page: OnBoardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon (optional — slide 1 has none)
        if (page.iconRes != null) {
            Image(
                painter = painterResource(page.iconRes),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 24.dp),
            )
        }

        // Title (30sp, matches the XML layout)
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
            textAlign = TextAlign.Center,
        )

        // Description
        Text(
            text = stringResource(page.descriptionRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnBoardingScreenPreview() {
    AppTheme {
        OnBoardingScreen(onFinish = {})
    }
}
