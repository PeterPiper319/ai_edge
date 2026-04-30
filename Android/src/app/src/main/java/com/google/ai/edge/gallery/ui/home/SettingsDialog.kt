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

package com.google.ai.edge.gallery.ui.home

import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.infrastructure.ROUTER_PASSWORD_SECRET_KEY
import com.google.ai.edge.gallery.infrastructure.ROUTER_USERNAME_SECRET_KEY
import com.google.ai.edge.gallery.infrastructure.RouterApiConfig
import com.google.ai.edge.gallery.infrastructure.RouterManager
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.launch

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  val interactionSource = remember { MutableInteractionSource() }
  var routerUsername by
    remember {
      mutableStateOf(modelManagerViewModel.dataStoreRepository.readSecret(ROUTER_USERNAME_SECRET_KEY).orEmpty())
    }
  var routerPassword by
    remember {
      mutableStateOf(modelManagerViewModel.dataStoreRepository.readSecret(ROUTER_PASSWORD_SECRET_KEY).orEmpty())
    }
  var routerStatus by remember { mutableStateOf<String?>(null) }
  var routerBlinkInProgress by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          // Subtitle.
          Text(
            "App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-6).dp),
          )
        }

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          val context = LocalContext.current
          // Theme switcher.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Theme",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            MultiChoiceSegmentedButtonRow {
              THEME_OPTIONS.forEachIndexed { index, theme ->
                SegmentedButton(
                  shape =
                    SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                  onCheckedChange = {
                    selectedTheme = theme

                    // Update theme settings.
                    // This will update app's theme.
                    ThemeSettings.themeOverride.value = theme

                    // Save to data store.
                    modelManagerViewModel.saveThemeOverride(theme)

                    // Update ui mode.
                    //
                    // This is necessary to make other Activities launched from MainActivity to have
                    // the correct theme.
                    val uiModeManager =
                      context.applicationContext.getSystemService(Context.UI_MODE_SERVICE)
                        as UiModeManager
                    if (theme == Theme.THEME_AUTO) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                    } else if (theme == Theme.THEME_LIGHT) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                    } else {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                    }
                  },
                  checked = theme == selectedTheme,
                  label = { Text(themeLabel(theme)) },
                )
              }
            }
          }

          RouterDebugSection(modelManagerViewModel = modelManagerViewModel)
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Close button
          Button(onClick = { onDismissed() }) { Text("Close") }
        }
      }
    }
  }
}

@Composable
internal fun RouterDebugSection(
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
) {
  val focusManager = LocalFocusManager.current
  var routerUsername by
    remember {
      mutableStateOf(modelManagerViewModel.dataStoreRepository.readSecret(ROUTER_USERNAME_SECRET_KEY).orEmpty())
    }
  var routerPassword by
    remember {
      mutableStateOf(modelManagerViewModel.dataStoreRepository.readSecret(ROUTER_PASSWORD_SECRET_KEY).orEmpty())
    }
  var routerStatus by remember { mutableStateOf<String?>(null) }
  var routerBlinkInProgress by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()

  Column(
    modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "Router debug",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Temporary manual trigger for Rain router blink testing. Filter Logcat by RouterManager before running.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = routerUsername,
      onValueChange = { routerUsername = it },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("Router username") },
    )
    OutlinedTextField(
      value = routerPassword,
      onValueChange = { routerPassword = it },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("Router password") },
      visualTransformation = PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = {
          focusManager.clearFocus()
          routerBlinkInProgress = true
          routerStatus = "Blinking router connection. Watch the router LED and Wi-Fi status."
          coroutineScope.launch {
            runCatching {
                require(routerUsername.isNotBlank()) { "Router username is required." }
                require(routerPassword.isNotBlank()) { "Router password is required." }
                val normalizedPassword = routerPassword.trim()
                modelManagerViewModel.dataStoreRepository.saveSecret(
                  ROUTER_USERNAME_SECRET_KEY,
                  routerUsername.trim(),
                )
                modelManagerViewModel.dataStoreRepository.saveSecret(
                  ROUTER_PASSWORD_SECRET_KEY,
                  normalizedPassword,
                )
                RouterManager(
                    config =
                      RouterApiConfig(
                        username = routerUsername.trim(),
                        password = normalizedPassword,
                      )
                  )
                  .blinkConnection()
              }
              .onSuccess {
                routerStatus =
                  "Router blink completed. Verify the LED change, Wi-Fi reconnection, and public IP at ident.me."
              }
              .onFailure { error ->
                routerStatus = error.message ?: "Router blink failed. Check Logcat for RouterManager."
              }
            routerBlinkInProgress = false
          }
        },
        enabled = !routerBlinkInProgress,
      ) {
        Text(if (routerBlinkInProgress) "Blinking..." else "Test router blink")
      }
    }
    routerStatus?.let { status ->
      Text(
        text = status,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun themeLabel(theme: Theme): String {
  return when (theme) {
    Theme.THEME_AUTO -> "Auto"
    Theme.THEME_LIGHT -> "Light"
    Theme.THEME_DARK -> "Dark"
    else -> "Unknown"
  }
}
