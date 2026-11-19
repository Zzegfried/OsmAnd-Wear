package net.osmand.plus.plugins.wear

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import net.osmand.plus.OsmAndLocationProvider
import net.osmand.plus.NavigationService
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.wear.protocol.LinkDiagnostics

/**
 * While diagnostic logging is on, records the phone states that decide whether the watch link and
 * the location service may run: screen, unlock, Bluetooth adapter and connections, airplane mode,
 * Doze, battery saver and memory pressure. [state] and [process] describe the same conditions on
 * demand. No device names or addresses are logged; a Bluetooth device is named by its class only.
 */
internal class WearDeviceEvents private constructor(private val app: OsmandApplication) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val power = app.getSystemService(PowerManager::class.java)
        val event = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> "phone screen on"
            Intent.ACTION_SCREEN_OFF -> "phone screen off"
            Intent.ACTION_USER_PRESENT -> "phone unlocked"
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> "phone airplane mode ${intent.getBooleanExtra("state", false)}"
            BluetoothAdapter.ACTION_STATE_CHANGED -> "phone Bluetooth " + adapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
            BluetoothDevice.ACTION_ACL_CONNECTED -> "phone Bluetooth connection up: ${deviceKind(intent)}"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "phone Bluetooth connection down: ${deviceKind(intent)}"
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> "phone Bluetooth pairing ${bondState(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1))}: ${deviceKind(intent)}"
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "phone idle (Doze) ${power?.isDeviceIdleMode}"
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "phone battery saver ${power?.isPowerSaveMode}"
            else -> return
        }
        WearOsPlugin.trace(event)
    }

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { WearOsPlugin.trace("phone memory trim level $level") }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() { WearOsPlugin.trace("phone low memory") }
    }

    companion object {
        @Volatile private var registered: WearDeviceEvents? = null

        /** Registers the receiver while [enabled], unregisters it otherwise. Main thread. */
        fun update(app: OsmandApplication, enabled: Boolean) {
            val current = registered
            if (enabled && current == null) {
                val receiver = WearDeviceEvents(app)
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    if (Build.VERSION.SDK_INT >= 23) addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
                runCatching {
                    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    app.registerComponentCallbacks(receiver.memory)
                    registered = receiver
                }.onFailure { WearOsPlugin.trace("phone device events not registered: ${LinkDiagnostics.describe(it)}") }
            } else if (!enabled && current != null) {
                runCatching { app.unregisterReceiver(current) }
                runCatching { app.unregisterComponentCallbacks(current.memory) }
                registered = null
            }
        }

        /**
         * The phone conditions behind a session or location decision: lock, screen, whether OsmAnd
         * is in front and has a task, permissions, and Android's background and battery limits.
         */
        fun state(app: OsmandApplication): String = runCatching {
            val power = app.getSystemService(PowerManager::class.java)
            val activity = app.getSystemService(ActivityManager::class.java)
            val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
            val background = if (Build.VERSION.SDK_INT >= 29)
                ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED else null
            val bluetooth = if (Build.VERSION.SDK_INT >= 31)
                ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true
            "locked=$locked screenOn=${power?.isInteractive} foreground=${app.isAppInForegroundOnRootDevice} " +
                "task=${activity?.appTasks?.isNotEmpty()} location=${OsmAndLocationProvider.isLocationPermissionAvailable(app)} " +
                "backgroundLocation=$background bluetooth=$bluetooth osmandService=${osmandService(app)} ${limits(app)}"
        }.getOrElse { "state unavailable: ${LinkDiagnostics.describe(it)}" }

        /** What OsmAnd's own navigation service (its location foreground service) is running for, or "off". */
        fun osmandService(app: OsmandApplication): String = app.navigationService?.let { service ->
            listOfNotNull("navigation".takeIf { service.isUsedBy(NavigationService.USED_BY_NAVIGATION) },
                "recording".takeIf { service.isUsedBy(NavigationService.USED_BY_GPX) },
                "AIS".takeIf { service.isUsedBy(NavigationService.USED_BY_AIS) }).joinToString("+").ifEmpty { "idle" }
        } ?: "off"

        /** Android's background and battery limits for OsmAnd. */
        fun limits(app: OsmandApplication): String = runCatching {
            val power = app.getSystemService(PowerManager::class.java)
            val activity = app.getSystemService(ActivityManager::class.java)
            val exempt = if (Build.VERSION.SDK_INT >= 23) power?.isIgnoringBatteryOptimizations(app.packageName) else null
            val restricted = if (Build.VERSION.SDK_INT >= 28) activity?.isBackgroundRestricted else null
            val bucket = if (Build.VERSION.SDK_INT >= 28)
                app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.let(::bucketName) else null
            val airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "batteryExempt=$exempt backgroundRestricted=$restricted standby=$bucket batterySaver=${power?.isPowerSaveMode} " +
                "doze=${if (Build.VERSION.SDK_INT >= 23) power?.isDeviceIdleMode else null} airplane=$airplane"
        }.getOrElse { "limits unavailable: ${LinkDiagnostics.describe(it)}" }

        /** Why the previous OsmAnd process ended and why this one started, as Android recorded it. */
        fun process(app: OsmandApplication): String {
            val activity = app.getSystemService(ActivityManager::class.java) ?: return "process info unavailable"
            val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
                activity.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()?.let {
                    "previous process ended ${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date(it.timestamp))}: " +
                        "${exitReason(it.reason)} (importance ${it.importance}${it.description?.let { text -> ", " + LinkDiagnostics.scrub(text) } ?: ""})"
                } ?: "no previous process recorded"
            }.getOrElse { "exit reason unavailable: ${LinkDiagnostics.describe(it)}" } else "exit reason needs Android 11"
            val start = if (Build.VERSION.SDK_INT >= 35) runCatching {
                activity.getHistoricalProcessStartReasons(1).firstOrNull()?.let {
                    "this process started by ${startReason(it.reason)} (${startType(it.startType)})"
                } ?: "start reason not recorded"
            }.getOrElse { "start reason unavailable: ${LinkDiagnostics.describe(it)}" } else "start reason needs Android 15"
            return "$exit; $start"
        }

        private fun adapterState(state: Int) = when (state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning off"
            else -> "unknown"
        }

        private fun bondState(state: Int) = when (state) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_NONE -> "removed"
            else -> "unknown"
        }

        /** The device's major class, never its name or address. */
        @Suppress("DEPRECATION", "MissingPermission")
        private fun deviceKind(intent: Intent): String = runCatching {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (device?.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.WEARABLE -> "wearable"
                BluetoothClass.Device.Major.PHONE -> "phone"
                BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio"
                BluetoothClass.Device.Major.COMPUTER -> "computer"
                BluetoothClass.Device.Major.UNCATEGORIZED -> "uncategorized"
                null -> "unknown"
                else -> "other"
            }
        }.getOrDefault("unknown")

        private fun bucketName(bucket: Int) = when (bucket) {
            5 -> "exempted"
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            45 -> "restricted"
            50 -> "never"
            else -> "bucket $bucket"
        }

        private fun exitReason(reason: Int) = when (reason) {
            1 -> "exited itself"
            2 -> "killed by signal"
            3 -> "low memory"
            4 -> "crash"
            5 -> "native crash"
            6 -> "not responding (ANR)"
            7 -> "initialisation failure"
            8 -> "permission changed"
            9 -> "excessive resource use"
            10 -> "user request"
            11 -> "user stopped the app"
            12 -> "dependency died"
            13 -> "other system reason"
            14 -> "freezer"
            15 -> "package state changed"
            16 -> "package updated"
            else -> "unknown reason $reason"
        }

        private fun startReason(reason: Int) = when (reason) {
            0 -> "alarm"
            1 -> "backup"
            2 -> "boot"
            3 -> "broadcast"
            4 -> "content provider"
            5 -> "job"
            6 -> "launcher"
            7 -> "recent apps"
            8 -> "other"
            9 -> "push message"
            10 -> "service"
            11 -> "activity start"
            else -> "reason $reason"
        }

        private fun startType(type: Int) = when (type) {
            1 -> "cold"
            2 -> "warm"
            3 -> "hot"
            else -> "type $type"
        }
    }
}
