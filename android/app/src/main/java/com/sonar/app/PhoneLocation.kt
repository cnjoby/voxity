package com.sonar.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import java.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Some providers refresh elapsed time on a cached fix without refreshing its sample time. */
internal fun freshLocationSample(
    sampledAt: Long,
    elapsedAt: Long,
    wallNow: Long,
    elapsedNow: Long,
) = wallNow - sampledAt in -10_000L..55_000L && elapsedNow - elapsedAt in 0..55_000_000_000L

/** One foreground fix; no Google Play dependency, background service or trajectory storage. */
class PhoneLocation(private val context: Context) {
    fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun current(): JSONObject =
        withTimeout(20000) {
            check(permitted()) { "允许定位后，就能问附近的人；也可以手选询问地点。" }
            val manager = context.getSystemService(LocationManager::class.java)
            val providers =
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
                    manager.allProviders.contains(it) && manager.isProviderEnabled(it)
                }
            check(providers.isNotEmpty()) { "请打开手机定位，或手选询问地点。" }
            val location =
                suspendCancellableCoroutine<Location> { continuation ->
                    val signals = providers.map { CancellationSignal() }
                    val handler = Handler(Looper.getMainLooper())
                    var remaining = providers.size
                    var best: Location? = null
                    fun finish(value: Location?) {
                        if (!continuation.isActive) return
                        signals.forEach { it.cancel() }
                        if (value != null) continuation.resumeWith(Result.success(value))
                        else
                            continuation.resumeWith(
                                Result.failure(IllegalStateException("暂时没能取得位置，请到信号较好的地方重试，或手选地点。"))
                            )
                    }
                    continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
                    fun request(index: Int, provider: String) {
                        if (!continuation.isActive) return
                        LocationManagerCompat.getCurrentLocation(
                            manager,
                            provider,
                            signals[index],
                            ContextCompat.getMainExecutor(context),
                        ) { fix ->
                            if (!continuation.isActive) return@getCurrentLocation
                            if (
                                fix != null &&
                                    !freshLocationSample(
                                        fix.time,
                                        fix.elapsedRealtimeNanos,
                                        System.currentTimeMillis(),
                                        SystemClock.elapsedRealtimeNanos(),
                                    )
                            ) {
                                // Keep waiting within the same 20-second deadline; never relabel
                                // a cached coordinate with the upload/current time.
                                handler.postDelayed({ request(index, provider) }, 1000)
                                return@getCurrentLocation
                            }
                            if (
                                fix != null &&
                                    fix.hasAccuracy() &&
                                    (best == null || fix.accuracy < best!!.accuracy)
                            )
                                best = fix
                            remaining--
                            if (best != null && best!!.accuracy <= 100 || remaining == 0)
                                finish(best)
                        }
                    }
                    providers.forEachIndexed { index, provider -> request(index, provider) }
                }
            @Suppress("DEPRECATION")
            val mock =
                if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
            json(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "coordinate_system" to "wgs84",
                "accuracy_m" to location.accuracy,
                "sampled_at" to Instant.ofEpochMilli(location.time).toString(),
                "source" to
                    if (mock || Build.HARDWARE in setOf("ranchu", "goldfish")) "android_mock"
                    else "android",
            )
        }
}
