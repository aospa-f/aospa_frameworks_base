/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.radio

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject

/**
 * Turns off Wi-Fi and Bluetooth after five minutes without a connection when the user enables
 * "Turn off Wi-Fi and Bluetooth when idle" in Network & internet.
 */
@SysUISingleton
class IdleRadioTimeoutStartable
@Inject
constructor(
    @Application private val context: Context,
) : CoreStartable {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val connectedBluetoothDevices = mutableSetOf<String>()

    private var wifiTimeoutScheduled = false
    private var bluetoothTimeoutScheduled = false

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_WIFI_TIMEOUT -> {
                    wifiTimeoutScheduled = false
                    if (isEnabled() && wifiManager?.isWifiEnabled == true && !isWifiConnected()) {
                        wifiManager?.isWifiEnabled = false
                    }
                }
                ACTION_BLUETOOTH_TIMEOUT -> {
                    bluetoothTimeoutScheduled = false
                    if (isEnabled() && bluetoothAdapter?.isEnabled == true &&
                        !isBluetoothConnected()
                    ) {
                        try {
                            bluetoothAdapter?.disable()
                        } catch (e: SecurityException) {
                            // SystemUI is expected to hold BLUETOOTH_CONNECT.
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )?.address?.let { connectedBluetoothDevices.add(it) }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )?.address?.let { connectedBluetoothDevices.remove(it) }
                }
            }
            evaluate()
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            evaluate()
        }
    }

    override fun start() {
        // Clear any alarms left behind by a previous SystemUI process.
        alarmManager?.cancel(pendingIntent(ACTION_WIFI_TIMEOUT, REQUEST_WIFI))
        alarmManager?.cancel(pendingIntent(ACTION_BLUETOOTH_TIMEOUT, REQUEST_BLUETOOTH))

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_WIFI_TIMEOUT)
            addAction(ACTION_BLUETOOTH_TIMEOUT)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.IDLE_RADIO_TIMEOUT),
            false,
            settingsObserver,
        )
        evaluate()
    }

    private fun evaluate() {
        if (!isEnabled()) {
            cancelWifiTimeout()
            cancelBluetoothTimeout()
            return
        }

        if (wifiManager?.isWifiEnabled == true && !isWifiConnected()) {
            scheduleWifiTimeout()
        } else {
            cancelWifiTimeout()
        }

        if (bluetoothAdapter?.isEnabled == true && !isBluetoothConnected()) {
            scheduleBluetoothTimeout()
        } else {
            cancelBluetoothTimeout()
        }
    }

    private fun isEnabled(): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.IDLE_RADIO_TIMEOUT,
            0,
        ) == 1

    private fun isWifiConnected(): Boolean {
        val info = wifiManager?.connectionInfo ?: return false
        return info.networkId != -1 && info.supplicantState == SupplicantState.COMPLETED
    }

    private fun isBluetoothConnected(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false
        if (connectedBluetoothDevices.isNotEmpty()) return true

        // BluetoothManager.getConnectedDevices only documents GATT/GATT_SERVER, but the
        // Bluetooth service also answers for the other profiles on this platform.
        return BLUETOOTH_PROFILES.any { profile ->
            try {
                bluetoothManager?.getConnectedDevices(profile)?.isNotEmpty() == true
            } catch (e: SecurityException) {
                // If the connection state cannot be read, never turn Bluetooth off.
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }

    private fun scheduleWifiTimeout() {
        if (wifiTimeoutScheduled) return
        schedule(ACTION_WIFI_TIMEOUT, REQUEST_WIFI)
        wifiTimeoutScheduled = true
    }

    private fun scheduleBluetoothTimeout() {
        if (bluetoothTimeoutScheduled) return
        schedule(ACTION_BLUETOOTH_TIMEOUT, REQUEST_BLUETOOTH)
        bluetoothTimeoutScheduled = true
    }

    private fun schedule(action: String, requestCode: Int) {
        val pendingIntent = pendingIntent(action, requestCode)
        val triggerAt = SystemClock.elapsedRealtime() + IDLE_TIMEOUT_MS
        try {
            alarmManager?.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        }
    }

    private fun cancelWifiTimeout() {
        if (!wifiTimeoutScheduled) return
        alarmManager?.cancel(pendingIntent(ACTION_WIFI_TIMEOUT, REQUEST_WIFI))
        wifiTimeoutScheduled = false
    }

    private fun cancelBluetoothTimeout() {
        if (!bluetoothTimeoutScheduled) return
        alarmManager?.cancel(pendingIntent(ACTION_BLUETOOTH_TIMEOUT, REQUEST_BLUETOOTH))
        bluetoothTimeoutScheduled = false
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val ACTION_WIFI_TIMEOUT = "com.android.systemui.action.IDLE_WIFI_TIMEOUT"
        const val ACTION_BLUETOOTH_TIMEOUT =
            "com.android.systemui.action.IDLE_BLUETOOTH_TIMEOUT"
        const val REQUEST_WIFI = 1001
        const val REQUEST_BLUETOOTH = 1002
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        val BLUETOOTH_PROFILES = intArrayOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER,
            BluetoothProfile.SAP,
            BluetoothProfile.HEARING_AID,
            BluetoothProfile.LE_AUDIO,
        )
    }
}

@Module
interface IdleRadioTimeoutStartableModule {
    @Binds
    @IntoMap
    @ClassKey(IdleRadioTimeoutStartable::class)
    fun startable(startable: IdleRadioTimeoutStartable): CoreStartable
}
