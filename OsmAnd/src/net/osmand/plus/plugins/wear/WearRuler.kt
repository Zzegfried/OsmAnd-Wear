package net.osmand.plus.plugins.wear

import net.osmand.data.RotatedTileBox
import net.osmand.plus.OsmandApplication
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.plus.utils.OsmAndFormatterParams.NO_TRAILING_ZEROS

/** Phone-side map scale value for the watch. Pixels stay a watch concern. */
internal object WearRuler {
    data class Value(val label: String, val ratio: Float)

    fun read(app: OsmandApplication, box: RotatedTileBox): Value? {
        if (!WearOsPlugin.rulerEnabled(app)) return null
        val width = box.pixWidth
        val density = box.pixDensity
        if (width <= 0 || density <= 0.0) return null
        val targetMeters = width / 4.0 / density
        val distance = OsmAndFormatter.calculateRoundedDist(targetMeters, app)
        if (!distance.isFinite() || distance <= 0.0) return null
        val ratio = (density * distance / width).toFloat()
        if (!ratio.isFinite() || ratio !in 0.05f..0.5f) return null
        val label = OsmAndFormatter.getFormattedDistance(distance.toFloat(), app, NO_TRAILING_ZEROS)
        if (label.isBlank() || label.length > 24) return null
        return Value(label, ratio)
    }
}
