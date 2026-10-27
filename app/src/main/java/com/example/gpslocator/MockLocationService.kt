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

    // 激活系统定位派发队列与 LastKnownLocation 缓存的监听器
    private val gpsKeepAliveListener = LocationListener { _ -> }
    private val netKeepAliveListener = LocationListener { _ -> }

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            pushMockLocationToProviders(currentWgsLat, currentWgsLng)
            // 每 100 毫秒稳定推送一次，绝不中途调用 removeTestProvider
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
            // 仅在首次启动时注册 TestProvider，后续运行期间不再卸载，避免定位闪断
            ensureProviderRegistered(LocationManager.GPS_PROVIDER, cleanFirst = true)
            ensureProviderRegistered(LocationManager.NETWORK_PROVIDER, cleanFirst = true)
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
     * 注册单个测试定位源（严格区分 GPS 卫星源和 Network 网络源硬件属性）
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
                    if (isGps) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE
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
                    if (isGps) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
                )
            }
        } catch (_: IllegalArgumentException) {
            // 如果已经存在则直接启用，不要移除
        } catch (_: Exception) {}

        try {
            locationManager.setTestProviderEnabled(provider, true)
        } catch (_: Exception) {}

        try {
            val statusBundle = Bundle().apply {
                putInt("satellites", 13)
                putInt("maxCn0", 45)
                putInt("meanCn0", 38)
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

        // 模拟真实室外强 GPS 信号的自然微幅波动（±0.15米），防止被第三方 SDK 判定为死数据
        val jitterLat = wgsLat + Random.nextDouble(-0.0000012, 0.0000012)
        val jitterLng = wgsLng + Random.nextDouble(-0.0000012, 0.0000012)

        // 1. 推送 GPS 卫星定位通道（高德/腾讯/百度 SDK 在 GPS 信号精度 <= 5m 时最高优先级采用）
        pushSingleProvider(
            provider = LocationManager.GPS_PROVIDER,
            lat = jitterLat,
            lng = jitterLng,
            accuracyVal = 2.0f,
            nowTime = nowTime,
            elapsedNanos = elapsedNanos
        )

        // 2. 推送 Network 网络定位通道
        pushSingleProvider(
            provider = LocationManager.NETWORK_PROVIDER,
            lat = jitterLat,
            lng = jitterLng,
            accuracyVal = 5.0f,
            nowTime = nowTime,
            elapsedNanos = elapsedNanos
        )
    }

    private fun pushSingleProvider(
        provider: String,
        lat: Double,
        lng: Double,
        accuracyVal: Float,
        nowTime: Long,
        elapsedNanos: Long
    ) {
        try {
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lng
                altitude = 52.0
                accuracy = accuracyVal
                bearing = 90.0f
                speed = 0.02f
                time = nowTime
                elapsedRealtimeNanos = elapsedNanos

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = 1.5f
                    speedAccuracyMetersPerSecond = 0.1f
                    bearingAccuracyDegrees = 2.0f
                }

                extras = Bundle().apply {
                    putInt("satellites", 13)
                    putInt("maxCn0", 45)
                    putInt("meanCn0", 38)
                    putString("mockProvider", "gps")
                }
            }
            locationManager.setTestProviderLocation(provider, loc)
        } catch (_: Exception) {
            // 仅针对当前报错的单个通道补充注册，绝不卸载其他正常工作的通道
            ensureProviderRegistered(provider, cleanFirst = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerKeepAliveListeners() {
        mainHandler.post {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    200L,
                    0f,
                    gpsKeepAliveListener
                )
            } catch (_: Exception) {}
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    200L,
                    0f,
                    netKeepAliveListener
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
            if (floatingView != null) {
                windowManager.removeView(floatingView)
                floatingView = null
            }
        } catch (_: Exception) {}
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
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
