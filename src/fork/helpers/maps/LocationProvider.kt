package desu.inugram.helpers.maps

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

internal interface LocationProvider {
    fun start(callback: (Location) -> Unit)
    fun stop()
    fun requestLastLocation(callback: (Location?) -> Unit)
}

internal fun createLocationProvider(ctx: Context): LocationProvider =
    if (isGmsAvailable(ctx)) FusedLocationProvider(ctx) else NativeLocationProvider(ctx)

private fun isGmsAvailable(ctx: Context): Boolean = try {
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
} catch (_: Throwable) {
    false
}

private fun hasLocationPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private const val UPDATE_INTERVAL_MS = 1000L
private const val MIN_UPDATE_INTERVAL_MS = 500L

private class FusedLocationProvider(private val ctx: Context) : LocationProvider {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(ctx)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(callback: (Location) -> Unit) {
        if (this.callback != null || !hasLocationPermission(ctx)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(callback)
            }
        }
        this.callback = cb
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
        }
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    @SuppressLint("MissingPermission")
    override fun requestLastLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission(ctx)) {
            callback(null); return
        }
        try {
            client.lastLocation
                .addOnSuccessListener { callback(it) }
                .addOnFailureListener { callback(null) }
        } catch (_: SecurityException) {
            callback(null)
        }
    }
}

private class NativeLocationProvider(private val ctx: Context) : LocationProvider {
    private val manager: LocationManager =
        ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    private var bestSoFar: Location? = null

    @SuppressLint("MissingPermission")
    override fun start(callback: (Location) -> Unit) {
        if (listener != null || !hasLocationPermission(ctx)) return
        bestSoFar = null
        val l = LocationListener { loc ->
            if (isBetterLocation(loc, bestSoFar)) {
                bestSoFar = loc
                callback(loc)
            }
        }
        listener = l
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, l, Looper.getMainLooper())
                }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun stop() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
        bestSoFar = null
    }

    @SuppressLint("MissingPermission")
    override fun requestLastLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission(ctx)) {
            callback(null); return
        }
        try {
            val gps = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val net = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
            // pick freshest
            callback(
                when {
                    gps == null -> net
                    net == null -> gps
                    gps.time >= net.time -> gps
                    else -> net
                }
            )
        } catch (_: SecurityException) {
            callback(null)
        }
    }
}

private const val FRESHNESS_THRESHOLD_NS = 30_000_000_000L // 30s in nanoseconds
private const val SIGNIFICANT_ACCURACY_DROP_M = 200f

// adapted from android docs' LocationProvider best-practices sample, with a tighter freshness window
// suitable for live tracking (we update at 1Hz, not occasional polls).
private fun isBetterLocation(candidate: Location, current: Location?): Boolean {
    if (current == null) return true
    val timeDelta = candidate.elapsedRealtimeNanos - current.elapsedRealtimeNanos
    if (timeDelta > FRESHNESS_THRESHOLD_NS) return true   // current is stale
    if (timeDelta < -FRESHNESS_THRESHOLD_NS) return false // candidate is stale

    val accuracyDelta = candidate.accuracy - current.accuracy
    val isNewer = timeDelta > 0
    val sameProvider = candidate.provider == current.provider

    if (accuracyDelta < 0) return true                                // strictly more accurate
    if (isNewer && accuracyDelta <= 0) return true                    // newer and not worse
    if (isNewer && accuracyDelta < SIGNIFICANT_ACCURACY_DROP_M && sameProvider) return true
    return false
}
