package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Looper
import android.os.OperationCanceledException
import net.osmand.plus.OsmandApplication
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

/** One in-process watch read. The service worker owns this request and its cancellation. */
internal class WearPreview(private val app: OsmandApplication) : Closeable {
    data class Frame(val reply: MapReply, val image: ByteArray?)
    private val cancellation = CancellationSignal()

    fun read(request: MapRequest): Frame {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!WearOsPlugin.allowed(app)) return Frame(MapReply(request.id, MapProtocol.DENIED), null)
        if (!request.valid()) return Frame(MapReply(request.id, MapProtocol.INVALID_REQUEST), null)
        try {
            cancellation.throwIfCanceled()
            val snapshot = MapPreviewRenderer.snapshot(app, request, cancellation, watchLocation = WearOsPlugin.location(app))
            cancellation.throwIfCanceled()
            if (!WearOsPlugin.allowed(app)) throw MapPreviewRenderer.Failure(MapProtocol.DENIED)
            val revision = MapPreviewRenderer.baseRevision(app, snapshot)
            val baseSnapshot = MapPreviewRenderer.baseSnapshot(snapshot, request, revision)
            val baseView = MapView(baseSnapshot.box.latitude, baseSnapshot.box.longitude, baseSnapshot.box.zoom,
                baseSnapshot.box.pixWidth, baseSnapshot.box.pixHeight)
            val base = BaseMap(revision, baseView)
            val reuse = request.cachedBase?.let { it.revision == revision && it.view == baseView } == true
            // An unchanged overlay reuses the last compressed bytes; nothing is drawn or compressed.
            val overlay = snapshot.cachedOverlay ?: run {
                val drawn = MapPreviewRenderer.overlay(snapshot, app, cancellation)
                val encoded = try { encode(drawn, Bitmap.CompressFormat.PNG, MapProtocol.MAX_BYTES - 12) } finally { drawn.recycle() }
                MapPreviewRenderer.rememberOverlay(snapshot.overlayKey, encoded)
            }
            // The watch already holds this exact overlay: send an empty layer and keep only its revision.
            val overlayBytes = if (snapshot.cachedOverlay != null && request.cachedOverlay == overlay.revision)
                ByteArray(0) else overlay.bytes
            val baseBytes = if (reuse) null else {
                val bitmap = MapPreviewRenderer.render(app, baseSnapshot, cancellation, false)
                try { encodeMap(bitmap, MapProtocol.MAX_BYTES - 12 - overlayBytes.size) } finally { bitmap.recycle() }
            }
            val image = MapProtocol.layerImage(baseBytes, overlayBytes)
            cancellation.throwIfCanceled()
            if (!WearOsPlugin.allowed(app)) throw MapPreviewRenderer.Failure(MapProtocol.DENIED)
            val box = snapshot.box
            val ruler = WearRuler.read(app, box)
            val position = snapshot.position
            val reply = MapReply(request.id, MapProtocol.OK,
                MapView(box.latitude, box.longitude, box.zoom, box.pixWidth, box.pixHeight), snapshot.utc,
                snapshot.navigation, snapshot.tracks, image.size, position?.status ?: MapProtocol.POSITION_UNAVAILABLE,
                position?.latitude ?: 0.0, position?.longitude ?: 0.0, position?.utc ?: 0,
                snapshot.following, base, position?.bearing, ruler?.label, ruler?.ratio ?: 0f,
                snapshot.clockText, overlay.revision)
            check(reply.matches(request))
            return Frame(reply, image)
        } catch (error: Exception) {
            val status = when (error) {
                is MapPreviewRenderer.Failure -> error.status
                is OperationCanceledException -> MapProtocol.CANCELLED
                else -> MapProtocol.RENDER_ERROR
            }
            // Expected outcomes (no map here, cancelled, busy content) are counted by the router;
            // an unexpected error is logged with its message.
            if (status == MapProtocol.RENDER_ERROR) WearOsPlugin.trace("map render error: ${LinkDiagnostics.describe(error)}")
            return Frame(MapReply(request.id, status), null)
        }
    }

    /**
     * WebP is almost always far smaller for rendered maps, so it is the only encoding tried first.
     * PNG is made only when WebP fails or does not fit, instead of encoding both on every base map.
     */
    private fun encodeMap(bitmap: Bitmap, limit: Int): ByteArray {
        try { return encode(bitmap, WEBP_LOSSY, limit) }
        catch (_: MapPreviewRenderer.Failure) { }
        catch (_: IOException) { }
        return encode(bitmap, Bitmap.CompressFormat.PNG, limit)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, limit: Int): ByteArray {
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                cancellation.throwIfCanceled()
                if (count.toLong() + length > limit) throw MapPreviewRenderer.Failure(MapProtocol.LIMIT_EXCEEDED)
                super.write(bytes, offset, length)
            }
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        }
        if (!bitmap.compress(format, if (format == WEBP_LOSSY) 95 else 100, output) || output.size() == 0)
            throw IOException("Preview encoding")
        return output.toByteArray()
    }

    @Synchronized fun abort() { cancellation.cancel() }

    private companion object {
        /**
         * Lossy WebP. Android 11 replaced the plain WEBP format with explicit lossy and lossless
         * ones; below quality 100 the old name already meant lossy, so older phones keep it.
         */
        val WEBP_LOSSY: Bitmap.CompressFormat =
            if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }
    override fun close() = abort()
}
