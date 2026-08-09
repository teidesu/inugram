package desu.inugram.helpers.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.IMapsProvider.LatLng
import org.telegram.messenger.R
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.android.geometry.LatLngBounds as MlLatLngBounds

internal const val BRIGHT_STYLE = "https://tiles.openfreemap.org/styles/bright"
internal const val SATELLITE_STYLE_JSON = """{
  "version": 8,
  "sources": {
    "esri": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [{ "id": "esri", "type": "raster", "source": "esri" }]
}"""
internal const val MAX_ZOOM = 21f

internal const val ATTRIBUTION_BRIGHT =
    """<a href="https://openfreemap.org/">OpenFreeMap</a> <a href="https://www.openmaptiles.org/">© OpenMapTiles</a> Data from <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>"""
internal const val ATTRIBUTION_SATELLITE = """Powered by <a href="https://www.esri.com/">Esri</a>"""

internal const val SRC_MARKERS = "inu_markers"
internal const val SRC_MARKERS_FLAT = "inu_markers_flat"
internal const val SRC_CIRCLES = "inu_circles"
internal const val LAYER_MARKERS = "inu_markers_layer"
internal const val LAYER_MARKERS_FLAT = "inu_markers_flat_layer"
internal const val LAYER_CIRCLES_FILL = "inu_circles_fill_layer"
internal const val LAYER_CIRCLES_STROKE = "inu_circles_stroke_layer"

class MapLibreMapsProvider : IMapsProvider {

    override fun initializeMaps(context: Context) {
        MapLibre.getInstance(context)
    }

    override fun onCreateMapView(context: Context): IMapsProvider.IMapView = MlIMapView(context)

    override fun onCreateMarkerOptions(): IMapsProvider.IMarkerOptions = MlMarkerOptionsImpl()
    override fun onCreateCircleOptions(): IMapsProvider.ICircleOptions = MlCircleOptionsImpl()
    override fun onCreateLatLngBoundsBuilder(): IMapsProvider.ILatLngBoundsBuilder = MlBoundsBuilderImpl()

    override fun newCameraUpdateLatLng(latLng: LatLng): IMapsProvider.ICameraUpdate =
        MlCameraUpdate(CameraUpdateFactory.newLatLng(latLng.toMl()))

    override fun newCameraUpdateLatLngZoom(latLng: LatLng, zoom: Float): IMapsProvider.ICameraUpdate =
        MlCameraUpdate(CameraUpdateFactory.newLatLngZoom(latLng.toMl(), zoom.toDouble()))

    override fun newCameraUpdateLatLngBounds(bounds: IMapsProvider.ILatLngBounds, padding: Int): IMapsProvider.ICameraUpdate =
        MlCameraUpdate(CameraUpdateFactory.newLatLngBounds((bounds as MlBoundsImpl).bounds, padding))

    override fun loadRawResourceStyle(context: Context, resId: Int): IMapsProvider.IMapStyleOptions = MlStyleOptions
    // self-contained renderer; pretend "maps app" is us so isMapsInstalled() never prompts to install Google Maps
    override fun getMapsAppPackageName(): String = ApplicationLoader.applicationContext.packageName
    override fun getInstallMapsString(): Int = R.string.InstallGoogleMaps
}

internal fun LatLng.toMl() = MlLatLng(latitude, longitude)
internal fun MlLatLng.toApi() = LatLng(latitude, longitude)

internal object MlStyleOptions : IMapsProvider.IMapStyleOptions
internal class MlCameraUpdate(val update: CameraUpdate) : IMapsProvider.ICameraUpdate

internal class MlBoundsImpl(val bounds: MlLatLngBounds) : IMapsProvider.ILatLngBounds {
    override fun getCenter(): LatLng = bounds.center.toApi()
}

internal class MlBoundsBuilderImpl : IMapsProvider.ILatLngBoundsBuilder {
    private val points = ArrayList<MlLatLng>()
    override fun include(latLng: LatLng) = apply { points.add(latLng.toMl()) }
    override fun build(): IMapsProvider.ILatLngBounds = MlBoundsImpl(MlLatLngBounds.fromLatLngs(points))
}

internal class MlMarkerOptionsImpl : IMapsProvider.IMarkerOptions {
    var position: MlLatLng = MlLatLng(0.0, 0.0)
    var bitmap: Bitmap? = null
    var iconResId: Int = 0
    var anchorU: Float = 0.5f
    var anchorV: Float = 1f
    var flat: Boolean = false

    override fun position(latLng: LatLng) = apply { position = latLng.toMl() }
    override fun icon(bitmap: Bitmap) = apply { this.bitmap = bitmap; iconResId = 0 }
    override fun icon(resId: Int) = apply { iconResId = resId; bitmap = null }
    override fun anchor(lat: Float, lng: Float) = apply { anchorU = lat; anchorV = lng }
    override fun title(title: String?) = this
    override fun snippet(snippet: String?) = this
    override fun flat(flat: Boolean) = apply { this.flat = flat }
}

internal class MlCircleOptionsImpl : IMapsProvider.ICircleOptions {
    var center: MlLatLng = MlLatLng(0.0, 0.0)
    var radiusMeters: Double = 0.0
    var stroke: Int = Color.BLACK
    var fill: Int = Color.TRANSPARENT
    var strokeWidth: Int = 0

    override fun center(latLng: LatLng) = apply { center = latLng.toMl() }
    override fun radius(radius: Double) = apply { radiusMeters = radius }
    override fun strokeColor(color: Int) = apply { stroke = color }
    override fun fillColor(color: Int) = apply { fill = color }
    override fun strokePattern(items: List<IMapsProvider.PatternItem>) = this
    override fun strokeWidth(width: Int) = apply { strokeWidth = width }
}
