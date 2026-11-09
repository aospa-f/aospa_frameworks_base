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
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import android.database.ContentObserver
import android.hardware.display.ColorDisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// Mirrors android.R.integer.config_nightDisplayColorTemperature* on this platform.
private const val NIGHT_TEMP_MIN = 2596
private const val NIGHT_TEMP_MAX = 5500

// Mirrors android.R.integer.config_reduceBrightColorsStrengthDefault.

// Match the main brightness slider's track and thumb proportions.
private val TRACK_HEIGHT = 40.dp
private val TRACK_CORNER = 12.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 52.dp
private val TRACK_GAP = 6.dp
private val TRAILING_ICON_SIZE = 40.dp
private val TRAILING_ICON_GAP = 8.dp

// Direct-settings read of the reduce-bright-colors level (0..100 dim strength).
private fun readReduceBrightColorsLevel(resolver: android.content.ContentResolver): Int =
    Settings.Secure.getInt(
        resolver,
        Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
        0,
    )

// Minimum level delta before a drag is trusted (a grazing mode-switch swipe
// never reaches this, a deliberate drag or tap does).
private const val DRAG_VALIDATION_LEVELS = 6

/**
 * Extra dim and Night Light intensity sliders shown directly below the main brightness slider
 * while their respective feature is enabled. The trailing icon sits inside the dark end of the
 * bar, like the auto-brightness button in the main brightness pill, and turns the feature off.
 */
