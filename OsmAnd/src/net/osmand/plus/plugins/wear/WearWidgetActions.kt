package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import net.osmand.plus.OsmandApplication
import net.osmand.plus.OsmAndLocationProvider
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.monitoring.OsmandMonitoringPlugin
import java.util.UUID

/** Executes recording commands in OsmAnd. The watch never owns the recording. */
internal object WearWidgetActions {
    /** Call on the phone main thread after the Wear session and location service are verified. */
    fun perform(app: OsmandApplication, node: String, session: UUID,
                command: Int, expectedState: Int): Int {
        if (command !in WidgetProtocol.START..WidgetProtocol.FINISH ||
            expectedState !in WidgetProtocol.INACTIVE..WidgetProtocol.PAUSED) {
            return refuse(WidgetProtocol.INVALID, "unknown command or state")
        }
        if (!WearOsPlugin.recordingControlsEnabled()) return refuse(WidgetProtocol.DENIED, "watch recording controls turned off")

        val monitor = PluginsHelper.getActivePlugin(OsmandMonitoringPlugin::class.java)
            ?: return refuse(WidgetProtocol.UNAVAILABLE, "trip recording plugin not active")
        // Re-read phone state at execution time. A visible widget in an old watch
        // snapshot does not grant permission to act after the phone profile changes.
        val snapshot = WearWidgetSnapshot.read(app)
        if (snapshot.rows.none { it.kind == WearWidgetSnapshot.Kind.RECORDING_DISTANCE }) {
            return refuse(WidgetProtocol.DENIED, "recording widget not shown in the current profile")
        }
        val state = when (snapshot.recordingState) {
            WearWidgetSnapshot.RecordingState.INACTIVE -> WidgetProtocol.INACTIVE
            WearWidgetSnapshot.RecordingState.RECORDING -> WidgetProtocol.RECORDING
            WearWidgetSnapshot.RecordingState.PAUSED_OR_UNSAVED -> WidgetProtocol.PAUSED
            WearWidgetSnapshot.RecordingState.SAVING -> WidgetProtocol.SAVING
        }
        if (state != expectedState) return refuse(WidgetProtocol.STALE, "recording state changed on the phone")

        val allowed = when (command) {
            WidgetProtocol.START -> state == WidgetProtocol.INACTIVE
            WidgetProtocol.PAUSE -> state == WidgetProtocol.RECORDING
            WidgetProtocol.RESUME -> state == WidgetProtocol.PAUSED
            WidgetProtocol.FINISH -> state == WidgetProtocol.RECORDING || state == WidgetProtocol.PAUSED
            else -> false
        }
        if (!allowed) return refuse(WidgetProtocol.INVALID, "command not valid in this recording state")
        if ((command == WidgetProtocol.START || command == WidgetProtocol.RESUME) &&
            !OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
            return refuse(WidgetProtocol.PHONE_REQUIRED, "location permission missing")
        }
        // Android will not let OsmAnd create a new location FGS under lock.
        // The live watch session already has one. Keep it as the location
        // source for OsmAnd's recorder until Finish when starting under lock.
        val watchSource = (command == WidgetProtocol.START || command == WidgetProtocol.RESUME) &&
            !app.isAppInForeground
        if (watchSource && !WearLocationService.enableRecording(node, session))
            return refuse(WidgetProtocol.PHONE_REQUIRED, "OsmAnd in background and no watch location service to record from")
        if (command == WidgetProtocol.FINISH && !monitor.hasDataToSave()) {
            return refuse(WidgetProtocol.UNAVAILABLE, "nothing recorded to save")
        }

        return try {
            when (command) {
                WidgetProtocol.START -> monitor.startRecording(null)
                WidgetProtocol.PAUSE, WidgetProtocol.RESUME -> monitor.pauseOrResumeRecording()
                WidgetProtocol.FINISH -> {
                    monitor.saveCurrentTrack()
                    WearLocationService.disableRecording()
                }
            }
            WidgetProtocol.OK
        } catch (error: RuntimeException) {
            WearOsPlugin.trace("recording action failed in OsmAnd: ${LinkDiagnostics.describe(error)}; ${WearDeviceEvents.state(app)}")
            // Starting the navigation service can fail while Android restricts
            // background work. Restore the phone recording flag in that case.
            when (command) {
                WidgetProtocol.START -> runCatching { monitor.stopRecording(true) }
                WidgetProtocol.RESUME -> runCatching {
                    if (monitor.isRecordingTrack) monitor.pauseOrResumeRecording()
                }
            }
            if (command == WidgetProtocol.START) WearLocationService.disableRecording()
            WidgetProtocol.UNAVAILABLE
        }
    }

    private fun refuse(status: Int, reason: String): Int {
        WearOsPlugin.trace("recording action refused: $reason")
        return status
    }
}
