package net.osmand.plus.plugins.wear

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Bluetooth build input: the only factory compiled into this variant. A Play-only build selects
 * `src-dataLayer` instead (via the `transport` Gradle property), which supplies the same symbol with
 * a Data Layer implementation. There is no runtime transport branch.
 */
internal object PhoneTransportFactory {
    @Volatile private var instance: PhoneTransport? = null

    fun get(context: Context): PhoneTransport = instance ?: synchronized(this) {
        instance ?: BluetoothPhoneTransport(context.applicationContext).also { instance = it }
    }

    /** Whether this build's platform transport component is present. */
    fun supported(context: Context): Boolean = try {
        context.packageManager.getServiceInfo(ComponentName(context, WearBluetoothService::class.java), 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
