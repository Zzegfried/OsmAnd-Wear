package net.osmand.plus.plugins.wear

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.ListPreferenceEx
import net.osmand.plus.settings.preferences.SwitchPreferenceEx

/** Phone-side controls, pairing prompt and honest status for the matching Wear OS companion. */
class WearSettingsFragment : BaseSettingsFragment() {
    private var connection: Preference? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pairingDialog: AlertDialog? = null
    private var forgetDialog: AlertDialog? = null
    /** Asked once per visit; closing the system dialog resumes this screen, which must not ask again. */
    private var permissionAsked = false
    /** Denied with "don't ask again": the system no longer shows the dialog, only app settings can grant it. */
    private var permissionBlocked = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        permissionAsked = savedInstanceState?.getBoolean(PERMISSION_ASKED_KEY) ?: false
        permissionBlocked = savedInstanceState?.getBoolean(PERMISSION_BLOCKED_KEY) ?: false
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(PERMISSION_ASKED_KEY, permissionAsked)
        outState.putBoolean(PERMISSION_BLOCKED_KEY, permissionBlocked)
    }

    private fun plugin() = PluginsHelper.getPlugin(WearOsPlugin::class.java)
    private fun transport() = PhoneTransportFactory.get(requireContext())
    private fun setup() = transport().setup()

    override fun setupPreferences() {
        val plugin = plugin() ?: return

        val alerts = category(R.string.wear_watch_alerts_category)
        alerts.addPreference(toggle(plugin.watchOffRoute.id, R.string.wear_watch_off_route,
            R.string.wear_watch_alerts_summary, plugin.watchOffRoute.getModeValue(selectedAppMode)))
        alerts.addPreference(toggle(plugin.watchRouteRecalculated.id, R.string.wear_watch_route_recalculated,
            R.string.wear_watch_alerts_summary, plugin.watchRouteRecalculated.getModeValue(selectedAppMode)))

        val map = category(R.string.wear_watch_map_category)
        map.addPreference(choice(plugin.watchLabelScale.id, R.string.wear_watch_label_scale,
            arrayOf(getString(R.string.wear_watch_label_follow_phone), getString(R.string.wear_watch_label_125),
                getString(R.string.wear_watch_label_150)), arrayOf(100, 125, 150),
            plugin.watchLabelScale.getModeValue(selectedAppMode)))
        map.addPreference(choice(plugin.watchInitialZoom.id, R.string.wear_watch_initial_zoom,
            arrayOf(getString(R.string.wear_watch_zoom_current), getString(R.string.wear_watch_zoom_out_one),
                getString(R.string.wear_watch_zoom_out_two)), arrayOf(0, -1, -2),
            plugin.watchInitialZoom.getModeValue(selectedAppMode)))
        map.addPreference(toggle(plugin.watchRuler.id, R.string.wear_watch_ruler,
            R.string.wear_watch_ruler_summary, plugin.watchRuler.getModeValue(selectedAppMode)))
        map.addPreference(toggle(plugin.watchClock.id, R.string.wear_watch_clock,
            R.string.wear_watch_clock_summary, plugin.watchClock.getModeValue(selectedAppMode)))

        val controls = category(R.string.wear_watch_activity_category)
        controls.addPreference(toggle(plugin.watchRecordingControls.id, R.string.wear_watch_recording_controls,
            R.string.wear_watch_recording_summary, plugin.watchRecordingControls.get()))

        val diagnostics = category(R.string.wear_watch_diagnostics_category)
        diagnostics.addPreference(toggle(plugin.diagnosticLogging.id, R.string.wear_watch_diagnostic_logging,
            R.string.wear_watch_diagnostic_logging_summary, plugin.diagnosticLogging.get()))

        connection = Preference(requireContext()).apply {
            key = "wear_connection_status"
            setTitle(R.string.wear_watch_status)
            layoutResource = R.layout.preference_with_descr
            isPersistent = false
            isSelectable = false
            setOnPreferenceClickListener {
                if (missingPermission() != null) requestBluetoothPermission()
                true
            }
        }.also(::addOnPreferencesScreen)
        Preference(requireContext()).apply {
            key = FORGET_KEY
            setTitle(R.string.wear_watch_forget)
            setSummary(R.string.wear_watch_forget_summary)
            layoutResource = R.layout.preference_with_descr
            isPersistent = false
            isSelectable = true
            setOnPreferenceClickListener {
                showForgetDialog()
                true
            }
        }.also(::addOnPreferencesScreen)
        // Last row: the companion version shared with the watch app, which shows the same text on Info.
        Preference(requireContext()).apply {
            key = VERSION_KEY
            title = getString(R.string.wear_companion_version, getString(R.string.wear_companion_version_name),
                getString(R.string.wear_companion_build))
            layoutResource = R.layout.preference_with_descr
            isPersistent = false
            isSelectable = false
        }.also(::addOnPreferencesScreen)
        refreshConnection()
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(title)
        layoutResource = R.layout.preference_category_with_descr
        isIconSpaceReserved = true
    }.also(::addOnPreferencesScreen)

    private fun toggle(key: String, title: Int, description: Int, value: Boolean): SwitchPreferenceEx =
        createSwitchPreferenceEx(key, getString(title), getString(description),
            R.layout.preference_with_descr_dialog_and_switch).apply { isChecked = value }

    private fun choice(key: String, title: Int, names: Array<String>, values: Array<Int>,
                       value: Int): ListPreferenceEx = createListPreferenceEx(key, names, values,
        title, R.layout.preference_with_descr).apply { setValue(value) }

    private fun status(): String = listOfNotNull(
        getString(if (WearOsPlugin.allowed(app)) R.string.wear_watch_status_enabled else R.string.wear_watch_status_disabled),
        setup()?.statusText(requireContext()),
        missingPermission()?.let { getString(R.string.wear_watch_bluetooth_permission_retry) },
        getString(if (WearLocationService.hasActiveSession()) R.string.wear_watch_status_session_active
            else R.string.wear_watch_status_session_inactive),
        getString(R.string.wear_watch_status_notifications)
    ).joinToString("\n")

    private fun refreshConnection() {
        connection?.summary = status()
        // The status row becomes the retry control while the Bluetooth permission is missing.
        connection?.isSelectable = WearOsPlugin.allowed(app) && missingPermission() != null
    }

    /** The adapter's runtime permission when it is required and not granted, otherwise null. */
    private fun missingPermission(): String? {
        if (!isAdded || Build.VERSION.SDK_INT < 31) return null
        return setup()?.permission()?.takeIf {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** User-initiated: shows the system dialog, or app settings once the dialog is blocked. */
    private fun requestBluetoothPermission() {
        val permission = missingPermission() ?: return
        if (permissionBlocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)))
            return
        }
        permissionAsked = true
        requestedPermission = permission
        permissionRequest.launch(permission)
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference.key == FORGET_KEY) {
            showForgetDialog()
            return true
        }
        return super.onPreferenceClick(preference)
    }

    private fun showForgetDialog() {
        if (!isAdded || forgetDialog != null) return
        forgetDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.wear_watch_forget)
            .setMessage(R.string.wear_watch_forget_message)
            .setPositiveButton(R.string.shared_string_remove) { _, _ ->
                if (setup()?.forgetPairing() == true) {
                    Toast.makeText(requireContext(), R.string.wear_watch_forget_done, Toast.LENGTH_LONG).show()
                    ensureTransport()
                }
                refreshConnection()
            }
            .setNegativeButton(R.string.shared_string_cancel, null)
            .setOnDismissListener { forgetDialog = null }
            .show()
    }

    /**
     * The listener starts only from this visible settings screen. The first pair still requires the
     * eight-digit code confirmation; a running link is stopped from the notification or by disabling
     * the plugin.
     */
    private fun ensureTransport() {
        if (!WearOsPlugin.allowed(app) || !isAdded) return
        if (missingPermission() != null) {
            // Ask automatically only once; after a denial the status row offers the retry.
            if (!permissionAsked) requestBluetoothPermission()
            return
        }
        permissionBlocked = false
        if (transport().state().let { it == LinkState.OFF || it == LinkState.UNAVAILABLE }) transport().start()
    }

    private val pollPairing = object : Runnable {
        override fun run() {
            if (!isAdded) return
            refreshConnection()
            val code = setup()?.pairingCode()
            if (code != null && pairingDialog == null) showPairing(code)
            handler.postDelayed(this, 500)
        }
    }

    private fun showPairing(code: String) {
        if (!isAdded || pairingDialog != null) return
        pairingDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.wear_watch_pair_title)
            .setMessage(getString(R.string.wear_watch_pair_message, code))
            .setPositiveButton(R.string.shared_string_ok) { _, _ -> setup()?.confirmPairing(true) }
            .setNegativeButton(R.string.shared_string_cancel) { _, _ -> setup()?.confirmPairing(false) }
            .setOnDismissListener { pairingDialog = null }
            .show()
    }

    override fun onResume() {
        super.onResume()
        ensureTransport()
        refreshConnection()
        handler.post(pollPairing)
    }

    override fun onPause() {
        handler.removeCallbacks(pollPairing)
        if (pairingDialog != null) setup()?.confirmPairing(false)
        pairingDialog?.dismiss()
        pairingDialog = null
        forgetDialog?.dismiss()
        forgetDialog = null
        super.onPause()
    }

    /** The permission the open system dialog asks for, read by the result callback. */
    private var requestedPermission: String? = null

    /** Registered with the fragment so the result survives the system dialog and recreation. */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!isAdded) return@registerForActivityResult
        // After recreation the field is empty; a denied permission is then still the missing one.
        val permission = requestedPermission ?: missingPermission()
        requestedPermission = null
        if (granted) {
            permissionBlocked = false
            ensureTransport()
        } else {
            // After a denial, no rationale means "don't ask again": the system dialog is blocked.
            permissionBlocked = permission?.let { !shouldShowRequestPermissionRationale(it) } == true
        }
        refreshConnection()
    }

    companion object {
        private const val FORGET_KEY = "wear_forget_watch"
        private const val VERSION_KEY = "wear_companion_version"
        private const val PERMISSION_ASKED_KEY = "wear_bluetooth_permission_asked"
        private const val PERMISSION_BLOCKED_KEY = "wear_bluetooth_permission_blocked"
    }
}
