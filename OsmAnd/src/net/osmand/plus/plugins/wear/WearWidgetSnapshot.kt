package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.content.res.Configuration
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.monitoring.OsmandMonitoringPlugin
import net.osmand.plus.plugins.monitoring.widgets.TripRecordingDistanceWidgetState
import net.osmand.plus.settings.backend.WidgetsAvailabilityHelper
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.widgetstates.TimeToNavigationPointWidgetState
import net.osmand.util.Algorithms
import java.util.concurrent.TimeUnit

/** A read-only view of the four phone widgets supported by the watch Info screen. */
internal data class WearWidgetSnapshot(
    val capturedUtcMs: Long,
    val activeProfile: String,
    val rows: List<Row>,
    val recordingState: RecordingState,
    val startupDialog: Boolean,
    val etaMode: EtaMode,
) {
    enum class Kind { CURRENT_TIME, ALTITUDE_CURRENT, RECORDING_DISTANCE, TIME_TO_DESTINATION, DISTANCE_TO_DESTINATION }
    enum class RecordingState { INACTIVE, RECORDING, PAUSED_OR_UNSAVED, SAVING }
    enum class EtaMode { ARRIVAL, TIME_TO_GO }

    data class Row(
        val kind: Kind,
        val title: String,
        val value: String,
        val unit: String?,
        /** OsmAnd drawable resource entry name. The transport may map this to a compact icon code. */
        val iconKey: String,
        val nightMode: Boolean,
        val labelColor: Int,
        val valueColor: Int,
        val accentColor: Int,
        val boldText: Boolean,
        val sizeMode: String,
    )

    companion object {
        private const val NO_VALUE = "—"

        /** Does not construct MapActivity widgets, start location, or change recording state. */
        fun read(app: OsmandApplication): WearWidgetSnapshot {
            val settings = app.settings
            val profile = settings.applicationMode
            val layout = if (settings.USE_SEPARATE_LAYOUTS.get()) {
                if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ScreenLayoutMode.LANDSCAPE else ScreenLayoutMode.PORTRAIT
            } else null
            val visibility = MapWidgetInfo.getWidgetsVisibility(app, profile, layout)
            val customKeys = settings.getCustomWidgetsKeys(layout).getStringsListForProfile(profile).orEmpty()
            val night = app.daynightHelper.isNightMode(profile, ThemeUsageContext.MAP)
            val bold = app.routingHelper.isFollowingMode
            val now = System.currentTimeMillis()
            val monitor = PluginsHelper.getActivePlugin(OsmandMonitoringPlugin::class.java)
            val track = app.savingTrackHelper
            val recordingState = when {
                monitor?.isSaving == true -> RecordingState.SAVING
                track.isRecording -> RecordingState.RECORDING
                track.hasDataToSave() -> RecordingState.PAUSED_OR_UNSAVED
                else -> RecordingState.INACTIVE
            }
            val etaState = TimeToNavigationPointWidgetState(app, null, false)
            val etaMode = if (etaState.preference.getModeValue(profile)) EtaMode.ARRIVAL else EtaMode.TIME_TO_GO
            val rows = ArrayList<Row>(5)

            fun selectedKey(type: WidgetType): String? {
                if (!WidgetsAvailabilityHelper.isWidgetAvailable(app, type.id, profile)) return null
                val candidates = sequenceOf(type.id) + customKeys.asSequence()
                    .filter { WidgetType.getDefaultWidgetId(it) == type.id }
                return candidates.firstOrNull { key ->
                    when {
                        visibility.contains(key) || visibility.contains("+$key") -> true
                        visibility.contains("-$key") -> false
                        else -> key == type.id && WidgetsAvailabilityHelper.isWidgetVisibleByDefault(app, key, profile)
                    }
                }
            }

            fun row(type: WidgetType, key: String, kind: Kind, title: String,
                    value: String, unit: String?, icon: Int): Row {
                val panel = type.getPanel(key, profile, layout, settings)
                val appearance = app.panelAppearanceSettingsManager.resolveCommitted(
                    panel, layout, night, bold, app.resources.displayMetrics.density)
                return Row(kind, title, value, unit,
                    app.resources.getResourceEntryName(icon), night,
                    appearance.secondaryTextColor, appearance.primaryTextColor,
                    profile.getProfileColor(night), appearance.boldText, appearance.sizeMode.name)
            }

            selectedKey(WidgetType.CURRENT_TIME)?.let { key ->
                val time = OsmAndFormatter.getFormattedTime(app, now)
                rows += row(WidgetType.CURRENT_TIME, key, Kind.CURRENT_TIME,
                    app.getString(WidgetType.CURRENT_TIME.titleId), time.first, time.second,
                    WidgetType.CURRENT_TIME.getIconId(night))
            }

            selectedKey(WidgetType.ALTITUDE_MY_LOCATION)?.let { key ->
                // The watch session keeps phone GPS active while MapActivity is asleep.
                // Prefer that fresh fix; OsmAnd's map fix can stop updating under lock.
                val location = WearOsPlugin.location(app)?.takeIf { now - it.time in 0..30_000 }
                    ?: app.locationProvider.lastKnownLocation
                val altitude = if (location != null && location.hasAltitude()) {
                    OsmAndFormatter.getFormattedAlt(location.altitude.toInt().toDouble(), app)
                } else null
                val split = altitude?.lastIndexOf(' ') ?: -1
                val value = if (altitude == null) NO_VALUE else if (split < 0) altitude else altitude.substring(0, split)
                val unit = if (split < 0) null else altitude?.substring(split + 1)
                rows += row(WidgetType.ALTITUDE_MY_LOCATION, key, Kind.ALTITUDE_CURRENT,
                    app.getString(WidgetType.ALTITUDE_MY_LOCATION.titleId), value, unit,
                    WidgetType.ALTITUDE_MY_LOCATION.getIconId(night))
            }

            if (monitor != null) selectedKey(WidgetType.TRIP_RECORDING_DISTANCE)?.let { key ->
                val customId = key.takeIf { it != WidgetType.TRIP_RECORDING_DISTANCE.id }
                    ?.removePrefix(WidgetType.TRIP_RECORDING_DISTANCE.id)
                val mode = TripRecordingDistanceWidgetState(app, customId,
                    WidgetType.TRIP_RECORDING_DISTANCE).distanceModePreference.getModeValue(profile)
                val distance = when (mode) {
                    TripRecordingDistanceWidgetState.TripRecordingDistanceMode.TOTAL_DISTANCE -> track.distance
                    TripRecordingDistanceWidgetState.TripRecordingDistanceMode.LAST_UPHILL ->
                        track.currentTrack.getTrackAnalysis(app).lastUphill?.distance?.toFloat() ?: 0f
                    TripRecordingDistanceWidgetState.TripRecordingDistanceMode.LAST_DOWNHILL ->
                        track.currentTrack.getTrackAnalysis(app).lastDownhill?.distance?.toFloat() ?: 0f
                }
                val formatted = if (distance > 0) OsmAndFormatter.getFormattedDistanceValue(distance, app) else null
                val value = when {
                    monitor.isSaving -> app.getString(R.string.shared_string_save)
                    formatted != null -> formatted.value
                    else -> app.getString(R.string.monitoring_control_start)
                }
                val unit = if (monitor.isSaving) null else formatted?.unit
                val icon = when {
                    monitor.isSaving || recordingState == RecordingState.RECORDING ->
                        if (night) R.drawable.widget_monitoring_rec_big_night else R.drawable.widget_monitoring_rec_big_day
                    recordingState == RecordingState.PAUSED_OR_UNSAVED ->
                        if (night) R.drawable.widget_monitoring_rec_inactive_night else R.drawable.widget_monitoring_rec_inactive_day
                    mode != TripRecordingDistanceWidgetState.TripRecordingDistanceMode.TOTAL_DISTANCE -> mode.getIcon(night)
                    else -> if (night) R.drawable.widget_monitoring_rec_inactive_night else R.drawable.widget_monitoring_rec_inactive_day
                }
                rows += row(WidgetType.TRIP_RECORDING_DISTANCE, key, Kind.RECORDING_DISTANCE,
                    app.getString(WidgetType.TRIP_RECORDING_DISTANCE.titleId), value, unit, icon)
            }

            selectedKey(WidgetType.TIME_TO_DESTINATION)?.takeIf { app.routingHelper.isRouteCalculated }?.let { key ->
                val customId = key.takeIf { it != WidgetType.TIME_TO_DESTINATION.id }
                    ?.removePrefix(WidgetType.TIME_TO_DESTINATION.id)
                val state = TimeToNavigationPointWidgetState(app, customId, false)
                val arrival = state.preference.getModeValue(profile)
                val left = if (app.routingHelper.isRouteCalculated) app.routingHelper.leftTime else 0
                val formatted = when {
                    left <= 0 -> NO_VALUE to null
                    arrival -> OsmAndFormatter.getFormattedTime(app, now + left * 1000L).let { it.first to it.second }
                    else -> Algorithms.formatMinutesDuration(
                        TimeUnit.MINUTES.convert(left.toLong(), TimeUnit.SECONDS).toInt(), true) to app.getString(R.string.int_hour)
                }
                val icon = TimeToNavigationPointWidgetState.TimeToNavigationPointState
                    .getState(false, arrival).getIconId(night)
                rows += row(WidgetType.TIME_TO_DESTINATION, key, Kind.TIME_TO_DESTINATION,
                    state.title, formatted.first, formatted.second, icon)
            }

            selectedKey(WidgetType.DISTANCE_TO_DESTINATION)?.takeIf { app.routingHelper.isRouteCalculated }?.let { key ->
                val meters = app.routingHelper.leftDistance
                val formatted = if (meters > 0) OsmAndFormatter.getFormattedDistance(meters.toFloat(), app) else NO_VALUE
                val split = formatted.lastIndexOf(' ')
                val value = if (split < 0) formatted else formatted.substring(0, split)
                val unit = if (split < 0) null else formatted.substring(split + 1)
                rows += row(WidgetType.DISTANCE_TO_DESTINATION, key, Kind.DISTANCE_TO_DESTINATION,
                    app.getString(WidgetType.DISTANCE_TO_DESTINATION.titleId), value, unit,
                    WidgetType.DISTANCE_TO_DESTINATION.getIconId(night))
            }

            data class WidgetOrder(val panel: Int, val page: Int, val order: Int, val visibility: Int)
            fun typeFor(kind: Kind) = when (kind) {
                Kind.CURRENT_TIME -> WidgetType.CURRENT_TIME
                Kind.ALTITUDE_CURRENT -> WidgetType.ALTITUDE_MY_LOCATION
                Kind.RECORDING_DISTANCE -> WidgetType.TRIP_RECORDING_DISTANCE
                Kind.TIME_TO_DESTINATION -> WidgetType.TIME_TO_DESTINATION
                Kind.DISTANCE_TO_DESTINATION -> WidgetType.DISTANCE_TO_DESTINATION
            }
            fun orderFor(kind: Kind): WidgetOrder {
                val type = typeFor(kind)
                val key = selectedKey(type) ?: return WidgetOrder(Int.MAX_VALUE, 0, 0, Int.MAX_VALUE)
                val panel = type.getPanel(key, profile, layout, settings)
                val visibilityIndex = visibility.indexOf(key).let { if (it >= 0) it else Int.MAX_VALUE }
                return WidgetOrder(panel.ordinal, panel.getWidgetPage(profile, layout, key, settings),
                    panel.getWidgetOrder(profile, layout, key, settings), visibilityIndex)
            }
            val orderedRows = rows.sortedWith(compareBy(
                { orderFor(it.kind).panel }, { orderFor(it.kind).page },
                { orderFor(it.kind).order }, { orderFor(it.kind).visibility },
                { it.kind.ordinal }))

            return WearWidgetSnapshot(now, profile.stringKey, orderedRows, recordingState,
                settings.SHOW_TRIP_REC_START_DIALOG.get(), etaMode)
        }

        fun routeProfile(app: OsmandApplication, now: Long): WidgetProtocol.Profile? {
            val helper = app.routingHelper
            if (!helper.isRouteCalculated) return null
            val route = helper.route
            val locations = route.immutableAllLocations
            if (locations.size < 2) return null
            // Route locations may have elevation only at some vertices. Keep the measured
            // points and their distance along the complete route; the line joins gaps.
            val vertices = ArrayList<Pair<Int, Int>>()
            var distance = 0f
            var previous: net.osmand.Location? = null
            for (location in locations) {
                if (previous != null) distance += previous.distanceTo(location)
                if (!distance.isFinite() || distance > 20_000_000f) return null
                if (location.hasAltitude() && location.altitude.isFinite() &&
                    location.altitude in -500.0..10_000.0) {
                    vertices += distance.toInt() to location.altitude.toInt()
                }
                previous = location
            }
            val total = distance.toInt()
            if (total < 1 || vertices.size < 2 || vertices.last().first <= vertices.first().first) return null
            // Keep the endpoints and both extremes of each distance bucket.
            val selected = sortedSetOf(0, vertices.lastIndex)
            for (bucket in 0 until 63) {
                val start = bucket * (vertices.size - 1) / 63
                val end = (bucket + 1) * (vertices.size - 1) / 63
                if (start > end) continue
                val range = start..end
                selected += range.minBy { vertices[it].second }
                selected += range.maxBy { vertices[it].second }
            }
            val points = selected.map { vertices[it] }
            val fix = WearOsPlugin.location(app)?.takeIf { now - it.time in 0..30_000 }
                ?: app.locationProvider.lastKnownLocation?.takeIf { now - it.time in 0..30_000 }
            // Route progress belongs to RoutingHelper; require its fix to match the live watch fix.
            val routedFix = helper.lastFixedLocation
            val position = if (fix != null && routedFix != null && now - fix.time in 0..30_000 &&
                now - routedFix.time in 0..30_000 && fix.distanceTo(routedFix) <= 50f)
                route.distanceFromStart.coerceIn(0, total) else -1
            return WidgetProtocol.Profile(total, position, points,
                OsmAndFormatter.getFormattedAlt(points.minOf { it.second }.toDouble(), app),
                OsmAndFormatter.getFormattedAlt(points.maxOf { it.second }.toDouble(), app),
                OsmAndFormatter.getFormattedDistance(total.toFloat(), app)).takeIf { it.valid() }
        }
    }
}
