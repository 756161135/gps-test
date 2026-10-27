package com.example.gpslocator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var tvFloatCoord: TextView? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var targetWgsLat = 39.9074

    @Volatile
    private var targetWgsLng = 116.3912

    // 空监听器：用于强制激活系统的 LocationManager 缓存，使其他 App 调用 getLastKnownLocation 时也能立即拿到新坐标
    private val activeKeepAliveListener = LocationListener { _ -> }

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            pushMockLocationToAllProviders(targetWgsLat, targetWgsLng)
            // 每 100 毫秒高频推送一次
            workerHandler?.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
        showFloatingWindowIfPermitted()
        updateFloatText()

        if (!isRunning) {
            isRunning = true
            initAllTestProviders()
            registerKeepAliveListeners()
            workerHandler?.removeCallbacks(pushRunnable)
            workerHandler?.post(pushRunnable)
        } else {
            workerHandler?.post {
                pushMockLocationToAllProviders(targetWgsLat, targetWgsLng)
            }
        }

        return START_STICKY
    }

    /**
     * 显示全局悬浮窗（保持进程为 TOP 前台状态，并支持在其他 App 界面内直接微调坐标）
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWindowIfPermitted() {
        if (floatingView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 260
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD1E1E1E"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#55FFFFFF"))
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = "📍 定位生效中(按住拖动)"
            setTextColor(Color.parseColor("#4CD964"))
            textSize = 11f
            setPadding(0, 0, 16, 4)
        }

        val btnToggleMinimize = TextView(this).apply {
            text = " [收起]"
            setTextColor(Color.WHITE)
            textSize = 11f
        }

        titleRow.addView(tvTitle)
        titleRow.addView(btnToggleMinimize)

        tvFloatCoord = TextView(this).apply {
            text = String.format("%.5f, %.5f", targetWgsLat, targetWgsLng)
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(0, 2, 0, 8)
        }

        val controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row1.addView(createMiniButton("北50m") { offsetWgsByMeters(50.0, 0.0) })
        row1.addView(createMiniButton("南50m") { offsetWgsByMeters(-50.0, 0.0) })
        row1.addView(createMiniButton("随机") {
            val dist = Random.nextDouble(30.0, 150.0)
            val ang = Random.nextDouble(0.0, 2 * Math.PI)
            offsetWgsByMeters(dist * sin(ang), dist * cos(ang))
        })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row2.addView(createMiniButton("西50m") { offsetWgsByMeters(0.0, -50.0) })
        row2.addView(createMiniButton("东50m") { offsetWgsByMeters(0.0, 50.0) })
        row2.addView(createMiniButton("主界面") {
            val intent = Intent(this@MockLocationService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        })

        controlsContainer.addView(tvFloatCoord)
        controlsContainer.addView(row1)
        controlsContainer.addView(row2)

        btnToggleMinimize.setOnClickListener {
            if (controlsContainer.visibility == View.VISIBLE) {
                controlsContainer.visibility = View.GONE
                btnToggleMinimize.text = " [展开]"
            } else {
                controlsContainer.visibility = View.VISIBLE
                btnToggleMinimize.text = " [收起]"
            }
        }

        root.addView(titleRow)
        root.addView(controlsContainer)

        // 支持手指拖动悬浮窗
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        titleRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(root, params)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(root, params)
            floatingView = root
        } catch (_: Exception) {}
    }

    private fun createMiniButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(16, 8, 16, 8)
            setOnClickListener { onClick() }
        }
    }

    private fun offsetWgsByMeters(northMeters: Double, eastMeters: Double) {
        val dLat = northMeters / 111320.0
        val dLng = eastMeters / (111320.0 * cos(Math.toRadians(targetWgsLat)))
        targetWgsLat += dLat
        targetWgsLng += dLng
        updateFloatText()
        workerHandler?.post {
            pushMockLocationToAllProviders(targetWgsLat, targetWgsLng)
        }
    }

    private fun updateFloatText() {
        mainHandler.post {
            tvFloatCoord?.text = String.format("WGS: %.5f, %.5f", targetWgsLat, targetWgsLng)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerKeepAliveListeners() {
        mainHandler.post {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    locationManager.requestLocationUpdates(p, 500L, 0f, activeKeepAliveListener)
                } catch (_: Exception) {}
            }
        }
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

        // 加入 ±0.2米 的真实 GPS 自然微抖动，避免其他 App 因坐标完全静止不变而过滤掉位置更新
        val jitterLat = lat + Random.nextDouble(-0.0000018, 0.0000018)
        val jitterLng = lng + Random.nextDouble(-0.0000018, 0.0000018)

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            try {
                val loc = Location(provider).apply {
                    latitude = jitterLat
                    longitude = jitterLng
                    altitude = 52.0 + Random.nextDouble(-0.5, 0.5)
                    accuracy = if (provider == LocationManager.NETWORK_PROVIDER) 8.0f else 2.2f
                    bearing = Random.nextDouble(0.0, 360.0).toFloat()
                    speed = 0.05f
                    time = nowTime
                    elapsedRealtimeNanos = elapsedNanos

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 2.0f
                        speedAccuracyMetersPerSecond = 0.1f
                        bearingAccuracyDegrees = 5.0f
                    }

                    extras = Bundle().apply {
                        putInt("satellites", 13)
                        putInt("maxCn0", 43)
                        putInt("meanCn0", 36)
                    }
                }
                locationManager.setTestProviderLocation(provider, loc)
            } catch (_: Exception) {
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
                "测试定位悬浮窗与保活服务",
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
            .setContentTitle("测试定位正在运行（已开启悬浮窗保活）")
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
        try {
            locationManager.removeUpdates(activeKeepAliveListener)
        } catch (_: Exception) {}
        try {
            if (floatingView != null) {
                windowManager.removeView(floatingView)
                floatingView = null
            }
        } catch (_: Exception) {}
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
