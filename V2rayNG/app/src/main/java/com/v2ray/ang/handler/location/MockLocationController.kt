package com.v2ray.ang.handler.location

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

enum class MockDeliveryResult { DELIVERED, NOT_ALLOWED, FAILED }

/**
 * Optional bridge to Android's *testing* mock-location mechanism. It does not require root and
 * cannot override GPS system-wide: it only works when the user has chosen this app as the "mock
 * location app" in Developer options, and apps may still ignore or detect mock locations.
 *
 * Build boundary: Android lists an app in that picker only if its manifest declares
 * `ACCESS_MOCK_LOCATION`. Lint rejects that declaration in release manifests, so it is declared only
 * in `src/debug/AndroidManifest.xml`. Release builds therefore report BUILD_UNSUPPORTED rather than
 * pretending.
 */
class MockLocationController(private val context: Context) {

    companion object {
        const val PERMISSION = "android.permission.ACCESS_MOCK_LOCATION"
        private const val OP_MOCK_LOCATION = "android:mock_location"
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }

    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var providersAdded = false

    fun isPermissionDeclared(): Boolean = try {
        val requested = requestedPermissions()
        requested?.contains(PERMISSION) == true
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "MockLocation: cannot read declared permissions", e)
        false
    }

    private fun requestedPermissions(): Array<String>? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            ).requestedPermissions
        } else {
            legacyRequestedPermissions(pm)
        }
    }

    // Compatibility: the Int-flags overload is deprecated from API 33 but is the only one below it.
    // Remove this function when minSdk >= 33.
    @Suppress("DEPRECATION")
    private fun legacyRequestedPermissions(pm: PackageManager): Array<String>? =
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions

    /** True when the user picked this app under Developer options > "Select mock location app". */
    fun isSelectedAsMockApp(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(OP_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                legacyCheckOp(appOps)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "MockLocation: cannot read app-op state", e)
            false
        }
    }

    // Compatibility: checkOpNoThrow is deprecated from API 29 (replaced by unsafeCheckOpNoThrow).
    // Remove this function when minSdk >= 29.
    @Suppress("DEPRECATION")
    private fun legacyCheckOp(appOps: AppOpsManager): Int =
        appOps.checkOpNoThrow(OP_MOCK_LOCATION, Process.myUid(), context.packageName)

    /**
     * Pushes one fix to the GPS and network test providers, registering them first if needed.
     * DELIVERED means Android accepted the call; it says nothing about any app consuming it.
     */
    fun deliver(latitude: Double, longitude: Double): MockDeliveryResult {
        val manager = locationManager ?: return MockDeliveryResult.FAILED
        return try {
            if (!providersAdded) {
                PROVIDERS.forEach { addProvider(manager, it) }
                providersAdded = true
            }
            PROVIDERS.forEach { name ->
                val fix = Location(name).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    accuracy = 10f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                manager.setTestProviderLocation(name, fix)
            }
            MockDeliveryResult.DELIVERED
        } catch (e: SecurityException) {
            // Thrown when the app is not the selected mock location app. Coordinates are not logged.
            LogUtil.w(AppConfig.TAG, "MockLocation: not allowed to deliver (not selected as mock app?)")
            providersAdded = false
            MockDeliveryResult.NOT_ALLOWED
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "MockLocation: delivery failed", e)
            providersAdded = false
            MockDeliveryResult.FAILED
        }
    }

    /** Removes the test providers so real location sources resume. Safe to call repeatedly. */
    fun stop() {
        val manager = locationManager ?: return
        PROVIDERS.forEach { name ->
            try {
                manager.removeTestProvider(name)
            } catch (e: Exception) {
                // Not registered or not allowed: nothing to undo.
            }
        }
        providersAdded = false
    }

    private fun addProvider(manager: LocationManager, name: String) {
        try {
            manager.removeTestProvider(name) // drop a stale registration from an earlier run
        } catch (e: Exception) {
            // none registered
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.addTestProvider(
                name,
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(false)
                    .setHasSpeedSupport(false)
                    .setHasBearingSupport(false)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
            )
        } else {
            legacyAddProvider(manager, name)
        }
        manager.setTestProviderEnabled(name, true)
    }

    // Compatibility: the ten-argument overload is deprecated from API 31 (ProviderProperties).
    // Remove this function when minSdk >= 31.
    @Suppress("DEPRECATION")
    private fun legacyAddProvider(manager: LocationManager, name: String) {
        manager.addTestProvider(name, false, false, false, false, false, false, false, 1, 1)
    }
}
