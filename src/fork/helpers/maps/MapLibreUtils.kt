package desu.inugram.helpers.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Polygon
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.IMapsProvider
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.geojson.Point as GeoPoint

internal class MlUISettings(private val map: MapLibreMap) : IMapsProvider.IUISettings {
    override fun setZoomControlsEnabled(enabled: Boolean) {}
    override fun setMyLocationButtonEnabled(enabled: Boolean) {}
    override fun setCompassEnabled(enabled: Boolean) {
        map.uiSettings.isCompassEnabled = enabled
    }
}

// MapLibre's iconAnchor only supports preset enum values, not arbitrary fractions; pad the bitmap so that
// the requested anchor lands at the bitmap center, then use ICON_ANCHOR_CENTER.
internal fun padForCenterAnchor(bmp: Bitmap, anchorU: Float, anchorV: Float): Bitmap {
    val w = bmp.width
    val h = bmp.height
    val cu = anchorU * w
    val cv = anchorV * h
    val newW = (2 * maxOf(cu, w - cu)).toInt().coerceAtLeast(w)
    val newH = (2 * maxOf(cv, h - cv)).toInt().coerceAtLeast(h)
    if (newW == w && newH == h) return bmp
    val out = createBitmap(newW, newH)
    Canvas(out).drawBitmap(bmp, newW / 2f - cu, newH / 2f - cv, null)
    return out
}

internal fun buildCirclePolygon(center: MlLatLng, radiusM: Double, segments: Int = 64): Polygon {
    val coords = ArrayList<GeoPoint>(segments + 1)
    val lat = Math.toRadians(center.latitude)
    val lng = Math.toRadians(center.longitude)
    val ang = radiusM / 6378137.0
    for (i in 0..segments) {
        val brng = 2 * Math.PI * i / segments
        val lat2 = asin(sin(lat) * cos(ang) + cos(lat) * sin(ang) * cos(brng))
        val lng2 = lng + atan2(
            sin(brng) * sin(ang) * cos(lat),
            cos(ang) - sin(lat) * sin(lat2),
        )
        coords.add(GeoPoint.fromLngLat(Math.toDegrees(lng2), Math.toDegrees(lat2)))
    }
    return Polygon.fromLngLats(listOf(coords))
}

internal fun colorToCss(c: Int): String =
    "rgba(${Color.red(c)},${Color.green(c)},${Color.blue(c)},${Color.alpha(c) / 255f})"

private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
private fun strokePaint(color: Int, widthPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = widthPx
}

internal fun blueDotBitmap(): Bitmap {
    val sizePx = AndroidUtilities.dp(18f).coerceAtLeast(8)
    val bmp = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bmp)
    val c = sizePx / 2f
    canvas.drawCircle(c, c, c, fillPaint(Color.WHITE))
    canvas.drawCircle(c, c, c * 0.78f, fillPaint(0xFF4285F4.toInt()))
    return bmp
}

internal fun headingArrowBitmap(ctx: Context): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val triW = AndroidUtilities.dp(10f).toFloat()
    val triH = triW / 2f
    val gap = AndroidUtilities.dp(9f).toFloat()
    val w = triW.toInt()
    val h = (triH + gap).toInt()
    val bmp = createBitmap(w, h)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(w / 2f, 0f)
        lineTo(w.toFloat(), triH)
        lineTo(0f, triH)
        close()
    }
    canvas.drawPath(path, fillPaint(0xFF4285F4.toInt()))
    canvas.drawPath(path, strokePaint(Color.WHITE, 0.75f * d))
    return bmp
}
