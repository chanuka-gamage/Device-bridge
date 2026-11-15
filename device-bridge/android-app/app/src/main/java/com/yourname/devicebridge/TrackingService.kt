package com.yourname.devicebridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TrackingService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIF_ID = 1
        // How often to report, in milliseconds. 60s is a reasonable battery/accuracy tradeoff.
        private const val REPORT_INTERVAL_MS = 60_000L
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var deviceId: String = ""

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { sendReport(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("device_bridge", Context.MODE_PRIVATE)
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        isRunning = true
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, REPORT_INTERVAL_MS)
            .setMinUpdateIntervalMillis(REPORT_INTERVAL_MS / 2)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            // Permission was revoked after the service started; stop cleanly.
            stopSelf()
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Pair(level, charging)
    }

    private fun sendReport(location: Location) {
        val serverUrl = prefs.getString("server_url", "")?.trimEnd('/') ?: return
        val token = prefs.getString("token", "") ?: ""
        val deviceName = prefs.getString("device_name", "My Phone") ?: "My Phone"
        if (serverUrl.isBlank() || token.isBlank()) return

        val (batteryLevel, charging) = getBatteryInfo()

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("battery", batteryLevel)
            put("charging", charging)
        }.toString()

        val request = Request.Builder()
            .url("$serverUrl/report")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(MediaType.parse("application/json"), body))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Network hiccup - next scheduled report will retry. Nothing to do here.
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Device tracking", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Bridge")
            .setContentText("Sharing location with your dashboard")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
}
