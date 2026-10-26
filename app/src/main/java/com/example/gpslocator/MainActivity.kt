package com.example.gpslocator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var tvInfo: TextView
    private lateinit var webViewMap: WebView

    // 默认显示的国内地图坐标 (GCJ-02 坐标系)
    private var baseGcjLat = 39.908823
    private var baseGcjLng = 116.397470

    private var currentGcjLat = baseGcjLat
    private var currentGcjLng = baseGcjLng

    // 转换后用于注入底层 GPS 的 WGS-84 坐标（让百度地图/高德地图识别后完全吻合）
    private var mockWgsLat = 39.9074
    private var mockWgsLng = 116.3912

    private var isMocking = false
    private val handler = Handler(Looper.getMainLooper())

    private val mockRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                pushTestLocation(mockWgsLat, mockWgsLng)
                handler.postDelayed(this, 800L)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fetchCurrentRealLocation()
        } else {
            Toast.makeText(this, "请授予位置权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo = findViewById(R.id.tvInfo)
        webViewMap = findViewById(R.id.webViewMap)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        syncWgsFromGcj()
        initMapWebView()

        findViewById<Button>(R.id.btnGetRealLocation).setOnClickListener {
            checkPermissionAndFetchReal()
        }

        findViewById<Button>(R.id.btnNorth).setOnClickListener { moveByMeters(100.0, 0.0) }
        findViewById<Button>(R.id.btnSouth).setOnClickListener { moveByMeters(-100.0, 0.0) }
        findViewById<Button>(R.id.btnEast).setOnClickListener { moveByMeters(0.0, 100.0) }
        findViewById<Button>(R.id.btnWest).setOnClickListener { moveByMeters(0.0, -100.0) }

        findViewById<Button>(R.id.btnRandomNearby).setOnClickListener {
            val distance = Random.nextDouble(50.0, 300.0)
            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val dNorth = distance * sin(angle)
            val dEast = distance * cos(angle)
            currentGcjLat = baseGcjLat
            currentGcjLng = baseGcjLng
            moveByMeters(dNorth, dEast)
        }

        findViewById<Button>(R.id.btnOpenBaidu).setOnClickListener {
            openBaiduMapApp()
        }

        findViewById<Button>(R.id.btnStopMock).setOnClickListener {
            stopMockLocation()
        }
    }

    /**
     * 打开手机上的百度地图 App（若未安装则用网页版百度地图打开当前坐标）
     */
    private fun openBaiduMapApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.baidu.BaiduMap")
            if (intent != null) {
                startActivity(intent)
            } else {
                // 如果没装百度地图 App，打开百度地图网页版查看当前选点
                val uri = Uri.parse("https://api.map.baidu.com/marker?location=$currentGcjLat,$currentGcjLng&title=当前测试位置&content=测试点&output=html&coord_type=gcj02")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开百度地图", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initMapWebView() {
        webViewMap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webViewMap.webViewClient = WebViewClient()
        webViewMap.webChromeClient = WebChromeClient()
        webViewMap.addJavascriptInterface(MapJsBridge(), "AndroidBridge")

        // 使用国内高速 CDN (Staticfile / Baomitu) + 高德中文街道瓦片，国内网络秒开
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <link rel="stylesheet" href="https://lib.baomitu.com/leaflet/1.9.4/leaflet.min.css" />
                <script src="https://lib.baomitu.com/leaflet/1.9.4/leaflet.min.js"></script>
                <style>
                    html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; background: #eaeaea; }
                    .custom-pin {
                        width: 24px; height: 24px;
                        background: #ff3b30;
                        border: 3px solid #ffffff;
                        border-radius: 50%;
                        box-shadow: 0 2px 6px rgba(0,0,0,0.45);
                    }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map', {
                        center: [$baseGcjLat, $baseGcjLng],
                        zoom: 16,
                        zoomControl: true
                    });

                    // 国内高德中文街道瓦片（免 Key，国内秒加载）
                    L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
                        subdomains: ['1', '2', '3', '4'],
                        minZoom: 3,
                        maxZoom: 18,
                        attribution: '中文电子地图'
                    }).addTo(map);

                    // 使用纯 CSS 绘制醒目的红色定位圆点，不依赖外部图片资源
                    var redDotIcon = L.divIcon({
                        className: '',
                        html: '<div class="custom-pin"></div>',
                        iconSize: [30, 30],
                        iconAnchor: [15, 15]
                    });

                    var marker = L.marker([$baseGcjLat, $baseGcjLng], {
                        icon: redDotIcon,
                        draggable: true
                    }).addTo(map);

                    // 点击地图任意位置选点
                    map.on('click', function(e) {
                        var lat = e.latlng.lat;
                        var lng = e.latlng.lng;
                        marker.setLatLng([lat, lng]);
                        AndroidBridge.onMapLocationPicked(lat, lng);
                    });

                    // 拖动小红点选点
                    marker.on('dragend', function(e) {
                        var pos = marker.getLatLng();
                        AndroidBridge.onMapLocationPicked(pos.lat, pos.lng);
                    });

                    function updateMarker(lat, lng) {
                        marker.setLatLng([lat, lng]);
                        map.setView([lat, lng], map.getZoom());
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        webViewMap.loadDataWithBaseURL("https://webrd01.is.autonavi.com/", html, "text/html", "UTF-8", null)
    }

    inner class MapJsBridge {
        @JavascriptInterface
        fun onMapLocationPicked(gcjLat: Double, gcjLng: Double) {
            handler.post {
                baseGcjLat = gcjLat
                baseGcjLng = gcjLng
                currentGcjLat = gcjLat
                currentGcjLng = gcjLng
                syncWgsFromGcj()

                if (startMockProviderIfNeeded()) {
                    pushTestLocation(mockWgsLat, mockWgsLng)
                    updateUI("已选点并生效（可打开百度地图查看）")
                }
            }
        }
    }

    private fun updateMapMarker(gcjLat: Double, gcjLng: Double) {
        webViewMap.evaluateJavascript("updateMarker($gcjLat, $gcjLng);", null)
    }

    private fun checkPermissionAndFetchReal() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fetchCurrentRealLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentRealLocation() {
        stopMockLocation()
        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (loc != null) {
            // 手机硬件返回的是 WGS-84，转换成国内地图坐标 (GCJ-02) 显示在地图上
            val gcj = wgs84ToGcj02(loc.latitude, loc.longitude)
            baseGcjLat = gcj[0]
            baseGcjLng = gcj[1]
            currentGcjLat = baseGcjLat
            currentGcjLng = baseGcjLng
            syncWgsFromGcj()
            updateMapMarker(currentGcjLat, currentGcjLng)
            updateUI("已定位到当前真实位置")
        } else {
            updateUI("暂未获取到缓存位置，请直接在下方地图缩放点击选点")
        }
    }

    private fun moveByMeters(northMeters: Double, eastMeters: Double) {
        val deltaLat = northMeters / 111320.0
        val deltaLng = eastMeters / (111320.0 * cos(Math.toRadians(currentGcjLat)))

        currentGcjLat += deltaLat
        currentGcjLng += deltaLng
        syncWgsFromGcj()

        updateMapMarker(currentGcjLat, currentGcjLng)

        if (startMockProviderIfNeeded()) {
            pushTestLocation(mockWgsLat, mockWgsLng)
            updateUI("位置已偏移 (北${northMeters.toInt()}m, 东${eastMeters.toInt()}m)")
        }
    }

    private fun syncWgsFromGcj() {
        val wgs = gcj02ToWgs84(currentGcjLat, currentGcjLng)
        mockWgsLat = wgs[0]
        mockWgsLng = wgs[1]
    }

    private fun startMockProviderIfNeeded(): Boolean {
        if (isMocking) return true
        return try {
            setupSingleProvider(LocationManager.GPS_PROVIDER)
            try {
                setupSingleProvider(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) {}

            isMocking = true
            handler.post(mockRunnable)
            true
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "请先在【开发者选项 -> 选择模拟位置信息应用】中选择本应用",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun setupSingleProvider(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                provider,
                false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                false, false, false, false, true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        }
        locationManager.setTestProviderEnabled(provider, true)
    }

    private fun pushTestLocation(wgsLat: Double, wgsLng: Double) {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                val mockLocation = Location(p).apply {
                    latitude = wgsLat
                    longitude = wgsLng
                    altitude = 50.0
                    accuracy = 3.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                locationManager.setTestProviderLocation(p, mockLocation)
            } catch (_: Exception) {}
        }
    }

    private fun stopMockLocation() {
        isMocking = false
        handler.removeCallbacks(mockRunnable)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                locationManager.removeTestProvider(p)
            } catch (_: Exception) {}
        }
        updateUI("已停止测试定位")
    }

    private fun updateUI(status: String) {
        tvInfo.text = """
            状态：$status
            地图坐标(GCJ02)：${String.format("%.6f", currentGcjLat)}, ${String.format("%.6f", currentGcjLng)}
            注入GPS(WGS84)：${String.format("%.6f", mockWgsLat)}, ${String.format("%.6f", mockWgsLng)}
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMockLocation()
    }

    // ==================== GCJ-02(国内地图) 与 WGS-84(GPS) 坐标互转算法 ====================
    private val pi = 3.1415926535897932384626
    private val a = 6378245.0
    private val ee = 0.00669342162296594323

    private fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): DoubleArray {
        var dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        var dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * pi
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi)
        dLng = (dLng * 180.0) / (a / sqrtMagic * cos(radLat) * pi)
        return doubleArrayOf(wgsLat + dLat, wgsLng + dLng)
    }

    private fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): DoubleArray {
        val gcj = wgs84ToGcj02(gcjLat, gcjLng)
        val dLat = gcj[0] - gcjLat
        val dLng = gcj[1] - gcjLng
        return doubleArrayOf(gcjLat - dLat, gcjLng - dLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        ret += (20.0 * sin(y * pi) + 40.0 * sin(y / 3.0 * pi)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * pi) + 320 * sin(y * pi / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        ret += (20.0 * sin(x * pi) + 40.0 * sin(x / 3.0 * pi)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * pi) + 300.0 * sin(x / 30.0 * pi)) * 2.0 / 3.0
        return ret
    }
}
