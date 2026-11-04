/*
 * Copyright (C) 2026 The AOSPA Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.composefragment

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import kotlin.math.roundToInt

// Mirrors android.R.integer.config_nightDisplayColorTemperature* on this platform.
private const val NIGHT_TEMP_MIN = 2596
private const val NIGHT_TEMP_MAX = 5500
private const val NIGHT_TEMP_DEFAULT = 2850

// Mirrors android.R.integer.config_reduceBrightColorsStrengthDefault.
private const val EXTRA_DIM_DEFAULT = 50

/**
 * Extra dim and Night Light intensity sliders shown directly below the main brightness slider
 * while their respective feature is enabled.
 */
@Composable
fun DisplayExtraBrightnessSliders(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolver = context.contentResolver

    var extraDimOn by remember {
        mutableStateOf(
            readSecureInt(context, Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 0) != 0
        )
    }
    var extraDimLevel by remember {
        mutableIntStateOf(
            readSecureInt(context, Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL, EXTRA_DIM_DEFAULT)
        )
    }
    var nightLightOn by remember {
        mutableStateOf(readSecureInt(context, Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0) != 0)
    }
    var nightTemperature by remember {
        mutableIntStateOf(
            readSecureInt(
                context,
                Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                NIGHT_TEMP_DEFAULT,
            )
        )
    }

    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    when (uri?.lastPathSegment) {
                        Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED ->
                            extraDimOn =
                                readSecureInt(
                                    context,
                                    Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                                    0,
                                ) != 0
                        Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL ->
                            extraDimLevel =
                                readSecureInt(
                                    context,
                                    Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                                    EXTRA_DIM_DEFAULT,
                                )
                        Settings.Secure.NIGHT_DISPLAY_ACTIVATED ->
                            nightLightOn =
                                readSecureInt(
                                    context,
                                    Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                                    0,
                                ) != 0
                        Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE ->
                            nightTemperature =
                                readSecureInt(
                                    context,
                                    Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                                    NIGHT_TEMP_DEFAULT,
                                )
                    }
                }
            }
        listOf(
                Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
            )
            .forEach {
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(it),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
            }
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    if (!extraDimOn && !nightLightOn) {
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (extraDimOn) {
            ExtraBrightnessSlider(
                label = stringResource(R.string.extra_dim_brightness_label),
                value = extraDimLevel / 100f,
                onValueChange = { value ->
                    val level = (value * 100f).roundToInt().coerceIn(0, 100)
                    extraDimLevel = level
                    Settings.Secure.putIntForUser(
                        resolver,
                        Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                        level,
                        UserHandle.USER_CURRENT,
                    )
                },
            )
        }
        if (nightLightOn) {
            ExtraBrightnessSlider(
                label = stringResource(R.string.quick_settings_night_display_label),
                value =
                    ((NIGHT_TEMP_MAX - nightTemperature).toFloat() /
                            (NIGHT_TEMP_MAX - NIGHT_TEMP_MIN))
                        .coerceIn(0f, 1f),
                onValueChange = { value ->
                    val temperature =
                        (NIGHT_TEMP_MAX - value * (NIGHT_TEMP_MAX - NIGHT_TEMP_MIN))
                            .roundToInt()
                            .coerceIn(NIGHT_TEMP_MIN, NIGHT_TEMP_MAX)
                    nightTemperature = temperature
                    Settings.Secure.putIntForUser(
                        resolver,
                        Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                        temperature,
                        UserHandle.USER_CURRENT,
                    )
                },
            )
        }
    }
}

@Composable
private fun ExtraBrightnessSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(88.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(36.dp),
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        )
        Text(
            text = "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

private fun readSecureInt(context: Context, key: String, default: Int): Int =
    Settings.Secure.getIntForUser(
        context.contentResolver,
        key,
        default,
        UserHandle.USER_CURRENT,
    )
