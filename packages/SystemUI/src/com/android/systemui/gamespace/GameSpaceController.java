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

package com.android.systemui.gamespace;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * GameSpace: while a selected game is in the foreground this keeps the status bar and edge
 * gestures locked and, optionally, turns on Do Not Disturb. A small floating handle on the
 * left edge of the screen toggles the lock.
 */
public final class GameSpaceController {
    private static final String TAG = "GameSpace";
    private static final String KEY_APPS = "ax_gamespace_apps";
    private static final String KEY_LOCK = "ax_gamespace_lock";
    private static final String KEY_DND = "ax_gamespace_dnd";
    private static final String KEY_GESTURE_LOCK = "ax_gaming_gesture_lock";
    private static final int FADE_DELAY_MS = 4000;

    private static GameSpaceController sInstance;
    private static volatile boolean sLocked;

    /** True while the GameSpace lock is active; read by the edge gesture handler. */
    public static boolean isLocked() {
        return sLocked;
    }

    public static synchronized void init(Context context) {
        if (sInstance == null) {
            sInstance = new GameSpaceController(context.getApplicationContext());
        }
    }

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindowManager;
    private final StatusBarManager mStatusBarManager;
    private final NotificationManager mNotificationManager;

    private View mHandle;
    private boolean mLocked;
    private String mGame;
    private int mSavedInterruptionFilter = -1;
    private String mForegroundPackage;
    private int mForegroundTaskId = -1;
    private int mGameTaskId = -1;

    private final Runnable mFade = () -> {
        if (mHandle != null) {
            mHandle.animate().alpha(0.15f).setDuration(300).start();
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mHandler.post(() -> evaluate(mForegroundTaskId, mForegroundPackage));
        }
    };

