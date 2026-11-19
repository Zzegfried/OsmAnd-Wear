package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import net.osmand.PlatformUtil
import net.osmand.plus.BuildConfig
import net.osmand.plus.OsmandApplication
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Transport-independent, bounded Wear v1 request handler. Peer IDs are backend-qualified keys. */
internal class WearRequestRouter(
    private val app: OsmandApplication,
    private val connected: (String) -> Boolean
) : WearRequestHandler {
    private val LOG = PlatformUtil.getLog(WearRequestRouter::class.java)
    private val main = Handler(Looper.getMainLooper())
    /**
     * Shared workers for map, widget and profile reads, so a frame every two seconds reuses a
     * thread instead of starting one. Reads never queue behind each other: a slow render must not
     * delay Info. Idle threads end after 30 s, so a closed router leaves nothing running.
     */
    private val workers = java.util.concurrent.ThreadPoolExecutor(0, MAX_WORKERS, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { task ->
        Thread(task, "OsmAndWatchWorker").apply { isDaemon = true }
    }
    private val recent = linkedMapOf<String, Long>()
    private var active: MapJob? = null
    private val sessionAdmission = AtomicBoolean()
    private val widgetAdmission = AtomicBoolean()
    private val profileAdmission = AtomicBoolean()
    private val widgetActive = mutableSetOf<WidgetJob>()
    private val recentWidgetActions = linkedMapOf<UUID, Long>()
    /** The last session-open line written; main thread only. */
    private var lastSessionLine: String? = null

    private inner class WidgetJob(
        val peer: String,
        val kind: String,
        val error: (Int) -> ByteArray,
        val admission: AtomicBoolean
    ) {
        val answer = CompletableFuture<WearReply>()
        val timeout = Runnable { fail(WidgetProtocol.PHONE_REQUIRED) }

        fun finish(bytes: ByteArray) {
            if (!answer.complete(WearReply(bytes))) return
            main.removeCallbacks(timeout)
            synchronized(this@WearRequestRouter) { widgetActive.remove(this) }
            admission.set(false)
        }

        fun fail(status: Int) {
            if (BuildConfig.DEBUG) LOG.debug("$kind failed status=$status")
            WearOsPlugin.trace("$kind request failed: ${widgetStatus(status)}")
            finish(error(status))
        }
    }

    private inner class MapJob(val peer: String, val request: MapRequest) {
        val answer = CompletableFuture<WearReply>()
        val started = SystemClock.elapsedRealtime()
        val preview = WearPreview(app)
        @Volatile var stopped = false
        val timeout = Runnable {
            WearOsPlugin.trace("map request timed out after 20 s")
            abort(MapProtocol.TIMEOUT)
        }

        fun abort(status: Int) {
            stopped = true
            answer.complete(WearReply(MapProtocol.encode(MapReply(request.id, status))))
            preview.abort()
        }
    }

    /** Returns null only for an unknown v1 path. No request bypasses authenticated-link admission. */
    override fun request(peer: String, path: String, data: ByteArray): CompletableFuture<WearReply>? {
        lastRequestAt = SystemClock.elapsedRealtime()
        if (!connected(peer)) {
            WearOsPlugin.trace("request refused: sender is not the connected watch (${path.substringAfterLast('/')})")
            // A map request always gets a decodable status so the watch fails only that request.
            val map = if (path == MapProtocol.REQUEST_PATH) MapProtocol.request(data) else null
            return CompletableFuture.completedFuture(WearReply(map?.let {
                MapProtocol.encode(MapReply(it.id, MapProtocol.UNAVAILABLE))
            } ?: ByteArray(0)))
        }
        if (path == WidgetProtocol.SNAPSHOT_PATH) {
            val parsed = WidgetProtocol.request(data) ?: return malformed("snapshot")
            return widgetCall(peer, parsed.session,
                { WidgetProtocol.encode(WidgetProtocol.Snapshot(parsed.id, it)) }, kind = "snapshot") { job ->
                workers.execute {
                    val snapshot = runCatching { WearWidgetSnapshot.read(app) }
                        .onFailure { WearOsPlugin.trace("snapshot read failed: ${LinkDiagnostics.describe(it)}") }.getOrNull()
                    main.post {
                        if (job.answer.isDone) return@post
                        if (!WearOsPlugin.allowed(app)) {
                            job.fail(WidgetProtocol.DENIED)
                            return@post
                        }
                        if (!WearLocationService.owns(peer, parsed.session)) {
                            job.fail(WidgetProtocol.STALE)
                            return@post
                        }
                        if (snapshot == null) {
                            job.fail(WidgetProtocol.UNAVAILABLE)
                            return@post
                        }
                        runCatching { job.finish(WidgetProtocol.encode(snapshot.toWire(parsed.id))) }
                            .onFailure { job.fail(WidgetProtocol.UNAVAILABLE) }
                    }
                }
            }
        }
        if (path == WidgetProtocol.PROFILE_PATH) {
            val parsed = WidgetProtocol.request(data) ?: return malformed("profile")
            return widgetCall(peer, parsed.session,
                { WidgetProtocol.encode(WidgetProtocol.ProfileReply(parsed.id, it)) },
                requireSession = false, admission = profileAdmission, kind = "profile") { job ->
                workers.execute {
                    val profile = runCatching { WearWidgetSnapshot.routeProfile(app, System.currentTimeMillis()) }
                        .onFailure { WearOsPlugin.trace("route profile read failed: ${LinkDiagnostics.describe(it)}") }.getOrNull()
                    main.post {
                        if (job.answer.isDone) return@post
                        if (!WearOsPlugin.allowed(app)) {
                            job.fail(WidgetProtocol.DENIED)
                            return@post
                        }
                        job.finish(WidgetProtocol.encode(WidgetProtocol.ProfileReply(parsed.id, WidgetProtocol.OK, profile)))
                    }
                }
            }
        }
        if (path == WidgetProtocol.ACTION_PATH) {
            val action = WidgetProtocol.action(data) ?: return malformed("recording action")
            return widgetCall(peer, action.session,
                { WidgetProtocol.encode(WidgetProtocol.Result(action.id, it)) }, kind = "action") { job ->
                val now = SystemClock.elapsedRealtime()
                recentWidgetActions.entries.removeAll { now - it.value > 60_000 }
                if (recentWidgetActions.containsKey(action.id)) {
                    WearOsPlugin.trace("recording action ignored: repeated request")
                    job.fail(WidgetProtocol.STALE)
                    return@widgetCall
                }
                if (recentWidgetActions.size >= 64) recentWidgetActions.remove(recentWidgetActions.keys.first())
                recentWidgetActions[action.id] = now
                val status = runCatching { WearWidgetActions.perform(app, peer, action.session,
                    action.command, action.expectedState) }
                    .onFailure { WearOsPlugin.trace("recording action failed: ${LinkDiagnostics.describe(it)}") }
                    .getOrDefault(WidgetProtocol.UNAVAILABLE)
                val state = runCatching { WearWidgetSnapshot.read(app).recordingState.wire() }
                    .getOrDefault(WidgetProtocol.INACTIVE)
                WearOsPlugin.trace("recording action ${commandName(action.command)} (watch expected ${recordingName(action.expectedState)}): " +
                    "${widgetStatus(status)}; recording now ${recordingName(state)}")
                job.finish(WidgetProtocol.encode(WidgetProtocol.Result(action.id, status, state)))
            }
        }
        if (path == MapProtocol.SESSION_PATH) {
            val session = MapProtocol.session(data) ?: return malformed("session")
            // Close must reach the main thread even when an earlier open is still being admitted.
            // Otherwise a busy reply can leave GPS owned until expiry after the watch exits.
            if (session.second && !sessionAdmission.compareAndSet(false, true)) {
                WearOsPlugin.trace("session open: BUSY (previous open still being admitted)")
                return completed(MapProtocol.sessionReply(MapProtocol.SESSION_BUSY, WearOsPlugin.diagnosticsEnabled()))
            }
            val answer = CompletableFuture<WearReply>()
            val timeout = Runnable {
                LOG.info("Watch session admission timed out")
                WearOsPlugin.trace("session ${if (session.second) "open" else "close"}: BUSY (main thread did not answer in 10 s)")
                answer.complete(WearReply(MapProtocol.sessionReply(MapProtocol.SESSION_BUSY, WearOsPlugin.diagnosticsEnabled())))
            }
            main.postDelayed(timeout, 10_000)
            answer.whenComplete { _, _ ->
                main.removeCallbacks(timeout)
                if (session.second) sessionAdmission.set(false)
            }
            main.post {
                if (answer.isDone) return@post
                val code = when {
                    !connected(peer) -> MapProtocol.SESSION_DENIED
                    session.second -> WearLocationService.refresh(app, peer, session.first, MapProtocol.sessionAwake(data))
                    else -> {
                        WearLocationService.release(peer, session.first)
                        MapProtocol.SESSION_READY
                    }
                }
                if (BuildConfig.DEBUG) LOG.debug("session refresh status=$code")
                // The phone state is logged with each open whose status or state changed, so a refusal
                // can be read on its own; the watch's retries every 5 s are otherwise only counted.
                if (session.second) {
                    val line = "session open (watch ${if (MapProtocol.sessionAwake(data)) "awake" else "asleep"}): " +
                        "${sessionStatus(code)}; ${WearDeviceEvents.state(app)}"
                    if (line != lastSessionLine) WearOsPlugin.trace(line) else WearOsPlugin.traceCount("session open repeated: ${sessionStatus(code)}")
                    lastSessionLine = line
                } else {
                    lastSessionLine = null
                    WearOsPlugin.trace("session close: ${sessionStatus(code)}")
                }
                answer.complete(WearReply(MapProtocol.sessionReply(code, WearOsPlugin.diagnosticsEnabled())))
            }
            return answer
        }
        if (path != MapProtocol.REQUEST_PATH) return null
        val parsed = MapProtocol.request(data) ?: return malformed("map")
        if (!WearOsPlugin.allowed(app)) {
            WearOsPlugin.trace("map request refused: plugin disabled")
            return completed(MapProtocol.encode(MapReply(parsed.id, MapProtocol.DENIED)))
        }
        val job: MapJob
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            recent.entries.removeAll { now - it.value > MapProtocol.TIMEOUT_MS }
            if (recent.containsKey(parsed.id.toString())) {
                WearOsPlugin.trace("map request refused: repeated request ID")
                return completed(MapProtocol.encode(MapReply(parsed.id, MapProtocol.INVALID)))
            }
            if (active != null) {
                WearOsPlugin.traceCount("map busy")
                return completed(MapProtocol.encode(MapReply(parsed.id, MapProtocol.BUSY)))
            }
            if (recent.size >= 32) recent.remove(recent.keys.first())
            recent[parsed.id.toString()] = now
            job = MapJob(peer, parsed)
            active = job
        }
        timing(job, "admitted")
        main.postDelayed(job.timeout, 20_000)
        try { workers.execute { deliver(job) } }
        catch (error: Exception) {
            WearOsPlugin.trace("map request not started: ${LinkDiagnostics.describe(error)}")
            job.abort(MapProtocol.UNAVAILABLE)
            main.removeCallbacks(job.timeout)
            synchronized(this) { if (active === job) active = null }
        }
        return job.answer
    }

    override fun cancel(peer: String, data: ByteArray) {
        val id = MapProtocol.cancelId(data) ?: return
        val job = synchronized(this) { active?.takeIf { it.peer == peer && it.request.id == id } }
        WearOsPlugin.traceCount(if (job != null) "map cancelled while rendering" else "cancel for no running map")
        job?.abort(MapProtocol.CANCELLED)
    }

    override fun disconnected(peer: String) {
        // A socket loss is not an application close. Keep the existing location lease so the
        // authenticated watch can reconnect while the phone is locked without starting a new FGS.
        LOG.info("Watch peer disconnected; retaining bounded location lease")
        WearOsPlugin.trace("watch link lost; location lease kept=${WearLocationService.hasActiveSession()}")
        synchronized(this) { active?.takeIf { it.peer == peer } }?.abort(MapProtocol.UNAVAILABLE)
        failWidgetJobs(WidgetProtocol.UNAVAILABLE, peer)
    }

    fun disabled() {
        synchronized(this) { active }?.abort(MapProtocol.DENIED)
        failWidgetJobs(WidgetProtocol.DENIED)
        MapPreviewRenderer.clearOverlayCache()
    }

    fun close() {
        synchronized(this) { active }?.abort(MapProtocol.UNAVAILABLE)
        failWidgetJobs(WidgetProtocol.UNAVAILABLE)
        MapPreviewRenderer.clearOverlayCache()
    }

    private fun completed(payload: ByteArray) = CompletableFuture.completedFuture(WearReply(payload))

    /** An undecodable request gets an empty reply; the watch then fails that request alone. */
    private fun malformed(kind: String): CompletableFuture<WearReply> {
        WearOsPlugin.trace("$kind request malformed; empty reply sent")
        return completed(ByteArray(0))
    }

    private fun mapStatus(code: Int) = when (code) {
        MapProtocol.OK -> "OK"
        MapProtocol.BUSY -> "BUSY"
        MapProtocol.DENIED -> "DENIED"
        MapProtocol.NO_MAP -> "NO_MAP"
        MapProtocol.TIMEOUT -> "TIMEOUT"
        MapProtocol.UNAVAILABLE -> "UNAVAILABLE"
        MapProtocol.NOT_READY -> "NOT_READY"
        MapProtocol.LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
        MapProtocol.CONTENT_CHANGED -> "CONTENT_CHANGED"
        MapProtocol.CANCELLED -> "CANCELLED"
        MapProtocol.INVALID -> "INVALID"
        MapProtocol.INVALID_REQUEST -> "INVALID_REQUEST"
        MapProtocol.RENDER_ERROR -> "RENDER_ERROR"
        MapProtocol.UNSUPPORTED -> "UNSUPPORTED"
        else -> "status $code"
    }

    private fun widgetStatus(code: Int) = when (code) {
        WidgetProtocol.OK -> "OK"
        WidgetProtocol.DENIED -> "DENIED"
        WidgetProtocol.STALE -> "STALE (not the watch's live session)"
        WidgetProtocol.UNAVAILABLE -> "UNAVAILABLE"
        WidgetProtocol.PHONE_REQUIRED -> "PHONE_REQUIRED (no answer in 10 s)"
        WidgetProtocol.INVALID -> "INVALID"
        else -> "status $code"
    }

    private fun commandName(command: Int) = when (command) {
        WidgetProtocol.START -> "start"
        WidgetProtocol.PAUSE -> "pause"
        WidgetProtocol.RESUME -> "resume"
        WidgetProtocol.FINISH -> "finish"
        else -> "command $command"
    }

    private fun recordingName(state: Int) = when (state) {
        WidgetProtocol.INACTIVE -> "not recording"
        WidgetProtocol.RECORDING -> "recording"
        WidgetProtocol.PAUSED -> "paused"
        WidgetProtocol.SAVING -> "saving"
        else -> "state $state"
    }

    private fun durationBand(ms: Long) = when {
        ms < 1_000 -> "under 1 s"
        ms < 2_000 -> "1-2 s"
        ms < 5_000 -> "2-5 s"
        else -> "over 5 s"
    }

    private fun sessionStatus(code: Int) = when (code) {
        MapProtocol.SESSION_READY -> "READY"
        MapProtocol.SESSION_PHONE_REQUIRED -> "PHONE_REQUIRED"
        MapProtocol.SESSION_STOPPED -> "STOPPED"
        MapProtocol.SESSION_DENIED -> "DENIED"
        MapProtocol.SESSION_CONNECTING -> "CONNECTING"
        MapProtocol.SESSION_BUSY -> "BUSY"
        else -> "status $code"
    }

    private fun failWidgetJobs(status: Int, peer: String? = null) {
        val jobs = synchronized(this) { widgetActive.toList() }
        jobs.filter { peer == null || it.peer == peer }.forEach { it.fail(status) }
    }

    private fun widgetCall(peer: String, session: UUID, error: (Int) -> ByteArray,
                           requireSession: Boolean = true,
                           admission: AtomicBoolean = widgetAdmission,
                           kind: String,
                           authorized: (WidgetJob) -> Unit): CompletableFuture<WearReply> {
        if (!WearOsPlugin.allowed(app)) {
            WearOsPlugin.trace("$kind request refused: plugin disabled")
            return completed(error(WidgetProtocol.DENIED))
        }
        if (!admission.compareAndSet(false, true)) {
            WearOsPlugin.trace("$kind request refused: previous $kind request still running")
            return completed(error(WidgetProtocol.UNAVAILABLE))
        }
        val job = WidgetJob(peer, kind, error, admission)
        synchronized(this) { widgetActive.add(job) }
        main.postDelayed(job.timeout, 10_000)
        main.post {
            if (job.answer.isDone) return@post
            if (!connected(peer)) {
                job.fail(WidgetProtocol.DENIED)
                return@post
            }
            if (!WearOsPlugin.allowed(app)) {
                job.fail(WidgetProtocol.DENIED)
                return@post
            }
            if (requireSession && !WearLocationService.owns(peer, session)) {
                job.fail(WidgetProtocol.STALE)
                return@post
            }
            runCatching { authorized(job) }.onFailure {
                WearOsPlugin.trace("$kind request failed: ${LinkDiagnostics.describe(it)}")
                job.fail(WidgetProtocol.UNAVAILABLE)
            }
        }
        return job.answer
    }

    private fun deliver(job: MapJob) {
        try {
            check(connected(job.peer) && !job.stopped)
            val frame = job.preview.read(job.request)
            timing(job, "preview_ready bytes=${frame.reply.bytes}")
            check(!job.stopped && WearOsPlugin.allowed(app))
            check(frame.reply.matches(job.request))
            val header = MapProtocol.encode(frame.reply)
            val image = frame.image
            check((frame.reply.status == MapProtocol.OK) == (image != null))
            check(image == null || image.size == frame.reply.bytes)
            WearOsPlugin.traceCount(if (frame.reply.status == MapProtocol.OK) "map ok" else "map status ${mapStatus(frame.reply.status)}")
            val took = SystemClock.elapsedRealtime() - job.started
            WearOsPlugin.traceCount("map frame ${durationBand(took)}")
            if (took > SLOW_FRAME_MS) WearOsPlugin.trace("map frame slow: $took ms (${mapStatus(frame.reply.status)}, ${frame.reply.bytes} bytes)")
            if (image != null && 2L + header.size + image.size <= MapProtocol.MAX_INLINE_BYTES) {
                timing(job, "inline bytes=${image.size}")
                job.answer.complete(WearReply(MapProtocol.inlineImage(header, image)))
            } else {
                check(image == null || image.size in 1..MapProtocol.MAX_BYTES)
                job.answer.complete(WearReply(header, image))
            }
        } catch (error: Exception) {
            WearOsPlugin.trace("map request failed: ${if (job.stopped) "stopped" else LinkDiagnostics.describe(error)}")
            job.answer.complete(WearReply(MapProtocol.encode(MapReply(job.request.id,
                if (WearOsPlugin.allowed(app)) MapProtocol.UNAVAILABLE else MapProtocol.DENIED))))
        } finally {
            job.stopped = true
            try { job.preview.close() } finally {
                main.removeCallbacks(job.timeout)
                synchronized(this) { if (active === job) active = null }
            }
        }
    }

    private fun timing(job: MapJob, phase: String) {
        if (BuildConfig.DEBUG) LOG.debug(
            "t=${SystemClock.elapsedRealtime()} id=${job.request.id} $phase")
    }

    private fun WearWidgetSnapshot.RecordingState.wire() = when (this) {
        WearWidgetSnapshot.RecordingState.INACTIVE -> WidgetProtocol.INACTIVE
        WearWidgetSnapshot.RecordingState.RECORDING -> WidgetProtocol.RECORDING
        WearWidgetSnapshot.RecordingState.PAUSED_OR_UNSAVED -> WidgetProtocol.PAUSED
        WearWidgetSnapshot.RecordingState.SAVING -> WidgetProtocol.SAVING
    }

    private fun WearWidgetSnapshot.toWire(id: UUID): WidgetProtocol.Snapshot {
        val rows = rows.map { row ->
            val kind = when (row.kind) {
                WearWidgetSnapshot.Kind.CURRENT_TIME -> WidgetProtocol.CURRENT_TIME
                WearWidgetSnapshot.Kind.ALTITUDE_CURRENT -> WidgetProtocol.ALTITUDE
                WearWidgetSnapshot.Kind.RECORDING_DISTANCE -> WidgetProtocol.RECORDING_DISTANCE
                WearWidgetSnapshot.Kind.TIME_TO_DESTINATION -> WidgetProtocol.TIME_TO_DESTINATION
                WearWidgetSnapshot.Kind.DISTANCE_TO_DESTINATION -> WidgetProtocol.DISTANCE_TO_DESTINATION
            }
            fun clean(value: String, max: Int) = value.filter { it.code >= 0x20 && it.code != 0x7f }.take(max)
            WidgetProtocol.Row(kind, clean(row.title, 64).ifEmpty { " " }, clean(row.value, 64),
                clean(row.unit.orEmpty(), 24), clean(row.iconKey, 64), row.nightMode,
                row.labelColor, row.valueColor, row.accentColor, row.boldText, clean(row.sizeMode, 24))
        }
        return WidgetProtocol.Snapshot(id, WidgetProtocol.OK, capturedUtcMs, rows, recordingState.wire(),
            startupDialog, WearOsPlugin.recordingControlsEnabled())
    }

    companion object {
        /**
         * Admission allows one map, one snapshot and one profile read at a time. The margin covers
         * reads that outlive their timeout; a rejected read fails only that request.
         */
        private const val MAX_WORKERS = 6
        /** A frame slower than this gets its own diagnostic line. */
        private const val SLOW_FRAME_MS = 5_000L

        /** When the connected watch last sent any request; read by the diagnostic heartbeat. */
        @Volatile private var lastRequestAt = 0L

        internal fun lastRequestAge(): String =
            if (lastRequestAt == 0L) "never" else "${(SystemClock.elapsedRealtime() - lastRequestAt) / 1000}s ago"
    }
}
