package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.data.RotatedTileBox
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.render.MapRenderRepositories
import net.osmand.plus.render.OffscreenRenderOptions
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import net.osmand.plus.plugins.wear.protocol.MapProtocol.TIMEOUT
import net.osmand.plus.plugins.wear.protocol.MapProtocol.NOT_READY
import net.osmand.plus.plugins.wear.protocol.MapProtocol.POSITION_FRESH
import net.osmand.plus.plugins.wear.protocol.MapProtocol.POSITION_STALE
import net.osmand.plus.plugins.wear.protocol.MapProtocol.LIMIT_EXCEEDED
import net.osmand.plus.plugins.wear.protocol.MapProtocol.CONTENT_CHANGED
import net.osmand.plus.plugins.wear.protocol.MapProtocol.NO_MAP
import net.osmand.util.MapUtils
import net.osmand.binary.BinaryMapDataObject
import net.osmand.ResultMatcher
import net.osmand.plus.routing.RouteCalculationResult
import net.osmand.plus.routing.RoutingHelperUtils
import net.osmand.plus.routing.RouteService
import net.osmand.render.RenderingRuleSearchRequest

/** Private readers and a bounded, detached snapshot. No phone-camera or navigation commands. */
object MapPreviewRenderer {
    private val LOG = net.osmand.PlatformUtil.getLog(MapPreviewRenderer::class.java)

    /** Route line colour used only when the map style defines none, as the phone route layer does. */
    const val FALLBACK_ROUTE_COLOR_RES: Int = R.color.nav_track
    const val POSITION_COLOR: Int = -16735233 // #00a3ff
    const val STALE_POSITION_COLOR: Int = -7829368 // #888888
    const val POSITION_FRESH_MS = 30_000L
    class Failure(val status: Int) : RuntimeException()
    data class Line(val color: Int, val navigation: Boolean, val coordinates: DoubleArray)
    data class Marker(val latitude: Double, val longitude: Double, val kind: Int)
    data class Position(val latitude: Double, val longitude: Double, val utc: Long, val status: Int, val bearing: Float? = null)
    class Snapshot(val box: RotatedTileBox, val style: OffscreenRenderOptions.Style,
        val files: List<File>, val lines: List<Line>, val navigation: Boolean, val tracks: Int, val utc: Long,
        val position: Position?, val following: Boolean, val markers: List<Marker> = emptyList(),
        val clockText: String? = null,
        /** What the overlay image depends on; null when it cannot be reused (overlay cut short for time). */
        val overlayKey: OverlayKey? = null,
        /** The compressed overlay from an earlier frame with the same [overlayKey]; [lines] are then empty. */
        val cachedOverlay: CachedOverlay? = null) {

        fun withFiles(files: List<File>) = Snapshot(box, style, files, lines, navigation, tracks, utc,
            position, following, markers, clockText, overlayKey, cachedOverlay)
    }

    /** A compressed overlay and the revision the watch uses to say it already holds it. */
    class CachedOverlay(val bytes: ByteArray, val revision: Long)

    /** Equal by reference: the route and track objects are compared as instances, never deeply. */
    class Same(private val ref: Any) {
        override fun equals(other: Any?) = other is Same && other.ref === ref
        override fun hashCode() = System.identityHashCode(ref)
    }
    data class SegmentKey(val segment: Same, val size: Int, val lastLatitude: Double, val lastLongitude: Double)
    data class TrackKey(val file: Same, val modified: Long, val color: Int, val segments: List<SegmentKey>)
    /**
     * Everything the overlay image is drawn from: the view, the untravelled route, the selected
     * tracks and the route markers. Position, clock and ruler are sent as values, not drawn.
     */
    data class OverlayKey(val latitude: Double, val longitude: Double, val zoom: Int, val width: Int, val height: Int,
        val route: Same?, val routeStart: Int, val routeSize: Int, val tracks: List<TrackKey>, val markers: List<Marker>,
        /** The route line colour, and the preview pixel where the line joins the position, if it does. */
        val routeColor: Int = 0, val routeJoinX: Int? = null, val routeJoinY: Int? = null)

