package desu.inugram.helpers.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.text.Html
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.util.Consumer
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.IMapsProvider.LatLng
import java.util.concurrent.atomic.AtomicLong
import org.maplibre.android.camera.CameraPosition as MlCameraPosition
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.geojson.Point as GeoPoint

internal class MlIMap(
    private val viewWrapper: MlIMapView,
    private val mlMap: MapLibreMap,
    initialStyle: Style,
) : IMapsProvider.IMap {

    private val markers = ArrayList<MlIMarker>()
    private val circles = ArrayList<MlICircle>()
    private var markerClickListener: IMapsProvider.OnMarkerClickListener? = null
    private var currentStyle: Style? = null
    private var destroyed = false

    private val ctx: Context get() = viewWrapper.mapView.context

    private val locationProvider: LocationProvider by lazy { createLocationProvider(ctx) }
    private var locationStarted = false
    private var locationConsumer: Consumer<Location>? = null
    private var visualLocationEnabled = false
    private var myLocationMarker: MlIMarker? = null
    private var myHeadingMarker: MlIMarker? = null
    private var myAccuracyCircle: MlICircle? = null

    private val refreshMarkers = Pending { refreshMarkerSources() }
    private val refreshCircles = Pending { refreshCircleSource() }

    fun onDestroy() {
        destroyed = true
        if (locationStarted) {
            locationProvider.stop()
            locationStarted = false
        }
        currentStyle = null
    }

    private inner class Pending(val action: () -> Unit) {
        private var scheduled = false
        fun schedule() {
            if (scheduled || destroyed) return
            scheduled = true
            viewWrapper.mapView.post {
                scheduled = false
                if (!destroyed) action()
            }
        }
    }

    init {
        bindToStyle(initialStyle)
        mlMap.addOnMapClickListener { latLng ->
            val pt = mlMap.projection.toScreenLocation(latLng)
            val features = mlMap.queryRenderedFeatures(PointF(pt.x, pt.y), LAYER_MARKERS, LAYER_MARKERS_FLAT)
            val id = features.firstOrNull()?.id() ?: return@addOnMapClickListener false
            val marker = markers.firstOrNull { it.featureId == id } ?: return@addOnMapClickListener false
            markerClickListener?.onClick(marker) ?: false
        }
    }

    private fun bindToStyle(style: Style) {
        currentStyle = style
        for (m in markers) m.paddedBitmap?.let { style.addImage(m.imageId, it) }

        style.addSource(GeoJsonSource(SRC_CIRCLES))
        style.addSource(GeoJsonSource(SRC_MARKERS))
        style.addSource(GeoJsonSource(SRC_MARKERS_FLAT))

        style.addLayer(
            FillLayer(LAYER_CIRCLES_FILL, SRC_CIRCLES).withProperties(
                PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
            )
        )
        style.addLayer(
            LineLayer(LAYER_CIRCLES_STROKE, SRC_CIRCLES).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                PropertyFactory.lineWidth(Expression.toNumber(Expression.get("strokeWidth"))),
            )
        )
        style.addLayer(markerSymbolLayer(LAYER_MARKERS_FLAT, SRC_MARKERS_FLAT, Property.ICON_ROTATION_ALIGNMENT_MAP))
        style.addLayer(markerSymbolLayer(LAYER_MARKERS, SRC_MARKERS, Property.ICON_ROTATION_ALIGNMENT_VIEWPORT))

        refreshMarkerSources()
        refreshCircleSource()
    }

    private fun markerSymbolLayer(id: String, source: String, alignment: String) =
        SymbolLayer(id, source).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotate(Expression.toNumber(Expression.get("rotation"))),
            PropertyFactory.iconRotationAlignment(alignment),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )

    private inline fun applyToStyle(block: (Style) -> Unit) {
        val style = currentStyle ?: return
        if (destroyed || !style.isFullyLoaded) return
        try {
            block(style)
        } catch (_: IllegalStateException) {
        }
    }

    private fun refreshMarkerSources() = applyToStyle { style ->
        val regular = ArrayList<Feature>()
        val flat = ArrayList<Feature>()
        for (m in markers) (if (m.flat) flat else regular).add(m.toFeature())
        (style.getSource(SRC_MARKERS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(regular))
        (style.getSource(SRC_MARKERS_FLAT) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(flat))
    }

    private fun refreshCircleSource() = applyToStyle { style ->
        val features = circles.map { it.toFeature() }
        (style.getSource(SRC_CIRCLES) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    override fun setMapType(mapType: Int) {
        val (builder, attr) = when (mapType) {
            IMapsProvider.MAP_TYPE_SATELLITE, IMapsProvider.MAP_TYPE_HYBRID ->
                Style.Builder().fromJson(SATELLITE_STYLE_JSON) to ATTRIBUTION_SATELLITE

            else -> Style.Builder().fromUri(BRIGHT_STYLE) to ATTRIBUTION_BRIGHT
        }
        viewWrapper.attribution.text = Html.fromHtml(attr)
        currentStyle = null
        mlMap.setStyle(builder) { style -> bindToStyle(style) }
    }

    override fun animateCamera(update: IMapsProvider.ICameraUpdate) {
        mlMap.animateCamera((update as MlCameraUpdate).update)
    }

    override fun animateCamera(update: IMapsProvider.ICameraUpdate, callback: IMapsProvider.ICancelableCallback?) {
        mlMap.animateCamera((update as MlCameraUpdate).update, callback?.toMl())
    }

    override fun animateCamera(update: IMapsProvider.ICameraUpdate, duration: Int, callback: IMapsProvider.ICancelableCallback?) {
        mlMap.animateCamera((update as MlCameraUpdate).update, duration, callback?.toMl())
    }

    override fun moveCamera(update: IMapsProvider.ICameraUpdate) {
        mlMap.moveCamera((update as MlCameraUpdate).update)
    }

    override fun getMaxZoomLevel(): Float = MAX_ZOOM
    override fun getMinZoomLevel(): Float = mlMap.minZoomLevel.toFloat()

    override fun getUiSettings(): IMapsProvider.IUISettings = MlUISettings(mlMap)
    override fun setOnCameraIdleListener(callback: Runnable?) {
        if (callback == null) return
        mlMap.addOnCameraIdleListener { callback.run() }
    }

    override fun setOnCameraMoveStartedListener(listener: IMapsProvider.OnCameraMoveStartedListener) {
        mlMap.addOnCameraMoveStartedListener { reason ->
            val out = when (reason) {
                MapLibreMap.OnCameraMoveStartedListener.REASON_API_ANIMATION ->
                    IMapsProvider.OnCameraMoveStartedListener.REASON_API_ANIMATION

                MapLibreMap.OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION ->
                    IMapsProvider.OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION

                else -> IMapsProvider.OnCameraMoveStartedListener.REASON_GESTURE
            }
            listener.onCameraMoveStarted(out)
        }
    }

    override fun getCameraPosition(): IMapsProvider.CameraPosition {
        val pos: MlCameraPosition = mlMap.cameraPosition
        val target = pos.target ?: MlLatLng(0.0, 0.0)
        return IMapsProvider.CameraPosition(target.toApi(), pos.zoom.toFloat())
    }

    override fun setOnMapLoadedCallback(callback: Runnable?) {
        if (callback == null) return
        viewWrapper.mapView.addOnDidFinishLoadingMapListener { callback.run() }
    }

    override fun getProjection(): IMapsProvider.IProjection = object : IMapsProvider.IProjection {
        override fun toScreenLocation(latLng: LatLng): Point {
            val pf = mlMap.projection.toScreenLocation(latLng.toMl())
            return Point(pf.x.toInt(), pf.y.toInt())
        }
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        mlMap.setPadding(left, top, right, bottom)
        val attr = viewWrapper.attribution
        val lp = attr.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = bottom + AndroidUtilities.dp(16f)
        attr.layoutParams = lp
    }

    override fun setMapStyle(style: IMapsProvider.IMapStyleOptions?) {} // fixed style

    override fun addMarker(markerOptions: IMapsProvider.IMarkerOptions): IMapsProvider.IMarker {
        val o = markerOptions as MlMarkerOptionsImpl
        val bitmap = o.bitmap ?: o.iconResId.takeIf { it != 0 }?.let { resToBitmap(it) }
        val abs = MlIMarker().apply {
            position = o.position
            anchorU = o.anchorU
            anchorV = o.anchorV
            flat = o.flat
            updateBitmap(bitmap)
        }
        markers.add(abs)
        refreshMarkers.schedule()
        return abs
    }

    override fun addCircle(circleOptions: IMapsProvider.ICircleOptions): IMapsProvider.ICircle {
        val o = circleOptions as MlCircleOptionsImpl
        val abs = MlICircle().apply {
            center = o.center
            radiusM = o.radiusMeters
            fill = o.fill
            stroke = o.stroke
            strokeWidthPx = o.strokeWidth.toFloat()
        }
        circles.add(abs)
        refreshCircles.schedule()
        return abs
    }

    override fun setOnMarkerClickListener(listener: IMapsProvider.OnMarkerClickListener) {
        markerClickListener = listener
    }

    override fun setOnCameraMoveListener(callback: Runnable?) {
        if (callback == null) return
        mlMap.addOnCameraMoveListener { callback.run() }
    }

    override fun setMyLocationEnabled(enabled: Boolean) {
        visualLocationEnabled = enabled
        if (enabled) {
            ensureLocationStarted()
            return
        }
        myLocationMarker?.remove(); myLocationMarker = null
        myHeadingMarker?.remove(); myHeadingMarker = null
        myAccuracyCircle?.remove(); myAccuracyCircle = null
        stopLocationIfIdle()
    }

    override fun setOnMyLocationChangeListener(callback: Consumer<Location>?) {
        locationConsumer = callback
        if (callback != null) ensureLocationStarted() else stopLocationIfIdle()
    }

    private fun ensureLocationStarted() {
        if (locationStarted) return
        locationStarted = true
        locationProvider.start { onLocation(it) }
        locationProvider.requestLastLocation { it?.let(::onLocation) }
    }

    private fun stopLocationIfIdle() {
        if (visualLocationEnabled || locationConsumer != null || !locationStarted) return
        locationProvider.stop()
        locationStarted = false
    }

    private fun onLocation(loc: Location) {
        locationConsumer?.accept(loc)
        if (visualLocationEnabled) updateMyLocationVisuals(loc)
    }

    private fun updateMyLocationVisuals(loc: Location) {
        val pos = LatLng(loc.latitude, loc.longitude)
        val accuracy = loc.accuracy.toDouble().coerceAtLeast(1.0)

        val dot = myLocationMarker
        if (dot == null) {
            myLocationMarker = addMarker(MlMarkerOptionsImpl().apply {
                position = pos.toMl()
                anchorU = 0.5f; anchorV = 0.5f
                bitmap = blueDotBitmap()
            }) as MlIMarker
        } else dot.setPosition(pos)

        if (loc.hasBearing()) {
            val heading = myHeadingMarker
            if (heading == null) {
                myHeadingMarker = (addMarker(MlMarkerOptionsImpl().apply {
                    position = pos.toMl()
                    anchorU = 0.5f; anchorV = 1f
                    flat = true
                    bitmap = headingArrowBitmap(ctx)
                }) as MlIMarker).also { it.setRotation(loc.bearing.toInt()) }
            } else {
                heading.setPosition(pos)
                heading.setRotation(loc.bearing.toInt())
            }
        } else {
            myHeadingMarker?.remove()
            myHeadingMarker = null
        }

        val circle = myAccuracyCircle
        if (circle == null) {
            myAccuracyCircle = addCircle(MlCircleOptionsImpl().apply {
                center = pos.toMl()
                radiusMeters = accuracy
                fill = 0x224285F4
                stroke = 0x554285F4
                strokeWidth = AndroidUtilities.dp(1f)
            }) as MlICircle
        } else {
            circle.setCenter(pos)
            circle.setRadius(accuracy)
        }
    }

    private fun resToBitmap(resId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(ctx, resId) ?: return null
        return if (drawable is BitmapDrawable) drawable.bitmap else drawable.toBitmap()
    }

    private fun IMapsProvider.ICancelableCallback.toMl() = object : MapLibreMap.CancelableCallback {
        override fun onFinish() = this@toMl.onFinish()
        override fun onCancel() = this@toMl.onCancel()
    }

    inner class MlIMarker : IMapsProvider.IMarker {
        val featureId: String = "m${markerIdCounter.incrementAndGet()}"
        val imageId: String = "img_$featureId"
        var position: MlLatLng = MlLatLng(0.0, 0.0)
        var rotation: Float = 0f
        var anchorU: Float = 0.5f
        var anchorV: Float = 1f
        var flat: Boolean = false
        var paddedBitmap: Bitmap? = null
        private var tagObj: Any? = null

        fun updateBitmap(bmp: Bitmap?) {
            paddedBitmap?.let { currentStyle?.removeImage(imageId) }
            paddedBitmap = bmp?.let { padForCenterAnchor(it, anchorU, anchorV) }
            paddedBitmap?.let { currentStyle?.addImage(imageId, it) }
        }

        fun toFeature(): Feature {
            val pt = GeoPoint.fromLngLat(position.longitude, position.latitude)
            return Feature.fromGeometry(pt, null, featureId).apply {
                addStringProperty("icon", imageId)
                addNumberProperty("rotation", rotation)
            }
        }

        override fun getTag(): Any? = tagObj
        override fun setTag(tag: Any?) {
            tagObj = tag
        }

        override fun getPosition(): LatLng = position.toApi()
        override fun setPosition(latLng: LatLng) {
            position = latLng.toMl()
            refreshMarkers.schedule()
        }

        override fun setRotation(rot: Int) {
            rotation = rot.toFloat()
            refreshMarkers.schedule()
        }

        override fun setIcon(bitmap: Bitmap) {
            updateBitmap(bitmap); refreshMarkers.schedule()
        }

        override fun setIcon(resId: Int) {
            resToBitmap(resId)?.let { updateBitmap(it); refreshMarkers.schedule() }
        }

        override fun remove() {
            currentStyle?.removeImage(imageId)
            markers.remove(this)
            refreshMarkers.schedule()
        }
    }

    inner class MlICircle : IMapsProvider.ICircle {
        val featureId: String = "c${circleIdCounter.incrementAndGet()}"
        var center: MlLatLng = MlLatLng(0.0, 0.0)
        var radiusM: Double = 0.0
        var fill: Int = Color.TRANSPARENT
        var stroke: Int = Color.BLACK
        var strokeWidthPx: Float = 0f

        fun toFeature(): Feature {
            val poly = buildCirclePolygon(center, radiusM)
            return Feature.fromGeometry(poly, null, featureId).apply {
                addStringProperty("fill", colorToCss(fill))
                addStringProperty("stroke", colorToCss(stroke))
                addNumberProperty("strokeWidth", strokeWidthPx)
            }
        }

        override fun setStrokeColor(color: Int) {
            stroke = color; refreshCircles.schedule()
        }

        override fun setFillColor(color: Int) {
            fill = color; refreshCircles.schedule()
        }

        override fun setRadius(radius: Double) {
            radiusM = radius; refreshCircles.schedule()
        }

        override fun getRadius(): Double = radiusM
        override fun setCenter(latLng: LatLng) {
            center = latLng.toMl()
            refreshCircles.schedule()
        }

        override fun remove() {
            circles.remove(this)
            refreshCircles.schedule()
        }
    }

    companion object {
        private val markerIdCounter = AtomicLong(0)
        private val circleIdCounter = AtomicLong(0)
    }
}
