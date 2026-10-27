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
import android.location.LocationProvider
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
        const val EXTRA_GCJ_LAT = "extra_gcj_lat"
        const val EXTRA_GCJ_LNG = "extra_gcj_lng"

        @Volatile
        var isRunning = false

        @Volatile
        var currentWgsLat = 39.9074

        @Volatile
        var currentWgsLng = 116.3912

        @Volatile
        var currentGcjLat = 39.908823

        @Volatile
        var currentGcjLng = 116.397470
    }

    private lateinit var locationManager: LocationManager
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var tvFloatCoord: TextView? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val gpsKeepAliveListener = LocationListener { _ -> }
    private val netKeepAliveListener = LocationListener { _ -> }
    private val fusedKeepAliveListener = LocationListener { _ -> }

    // 三个核心定位通道全部接管（隔离异常处理，互不影响）
    private val allTargetProviders = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused"
    )

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            pushMockLocationToProviders(currentWgsLat, currentWgsLng)
            // 每 80 毫秒高频推送一次，在联网状态下持续压制基站/Wi-Fi在线定位回调
            workerHandler?.postDelayed(this, 80L)
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
            currentWgsLat = intent.getDoubleExtra(EXTRA_WGS_LAT, currentWgsLat)
            currentWgsLng = intent.getDoubleExtra(EXTRA_WGS_LNG, currentWgsLng)
            currentGcjLat = intent.getDoubleExtra(EXTRA_GCJ_LAT, currentGcjLat)
            currentGcjLng = intent.getDoubleExtra(EXTRA_GCJ_LNG, currentGcjLng)
        }

        startForeground(1001, buildNotification())
        showFloatingWindowIfPermitted()
        updateFloatText()

        if (!isRunning) {
            isRunning = true
            // 首次启动时逐个独立注册 gps、network、fused 三个通道
            for (p in allTargetProviders) {
                ensureProviderRegistered(p, cleanFirst = true)
            }
            registerKeepAliveListeners()
            workerHandler?.removeCallbacks(pushRunnable)
            workerHandler?.post(pushRunnable)
        } else {
            workerHandler?.post {
                pushMockLocationToProviders(currentWgsLat, currentWgsLng)
            }
        }

        return START_STICKY
    }

    /**
     * 独立注册单个测试定位通道（全部设为最高精度 ACCURACY_FINE，压制联网基站定位权重）
     */
    private fun ensureProviderRegistered(provider: String, cleanFirst: Boolean) {
        try {
            if (cleanFirst) {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) {}
            }

            val isGps = (provider == LocationManager.GPS_PROVIDER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ !isGps,
                    /* requiresSatellite = */ isGps,
                    /* requiresCell = */ !isGps,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ !isGps,
                    /* requiresSatellite = */ isGps,
                    /* requiresCell = */ !isGps,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }
        } catch (_: Exception) {
            // 已存在或部分系统不支持时直接忽略，绝不影响其他通道
        }

        try {
            locationManager.setTestProviderEnabled(provider, true)
        } catch (_: Exception) {}

        try {
            val statusBundle = Bundle().apply {
                putInt("satellites", 15)
                putInt("maxCn0", 48)
                putInt("meanCn0", 41)
            }
            @Suppress("DEPRECATION")
            locationManager.setTestProviderStatus(
                provider,
                LocationProvider.AVAILABLE,
                statusBundle,
                System.currentTimeMillis()
            )
        } catch (_: Exception) {}
    }

    private fun pushMockLocationToProviders(wgsLat: Double, wgsLng: Double) {
        val nowTime = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        // 模拟真实室外强 GPS 信号的自然微幅波动（±0.1米）
        val jitterLat = wgsLat + Random.nextDouble(-0.0000009, 0.0000009)
        val jitterLng = wgsLng + Random.nextDouble(-0.0000009, 0.0000009)
        val simulatedBearing = Random.nextDouble(85.0, 95.0).toFloat()
        // 设置 0.65 ~ 0.95 m/s 的轻微室外移动速度，使国内定位 SDK 在联网时判定为“室外移动强GPS状态”而忽略基站定位
        val simulatedSpeed = Random.nextDouble(0.65, 0.95).toFloat()

        for (provider in allTargetProviders) {
            pushSingleProvider(
                provider = provider,
                lat = jitterLat,
                lng = jitterLng,
                accuracyVal = 1.2f, // 1.2米极高精度，权重远高于基站/Wi-Fi的30~200米精度
                bearingVal = simulatedBearing,
                speedVal = simulatedSpeed,
                nowTime = nowTime,
                elapsedNanos = elapsedNanos
            )
        }
    }

    private fun pushSingleProvider(
        provider: String,
        lat: Double,
        lng: Double,
        accuracyVal: Float,
        bearingVal: Float,
        speedVal: Float,
        nowTime: Long,
        elapsedNanos: Long
    ) {
        try {
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lng
                altitude = 52.5
                accuracy = accuracyVal
                bearing = bearingVal
                speed = speedVal
                time = nowTime
                elapsedRealtimeNanos = elapsedNanos

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 1.0f
                    speedAccuracyMetersPerSecond = 0.05f
                    bearingAccuracyDegrees = 1.5f
                }

                // 仅保留标准卫星数与信噪比字段，不添加任何 mock 字样属性
                extras = Bundle().apply {
                    putInt("satellites", 15)
                    putInt("maxCn0", 48)
                    putInt("meanCn0", 41)
                }
            }
            locationManager.setTestProviderLocation(provider, loc)
        } catch (_: Exception) {
            // 仅尝试启用当前单个通道，绝不调用 removeTestProvider 卸载其他通道
            ensureProviderRegistered(provider, cleanFirst = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerKeepAliveListeners() {
        mainHandler.post {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    100L,
                    0f,
                    gpsKeepAliveListener
                )
            } catch (_: Exception) {}
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    100L,
                    0f,
                    netKeepAliveListener
                )
            } catch (_: Exception) {}
            try {
                locationManager.requestLocationUpdates(
                    "fused",
                    100L,
                    0f,
                    fusedKeepAliveListener
                )
            } catch (_: Exception) {}
        }
    }

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
            text = "📍 定位锁定中(按住拖动)"
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
            text = String.format("坐标: %.5f, %.5f", currentGcjLat, currentGcjLng)
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
        row1.addView(createMiniButton("北50m") { offsetByMeters(50.0, 0.0) })
        row1.addView(createMiniButton("南50m") { offsetByMeters(-50.0, 0.0) })
        row1.addView(createMiniButton("随机") {
            val dist = Random.nextDouble(30.0, 150.0)
            val ang = Random.nextDouble(0.0, 2 * Math.PI)
            offsetByMeters(dist * sin(ang), dist * cos(ang))
        })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row2.addView(createMiniButton("西50m") { offsetByMeters(0.0, -50.0) })
        row2.addView(createMiniButton("东50m") { offsetByMeters(0.0, 50.0) })
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

    private fun offsetByMeters(northMeters: Double, eastMeters: Double) {
        val dLat = northMeters / 111320.0
        val dLng = eastMeters / (111320.0 * cos(Math.toRadians(currentWgsLat)))
        currentWgsLat += dLat
        currentWgsLng += dLng
        currentGcjLat += dLat
        currentGcjLng += dLng
        updateFloatText()
        workerHandler?.post {
            pushMockLocationToProviders(currentWgsLat, currentWgsLng)
        }
    }

    private fun updateFloatText() {
        mainHandler.post {
            tvFloatCoord?.text = String.format("坐标: %.5f, %.5f", currentGcjLat, currentGcjLng)
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
            .setContentText(String.format("当前地图坐标: %.6f, %.6f", currentGcjLat, currentGcjLng))
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
            locationManager.removeUpdates(gpsKeepAliveListener)
        } catch (_: Exception) {}
        try {
            locationManager.removeUpdates(netKeepAliveListener)
        } catch (_: Exception) {}
        try {
            locationManager.removeUpdates(fusedKeepAliveListener)
        } catch (_: Exception) {}
        try {
            if (floatingView != null) {
                windowManager.removeView(floatingView)
                floatingView = null
            }
        } catch (_: Exception) {}
        for (provider in allTargetProviders) {
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