@Composable
fun DisplayExtraBrightnessSliders(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val colorDisplayManager = remember {
        context.getSystemService(ColorDisplayManager::class.java)
    }

    var extraDimOn by remember {
        mutableStateOf(colorDisplayManager.isReduceBrightColorsActivated)
    }
    // Read the level straight from the settings table: the ColorDisplayManager
    // getter can briefly return the default while the QS panel re-lays-out, and
    // initializing from it made the slider jump between QS extended and compact
    // modes.
    var extraDimLevel by remember {
        mutableIntStateOf(readReduceBrightColorsLevel(resolver))
    }
    var nightLightOn by remember {
        mutableStateOf(colorDisplayManager.isNightDisplayActivated)
    }
    // Track the level we last wrote so the ContentObserver can distinguish
    // external changes from the echo of our own commit, and guard the thumb
    // against accidental gesture captures (a mode-switch swipe grazing the
    // row must not re-dim the screen).
    var lastCommittedLevel by remember { mutableIntStateOf(extraDimLevel) }
    var dragStartLevel by remember { mutableIntStateOf(extraDimLevel) }
    var dragValidated by remember { mutableStateOf(false) }
    var nightTemperature by remember {
        mutableIntStateOf(colorDisplayManager.nightDisplayColorTemperature)
    }

    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    when (uri?.lastPathSegment) {
                        Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED ->
                            extraDimOn = colorDisplayManager.isReduceBrightColorsActivated
                        Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL -> {
                            val applied = readReduceBrightColorsLevel(resolver)
                            if (applied != lastCommittedLevel) {
                                // Someone else changed the level; follow it.
                                extraDimLevel = applied
                                lastCommittedLevel = applied
                                dragStartLevel = applied
                                dragValidated = false
                            }
                        }
                        Settings.Secure.NIGHT_DISPLAY_ACTIVATED ->
                            nightLightOn = colorDisplayManager.isNightDisplayActivated
                        Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE ->
                            nightTemperature = colorDisplayManager.nightDisplayColorTemperature
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

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val extraDimSlider: @Composable (Modifier) -> Unit = { sliderModifier ->
        ExtraBrightnessSlider(
            modifier = sliderModifier,
            label = stringResource(R.string.extra_dim_brightness_label),
            value = (100 - extraDimLevel) / 100f,
            iconRes = R.drawable.ic_qs_extra_dim,
            onDisable = { colorDisplayManager.setReduceBrightColorsActivated(false) },
            onValueChange = { value ->
                val target = (100 - value * 100f).roundToInt().coerceIn(0, 100)
                if (!dragValidated) {
                    if (abs(target - dragStartLevel) >= DRAG_VALIDATION_LEVELS) {
                        dragValidated = true
                    }
                }
                if (dragValidated) {
                    extraDimLevel = target
                }
            },
            onValueChangeFinished = {
                if (dragValidated) {
                    lastCommittedLevel = extraDimLevel
                    colorDisplayManager.setReduceBrightColorsStrength(extraDimLevel)
                }
                dragValidated = false
                dragStartLevel = extraDimLevel
            },
        )
    }

    val nightLightSlider: @Composable (Modifier) -> Unit = { sliderModifier ->
        ExtraBrightnessSlider(
            modifier = sliderModifier,
            label = stringResource(R.string.quick_settings_night_display_label),
            value =
                ((NIGHT_TEMP_MAX - nightTemperature).toFloat() /
                        (NIGHT_TEMP_MAX - NIGHT_TEMP_MIN))
                    .coerceIn(0f, 1f),
            iconRes = R.drawable.ic_qs_night_light,
            onDisable = { colorDisplayManager.setNightDisplayActivated(false) },
            onValueChange = { value ->
                val temperature =
                    (NIGHT_TEMP_MAX - value * (NIGHT_TEMP_MAX - NIGHT_TEMP_MIN))
                        .roundToInt()
                        .coerceIn(NIGHT_TEMP_MIN, NIGHT_TEMP_MAX)
                nightTemperature = temperature
                colorDisplayManager.setNightDisplayColorTemperature(temperature)
            },
        )
    }

    if (isLandscape && extraDimOn && nightLightOn) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            extraDimSlider(Modifier.weight(1f))
            nightLightSlider(Modifier.weight(1f))
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (extraDimOn) {
                extraDimSlider(Modifier.fillMaxWidth())
            }
            if (nightLightOn) {
                nightLightSlider(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ExtraBrightnessSlider(
    modifier: Modifier = Modifier,
    label: String,
    value: Float,
    iconRes: Int,
    onDisable: () -> Unit,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1f,
            modifier =
                Modifier.weight(1f).height(THUMB_HEIGHT).semantics {
                    contentDescription = label
                    stateDescription = "${(value * 100).roundToInt()}%"
                },
            thumb = {
                Box(
                    modifier =
                        Modifier.width(THUMB_WIDTH)
                            .height(THUMB_HEIGHT)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                )
            },
            track = { sliderState ->
                val activeColor = MaterialTheme.colorScheme.primary
                val inactiveColor = LocalAndroidColorScheme.current.surfaceEffect1
                Canvas(modifier = Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
                    val gap = TRACK_GAP.toPx()
                    val corner = TRACK_CORNER.toPx()
                    val center = size.width * sliderState.coercedValueAsFraction
                    val activeEnd = (center - gap).coerceAtLeast(0f)
                    val inactiveStart = (center + gap).coerceAtMost(size.width)

                    if (activeEnd > 0f) {
                        val radius = min(corner, min(activeEnd / 2f, size.height / 2f))
                        drawRoundRect(
                            color = activeColor,
                            size = Size(activeEnd, size.height),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    }
                    if (inactiveStart < size.width) {
                        val width = size.width - inactiveStart
                        val radius = min(corner, min(width / 2f, size.height / 2f))
                        drawRoundRect(
                            color = inactiveColor,
                            topLeft = Offset(inactiveStart, 0f),
                            size = Size(width, size.height),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    }
                }
            },
        )
        Spacer(modifier = Modifier.width(TRAILING_ICON_GAP))
        Box(
            modifier =
                Modifier.size(TRAILING_ICON_SIZE)
                    .offset(x = (-4).dp)
                    .clip(CircleShape)
                    .background(LocalAndroidColorScheme.current.surfaceEffect1)
                    .clickable(role = Role.Button, onClick = onDisable)
                    .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
