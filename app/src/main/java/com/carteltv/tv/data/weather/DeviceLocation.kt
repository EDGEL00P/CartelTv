package com.carteltv.tv.data.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A real position from the device's own location service (Wi-Fi/network, or GPS on hardware that has it), good to
 * tens or hundreds of metres. Most TVs expose no provider; callers fall back to the IP location in that case.
 */
object DeviceLocation {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float)

    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun Location.fix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else Float.NaN)

    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val usable = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (usable.isEmpty()) return null

        val recent = (usable + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 30 * 60_000L }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (recent != null) return recent.fix()

        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location.fix()) }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Required on API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(usable.first(), listener, Looper.getMainLooper())
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
    }
}
