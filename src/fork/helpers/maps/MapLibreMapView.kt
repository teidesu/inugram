package desu.inugram.helpers.maps

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.util.Consumer
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.IMapsProvider
import org.telegram.ui.ActionBar.Theme
import org.maplibre.android.maps.MapView as MlMapView

internal class MlIMapView(context: Context) : IMapsProvider.IMapView {

    private var dispatchInterceptor: IMapsProvider.ITouchInterceptor? = null
    private var interceptInterceptor: IMapsProvider.ITouchInterceptor? = null
    private var layoutListener: Runnable? = null
    var imap: MlIMap? = null

    init {
        MapLibre.getInstance(context)
    }

    private val mapOptions = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true)
        .attributionEnabled(false)
        .logoEnabled(false)

    val mapView = object : MlMapView(context, mapOptions) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val di = dispatchInterceptor ?: return super.dispatchTouchEvent(ev)
            return di.onInterceptTouchEvent(ev) { e -> super.dispatchTouchEvent(e) }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val ii = interceptInterceptor ?: return super.onInterceptTouchEvent(ev)
            return ii.onInterceptTouchEvent(ev) { e -> super.onInterceptTouchEvent(e) }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            layoutListener?.run()
        }
    }

    val attribution = TextView(context).apply {
        textSize = 10f
        setTextColor(0xFF000000.toInt())
        setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink))
        background = GradientDrawable().apply {
            setColor(0xCCFFFFFF.toInt())
            cornerRadius = AndroidUtilities.dp(100f).toFloat()
        }
        setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(3f), AndroidUtilities.dp(8f), AndroidUtilities.dp(3f))
        linksClickable = true
        movementMethod = LinkMovementMethod.getInstance()
        text = Html.fromHtml(ATTRIBUTION_BRIGHT)
    }

    // map container parallaxes when bottom sheet expands; cancel that translation on attribution so it stays put
    private val container = object : FrameLayout(context) {
        override fun setTranslationY(translationY: Float) {
            super.setTranslationY(translationY)
            attribution.translationY = -translationY
        }
    }.apply {
        addView(mapView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(attribution, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = AndroidUtilities.dp(6f)
            bottomMargin = AndroidUtilities.dp(6f)
        })
    }

    override fun getView(): View = container

    override fun getMapAsync(callback: Consumer<IMapsProvider.IMap>) {
        mapView.getMapAsync { mlMap ->
            mlMap.setMaxZoomPreference(MAX_ZOOM.toDouble())
            mlMap.setStyle(Style.Builder().fromUri(BRIGHT_STYLE)) { style ->
                val map = MlIMap(this, mlMap, style)
                imap = map
                callback.accept(map)
            }
        }
    }

    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onCreate(savedInstance: Bundle?) {
        mapView.onCreate(savedInstance)
        mapView.onStart()
    }

    override fun onDestroy() {
        imap?.onDestroy()
        imap = null
        mapView.onStop()
        mapView.onDestroy()
    }

    override fun onLowMemory() = mapView.onLowMemory()
    override fun setOnDispatchTouchEventInterceptor(touchInterceptor: IMapsProvider.ITouchInterceptor?) {
        dispatchInterceptor = touchInterceptor
    }

    override fun setOnInterceptTouchEventInterceptor(touchInterceptor: IMapsProvider.ITouchInterceptor?) {
        interceptInterceptor = touchInterceptor
    }

    override fun setOnLayoutListener(callback: Runnable?) {
        layoutListener = callback
    }
}
