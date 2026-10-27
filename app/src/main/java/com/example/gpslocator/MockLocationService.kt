package com.example.gpslocator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class MockLocationService : Service() {

    companion object {
        const val ACTION_UPDATE_LOCATION = "com.example.gpslocator.UPDATE_LOCATION"
        const val ACTION_STOP_MOCK = "com.example.gpslocator.STOP_MOCK"
        const val EXTRA_WGS_LAT = "extra_wgs_lat"
        const val EXTRA_WGS_LNG = "extra_wgs_lng"

        @Volatile
        var isRunning = false
    }

    private lateinit var locationManager: LocationManager
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var targetWgsLat = 39.9074

    @Volatile
    private var targetWgsLng = 116.3912

    // 覆盖所有系统定位通道：GPS、网络、以及 Android 12+ 的 Fused 融合定位通道
    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused",
        LocationManager.PASSIVE_PROVIDER
    )

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            pushMockLocationToAllProviders(targetWgsLat, targetWgsLng)
            // 每 120 毫秒高频推送一次，防止真实 GPS 或网络定位穿插覆盖
            workerHandler?.postDelayed(this, 120L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsLocator::MockWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }

        workerThread = HandlerThread("MockLocationWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MOCK) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent != null && intent.hasExtra(EXTRA_WGS_LAT)) {
            targetWgsLat = intent.getDoubleExtra(EXTRA_WGS_LAT, targetWgsLat)
            targetWgsLng = intent.getDoubleExtra(EXTRA_WGS_LNG, targetWgsLng)
        }

        startForeground(1001, buildNotification())

        if (!isRunning) {
            isRunning = true
            initAllTestProviders()
            workerHandler?.removeCallbacks(pushRunnable)
            workerHandler?.post(pushRunnable)
        } else {
            // 立即推送一次新坐标
            workerHandler?.post {
                pushMockLocationToAllProviders(targetWgsLat, targetWgsLng)
            }
        }

        return START_STICKY
    }

    private fun initAllTestProviders() {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            try {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) {}

                val accuracy = if (provider == LocationManager.NETWORK_PROVIDER) {
                    Criteria.ACCURACY_COARSE
                } else {
                    Criteria.ACCURACY_FINE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val propAccuracy = if (provider == LocationManager.NETWORK_PROVIDER) {
                        ProviderProperties.ACCURACY_COARSE
                    } else {
                        ProviderProperties.ACCURACY_FINE
                    }
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW,
                        propAccuracy
                    )
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false, true, true, true,
                        Criteria.POWER_LOW,
                        accuracy
                    )
                }
                locationManager.setTestProviderEnabled(provider, true)
            } catch (_: Exception) {}
        }
    }

    private fun pushMockLocationToAllProviders(lat: Double, lng: Double) {
        val nowTime = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            try {
                val loc = Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 55.0
                    accuracy = 2.5f
                    bearing = 0.0f
                    speed = 0.0f
                    time = nowTime
                    elapsedRealtimeNanos = elapsedNanos

                    // 补齐 Android 8.0 (API 26) 及以上强制校验的完整传感器精度字段
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 2.0f
                        speedAccuracyMetersPerSecond = 0.1f
                        bearingAccuracyDegrees = 1.0f
                    }

                    // 补齐卫星数量信息，防止地图 SDK 因卫星数为 0 丢弃该 GPS 点
                    extras = Bundle().apply {
                        putInt("satellites", 12)
                        putInt("maxCn0", 42)
                        putInt("meanCn0", 35)
                    }
                }
                locationManager.setTestProviderLocation(provider, loc)
            } catch (_: Exception) {
                // 如果被系统重置，重新注册一次该 Provider
                try {
                    initAllTestProviders()
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "mock_location_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "测试定位保活服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("测试定位正在后台持续运行")
            .setContentText(String.format("当前注入WGS84: %.6f, %.6f", targetWgsLat, targetWgsLng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {}
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