    /** The last compressed overlay, in memory only. One watch is served at a time. */
    @Volatile private var lastOverlay: Pair<OverlayKey, CachedOverlay>? = null
    private val overlayRevisions = java.security.SecureRandom()

    /**
     * Gives [bytes] a new revision and keeps them as the overlay for [key]; a null key is never
     * cached. Revisions are random, so one from an earlier link or process never matches.
     */
    internal fun rememberOverlay(key: OverlayKey?, bytes: ByteArray): CachedOverlay {
        var revision = 0L
        while (revision == 0L) revision = overlayRevisions.nextLong()
        return CachedOverlay(bytes, revision).also { if (key != null) lastOverlay = key to it }
    }

    /** Drops the cached overlay, for example when the watch disconnects or the plugin is turned off. */
    internal fun clearOverlayCache() { lastOverlay = null; lastFollowCentre = null }

    private data class FollowCentre(val latitude: Double, val longitude: Double, val zoom: Int, val width: Int, val height: Int)
    /** The centre the last followed frame used. Read and written only by [capture] on the main thread. */
    @Volatile private var lastFollowCentre: FollowCentre? = null
    /** How far, in preview pixels, a followed position may move before the map recentres on it. */
    internal const val FOLLOW_DEAD_ZONE_PX = 4.0

    /** Whether two points are within [px] preview pixels of each other at [zoom] (256 px tiles). */
    internal fun withinFollowDeadZone(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, zoom: Int,
        px: Double = FOLLOW_DEAD_ZONE_PX): Boolean {
        val world = 256.0 * Math.pow(2.0, zoom.toDouble())
        fun mercator(latitude: Double): Double {
            val sine = Math.sin(Math.toRadians(latitude))
            return 0.5 - Math.log((1 + sine) / (1 - sine)) / (4 * Math.PI)
        }
        val dx = ((toLon - fromLon + 540) % 360 - 180) / 360 * world
        val dy = (mercator(toLat) - mercator(fromLat)) * world
        return dx * dx + dy * dy <= px * px
    }
    const val MARKER_START = 0
    const val MARKER_INTERMEDIATE = 1
    const val MARKER_DESTINATION = 2


    private fun validView(lat: Double, lon: Double, zoom: Int) = lat.isFinite() && lat in -85.0..85.0 &&
        lon.isFinite() && lon in -180.0..180.0 && zoom in 6..20

    /** Keeps the untravelled route only; the phone's route layer draws from the current route point too. */
    internal fun remainingRouteStart(currentRoute: Int, size: Int): Int =
        if (size == 0) 0 else currentRoute.coerceIn(0, size)

    /**
     * Where the phone's route layer starts the line: the untravelled points from [index], preceded
     * by [join], the position's projection onto the route, so the line reaches the position marker.
     */
    internal class RouteLineStart(val index: Int, val join: net.osmand.Location?)

