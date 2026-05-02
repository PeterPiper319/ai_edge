/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R

val appFontFamily =
  FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_extralight, FontWeight.ExtraLight),
    Font(R.font.nunito_light, FontWeight.Light),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
  )

val jgTitleFontFamily =
  FontFamily(
    Font(R.font.inter_variable, FontWeight.Bold),
  )

val jgBodyFontFamily =
  FontFamily(
    Font(R.font.roboto_variable, FontWeight.Normal),
    Font(R.font.roboto_variable, FontWeight.Medium),
    Font(R.font.roboto_variable, FontWeight.Bold),
  )

val baseline = Typography()

val JGScraperTypography =
  Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    displayMedium = baseline.displayMedium.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    displaySmall = baseline.displaySmall.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    titleLarge = baseline.titleLarge.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    titleMedium = baseline.titleMedium.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    titleSmall = baseline.titleSmall.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Normal),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Normal),
    bodySmall = baseline.bodySmall.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Normal),
    labelLarge = baseline.labelLarge.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Medium),
    labelMedium = baseline.labelMedium.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Medium),
    labelSmall = baseline.labelSmall.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Medium),
  )

val AppTypography = JGScraperTypography

val titleMediumNarrow =
  baseline.titleMedium.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(
    fontFamily = jgTitleFontFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )

val labelSmallNarrow = baseline.labelSmall.copy(fontFamily = jgBodyFontFamily, letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontFamily = jgBodyFontFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(fontFamily = jgBodyFontFamily, letterSpacing = 0.0.sp)

val bodySmallMediumNarrow =
  baseline.bodySmall.copy(fontFamily = jgBodyFontFamily, letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    fontFamily = jgBodyFontFamily,
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )

val homePageTitleStyle =
  baseline.displayMedium.copy(
    fontFamily = jgTitleFontFamily,
    fontSize = 48.sp,
    lineHeight = 48.sp,
    letterSpacing = -1.sp,
    fontWeight = FontWeight.Bold,
  )

val bodyLargeNarrow = baseline.bodyLarge.copy(fontFamily = jgBodyFontFamily, letterSpacing = 0.2.sp)
val bodyMediumMedium = baseline.bodyMedium.copy(fontFamily = jgBodyFontFamily, fontWeight = FontWeight.Medium)

val headlineLargeMedium = baseline.headlineLarge.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold)

val emptyStateTitle = baseline.headlineSmall.copy(fontFamily = jgTitleFontFamily, fontWeight = FontWeight.Bold, fontSize = 37.sp, lineHeight = 50.sp)
val emptyStateContent = baseline.bodyMedium.copy(fontFamily = jgBodyFontFamily, fontSize = 16.sp, lineHeight = 22.sp)