    private GameSpaceController(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mStatusBarManager = context.getSystemService(StatusBarManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        Settings.Secure.putIntForUser(mContext.getContentResolver(), KEY_GESTURE_LOCK, 0,
                UserHandle.USER_CURRENT);
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskMovedToFront(ActivityManager.RunningTaskInfo task) {
                    final String pkg = task.topActivity == null
                            ? null : task.topActivity.getPackageName();
                    final int taskId = task.taskId;
                    mHandler.post(() -> evaluate(taskId, pkg));
                }
            });
        } catch (RemoteException ignored) {
            // Task manager unavailable; the feature stays dormant.
        }
        registerObserver(KEY_APPS);
        registerObserver(KEY_LOCK);
        registerObserver(KEY_DND);

        // Pick up a game that was already in the foreground before SystemUI (re)started.
        mHandler.post(() -> {
            try {
                final List<ActivityManager.RunningTaskInfo> tasks =
                        ActivityTaskManager.getService().getTasks(
                                1, false, false, mContext.getDisplayId());
                if (tasks.isEmpty()) {
                    return;
                }
                final ActivityManager.RunningTaskInfo task = tasks.get(0);
                final String pkg = task.topActivity == null
                        ? null : task.topActivity.getPackageName();
                evaluate(task.taskId, pkg);
            } catch (RemoteException | SecurityException ignored) {
                // Task manager unavailable.
            }
        });
    }

    private void registerObserver(String key) {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(key), false, mSettingsObserver, UserHandle.USER_ALL);
    }

    private void evaluate(int taskId, String pkg) {
        mForegroundTaskId = taskId;
        mForegroundPackage = pkg;
        final boolean inGameTask = mGame != null && taskId != -1 && taskId == mGameTaskId;
        final boolean isGame = (inGameTask && isSelectedGame(mGame))
                || (pkg != null && isSelectedGame(pkg));
        if (!isGame) {
            if (mGame != null) {
                exitGame();
            }
            return;
        }
        if (mGame == null) {
            enterGame(taskId, pkg);
        } else if (inGameTask) {
            applySettings();
        } else if (pkg != null && pkg.equals(mGame)) {
            mGameTaskId = taskId;
            applySettings();
        } else {
            exitGame();
            enterGame(taskId, pkg);
        }
    }

    private boolean isSelectedGame(String pkg) {
        final String stored = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), KEY_APPS, UserHandle.USER_CURRENT);
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        return new HashSet<>(Arrays.asList(stored.split(","))).contains(pkg);
    }

    private boolean isEnabled(String key) {
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(), key, 1, UserHandle.USER_CURRENT) != 0;
    }

    private void enterGame(int taskId, String pkg) {
        mGame = pkg;
        mGameTaskId = taskId;
        Log.d(TAG, "enter " + pkg + " task=" + taskId);
        applySettings();
    }

    private void exitGame() {
        if (mLocked) {
            setLocked(false);
        }
        disableDnd();
        hideHandle();
        Log.d(TAG, "exit " + mGame);
        mGame = null;
        mGameTaskId = -1;
    }

    private void applySettings() {
        if (mGame == null) {
            return;
        }
        if (isEnabled(KEY_LOCK)) {
            showHandle();
            if (!mLocked) {
                setLocked(true);
            }
        } else {
            if (mLocked) {
                setLocked(false);
            }
            hideHandle();
        }
        if (isEnabled(KEY_DND)) {
            enableDnd();
        } else {
            disableDnd();
        }
    }

    private void enableDnd() {
        if (mSavedInterruptionFilter != -1) {
            return;
        }
        if (!mNotificationManager.isNotificationPolicyAccessGranted()) {
            Log.w(TAG, "Notification policy access unavailable");
            return;
        }
        final int current = mNotificationManager.getCurrentInterruptionFilter();
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
            return;
        }
        mSavedInterruptionFilter = current;
        try {
            mNotificationManager.setInterruptionFilter(
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        } catch (Exception e) {
            mSavedInterruptionFilter = -1;
            Log.w(TAG, "Unable to enable DND", e);
        }
    }

    private void disableDnd() {
        if (mSavedInterruptionFilter == -1) {
            return;
        }
        try {
            mNotificationManager.setInterruptionFilter(mSavedInterruptionFilter);
        } catch (Exception e) {
            Log.w(TAG, "Unable to restore DND", e);
        }
        mSavedInterruptionFilter = -1;
    }

    private void setLocked(boolean locked) {
        mLocked = locked;
        sLocked = locked;
        Settings.Secure.putIntForUser(mContext.getContentResolver(), KEY_GESTURE_LOCK,
                locked ? 1 : 0, UserHandle.USER_CURRENT);
        if (mStatusBarManager != null) {
            mStatusBarManager.disable(locked ? StatusBarManager.DISABLE_EXPAND : 0);
        }
        updateHandle();
    }

    private void showHandle() {
        if (mHandle != null) {
            return;
        }
        final View handle = new View(mContext);
        handle.setOnClickListener(v -> {
            if (mGame != null) {
                setLocked(!mLocked);
            }
            mHandler.removeCallbacks(mFade);
            handle.animate().alpha(1f).setDuration(150).start();
            mHandler.postDelayed(mFade, FADE_DELAY_MS);
        });
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(16), dp(72),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        params.x = 0;
        params.setTitle("GameSpace");
        try {
            mWindowManager.addView(handle, params);
        } catch (Exception e) {
            Log.w(TAG, "Overlay handle rejected, trying system alert", e);
            params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            try {
                mWindowManager.addView(handle, params);
            } catch (Exception second) {
                Log.w(TAG, "Unable to add GameSpace handle", second);
                return;
            }
        }
        mHandle = handle;
        updateHandle();
        handle.animate().alpha(1f).setDuration(150).start();
        mHandler.removeCallbacks(mFade);
        mHandler.postDelayed(mFade, FADE_DELAY_MS);
    }

    private void hideHandle() {
        mHandler.removeCallbacks(mFade);
        if (mHandle != null) {
            try {
                mWindowManager.removeView(mHandle);
            } catch (Exception ignored) {
                // Already detached.
            }
            mHandle = null;
        }
    }

    private void updateHandle() {
        if (mHandle == null) {
            return;
        }
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(8));
        background.setColor(mLocked ? 0xFFFFFFFF : 0xFFBDBDBD);
        mHandle.setBackground(background);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, mContext.getResources().getDisplayMetrics());
    }
}
