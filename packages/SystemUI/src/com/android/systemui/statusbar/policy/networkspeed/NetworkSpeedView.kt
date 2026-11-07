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

package com.android.systemui.statusbar.policy.networkspeed

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusIconDisplayable

/**
 * Inline network speed text rendered as a regular member of [com.android.systemui.statusbar.phone.StatusIconContainer].
 * Because it lives inside the icon container it can never push the battery off screen; when
 * space is tight the container shrinks this view first via [setShrunkWidth].
 */
class NetworkSpeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), StatusIconDisplayable {

    private val numberView: TextView
    private val unitView: TextView
    private var shown = false
    private var visibleState = StatusBarIconView.STATE_HIDDEN

    init {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        numberView = TextView(context).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextAppearance(context, R.style.TextAppearance_StatusBar_Default)
            setTypeface(typeface, Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        unitView = TextView(context).apply {
            setSingleLine(true)
            setTextAppearance(context, R.style.TextAppearance_StatusBar_Default)
            setTypeface(typeface, Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        row.addView(numberView)
        row.addView(unitView)
        addView(
                row,
                LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL,
                ),
        )
        visibility = View.GONE
    }

    override fun getSlot(): String = SLOT

    fun setVisible(show: Boolean) {
        if (show == shown) {
            return
        }
        shown = show
        visibility = if (show) View.VISIBLE else View.GONE
        requestLayout()
    }

    fun setSpeed(bytesPerSecond: Long, bits: Boolean) {
        val formatted = format(bytesPerSecond, bits)
        numberView.text = formatted.first
        unitView.text = formatted.second
    }

    /** Releases any shrink applied by the container during a tight-measure pass. */
    fun clearShrink() {
        numberView.maxWidth = Int.MAX_VALUE
    }

    /** Caps the number label so the total view width fits into [totalPx]. */
    fun setShrunkWidth(totalPx: Int) {
        val rest = unitView.measuredWidth + paddingLeft + paddingRight
        numberView.maxWidth = (totalPx - rest).coerceAtLeast(0)
    }

    override fun isIconVisible(): Boolean = shown

    override fun isIconBlocked(): Boolean = false

    override fun getVisibleState(): Int = visibleState

    override fun setVisibleState(state: Int, animate: Boolean) {
        visibleState = state
        val showText = state == StatusBarIconView.STATE_ICON
        numberView.visibility = if (showText) View.VISIBLE else View.INVISIBLE
        unitView.visibility = if (showText) View.VISIBLE else View.INVISIBLE
    }

    override fun setStaticDrawableColor(color: Int) {
        numberView.setTextColor(color)
        unitView.setTextColor(color)
    }

    override fun setDecorColor(color: Int) {}

    override fun onDarkChanged(area: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        // The text appearance already tracks the status bar look.
    }

    private fun format(bps: Long, bits: Boolean): Pair<String, String> {
        val value = if (bits) bps * 8 else bps
        val k = if (bits) 1000L else 1024L
        val m = k * k
        val g = m * k
        val divisor: Long
        val unit: String
        val decimals: Int
        when {
            value < k -> {
                divisor = 1L
                unit = if (bits) "bps" else "B/s"
                decimals = 0
            }
            value < m -> {
                divisor = k
                unit = if (bits) "kbps" else "KB/s"
                decimals = if (value < 10 * k) 1 else 0
            }
            value < g -> {
                divisor = m
                unit = if (bits) "Mbps" else "MB/s"
                decimals = if (value < 10 * m) 2 else 1
            }
            else -> {
                divisor = g
                unit = if (bits) "Gbps" else "GB/s"
                decimals = 2
            }
        }
        if (divisor == 1L) {
            return Pair(value.toString(), unit)
        }
        val whole = value / divisor
        var remainder = value % divisor
        val builder = StringBuilder().append(whole).append(".")
        repeat(decimals) {
            remainder *= 10
            builder.append(remainder / divisor)
            remainder %= divisor
        }
        return Pair(builder.toString(), unit)
    }

    companion object {
        const val SLOT = "network_speed"
    }
}