    /**
     * Mirrors `RouteLayer.drawLocations` for a calculated route. Direct-to routes are drawn whole.
     * Once past the first point, the position is projected onto the route leg it is on, and the
     * join is dropped when the position is further from the route than OsmAnd allows. Before
     * that, the line starts at OsmAnd's own projection (the fix itself for straight-line routes).
     * [here] is the position the watch frame shows, so the line meets the watch's marker; the
     * phone uses its animated marker, which follows the same fix.
     */
    internal fun routeLineStart(app: OsmandApplication, route: RouteCalculationResult,
        points: List<net.osmand.Location>, here: net.osmand.Location?): RouteLineStart {
        val helper = app.routingHelper
        if (points.isEmpty() || route.routeService == RouteService.DIRECT_TO) return RouteLineStart(0, null)
        val join: net.osmand.Location?
        val index: Int
        if (route.currentStraightAngleRoute > 0) {
            val location = here ?: helper.lastFixedLocation
            index = if (location == null) route.currentRoute else route.getCurrentRouteForLocation(location)
            join = if (location != null && index in 1 until points.size) {
                RoutingHelperUtils.getProject(location, points[index - 1], points[index])
                    .takeIf { location.distanceTo(it) <= helper.getMaxAllowedProjectDist(location) }
            } else null
        } else {
            index = route.currentStraightAngleRoute
            join = if (route.routeService == RouteService.STRAIGHT) helper.lastFixedLocation else helper.lastProjection
        }
        return RouteLineStart(remainingRouteStart(index, points.size),
            join?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() })
    }

    /**
     * The phone route layer's line colour for [style]'s day or night mode: the user's custom colour
     * when chosen, otherwise the map style's `route` colour. Altitude, slope and road-attribute
     * gradients are not drawn on the watch; those use the style colour too.
     */
    internal fun routeLineColor(app: OsmandApplication, style: OffscreenRenderOptions.Style): Int {
        val settings = app.settings
        val mode = app.routingHelper.appMode
        if (settings.ROUTE_COLORING_TYPE.getModeValue(mode).isCustomColor()) {
            return (if (style.night) settings.CUSTOM_ROUTE_COLOR_NIGHT else settings.CUSTOM_ROUTE_COLOR_DAY).getModeValue(mode)
        }
        val storage = style.storage
        var color = 0
        if (storage != null) {
            val request = RenderingRuleSearchRequest(storage)
            request.setBooleanFilter(storage.PROPS.R_NIGHT_MODE, style.night)
            if (request.searchRenderingAttribute("route")) color = request.getIntPropertyValue(storage.PROPS.R_COLOR)
        }
        // The route layer replaces an unset or black style colour with its default.
        return if (color == 0 || color == android.graphics.Color.BLACK) app.getColor(FALLBACK_ROUTE_COLOR_RES) else color
    }

    /** The UI thread owns profile/view/selection reads; only detached data crosses to the worker. */
    @Synchronized fun snapshot(app: OsmandApplication, params: MapRequest, cancellation: CancellationSignal,
        overlays: Boolean = true, watchLocation: net.osmand.Location? = null): Snapshot {
        check(Looper.myLooper() != Looper.getMainLooper())
        val task = FutureTask { capture(app, params, cancellation, overlays, watchLocation) }
        val main = Handler(Looper.getMainLooper())
        main.post(task)
        try { val captured = task.get(10, TimeUnit.SECONDS)
            cancellation.throwIfCanceled()
            val visible = captured.files.mapTo(HashSet()) { it.name }
            return captured.withFiles(selectPreviewFiles(app, captured.box, cancellation) { it.name in visible })
        }
        catch (error: ExecutionException) { throw error.cause ?: error }
        catch (_: TimeoutException) { cancellation.cancel(); throw Failure(TIMEOUT) }
        finally { task.cancel(false); main.removeCallbacks(task) }
    }

    private fun capture(app: OsmandApplication, params: MapRequest, cancellation: CancellationSignal,
        overlays: Boolean, watchLocation: net.osmand.Location?): Snapshot {
        require(params.valid())
        cancellation.throwIfCanceled()
        if (app.isApplicationInitializing) throw Failure(NOT_READY)
        val settings = app.settings
        val map = app.osmandMap.mapView
        val utc = System.currentTimeMillis()
        // Snapshot an existing map/watch-owned phone fix. This read never starts location or navigation.
        val fix = listOfNotNull(app.locationProvider.lastKnownLocation, watchLocation).maxByOrNull { it.time }?.let { net.osmand.Location(it) }
        val position = fix?.takeIf { validView(it.latitude, it.longitude, 15) && it.time > 0 && it.time <= utc }?.let {
            Position(it.latitude, it.longitude, it.time, if (utc - it.time <= POSITION_FRESH_MS) POSITION_FRESH else POSITION_STALE,
                it.bearing.takeIf { bearing -> utc - it.time <= POSITION_FRESH_MS && it.hasSpeed() && it.speed.isFinite() && it.speed >= 0.5f && it.hasBearing() && bearing.isFinite() && bearing >= 0f && bearing < 360f })
        }
        var lat: Double
        var lon: Double
        var zoom: Int
        val following = params.follow && position?.status == POSITION_FRESH
        if (following) {
            lat = position!!.latitude; lon = position.longitude
            zoom = if (params.view != null) params.view!!.zoom else if (map.mapActivity != null) map.zoom.coerceIn(6, 20) else settings.lastKnownMapZoom.coerceIn(6, 20)
        } else if (params.view != null) { lat = params.view!!.latitude; lon = params.view!!.longitude; zoom = params.view!!.zoom }
        else if (map.mapActivity != null) {
            val box = map.currentRotatedTileBox
            lat = box.latitude; lon = box.longitude; zoom = box.zoom.coerceIn(6, 20)
        } else {
            if (!settings.isLastKnownMapLocation) throw Failure(NOT_READY)
            val saved = settings.lastKnownMapLocation
            lat = saved.latitude; lon = saved.longitude; zoom = settings.lastKnownMapZoom.coerceIn(6, 20)
        }
        if (params.view == null) zoom = (zoom + WearOsPlugin.initialZoomOffset(app)).coerceIn(6, 20)
        if (following) {
            // GPS noise moves a standing person's fix every second. Keep the map still until the
            // position leaves a few-pixel dead zone, so unchanged frames can reuse their overlay.
            // The position marker is drawn from the exact fix and stays accurate.
            val previous = lastFollowCentre
            if (previous != null && previous.zoom == zoom && previous.width == params.width && previous.height == params.height &&
                withinFollowDeadZone(previous.latitude, previous.longitude, lat, lon, zoom)) {
                lat = previous.latitude; lon = previous.longitude
            } else {
                lastFollowCentre = FollowCentre(lat, lon, zoom, params.width, params.height)
            }
        }
        if (!validView(lat, lon, zoom)) throw Failure(NOT_READY)
        val box = RotatedTileBox.RotatedTileBoxBuilder().setLocation(lat, lon).setZoom(zoom).density(1f).setMapDensity(1.0)
            .setPixelDimensions(params.width, params.height, 0.5f, 0.5f).build()
        // Maps the phone's renderer shows. Read here on the main thread with the hidden-file rule,
        // e.g. travel guides turned off, then narrowed to the viewport on the worker.
        val phoneRenderer = app.resourceManager.renderer
        val files = app.resourceManager.indexFiles.values.filter {
            it.isFile && it.name.endsWith(".obf") && !phoneRenderer.isFileHidden(it.name)
        }
        val style = phoneRenderer.captureOffscreenStyle(zoom, WearOsPlugin.labelScale(app))
        val started = SystemClock.uptimeMillis()
        // Too much overlay data degrades the overlay (thinned, or later tracks left out); it never
        // fails the map. Only a route or track that changes during the copy fails the frame.
        fun copyPoints(size: Int, stride: Int, checkTime: Boolean, point: (Int) -> Pair<Double, Double>): DoubleArray? {
            val count = sampledCount(size, stride)
            return DoubleArray(count * 2).also { coordinates ->
                for (sample in 0 until count) {
                    if (sample % 128 == 0) {
                        cancellation.throwIfCanceled()
                        if (checkTime && SystemClock.uptimeMillis() - started > OVERLAY_COPY_MS) return null
                    }
                    val (latitude, longitude) = point(sampledIndex(sample, size, stride))
                    if (!latitude.isFinite() || !longitude.isFinite()) throw Failure(CONTENT_CHANGED)
                    coordinates[sample * 2] = latitude; coordinates[sample * 2 + 1] = longitude
                }
            }
        }
        val allSelected = if (overlays) app.selectedGpxHelper.selectedGPXFiles.toList() else emptyList()
        val selected = allSelected.take(MAX_OVERLAY_TRACKS)
        val route = app.routingHelper.route
        val routePoints = route.immutableAllLocations
        val lineStart = if (overlays) routeLineStart(app, route, routePoints, fix?.takeIf { position?.status == POSITION_FRESH })
            else RouteLineStart(0, null)
        val routeStart = lineStart.index
        val routeSize = if (overlays) routePoints.size - routeStart else 0
        val routeJoin = lineStart.join?.takeIf { routeSize > 0 }
        val routeColor = if (routeSize > 0) routeLineColor(app, style) else 0
        val trackLines = mutableListOf<Line>()
        var routeLine: Line? = null
        var tracks = 0
        val markers = readMarkers(app, routePoints.isNotEmpty())
        val key: OverlayKey?
        try {
            val modifiedTimes = selected.map { it.gpxFile.modifiedTime }
            val trackSegments = selected.map { it.pointsToDisplay.toList() }
            // Built from sizes and last points only, so a hit skips copying, drawing and compressing.
            key = if (!overlays) null else OverlayKey(lat, lon, zoom, params.width, params.height,
                if (routeSize > 0) Same(routePoints) else null, routeStart, routeSize,
                selected.indices.map { index ->
                    val file = selected[index]
                    TrackKey(Same(file), modifiedTimes[index], file.color.takeIf { it != 0 } ?: app.getColor(R.color.gpx_track),
                        trackSegments[index].map { segment ->
                            val points = segment.points
                            val last = points.lastOrNull()
                            SegmentKey(Same(segment), points.size, last?.lat ?: 0.0, last?.lon ?: 0.0)
                        })
                }, markers, routeColor,
                routeJoin?.let { Math.round(box.getPixXFromLatLon(it.latitude, it.longitude)) },
                routeJoin?.let { Math.round(box.getPixYFromLatLon(it.latitude, it.longitude)) })
            lastOverlay?.takeIf { key != null && it.first == key }?.let { (_, cached) ->
                return Snapshot(box, style, files, emptyList(), overlays && routePoints.isNotEmpty(), selected.size, utc,
                    position, following, markers, readClock(app, utc), key, cached)
            }
            val stride = overlayStride(routeSize.toLong() + trackSegments.sumOf { segments -> segments.sumOf { it.points.size.toLong() } })
            // The route copies first: it is bounded by the stride and is never dropped for time.
            if (routeSize > 0) {
                val remaining = checkNotNull(copyPoints(routeSize, stride, false) {
                    routePoints[routeStart + it].let { pt -> pt.latitude to pt.longitude }
                })
                // As on the phone, the line starts at the position's projection onto the route.
                routeLine = Line(routeColor, true, if (routeJoin == null) remaining
                    else doubleArrayOf(routeJoin.latitude, routeJoin.longitude) + remaining)
            }
            tracks@ for ((index, file) in selected.withIndex()) {
                val modified = modifiedTimes[index]
                val segments = trackSegments[index]
                val color = file.color.takeIf { it != 0 } ?: app.getColor(R.color.gpx_track)
                val fileLines = ArrayList<Line>(segments.size)
                for (segment in segments) {
                    if (trackLines.size + fileLines.size >= MAX_OVERLAY_LINES) break@tracks
                    val points = segment.points
                    val size = points.size
                    // Out of main-thread time: keep the tracks already copied, leave out the rest.
                    val copy = copyPoints(size, stride, true) { points[it].let { pt -> pt.lat to pt.lon } } ?: break@tracks
                    if (size != points.size) throw Failure(CONTENT_CHANGED)
                    fileLines.add(Line(color, false, copy))
                }
                if (modified != file.gpxFile.modifiedTime || segments != file.pointsToDisplay) throw Failure(CONTENT_CHANGED)
                trackLines.addAll(fileLines)
                tracks++
            }
            if (overlays && (route !== app.routingHelper.route || routePoints !== app.routingHelper.currentCalculatedRoute ||
                    allSelected != app.selectedGpxHelper.selectedGPXFiles)) throw Failure(CONTENT_CHANGED)
        } catch (_: ConcurrentModificationException) { throw Failure(CONTENT_CHANGED) }
        catch (_: IndexOutOfBoundsException) { throw Failure(CONTENT_CHANGED) }
        if (net.osmand.plus.BuildConfig.DEBUG && tracks < allSelected.size) {
            LOG.debug("Watch overlay degraded: tracks=$tracks/${allSelected.size}")
        }
        // Tracks draw first so the route stays on top, as before.
        val lines = trackLines + listOfNotNull(routeLine)
        // An overlay cut short for time depends on timing, not only on its inputs, so it is not reused.
        return Snapshot(box, style, files, lines, overlays && routePoints.isNotEmpty(), tracks, utc, position, following,
            markers, readClock(app, utc), key.takeIf { tracks == selected.size })
    }

    /** Combined overlay point budget; above it every route and track line is thinned evenly. */
    internal const val MAX_OVERLAY_POINTS = 200_000
    /** The reply's track count is bounded by the protocol; later selected tracks are left out. */
    internal const val MAX_OVERLAY_TRACKS = 128
    internal const val MAX_OVERLAY_LINES = 4096
    /** Main-thread time for copying tracks; tracks not copied in time are left out of this frame. */
    private const val OVERLAY_COPY_MS = 100L

    /** Keeps every point within budget, otherwise every n-th point of every line. */
    internal fun overlayStride(totalPoints: Long, budget: Int = MAX_OVERLAY_POINTS): Int =
        if (totalPoints <= budget) 1 else ((totalPoints + budget - 1) / budget).toInt()

    /** Points kept from a line of [size] at [stride]; the last point is always kept. */
    internal fun sampledCount(size: Int, stride: Int): Int =
        if (size <= 0) 0 else (size - 1) / stride + 1 + if ((size - 1) % stride == 0) 0 else 1

    internal fun sampledIndex(sample: Int, size: Int, stride: Int): Int = minOf(sample * stride, size - 1)

    /**
     * Upper bound on map files opened for one preview. Search work is bounded separately by the
     * object and point limits in [render], so this only bounds open file handles. It is set well
     * above a viewport's usual region, contour, depth and OsmAnd Live update files.
     */
    internal const val MAX_PREVIEW_FILES = 64

    /**
     * Selects installed maps whose map index intersects this small preview viewport. It reads the
     * index metadata that ResourceManager already parsed, so no file is opened or parsed here.
     * Files [shown] rejects, such as maps the phone's renderer hides, are never selected.
     */
    internal fun selectPreviewFiles(app: OsmandApplication, box: RotatedTileBox,
        cancellation: CancellationSignal? = null, shown: (File) -> Boolean = { true }): List<File> {
        val points = listOf(
            box.getLatLonFromPixel(0f, 0f),
            box.getLatLonFromPixel(box.pixWidth.toFloat(), 0f),
            box.getLatLonFromPixel(0f, box.pixHeight.toFloat()),
            box.getLatLonFromPixel(box.pixWidth.toFloat(), box.pixHeight.toFloat()))
        val xs = points.map { MapUtils.get31TileNumberX(it.longitude) }
        val ys = points.map { MapUtils.get31TileNumberY(it.latitude) }
        val readers = installedMapReaders(app)
        return selectBounded(readers.keys.filter(shown)) { file ->
            cancellation?.throwIfCanceled()
            val reader = readers.getValue(file)
            reader.isBasemap || reader.containsMapData(xs.min(), ys.min(), xs.max(), ys.max(), box.zoom)
        }
    }

    /** Index metadata already held by ResourceManager. Callers must not read the file through it. */
    private fun installedMapReaders(app: OsmandApplication): Map<File, BinaryMapIndexReader> {
        val readers = LinkedHashMap<File, BinaryMapIndexReader>()
        for (resource in app.resourceManager.fileReaders) {
            if (resource.isClosed) continue
            val reader = resource.shallowReader ?: continue
            val file = reader.file ?: continue
            if (file.isFile && file.name.endsWith(".obf") && reader.containsMapData()) readers[file] = reader
        }
        return readers
    }

    /**
     * Opens a private reader for one selected file. It shares the index already parsed by
     * ResourceManager, as BinaryMapReaderResource does for its other readers, and parses the
     * file itself only when the map was replaced or removed after selection.
     */
    private fun openPreviewReader(app: OsmandApplication, file: File): BinaryMapIndexReader {
        val indexed = app.resourceManager.fileReaders.firstOrNull { it.fileName == file.name && !it.isClosed }
            ?.shallowReader?.takeIf { it.file == file }
        val stream = RandomAccessFile(file, "r")
        try {
            return if (indexed != null) BinaryMapIndexReader(stream, indexed) else BinaryMapIndexReader(stream, file)
        } catch (error: Exception) {
            stream.close()
            throw error
        }
    }

    /** Pure selection/cap boundary; [selectPreviewFiles] supplies relevance for installed files. */
    internal fun selectBounded(files: List<File>, relevant: (File) -> Boolean): List<File> {
        val selected = ArrayList<File>()
        for (file in files) {
            if (relevant(file)) {
                selected.add(file)
                if (selected.size > MAX_PREVIEW_FILES) throw Failure(LIMIT_EXCEEDED)
            }
        }
        return selected
    }

    private fun readMarkers(app: OsmandApplication, hasRoute: Boolean): List<Marker> {
        if (!hasRoute) return emptyList()
        val helper = app.targetPointsHelper
        val result = ArrayList<Marker>()
        helper.pointToStartLocation?.let { start ->
            if (validView(start.latitude, start.longitude, 15)) result += Marker(start.latitude, start.longitude, MARKER_START)
        }
        val targets = helper.intermediatePointsWithTarget
        for ((index, target) in targets.withIndex()) {
            val point = target.latLon
            if (validView(point.latitude, point.longitude, 15)) {
                result += Marker(point.latitude, point.longitude,
                    if (index == targets.lastIndex) MARKER_DESTINATION else MARKER_INTERMEDIATE)
            }
        }
        return result
    }

    private fun readClock(app: OsmandApplication, utc: Long): String? {
        if (!WearOsPlugin.clockEnabled(app)) return null
        val formatted = net.osmand.plus.utils.OsmAndFormatter.getFormattedTime(app, utc)
        return if (formatted.second.isBlank()) formatted.first else "${formatted.first} ${formatted.second}"
    }


    fun render(app: OsmandApplication, snapshot: Snapshot, cancellation: CancellationSignal, overlays: Boolean = true): Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper())
        cancellation.throwIfCanceled()
        var objects = 0
        var points = 0L
        // Search limits only; the offscreen renderer also stops the search on cancellation.
        val limits = object : ResultMatcher<BinaryMapDataObject> {
            override fun publish(item: BinaryMapDataObject): Boolean {
                points += item.pointsLength
                if (++objects > 50_000 || points > 1_000_000) throw Failure(LIMIT_EXCEEDED)
                return true
            }
            override fun isCancelled() = false
        }
        val renderer = MapRenderRepositories(app, OffscreenRenderOptions(cancellation, snapshot.style, limits, true))
        try {
            for (file in snapshot.files) {
                cancellation.throwIfCanceled()
                renderer.initializeNewResource(file, openPreviewReader(app, file))
            }
            val box = snapshot.box
            val x = MapUtils.get31TileNumberX(box.longitude)
            val y = MapUtils.get31TileNumberY(box.latitude)
            if (renderer.metaInfoFiles.values.none { !it.isBasemap && it.containsMapData(x, y, maxOf(box.zoom, 15)) }) throw Failure(NO_MAP)
            renderer.loadMap(box, app.resourceManager.mapTileDownloader)
            cancellation.throwIfCanceled()
            // The private renderer's bitmap is mutable ARGB_8888; take it instead of copying it.
            val image = checkNotNull(renderer.takeBitmap())
            try {
                if (overlays) {
                    val canvas = Canvas(image); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    drawRoutes(image, snapshot, cancellation)
                    drawMarkers(image, snapshot, app, cancellation)

                    snapshot.position?.let { position ->
                        val x = box.getPixXFromLatLon(position.latitude, position.longitude)
                        val y = box.getPixYFromLatLon(position.latitude, position.longitude)
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.WHITE
                        canvas.drawCircle(x, y, 9f, paint)
                        paint.color = if (position.status == POSITION_FRESH) POSITION_COLOR else STALE_POSITION_COLOR
                        canvas.drawCircle(x, y, 6f, paint)
                    }

                }
                cancellation.throwIfCanceled()
                return image
            } catch (error: Throwable) { image.recycle(); throw error }
        } finally { renderer.close() }
    }

    /** Transparent routes, route markers and the optional clock; location stays scalar metadata. */
    fun overlay(snapshot: Snapshot, app: OsmandApplication, cancellation: CancellationSignal): Bitmap {
        val image = Bitmap.createBitmap(snapshot.box.pixWidth, snapshot.box.pixHeight, Bitmap.Config.ARGB_8888)
        try {
            drawRoutes(image, snapshot, cancellation)
            drawMarkers(image, snapshot, app, cancellation)
            return image
        }
        catch (error: Throwable) { image.recycle(); throw error }
    }

    private fun drawMarkers(image: Bitmap, snapshot: Snapshot, app: OsmandApplication,
        cancellation: CancellationSignal) {
        if (snapshot.markers.isEmpty()) return
        val box = snapshot.box
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val flags = HashMap<Int, Bitmap?>()
        try {
            for ((index, marker) in snapshot.markers.withIndex()) {
                if (index % 64 == 0) cancellation.throwIfCanceled()
                val x = box.getPixXFromLatLon(marker.latitude, marker.longitude)
                val y = box.getPixYFromLatLon(marker.latitude, marker.longitude)
                val flag = flags.getOrPut(marker.kind) {
                    val resource = when (marker.kind) {
                        MARKER_START -> R.drawable.map_start_point
                        MARKER_DESTINATION -> R.drawable.map_target_point
                        else -> R.drawable.map_intermediate_point
                    }
                    BitmapFactory.decodeResource(app.resources, resource,
                        BitmapFactory.Options().apply { inScaled = false })
                } ?: continue
                val height = image.height * 0.10f
                val width = height * flag.width / flag.height
                // The flag pole is at roughly a quarter of the image width; its base marks the point.
                val left = x - width * 0.25f
                val top = y - height
                canvas.drawBitmap(flag, null,
                    android.graphics.RectF(left, top, left + width, top + height), paint)
            }
        } finally {
            flags.values.filterNotNull().forEach { it.recycle() }
        }
    }

    private fun drawRoutes(image: Bitmap, snapshot: Snapshot, cancellation: CancellationSignal) {
        val box = snapshot.box
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (line in snapshot.lines) {
            paint.color = line.color; paint.strokeWidth = if (line.navigation) 6f else 5f
            val path = Path()
            for (index in line.coordinates.indices step 2) {
                if (index % 256 == 0) cancellation.throwIfCanceled()
                val x = box.getPixXFromLatLon(line.coordinates[index], line.coordinates[index + 1])
                val y = box.getPixYFromLatLon(line.coordinates[index], line.coordinates[index + 1])
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Route lines are solid; gradients, arrows and waypoints are not part of the preview protocol.
            canvas.drawPath(path, paint)
        }
    }

    fun baseSnapshot(snapshot: Snapshot, params: MapRequest, revision: String): Snapshot {
        val requested = snapshot.box
        val cached = params.cachedBase?.takeIf { it.revision == revision && it.covers(
            MapView(requested.latitude, requested.longitude, requested.zoom, requested.pixWidth, requested.pixHeight)) }
        val box = if (cached != null) RotatedTileBox.RotatedTileBoxBuilder().setLocation(cached.view.latitude, cached.view.longitude)
            .setZoom(requested.zoom).density(1f).setMapDensity(1.0).setPixelDimensions(cached.view.width, cached.view.height, 0.5f, 0.5f).build()
        else RotatedTileBox.RotatedTileBoxBuilder().setLocation(requested.latitude, requested.longitude).setZoom(requested.zoom)
            .density(1f).setMapDensity(1.0).setPixelDimensions(512, 512, 0.5f, 0.5f).build()
        return Snapshot(box, snapshot.style, snapshot.files, emptyList(), false, 0, snapshot.utc, null, false)
    }

    /** File size/mtime revisions avoid hashing large OBFs on every visible refresh. */
    fun baseRevision(app: OsmandApplication, snapshot: Snapshot): String {
        val metadata = StringBuilder("base-v1\n").append(app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime)
        val style = snapshot.style
        metadata.append('\n').append(style.storage.name).append('\n').append(style.night)
            .append('\n').append(style.locale).append('\n').append(style.textScale)
            .append('\n').append(style.request)
        val files = snapshot.files + app.rendererRegistry.externalRenderers.values + app.getAppPath(net.osmand.IndexConstants.FONT_INDEX_DIR).listFiles().orEmpty().toList()
        if (files.size > 512) throw Failure(LIMIT_EXCEEDED)
        for (file in files.sortedBy { it.absolutePath }) metadata.append('\n').append(file.absolutePath).append(':').append(file.length()).append(':').append(file.lastModified())
        return java.security.MessageDigest.getInstance("SHA-256").digest(metadata.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
