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
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.phone.StatusIconContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Samples total network traffic and feeds a [NetworkSpeedView] that lives inside the main
 * status bar's [StatusIconContainer], positioned just before the battery icon. Because the
 * indicator is a container child it can never push the battery off screen.
 *
 * Visibility follows the same settings the old XML based traffic indicator used:
 * [Settings.Secure.NETWORK_TRAFFIC_MODE], ..._AUTOHIDE and ..._UNITS.
 */
class NetworkSpeedController private constructor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var container: StatusIconContainer? = null
    private var speedView: NetworkSpeedView? = null
    private var started = false

    private var mode = MODE_DISABLED
    private var autoHide = false
    private var unitsBits = false
    private var connected = false

    private var lastSampleTime = 0L
    private var lastTotalBytes = 0L
    private var lastSpeed = 0L

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.post {
                reloadSettings()
                updateView()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connected = true
            mainHandler.post { updateView() }
        }

        override fun onLost(network: Network) {
            connected = hasValidatedNetwork()
            mainHandler.post { updateView() }
        }
    }

    fun attachContainer(target: StatusIconContainer) {
        if (!isMainBarContainer(target)) {
            return
        }
        if (speedView == null) {
            speedView = NetworkSpeedView(target.context)
        }
        container = target
        target.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                if (child === speedView) {
                    return
                }
                if (child is StatusBarIconView && BATTERY_SLOT == child.slot) {
                    tryInsert()
                }
            }

            override fun onChildViewRemoved(parent: View, child: View) {}
        })
        tryInsert()
        start()
    }

    private fun isMainBarContainer(c: StatusIconContainer): Boolean {
        var parent = c.parent as? View
        while (parent != null) {
            if (parent.id == R.id.status_bar_end_side_content) {
                return true
            }
            parent = parent.parent as? View
        }
        return false
    }

    private fun tryInsert() {
        val c = container ?: return
        val v = speedView ?: return
        if (v.parent === c) {
            return
        }
        for (i in 0 until c.childCount) {
            val child = c.getChildAt(i)
            if (child is StatusBarIconView && BATTERY_SLOT == child.slot) {
                c.addView(v, i)
                return
            }
        }
    }

    private fun start() {
        if (started) {
            return
        }
        started = true
        val resolver = context.contentResolver
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NETWORK_TRAFFIC_MODE), true, settingsObserver)
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NETWORK_TRAFFIC_AUTOHIDE), true, settingsObserver)
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NETWORK_TRAFFIC_UNITS), true, settingsObserver)
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Connectivity tracking unavailable; the indicator stays off.
        }
        connected = hasValidatedNetwork()
        reloadSettings()
        scope.launch {
            while (isActive) {
                val speed = sample()
                withContext(Dispatchers.Main) {
                    lastSpeed = speed
                    updateView()
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun reloadSettings() {
        val resolver = context.contentResolver
        mode = Settings.Secure.getIntForUser(
                resolver, Settings.Secure.NETWORK_TRAFFIC_MODE, MODE_DISABLED, UserHandle.USER_CURRENT)
        autoHide = Settings.Secure.getIntForUser(
                resolver, Settings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) == 1
        unitsBits = Settings.Secure.getIntForUser(
                resolver, Settings.Secure.NETWORK_TRAFFIC_UNITS, 0, UserHandle.USER_CURRENT) != 0
    }

    private fun sample(): Long {
        val now = SystemClock.elapsedRealtime()
        val total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        var speed = 0L
        if (lastSampleTime > 0 && total > lastTotalBytes && now > lastSampleTime) {
            speed = (total - lastTotalBytes) * 1000 / (now - lastSampleTime)
        }
        lastSampleTime = now
        lastTotalBytes = total
        return speed
    }

    private fun updateView() {
        val v = speedView ?: return
        val enabled = mode != MODE_DISABLED && connected
        val show = enabled && (!autoHide || lastSpeed > 0)
        v.setVisible(show)
        if (show) {
            v.setSpeed(lastSpeed, unitsBits)
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val BATTERY_SLOT = "battery"
        private const val REFRESH_INTERVAL_MS = 4000L
        private const val MODE_DISABLED = 0

        @Volatile
        private var sInstance: NetworkSpeedController? = null

        @JvmStatic
        fun onContainerAttached(container: StatusIconContainer) {
            val controller = sInstance ?: synchronized(this) {
                sInstance ?: NetworkSpeedController(container.context.applicationContext)
                        .also { sInstance = it }
            }
            controller.attachContainer(container)
        }
    }
}
