/*
 * Copyright (C) 2022 Nameless-AOSP Project
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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.telephony.RadioAccessFamily
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.provider.Settings

import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import com.android.systemui.res.R

import javax.inject.Inject

/**
 * Cycles through 5G only, 4G only and Preferred 5G on the default data SIM.
 *
 * The legacy NetToggle app changes preferred_network_mode through root/Shizuku. As a privileged
 * SystemUI process we can use the public TelephonyManager API instead.
 */
class PreferredNetworkTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
) : QSTileImpl<QSTile.State>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    private val telephonyManager: TelephonyManager =
        mContext.getSystemService(TelephonyManager::class.java)!!

    private fun getDataTelephonyManager(): TelephonyManager? {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (SubscriptionManager.isValidSubscriptionId(subId)) {
            telephonyManager.createForSubscriptionId(subId)
        } else {
            null
        }
    }

    override fun isAvailable(): Boolean {
        return telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_NONE &&
            getDataTelephonyManager() != null
    }

    override fun newTileState(): QSTile.State = QSTile.State()

    override fun handleClick(expandable: Expandable?) {
        val mode = getCurrentMode()
        setMode(mode.next())
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        val mode = getCurrentMode()
        state.state = Tile.STATE_ACTIVE
        state.label = getTileLabel()
        state.secondaryLabel = getModeLabel(mode)
        state.contentDescription = state.label
        state.icon = ResourceIcon.get(R.drawable.ic_qs_network_mode)
    }

    override fun getLongClickIntent(): Intent {
        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            intent.putExtra(Settings.EXTRA_SUB_ID, subId)
        }
        return intent
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.preferred_network_tile_label)

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL

    private fun getCurrentMode(): Mode {
        val tm = getDataTelephonyManager() ?: return Mode.PREFERRED_5G
        val raf = try {
            tm.getAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
            )
        } catch (e: Exception) {
            Log.w(NETWORK_TILE_TAG, "Unable to read network mode", e)
            return Mode.PREFERRED_5G
        }
        val hasNr = (raf and TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0L
        val hasLte = (raf and (TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
            TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA)) != 0L
        return when {
            hasNr && !hasLte -> Mode.FIVE_G_ONLY
            hasLte && !hasNr -> Mode.FOUR_G_ONLY
            else -> Mode.PREFERRED_5G
        }
    }

    private fun setMode(mode: Mode) {
        val tm = getDataTelephonyManager() ?: return
        val raf = when (mode) {
            Mode.FIVE_G_ONLY -> RadioAccessFamily.getRafFromNetworkType(
                TelephonyManager.NETWORK_MODE_NR_ONLY
            )
            Mode.FOUR_G_ONLY -> RadioAccessFamily.getRafFromNetworkType(
                TelephonyManager.NETWORK_MODE_LTE_ONLY
            )
            Mode.PREFERRED_5G -> RadioAccessFamily.getRafFromNetworkType(
                TelephonyManager.NETWORK_MODE_NR_LTE_GSM_WCDMA
            )
        }.toLong()
        try {
            tm.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                raf,
            )
        } catch (e: Exception) {
            Log.w(NETWORK_TILE_TAG, "Unable to set network mode $mode", e)
        }
        refreshState()
    }

    private fun getModeLabel(mode: Mode): CharSequence = when (mode) {
        Mode.FIVE_G_ONLY -> mContext.getString(R.string.preferred_network_5g_only)
        Mode.FOUR_G_ONLY -> mContext.getString(R.string.preferred_network_4g_only)
        Mode.PREFERRED_5G -> mContext.getString(R.string.preferred_network_5g_preferred)
    }

    private enum class Mode {
        FIVE_G_ONLY,
        FOUR_G_ONLY,
        PREFERRED_5G;

        fun next(): Mode = when (this) {
            FIVE_G_ONLY -> FOUR_G_ONLY
            FOUR_G_ONLY -> PREFERRED_5G
            PREFERRED_5G -> FIVE_G_ONLY
        }
    }

    companion object {
        const val TILE_SPEC = "preferred_network"
        private const val NETWORK_TILE_TAG = "PreferredNetworkTile"
    }
}
