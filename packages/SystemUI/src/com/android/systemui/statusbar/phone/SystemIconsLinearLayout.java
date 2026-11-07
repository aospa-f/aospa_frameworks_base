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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.android.keyguard.AlphaOptimizedLinearLayout;

/**
 * The system-icon area of the status bar: the {@link StatusIconContainer} plus the
 * trailing battery view.
 *
 * <p>The icon container reports whatever width its icons need; when the bar runs out
 * of space that full width would push the battery (a sibling laid out after it) past
 * the display edge and clip it. This layout therefore measures the trailing views
 * first and hands the icon container only the width that is actually left, letting
 * the container compress its gaps to fit.
 */
public class SystemIconsLinearLayout extends AlphaOptimizedLinearLayout {

    public SystemIconsLinearLayout(Context context) {
        super(context);
    }

    public SystemIconsLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SystemIconsLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SystemIconsLinearLayout(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int specWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);

        // Measure every trailing view (the battery) at its desired width first.
        int trailingWidth = 0;
        View iconContainer = null;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child instanceof StatusIconContainer) {
                iconContainer = child;
                continue;
            }
            measureChildWithMargins(child,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0,
                    heightMeasureSpec, 0);
            trailingWidth += child.getMeasuredWidth();
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            trailingWidth += lp.leftMargin + lp.rightMargin;
        }

        // Then give the icon container only what is left of the offered spec.
        final int containerSpec;
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            containerSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        } else {
            final int available = Math.max(0,
                    specWidth - getPaddingStart() - getPaddingEnd() - trailingWidth);
            containerSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
        }
        int containerWidth = 0;
        if (iconContainer != null) {
            measureChildWithMargins(iconContainer, containerSpec, 0, heightMeasureSpec, 0);
            containerWidth = iconContainer.getMeasuredWidth();
            final LayoutParams lp = (LayoutParams) iconContainer.getLayoutParams();
            containerWidth += lp.leftMargin + lp.rightMargin;
        }

        final int totalWidth = getPaddingStart() + getPaddingEnd()
                + trailingWidth + containerWidth;
        setMeasuredDimension(
                resolveSizeAndState(totalWidth, widthMeasureSpec, 0),
                resolveSizeAndState(getDesiredHeight(heightMeasureSpec),
                        heightMeasureSpec, 0) & MEASURED_SIZE_MASK);
    }

    private int getDesiredHeight(int heightMeasureSpec) {
        final int mode = MeasureSpec.getMode(heightMeasureSpec);
        if (mode == MeasureSpec.EXACTLY) {
            return MeasureSpec.getSize(heightMeasureSpec);
        }
        int highest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            highest = Math.max(getChildAt(i).getMeasuredHeight(), highest);
        }
        return highest + getPaddingTop() + getPaddingBottom();
    }
}
