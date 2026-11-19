package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.osmand.plus.plugins.wear.protocol.MapProtocol.POSITION_FRESH
import net.osmand.plus.OsmandApplication
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.monitoring.OsmandMonitoringPlugin
import net.osmand.plus.settings.enums.LocationSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.util.concurrent.TimeUnit

/** System location pipeline under a real secure lock. Synthetic AVD positions only. */
@RunWith(AndroidJUnit4::class)
class WearLocationSessionTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val app get() = test.targetContext.applicationContext as OsmandApplication
    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        test.runOnMainSync { try { block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }
    private fun shell(command: String) = test.uiAutomation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    private fun waitFor(label: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        check(condition()) { "Timeout: $label" }
    }
    private fun live(): WearLocationService? = WearLocationService::class.java.getDeclaredField("live").apply { isAccessible = true }.get(null) as WearLocationService?
    /** A synthetic GPS provider: satellite-based, with altitude, speed and bearing, high power and fine accuracy. */
    private fun addGpsTestProvider(manager: LocationManager) {
        if (Build.VERSION.SDK_INT >= 31) {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, android.location.provider.ProviderProperties.Builder()
                .setHasNetworkRequirement(false).setHasSatelliteRequirement(true).setHasCellRequirement(false)
                .setHasMonetaryCost(false).setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_HIGH)
                .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE).build())
        } else {
            // The pre-Android 12 form; its Criteria constants are the only way to state power and accuracy there.
            @Suppress("DEPRECATION")
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                android.location.Criteria.POWER_HIGH, android.location.Criteria.ACCURACY_FINE)
        }
    }
    private fun assertSessionAccepted(status: Int) {
        assertTrue("Expected active or starting location service, got $status",
            status == MapProtocol.SESSION_READY || status == MapProtocol.SESSION_CONNECTING)
    }
    /**
     * With OsmAnd in the background the phone always attempts the start, so the watch first waits.
     * Android then grants it (READY) or refuses the location type (PHONE_REQUIRED); returns which.
     */
    private fun attemptedStart(peer: String, id: UUID, state: String): Int {
        main { assertEquals(MapProtocol.SESSION_CONNECTING, WearLocationService.refresh(app, peer, id)) }
        val helper = WearLocationService::class.java.getDeclaredField("helper").apply { isAccessible = true }
        waitFor("$state start outcome") { !WearOsPlugin.locationStarting && live().let { it == null || helper.get(it) != null } }
        var status = -1
        main { status = WearLocationService.refresh(app, peer, id) }
        assertTrue("Expected READY or PHONE_REQUIRED, got $status",
            status == MapProtocol.SESSION_READY || status == MapProtocol.SESSION_PHONE_REQUIRED)
        android.util.Log.i("WearLocationSessionTest", "$state start ${if (status == MapProtocol.SESSION_READY) "granted" else "refused"}")
        return status
    }
    @Test(timeout = 20_000)
    fun watchPluginSettingsAndRecordingGate() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
        val profile = app.settings.applicationMode
        val oldOffRoute = plugin.watchOffRoute.getModeValue(profile)
        val oldRecalculated = plugin.watchRouteRecalculated.getModeValue(profile)
        val oldLabels = plugin.watchLabelScale.getModeValue(profile)
        val oldZoom = plugin.watchInitialZoom.getModeValue(profile)
        val oldRecording = plugin.watchRecordingControls.get()
        val oldRuler = plugin.watchRuler.getModeValue(profile)
        try {
            main {
                plugin.watchLabelScale.setModeValue(profile, 100)
                plugin.watchInitialZoom.setModeValue(profile, 0)
                plugin.watchRuler.setModeValue(profile, true)
            }
            assertEquals(1f, WearOsPlugin.labelScale(app))
            assertEquals(0, WearOsPlugin.initialZoomOffset(app))
            assertTrue(WearOsPlugin.rulerEnabled(app))
            val allowed = WidgetProtocol.Snapshot(UUID.randomUUID(), WidgetProtocol.OK,
                System.currentTimeMillis(), recordingControlsAllowed = false)
            val encoded = WidgetProtocol.encode(allowed)
            assertEquals(false, WidgetProtocol.snapshot(encoded)?.recordingControlsAllowed)
            assertNull(WidgetProtocol.snapshot(encoded.copyOf(encoded.size - 1)))
            main {
                plugin.watchOffRoute.setModeValue(profile, false)
                plugin.watchRouteRecalculated.setModeValue(profile, false)
                plugin.watchLabelScale.setModeValue(profile, 125)
                plugin.watchInitialZoom.setModeValue(profile, -1)
                plugin.watchRecordingControls.set(false)
                plugin.watchRuler.setModeValue(profile, false)
                assertFalse(WearOsPlugin.offRouteEnabled(app))
                assertFalse(WearOsPlugin.routeRecalculatedEnabled(app))
                assertFalse(WearOsPlugin.rulerEnabled(app))
                assertEquals(1.25f, WearOsPlugin.labelScale(app))
                assertEquals(-1, WearOsPlugin.initialZoomOffset(app))
                assertEquals(WidgetProtocol.DENIED, WearWidgetActions.perform(app, "synthetic-settings",
                    UUID.randomUUID(), WidgetProtocol.START, WidgetProtocol.INACTIVE))
                plugin.watchLabelScale.setModeValue(profile, 150)
                plugin.watchInitialZoom.setModeValue(profile, -2)
                assertEquals(1.5f, WearOsPlugin.labelScale(app))
                assertEquals(-2, WearOsPlugin.initialZoomOffset(app))
            }
        } finally {
            main {
                plugin.watchOffRoute.setModeValue(profile, oldOffRoute)
                plugin.watchRouteRecalculated.setModeValue(profile, oldRecalculated)
                plugin.watchLabelScale.setModeValue(profile, oldLabels)
                plugin.watchInitialZoom.setModeValue(profile, oldZoom)
                plugin.watchRecordingControls.set(oldRecording)
                plugin.watchRuler.setModeValue(profile, oldRuler)
            }
        }
    }
    @Test(timeout = 30_000)
    fun watchRulerFitsQuarterWidth() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
        val profile = app.settings.applicationMode
        val oldRuler = plugin.watchRuler.getModeValue(profile)
        val box = net.osmand.data.RotatedTileBox.RotatedTileBoxBuilder().setLocation(47.142, 9.5205)
            .setZoom(15).density(1f).setMapDensity(1.0).setPixelDimensions(400, 400, 0.5f, 0.5f).build()
        try {
            main { plugin.watchRuler.setModeValue(profile, true) }
            val ruler = checkNotNull(WearRuler.read(app, box)) { "No ruler for a standard watch view" }
            assertTrue("ratio=${ruler.ratio}", ruler.ratio in 0.1f..0.45f)
            assertTrue(ruler.label.isNotBlank() && ruler.label.length <= 24)
            main { plugin.watchRuler.setModeValue(profile, false) }
            assertNull(WearRuler.read(app, box))
        } finally {
            main { plugin.watchRuler.setModeValue(profile, oldRuler) }
        }
    }
    @Test(timeout = 120_000)
    fun watchRecordingUsesExistingLocationServiceUnderLock() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(InstrumentationRegistry.getArguments().getString("widgetRecording") == "true")
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure && !app.routingHelper.isFollowingMode)
        val monitor = checkNotNull(PluginsHelper.getPlugin(OsmandMonitoringPlugin::class.java))
        check(!monitor.isRecordingTrack && !monitor.hasDataToSave()) { "Existing recording data must remain untouched" }
        val wear = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
        val wearEnabled = wear.isEnabled; val monitorEnabled = monitor.isEnabled
        val settings = app.settings; val profile = settings.applicationMode
        val oldSource = settings.LOCATION_SOURCE.get()
        val oldSeparate = settings.USE_SEPARATE_LAYOUTS.get()
        val oldVisibility = settings.getMapInfoControls(null).getModeValue(profile)
        val oldInterval = settings.SAVE_GLOBAL_TRACK_INTERVAL.get()
        val oldDistance = settings.SAVE_TRACK_MIN_DISTANCE.get()
        val oldPrecision = settings.SAVE_TRACK_PRECISION.get()
        val oldSpeed = settings.SAVE_TRACK_MIN_SPEED.get()
        val oldLock = shell("settings get secure lock_screen_lock_after_timeout").trim()
        val manager = app.getSystemService(LocationManager::class.java)
        val recordedDir = app.appCustomization.tracksDir
        val savedBefore = if (recordedDir.exists()) recordedDir.walkTopDown()
            .filter { it.isFile }.map { it.canonicalPath }.toSet() else emptySet()
        val pin = "698241"; var pinSet = false; var mockAdded = false
        val peer = "synthetic-widget-owner"; val id = UUID.randomUUID()
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("phone foreground") { app.isAppInForeground }
            main {
                assertTrue(PluginsHelper.enablePlugin(null, app, wear, true))
                assertTrue(PluginsHelper.enablePlugin(null, app, monitor, true))
                settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API)
                settings.USE_SEPARATE_LAYOUTS.set(false)
                settings.getMapInfoControls(null).setModeValue(profile, "monitoring;")
                settings.SAVE_GLOBAL_TRACK_INTERVAL.set(0)
                settings.SAVE_TRACK_MIN_DISTANCE.set(0f)
                settings.SAVE_TRACK_PRECISION.set(0f)
                settings.SAVE_TRACK_MIN_SPEED.set(0f)
            }
            addGpsTestProvider(manager)
            mockAdded = true; manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, id)) }
            waitFor("existing watch location service") { live() != null }
            check(shell("locksettings set-pin $pin").contains("set", true)); pinSet = true
            shell("settings put secure lock_screen_lock_after_timeout 0"); shell("input keyevent 223")
            waitFor("secure lock") { keyguard.isDeviceLocked && !app.isAppInForeground }
            fun action(command: Int, expected: Int) {
                main { assertEquals(WidgetProtocol.OK, WearWidgetActions.perform(app, peer, id, command, expected)) }
            }
            fun fix(step: Int) {
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, android.location.Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 47.142 + step * 0.0001; longitude = 9.5205; time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); accuracy = 3f; speed = 1.4f; bearing = 90f
                })
            }
            action(WidgetProtocol.START, WidgetProtocol.INACTIVE)
            assertTrue(monitor.isRecordingTrack); assertNull(app.navigationService)
            val before = app.savingTrackHelper.trkPoints
            for (step in 1..4) { fix(step); SystemClock.sleep(500) }
            val latestFix = WearOsPlugin.latestLocation
            val automaticPoints = app.savingTrackHelper.trkPoints
            if (automaticPoints <= before && latestFix != null) {
                main { app.locationProvider.setLocationFromService(latestFix) }
            }
            assertTrue("No automatic track points: watchFix=${latestFix != null}, " +
                "recordingHold=${live()?.javaClass?.getDeclaredField("recordingFromWatch")?.apply { isAccessible = true }?.getBoolean(live())}, " +
                "manualFeedAddsPoint=${app.savingTrackHelper.trkPoints > automaticPoints}", automaticPoints > before)
            action(WidgetProtocol.PAUSE, WidgetProtocol.RECORDING)
            assertFalse(monitor.isRecordingTrack)
            val paused = app.savingTrackHelper.trkPoints
            for (step in 5..7) { fix(step); SystemClock.sleep(500) }
            assertEquals("Paused track gained points", paused, app.savingTrackHelper.trkPoints)
            action(WidgetProtocol.RESUME, WidgetProtocol.PAUSED)
            for (step in 8..11) { fix(step); SystemClock.sleep(500) }
            waitFor("resumed track points") { app.savingTrackHelper.trkPoints > paused }
            assertTrue(keyguard.isDeviceLocked); assertNull(app.navigationService)
            val beforeRelease = app.savingTrackHelper.trkPoints
            main { WearLocationService.release(peer, id) }
            assertNotNull("Recording must keep its existing location service", live())
            assertNull(WearOsPlugin.latestLocation)
            for (step in 12..15) { fix(step); SystemClock.sleep(500) }
            waitFor("recording after watch closes") { app.savingTrackHelper.trkPoints > beforeRelease }
            val newSession = UUID.randomUUID()
            main {
                assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, peer, newSession))
                assertTrue(WearLocationService.owns(peer, newSession))
            }
            main { assertEquals(WidgetProtocol.OK, WearWidgetActions.perform(app, peer, newSession,
                WidgetProtocol.FINISH, WidgetProtocol.RECORDING)) }
            waitFor("synthetic GPX save", 20_000) {
                !monitor.isSaving && !app.savingTrackHelper.hasDataToSave()
            }
            assertFalse(monitor.isRecordingTrack)
            val savedAfter = recordedDir.walkTopDown().filter { it.isFile }
                .map { it.canonicalPath }.toSet()
            assertTrue("Finish did not create a synthetic GPX", (savedAfter - savedBefore)
                .any { it.endsWith(".gpx", ignoreCase = true) })
        } finally {
            main {
                monitor.stopRecording(true)
                WearLocationService.disableRecording()
                WearLocationService.release(peer, id)
                app.stopService(Intent(app, WearLocationService::class.java))
                WearOsPlugin.pendingLocation = null; WearOsPlugin.pendingLocationAt = 0
            }
            if (pinSet) shell("locksettings clear --old $pin")
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            if (oldLock == "null") shell("settings delete secure lock_screen_lock_after_timeout")
            else shell("settings put secure lock_screen_lock_after_timeout $oldLock")
            if (mockAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            // Remove only files created by this synthetic test, never pre-existing tracks.
            if (recordedDir.exists()) recordedDir.walkTopDown().filter { it.isFile }
                .filter { it.canonicalPath !in savedBefore }.toList().forEach { it.delete() }
            main {
                settings.LOCATION_SOURCE.set(oldSource)
                settings.USE_SEPARATE_LAYOUTS.set(oldSeparate)
                settings.getMapInfoControls(null).setModeValue(profile, oldVisibility)
                settings.SAVE_GLOBAL_TRACK_INTERVAL.set(oldInterval)
                settings.SAVE_TRACK_MIN_DISTANCE.set(oldDistance)
                settings.SAVE_TRACK_PRECISION.set(oldPrecision)
                settings.SAVE_TRACK_MIN_SPEED.set(oldSpeed)
                PluginsHelper.enablePlugin(null, app, monitor, monitorEnabled)
                PluginsHelper.enablePlugin(null, app, wear, wearEnabled)
            }
        }
    }

    @Test(timeout = 120_000)
    fun freshFixUnderLockAndSessionCleanup() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "Only the isolated AVD without an existing lock credential is allowed" }
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get()) { "Do not disturb navigation/recording" }
        val manager = app.getSystemService(LocationManager::class.java)
        val oldSource = app.settings.LOCATION_SOURCE.get()
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
        val wasEnabled = plugin.isEnabled
        val oldLock = shell("settings get secure lock_screen_lock_after_timeout").trim()
        val pin = "698241" // Disposable AVD credential; never used on physical devices.
        var pinSet = false; var mockAdded = false
        val peer = "synthetic-location-owner"; val id = UUID.randomUUID()
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("phone app foreground") { app.isAppInForeground }
            main { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)); app.settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API); WearBluetoothService.start(app) }
            addGpsTestProvider(manager)
            mockAdded = true; manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, id)) }
            waitFor("watch location foreground service") { live() != null }
            fun helper() = live()?.let { service -> service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service) }
            fun helperName() = helper()?.javaClass?.simpleName
            waitFor("GPS subscription") { helperName() == "AndroidApiLocationServiceHelper" }
            fun fix(step: Int) {
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, android.location.Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 47.142 + step * 0.000001; longitude = 9.5205; time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); accuracy = 3f; speed = 1.4f; bearing = 90f
                })
            }
            fix(0)
            waitFor("first watch-owned fix") { WearOsPlugin.latestLocation != null }
            val foreground = live()
            main { WearLocationService.release(peer, UUID.randomUUID()) }
            assertSame("Wrong session must not stop GPS", foreground, live())
            // A setting change replaces only this subscription, then resumes actual system GPS.
            // Wait for a new helper, not just its class: F-Droid builds use the Android API for both sources.
            val androidHelper = helper()
            main { app.settings.LOCATION_SOURCE.set(LocationSource.GOOGLE_PLAY_SERVICES) }
            val googleHelper = if (runCatching { Class.forName("net.osmand.plus.helpers.GmsLocationServiceHelper") }.isSuccess)
                "GmsLocationServiceHelper" else "AndroidApiLocationServiceHelper"
            waitFor("Google location source") { helper().let { it != null && it !== androidHelper } && helperName() == googleHelper }
            val sourceHelper = helper()
            main { app.settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API) }
            waitFor("Android location source") { helper().let { it != null && it !== sourceHelper } && helperName() == "AndroidApiLocationServiceHelper" }
            fix(0); waitFor("fix after source change") { WearOsPlugin.latestLocation != null }
            check(shell("locksettings set-pin $pin").contains("set", true)); pinSet = true
            shell("settings put secure lock_screen_lock_after_timeout 0"); shell("input keyevent 223")
            waitFor("secure phone lock") { keyguard.isDeviceLocked && !app.isAppInForeground }
            val lockedAt = System.currentTimeMillis()
            for (step in 1..35) { assertTrue(keyguard.isDeviceLocked); fix(step); SystemClock.sleep(1_000) }
            val snapshot = MapPreviewRenderer.snapshot(app, MapRequest(UUID.randomUUID(), MapView(47.142, 9.5205, 15, 400, 400), false), CancellationSignal(),
                watchLocation = WearOsPlugin.location(app))
            val position = checkNotNull(snapshot.position)
            assertEquals(POSITION_FRESH, position.status); assertTrue(position.utc > lockedAt + 30_000)
            assertEquals(90f, position.bearing); assertEquals(47.142035, position.latitude, 0.000002)
            assertTrue(keyguard.isDeviceLocked)
            assertFalse(app.routingHelper.isFollowingMode); assertFalse(app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
            assertNull("Watch GPS must not create a navigation/recording service", app.navigationService)
            main { WearLocationService.release(peer, id) }
            waitFor("correct-session cleanup") { live() == null && WearOsPlugin.latestLocation == null }
            // Under lock the start is attempted at once; Android grants it (this emulator does) or refuses it.
            val lockedId = UUID.randomUUID()
            if (attemptedStart(peer, lockedId, "locked") == MapProtocol.SESSION_READY) {
                main { WearLocationService.release(peer, lockedId) }
                waitFor("locked session cleanup") { live() == null }
            }
            assertNull(live())
            shell("locksettings clear --old $pin"); pinSet = false; shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("foreground again") { app.isAppInForeground }
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, id)) }
            waitFor("restarted service") { live() != null }
            main { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, false)) }
            waitFor("plugin revocation cleanup") { live() == null && WearOsPlugin.latestLocation == null }
            main { assertEquals(MapProtocol.SESSION_DENIED, WearLocationService.refresh(app, peer, id)); assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, id)) }
            waitFor("service for notification stop") { live() != null }
            main { app.startService(Intent(app, WearLocationService::class.java).setAction("net.osmand.wear.STOP_LOCATION")) }
            waitFor("notification stop cleanup") { live() == null && WearOsPlugin.latestLocation == null }
            main { assertEquals(MapProtocol.SESSION_STOPPED, WearLocationService.refresh(app, peer, id)) }
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, UUID.randomUUID())) }
            waitFor("service for peer-loss check") { live() != null }
            main {
                val service = checkNotNull(live())
                service.javaClass.getDeclaredField("confirmed").apply { isAccessible = true }
                    .setLong(service, SystemClock.elapsedRealtime() - 180_001)
                service.javaClass.getDeclaredMethod("checkWatch").apply { isAccessible = true }.invoke(service)
            }
            waitFor("absent peer cleanup") { live() == null }
        } finally {
            main { WearLocationService.release(peer); app.stopService(Intent(app, WearLocationService::class.java)); WearOsPlugin.pendingLocation = null; WearOsPlugin.pendingLocationAt = 0 }
            if (pinSet) shell("locksettings clear --old $pin")
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            if (oldLock == "null") shell("settings delete secure lock_screen_lock_after_timeout") else shell("settings put secure lock_screen_lock_after_timeout $oldLock")
            if (mockAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            main { app.settings.LOCATION_SOURCE.set(oldSource); PluginsHelper.enablePlugin(null, app, plugin, wasEnabled) }
        }
    }

    /** Real watch negotiates GPS, sleeps through the liveness probe, wakes and closes it. */
    @Test(timeout = 240_000)
    fun pairedGpsWhileBothScreensSleep() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure && !app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        val manager = app.getSystemService(LocationManager::class.java)
        val oldSource = app.settings.LOCATION_SOURCE.get()
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val wasEnabled = plugin.isEnabled
        val oldLock = shell("settings get secure lock_screen_lock_after_timeout").trim()
        val pin = "698241"; var pinSet = false; var mockAdded = false
        var step = 0
        fun fix() {
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, android.location.Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 47.142 + step++ * 0.000001; longitude = 9.5205; time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); accuracy = 3f; speed = 1.4f; bearing = 90f
            })
        }
        fun status(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("foreground") { app.isAppInForeground }
            main { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)); app.settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API); WearBluetoothService.start(app) }
            addGpsTestProvider(manager)
            mockAdded = true; manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            status("GPS_PAIRED_READY: public synthetic GPS; phone foreground; waiting for actual watch session")
            waitFor("actual paired watch starts GPS", 60_000) { fix(); live() != null }
            check(shell("locksettings set-pin $pin").contains("set", true)); pinSet = true
            shell("settings put secure lock_screen_lock_after_timeout 0"); shell("input keyevent 223")
            waitFor("secure phone lock") { keyguard.isDeviceLocked && !app.isAppInForeground }
            val lockedAt = SystemClock.elapsedRealtime()
            status("GPS_PAIRED_LOCKED: phone securely locked; watch-owned GPS active")
            var probes = 0L
            val until = SystemClock.elapsedRealtime() + 180_000
            while (live() != null && SystemClock.elapsedRealtime() < until) {
                assertTrue(keyguard.isDeviceLocked)
                assertFalse(app.getSystemService(PowerManager::class.java).isInteractive)
                assertFalse(app.routingHelper.isFollowingMode); assertFalse(app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get()); assertNull(app.navigationService)
                fix()
                val service = live()
                if (service != null) probes = maxOf(probes, service.javaClass.getDeclaredField("confirmed").apply { isAccessible = true }.getLong(service))
                SystemClock.sleep(500)
            }
            assertNull("Watch close must release phone GPS", live())
            assertNull(WearOsPlugin.latestLocation)
            assertTrue("Both-screen sleep must include the 60-second watch liveness probe", probes > lockedAt + 55_000)
            status("GPS_PAIRED_PASSED: real paired session, fresh system GPS under secure lock, successful sleeping-watch liveness probe and watch-close cleanup; no navigation/recording")
        } finally {
            main { app.stopService(Intent(app, WearLocationService::class.java)); WearOsPlugin.pendingLocation = null; WearOsPlugin.pendingLocationAt = 0 }
            if (pinSet) shell("locksettings clear --old $pin")
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            if (oldLock == "null") shell("settings delete secure lock_screen_lock_after_timeout") else shell("settings put secure lock_screen_lock_after_timeout $oldLock")
            if (mockAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            main { app.settings.LOCATION_SOURCE.set(oldSource); PluginsHelper.enablePlugin(null, app, plugin, wasEnabled) }
        }
    }

    /** A listener callback can end before its asynchronous renderer; retain exactly that job. */
    @Test(timeout = 90_000)
    fun asynchronousMapJobKeepsListenerBound() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val enabled = plugin.isEnabled
        fun waiting() = Thread.getAllStackTraces().filter { (thread, stack) ->
            thread.name == "OsmAndWatchMap" && stack.any { it.className == "net.osmand.plus.plugins.wear.MapPreviewRenderer" && it.methodName == "snapshot" }
        }.size
        try {
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            main { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
            synchronized(MapPreviewRenderer) {
                test.sendStatus(0, Bundle().apply { putString("stream", "RETENTION_READY: waiting for real paired watch map request\n") })
                waitFor("paired map worker at snapshot", 25_000) { waiting() > 0 }
                SystemClock.sleep(8_000) // Longer than the failed service lifetime and watch retry delay.
                assertEquals("Listener destruction allowed replacement workers while the first RPC was active", 1, waiting())
                val services = shell("dumpsys activity services net.osmand.dev/net.osmand.plus.plugins.wear.WearMapService")
                assertTrue("The asynchronous map job lost its listener service", services.contains("ServiceRecord{"))
                test.sendStatus(0, Bundle().apply { putString("stream", "RETENTION_PASSED: one original worker and listener survive eight seconds after callback return\n") })
            }
        } finally {
            main { PluginsHelper.enablePlugin(null, app, plugin, false) }
            waitFor("map worker cleanup", 20_000) { Thread.getAllStackTraces().keys.none { it.name == "OsmAndWatchMap" } }
            main { PluginsHelper.enablePlugin(null, app, plugin, enabled) }
        }
    }

    /** The warm-up is bounded even if no watch ever opens its map. No GPS starts while waiting. */
    @Test(timeout = 240_000)
    fun unclaimedPrestartExpiresWithoutGps() = withPrestartedLocation { _, service, notifications ->
        assertNull(service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service))
        assertFalse(WearLocationService.hasActiveSession())
        val waiting = notifications.activeNotifications.single { it.id == 10031 }.notification
        assertEquals(app.getString(net.osmand.plus.R.string.wear_os_location_waiting),
            waiting.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        waitFor("unclaimed prestart expiry", 195_000) {
            live() == null && notifications.activeNotifications.none { it.id == 10031 }
        }
        assertNull(WearOsPlugin.latestLocation)
        assertNull(app.navigationService)
        assertFalse(app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
    }

    /** A claimed warm-up must probe even with frequent refreshes, then release an expired lease. */
    @Test(timeout = 110_000)
    fun prestartClaimArmsSessionExpiry() = withPrestartedLocation { transport, service, notifications ->
        val session = UUID.randomUUID()
        shell("input keyevent 223")
        waitFor("phone screen off and background") {
            !app.getSystemService(PowerManager::class.java).isInteractive && !app.isAppInForegroundOnRootDevice
        }
        main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, session)) }
        waitFor("claimed prestart GPS subscription") {
            service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service) != null
        }
        assertSame("Claim must reuse the foreground service", service, live())
        val active = notifications.activeNotifications.single { it.id == 10031 }.notification
        assertEquals(app.getString(net.osmand.plus.R.string.wear_os_location_text),
            active.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())

        // Real Handler delivery, not a direct invocation, proves that admission armed the probe.
        // Refreshing once a second must not push that first 60-second probe into the future.
        val deadline = SystemClock.elapsedRealtime() + 75_000
        while (transport.probes.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, session)) }
            SystemClock.sleep(1_000)
        }
        assertEquals("A prestart claim must schedule a real session probe", 1, transport.probes.size)
        main { transport.probes.single().completeExceptionally(java.util.concurrent.TimeoutException("Synthetic silent watch")) }
        test.waitForIdleSync()
        assertSame("One missing response inside the lease must keep GPS", service, live())
        assertTrue(WearLocationService.owns(transport.peer.key, session))

        // Advance only the stored confirmation age; production timeout constants stay unchanged.
        main {
            service.javaClass.getDeclaredField("confirmed").apply { isAccessible = true }
                .setLong(service, SystemClock.elapsedRealtime() - 180_001)
            service.javaClass.getDeclaredMethod("checkWatch").apply { isAccessible = true }.invoke(service)
        }
        assertEquals("An overdue callback must allow one final probe", 2, transport.probes.size)
        main { transport.probes.last().completeExceptionally(java.util.concurrent.TimeoutException("Synthetic silent watch")) }
        waitFor("silent claimed-session expiry") {
            live() == null && notifications.activeNotifications.none { it.id == 10031 }
        }
        assertEquals("Only one final probe is allowed", 2, transport.probes.size)
        assertNull(WearOsPlugin.latestLocation)
        assertNull(app.navigationService)
        assertFalse(app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
    }

    @Test(timeout = 60_000)
    fun lockedReconnectRetainsLocationLease() = withPrestartedLocation { transport, service, _ ->
        val id = UUID.randomUUID()
        main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id)) }
        val helper = service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service)
        shell("input keyevent 223")
        waitFor("screen off and background") {
            !app.getSystemService(PowerManager::class.java).isInteractive && !app.isAppInForegroundOnRootDevice
        }
        var connected = true
        val router = WearRequestRouter(app) { connected && it == transport.peer.key }
        try {
            connected = false
            router.disconnected(transport.peer.key)
            test.waitForIdleSync()
            assertSame("Brief socket loss must retain the running location FGS", service, live())
            assertTrue(WearLocationService.owns(transport.peer.key, id))
            connected = true
            val reply = checkNotNull(router.request(transport.peer.key, MapProtocol.SESSION_PATH,
                MapProtocol.session(id, true))).get(5, TimeUnit.SECONDS)
            assertArrayEquals(MapProtocol.sessionReply(MapProtocol.SESSION_READY, false), reply.payload)
            assertSame(service, live())
            assertSame("Reconnect must reuse the GPS subscription", helper,
                service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service))
            var closing: java.util.concurrent.CompletableFuture<WearReply>? = null
            main {
                checkNotNull(router.request(transport.peer.key, MapProtocol.SESSION_PATH,
                    MapProtocol.session(id, true)))
                val busy = checkNotNull(router.request(transport.peer.key, MapProtocol.SESSION_PATH,
                    MapProtocol.session(id, true)))
                assertArrayEquals("Admission congestion must not ask the user to open the phone",
                    MapProtocol.sessionReply(MapProtocol.SESSION_BUSY, false), busy.getNow(null).payload)
                closing = checkNotNull(router.request(transport.peer.key, MapProtocol.SESSION_PATH,
                    MapProtocol.session(id, false)))
            }
            test.waitForIdleSync()
            assertArrayEquals("Close must bypass an overlapping open", MapProtocol.sessionReply(MapProtocol.SESSION_READY, false),
                checkNotNull(closing).get(5, TimeUnit.SECONDS).payload)
            waitFor("explicit close releases location") { live() == null }
        } finally { router.close() }
    }

    /** A sleeping watch display lowers the phone location rate; waking restores it, keeping the last fix. */
    @Test(timeout = 60_000)
    fun sleepingWatchLowersLocationRate() = withPrestartedLocation { transport, service, _ ->
        val id = UUID.randomUUID()
        fun interval() = service.javaClass.getDeclaredField("requestedInterval").apply { isAccessible = true }.getLong(service)
        main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id)) }
        assertEquals("Awake watch: full rate", 0L, interval())
        main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id, awake = false)) }
        assertEquals("Sleeping watch: reduced rate", WearLocationService.ASLEEP_INTERVAL_MS, interval())
        val fix = net.osmand.Location("synthetic-rate").apply { latitude = 47.142; longitude = 9.5205; time = System.currentTimeMillis() }
        main { WearOsPlugin.latestLocation = fix }
        main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id, awake = true)) }
        assertEquals("Waking restores full rate", 0L, interval())
        assertNotNull("Changing rate keeps the last fix for the first frame after waking", WearOsPlugin.latestLocation)
        main { WearLocationService.refresh(app, transport.peer.key, id, awake = false) }
        main { assertTrue(WearLocationService.enableRecording(transport.peer.key, id)) }
        assertEquals("Recording from the watch always uses full rate", 0L, interval())
        main { WearLocationService.disableRecording() }
        assertEquals("Ending the recording returns to the sleeping rate", WearLocationService.ASLEEP_INTERVAL_MS, interval())
    }

    @Test(timeout = 60_000)
    fun delayedWatchdogCanRenewButNegativeReplyReleases() = withPrestartedLocation { transport, service, _ ->
        val id = UUID.randomUUID()
        main { assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id)) }
        fun overdueProbe() = main {
            service.javaClass.getDeclaredField("confirmed").apply { isAccessible = true }
                .setLong(service, SystemClock.elapsedRealtime() - 180_001)
            service.javaClass.getDeclaredMethod("checkWatch").apply { isAccessible = true }.invoke(service)
        }
        overdueProbe()
        assertSame(service, live())
        assertEquals(1, transport.probes.size)
        main { transport.probes.last().complete(true) }
        test.waitForIdleSync()
        assertSame("A responding peer must survive a delayed watchdog callback", service, live())
        val confirmed = service.javaClass.getDeclaredField("confirmed").apply { isAccessible = true }.getLong(service)
        assertTrue(SystemClock.elapsedRealtime() - confirmed < 5_000)
        overdueProbe()
        main { transport.probes.last().complete(false) }
        waitFor("negative reply releases location") { live() == null }
    }

    @Test(timeout = 60_000)
    fun renewedSessionIgnoresOldNegativeProbe() = withPrestartedLocation { transport, service, _ ->
        val id = UUID.randomUUID()
        main {
            assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id))
            service.javaClass.getDeclaredMethod("checkWatch").apply { isAccessible = true }.invoke(service)
            assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id))
            transport.probes.single().complete(false)
        }
        test.waitForIdleSync()
        assertSame("An old negative must not release a newly renewed session", service, live())
        shell("input keyevent 223")
        waitFor("phone background") { !app.isAppInForegroundOnRootDevice }
        main {
            WearLocationService.recordFailure(app, transport.peer.key to id, SecurityException("Synthetic foreground refusal"))
            assertEquals("A live FGS can recover without a background start", MapProtocol.SESSION_READY,
                WearLocationService.refresh(app, transport.peer.key, id))
        }
    }

    @Test(timeout = 60_000)
    fun finalProbeHasHardDeadline() = withPrestartedLocation { transport, service, _ ->
        val id = UUID.randomUUID()
        main {
            assertEquals(MapProtocol.SESSION_READY, WearLocationService.refresh(app, transport.peer.key, id))
            service.javaClass.getDeclaredField("confirmed").apply { isAccessible = true }
                .setLong(service, SystemClock.elapsedRealtime() - 180_001)
            service.javaClass.getDeclaredMethod("checkWatch").apply { isAccessible = true }.invoke(service)
        }
        assertSame(service, live())
        waitFor("unanswered final probe expires", 15_000) { live() == null }
        main { transport.probes.single().complete(true) }
        test.waitForIdleSync()
        assertNull("A late answer must not resurrect the service", live())
    }

    /** Host puts the paired watch to sleep first; real crypto/socket delivery, emulator radios only. */
    @Test(timeout = 100_000)
    fun pairedSleepingWatchRestoresSession() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("pairedRecovery") == "true")
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        // Instrumentation may replace the target process. Reopen its map before observing the
        // paired watch's service-owned reconnect; the host leaves the watch display off throughout.
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
        waitFor("sleeping paired watch establishes its session", 50_000) {
            live() != null && WearLocationService.activeSession() != null && WearBluetoothService.activeLink() != null
        }
        val service = checkNotNull(live())
        val owner = checkNotNull(WearLocationService.activeSession())
        val helper = service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service)
        shell("input keyevent 223")
        waitFor("phone background and display off") {
            !app.isAppInForegroundOnRootDevice && !app.getSystemService(PowerManager::class.java).isInteractive
        }
        repeat(2) {
            val oldLink = checkNotNull(WearBluetoothService.activeLink())
            val generation = service.javaClass.getDeclaredField("confirmationGeneration").apply { isAccessible = true }.getInt(service)
            main { oldLink.close() }
            waitFor("sleeping watch reconnects and restores the same session", 35_000) {
                WearBluetoothService.activeLink()?.let { it !== oldLink } == true &&
                    WearLocationService.activeSession() == owner &&
                    service.javaClass.getDeclaredField("confirmationGeneration").apply { isAccessible = true }.getInt(service) > generation
            }
            assertSame("Locked reconnect must keep the running location FGS", service, live())
            assertSame(helper, service.javaClass.getDeclaredField("helper").apply { isAccessible = true }.get(service))
        }
        test.sendStatus(0, Bundle().apply { putString("stream", "SLEEP_RECONNECT_PASSED: two authenticated reconnects retained the same session/FGS/GPS subscription with the phone screen off\n") })
    }

    private class SilentSessionTransport : PhoneTransport {
        val peer = AuthenticatedPeer(TransportKind.BLUETOOTH, "bluetooth:synthetic-prestart-owner")
        val probes = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<Boolean>>()
        override fun start() = true
        override fun stop() = Unit
        override fun state() = LinkState.READY
        override fun activePeer() = peer
        override fun checkSession(peer: AuthenticatedPeer, session: UUID) =
            java.util.concurrent.CompletableFuture<Boolean>().also { probes.add(it) }
        override fun sendRouteAlert(peer: AuthenticatedPeer, payload: ByteArray) = false
    }

    private class PairingStateTransport(var link: LinkState, var paired: Boolean) : PhoneTransport {
        override fun start() = true
        override fun stop() = Unit
        override fun state() = link
        override fun activePeer(): AuthenticatedPeer? = null
        override fun hasPairedPeer() = paired
        override fun checkSession(peer: AuthenticatedPeer, session: UUID) =
            java.util.concurrent.CompletableFuture<Boolean>().also { it.complete(false) }
        override fun sendRouteAlert(peer: AuthenticatedPeer, payload: ByteArray) = false
    }

    /** Records starts and stops; a start puts the link in the listening (CONNECTING) state. */
    private class SwitchedTransport : PhoneTransport {
        @Volatile var link = LinkState.OFF
        val starts = java.util.concurrent.atomic.AtomicInteger()
        override fun start(): Boolean { starts.incrementAndGet(); link = LinkState.CONNECTING; return true }
        override fun stop() { link = LinkState.OFF }
        override fun state() = link
        override fun activePeer(): AuthenticatedPeer? = null
        override fun checkSession(peer: AuthenticatedPeer, session: UUID) =
            java.util.concurrent.CompletableFuture<Boolean>().also { it.complete(false) }
        override fun sendRouteAlert(peer: AuthenticatedPeer, payload: ByteArray) = false
    }

    /** Switching the plugin off and on again from a screen starts the link at once, without a map resume. */
    @Test(timeout = 90_000)
    fun reenablingThePluginStartsTheLink() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(live() == null) { "An existing watch location session must not be disturbed" }
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val enabled = plugin.isEnabled
        val factory = PhoneTransportFactory::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousTransport = factory.get(null)
        val transport = SwitchedTransport()
        try {
            main { factory.set(null, transport); assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("visible phone") { app.isAppInForegroundOnRootDevice }
            var screen: MapActivity? = null
            waitFor("resumed map") {
                main { screen = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MapActivity>().firstOrNull() }
                screen != null
            }
            val map = checkNotNull(screen)
            main { assertTrue(PluginsHelper.enablePlugin(map, app, plugin, false)) }
            assertEquals("Disabling must stop the link", LinkState.OFF, transport.link)
            val before = transport.starts.get()
            main { assertTrue(PluginsHelper.enablePlugin(map, app, plugin, true)) }
            waitFor("link started by the switch", 5_000) { transport.link == LinkState.CONNECTING }
            assertEquals("One start for the switch", before + 1, transport.starts.get())
        } finally {
            main { factory.set(null, previousTransport); PluginsHelper.enablePlugin(null, app, plugin, enabled) }
        }
    }

    /** Screen-off prestart shows the location notification only for a paired watch on a running link. */
    @Test(timeout = 90_000)
    fun prestartRequiresPairedWatchOnRunningLink() {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        check(live() == null) { "An existing watch location session must not be disturbed" }
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val enabled = plugin.isEnabled
        val factory = PhoneTransportFactory::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousTransport = factory.get(null)
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        val transport = PairingStateTransport(LinkState.CONNECTING, paired = false)
        fun assertNoPrestart(label: String) {
            main { WearLocationService.prestart(app) }
            SystemClock.sleep(1_500)
            assertNull(label, live())
            assertTrue(label, notifications.activeNotifications.none { it.id == 10031 })
        }
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("visible phone for prestart") { app.isAppInForegroundOnRootDevice }
            main { factory.set(null, transport); assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
            assertNoPrestart("No paired watch: screen-off must not start location")
            transport.link = LinkState.OFF; transport.paired = true
            assertNoPrestart("Link off: no watch can claim the prestart")
            transport.link = LinkState.SETUP_REQUIRED
            assertNoPrestart("Pairing in progress: no paired watch yet")
            transport.link = LinkState.CONNECTING
            main { WearLocationService.prestart(app) }
            waitFor("paired watch on listening link prestarts") {
                live() != null && notifications.activeNotifications.any { it.id == 10031 }
            }
        } finally {
            main { app.stopService(Intent(app, WearLocationService::class.java)); factory.set(null, previousTransport)
                PluginsHelper.enablePlugin(null, app, plugin, enabled) }
            waitFor("prestart gate cleanup") { live() == null }
        }
    }

    /** Real Android service/notifications with a silent synthetic transport; never a physical device. */
    private fun withPrestartedLocation(block: (SilentSessionTransport, WearLocationService, android.app.NotificationManager) -> Unit) {
        check(Build.HARDWARE == "ranchu" && app.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(!app.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        check(live() == null) { "An existing watch location session must not be disturbed" }
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
        val enabled = plugin.isEnabled
        val source = app.settings.LOCATION_SOURCE.get()
        val factory = PhoneTransportFactory::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousTransport = factory.get(null)
        val bluetoothRunning = WearBluetoothService.isRunning()
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        val transport = SilentSessionTransport()
        try {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("visible phone for prestart") { app.isAppInForegroundOnRootDevice }
            main {
                factory.set(null, transport)
                assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true))
                app.settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API)
                WearLocationService.prestart(app)
            }
            waitFor("prestarted foreground service") {
                live() != null && notifications.activeNotifications.any { it.id == 10031 }
            }
            block(transport, checkNotNull(live()), notifications)
        } finally {
            try {
                main {
                    app.stopService(Intent(app, WearLocationService::class.java))
                    WearOsPlugin.pendingLocation = null
                    WearOsPlugin.pendingLocationAt = 0
                }
                waitFor("prestart fixture service cleanup") { live() == null }
            } finally {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
                main {
                    factory.set(null, previousTransport)
                    app.settings.LOCATION_SOURCE.set(source)
                    PluginsHelper.enablePlugin(null, app, plugin, enabled)
                    if (bluetoothRunning && enabled) WearBluetoothService.start(app)
                    else WearBluetoothService.stop(app)
                }
            }
        }
    }

    /** Removing the actual phone task must end GPS; Home and a locked phone keep the task. */
    @Test(timeout = 90_000)
    fun removingPhoneTaskStopsLocation() {
        check(Build.HARDWARE == "ranchu")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val enabled = plugin.isEnabled
        val source = app.settings.LOCATION_SOURCE.get()
        val auto = app.javaClass.getDeclaredField("androidAutoInForeground").apply { isAccessible = true }; val wasAuto = auto.getBoolean(app)
        val tasks = app.getSystemService(android.app.ActivityManager::class.java)
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        val peer = "synthetic-task-owner"; val id = UUID.randomUUID()
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("visible phone") { app.isAppInForegroundOnRootDevice }
            main { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)); app.settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API); WearBluetoothService.start(app) }
            waitFor("bluetooth link service") { WearBluetoothService.isRunning() }
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, id)) }
            waitFor("location service and notification") { live() != null && notifications.activeNotifications.any { it.id == 10031 } }
            val service = live()
            shell("input keyevent 3")
            waitFor("Home backgrounds phone") { !app.isAppInForegroundOnRootDevice }
            assertSame("Home must keep watch GPS", service, live())
            // A watch opened while the phone is unlocked with OsmAnd in the background is not
            // refused by the phone: the start is attempted and Android decides.
            main { WearLocationService.release(peer, id) }
            waitFor("background session cleanup") { live() == null }
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && !app.isAppInForegroundOnRootDevice)
            attemptedStart(peer, UUID.randomUUID(), "unlocked background")
            // A start Android refused is retried once its 5-second back-off ends.
            main { WearLocationService.release(peer) }
            waitFor("background start cleanup") { live() == null }
            val refusedId = UUID.randomUUID()
            main {
                WearLocationService.recordFailure(app, peer to refusedId, SecurityException("synthetic refusal"))
                assertEquals(MapProtocol.SESSION_PHONE_REQUIRED, WearLocationService.refresh(app, peer, refusedId))
            }
            SystemClock.sleep(5_200)
            attemptedStart(peer, refusedId, "retried background")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("visible phone again") { app.isAppInForegroundOnRootDevice }
            main {
                WearOsPlugin.latestLocation = net.osmand.Location("synthetic-task-close").apply { latitude = 47.142; longitude = 9.5205; time = System.currentTimeMillis() }
                tasks.appTasks.single { it.taskInfo.baseActivity?.packageName == app.packageName }.finishAndRemoveTask()
            }
            waitFor("phone task removed") { tasks.appTasks.none { it.taskInfo.baseActivity?.packageName == app.packageName } }
            waitFor("task removal stops GPS and notification", 5_000) { live() == null && WearOsPlugin.latestLocation == null && notifications.activeNotifications.none { it.id == 10031 } }
            // ProcessLifecycleOwner can still report foreground briefly after task removal.
            repeat(4) { main { assertEquals(MapProtocol.SESSION_PHONE_REQUIRED, WearLocationService.refresh(app, peer, UUID.randomUUID())) }; SystemClock.sleep(50) }
            assertNull(live())
            waitFor("removed phone is background") { !app.isAppInForegroundOnRootDevice }
            // A connected Android Auto activity must not be mistaken for reopening the phone app.
            main { auto.setBoolean(app, true) }
            repeat(3) { main { assertEquals(MapProtocol.SESSION_PHONE_REQUIRED, WearLocationService.refresh(app, peer, UUID.randomUUID())) }; SystemClock.sleep(300) }
            assertNull(live()); assertTrue(notifications.activeNotifications.none { it.id == 10031 })
            main { auto.setBoolean(app, wasAuto) }
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("explicit phone reopen") { app.isAppInForegroundOnRootDevice }
            main { assertSessionAccepted(WearLocationService.refresh(app, peer, id)) }
            waitFor("GPS can restart after phone reopen") { live() != null }
            main { WearLocationService.release(peer) }
            waitFor("reopened session cleanup") { live() == null && notifications.activeNotifications.none { it.id == 10031 } }
            test.sendStatus(0, Bundle().apply { putString("stream", "PHONE_TASK_CLOSE_PASSED: Home retains GPS; actual task removal clears service/fix/notification; background/Auto polls cannot restart; explicit phone reopen works\n") })
        } finally {
            main { auto.setBoolean(app, wasAuto); WearLocationService.release(peer); app.stopService(Intent(app, WearLocationService::class.java)); WearOsPlugin.pendingLocation = null; WearOsPlugin.pendingLocationAt = 0
                app.settings.LOCATION_SOURCE.set(source); PluginsHelper.enablePlugin(null, app, plugin, enabled) }
        }
    }

    /**
     * A watch close can clear the pending session before the requested foreground service starts.
     * The service must still call startForeground() before stopping, or Android kills the process.
     */
    @Test(timeout = 90_000)
    fun closeBeforeServiceStartDoesNotCrash() {
        check(Build.HARDWARE == "ranchu")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        check(!app.routingHelper.isFollowingMode && !app.settings.SAVE_GLOBAL_TRACK_TO_GPX.get())
        val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val enabled = plugin.isEnabled
        val source = app.settings.LOCATION_SOURCE.get()
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        val peer = "synthetic-early-close"; val id = UUID.randomUUID()
        try {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            shell("am start -W -n ${app.packageName}/net.osmand.plus.activities.MapActivity")
            waitFor("application initialization", 60_000) { !app.isApplicationInitializing }
            waitFor("visible phone") { app.isAppInForegroundOnRootDevice }
            main {
                assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true))
                app.settings.LOCATION_SOURCE.set(LocationSource.ANDROID_API)
                WearLocationService.release(peer); app.stopService(Intent(app, WearLocationService::class.java))
            }
            waitFor("no prior location service") { live() == null }
            // Open and close in one main-thread turn: onStartCommand always runs after the close.
            main {
                assertEquals(MapProtocol.SESSION_CONNECTING, WearLocationService.refresh(app, peer, id))
                WearLocationService.release(peer, id)
                assertNull(WearOsPlugin.pendingLocation)
            }
            // Android posts the missing-startForeground crash as soon as the service is torn down.
            SystemClock.sleep(3_000)
            waitFor("unclaimed start stops") { live() == null && notifications.activeNotifications.none { it.id == 10031 } }

            // An empty start must not end a lease that is already active.
            main { assertEquals(MapProtocol.SESSION_CONNECTING, WearLocationService.refresh(app, peer, id)) }
            waitFor("active lease") { WearLocationService.owns(peer, id) }
            val service = live()
            main { app.startForegroundService(Intent().setClassName(app, WearOsPlugin.LOCATION_SERVICE)) }
            SystemClock.sleep(1_000)
            assertSame("An empty start must keep the active lease", service, live())
            assertTrue(WearLocationService.owns(peer, id))
            main { WearLocationService.release(peer, id) }
            waitFor("lease cleanup") { live() == null }
        } finally {
            main { WearLocationService.release(peer); app.stopService(Intent(app, WearLocationService::class.java)); WearOsPlugin.pendingLocation = null; WearOsPlugin.pendingLocationAt = 0
                app.settings.LOCATION_SOURCE.set(source); PluginsHelper.enablePlugin(null, app, plugin, enabled) }
        }
    }
}
