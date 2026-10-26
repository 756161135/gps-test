package com.example.gpslocator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var tvInfo: TextView
    private lateinit var webViewMap: WebView

    // 默认基准坐标（获取真实位置后会自动更新）
    private var baseLat = 39.908823
    private var baseLng = 116.397470

    // 当前选中的测试坐标
    private var currentLat = baseLat
    private var currentLng = baseLng

    private var isMocking = false
    private val handler = Handler(Looper.getMainLooper())
    private val providerName = LocationManager.GPS_PROVIDER

    private val mockRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                pushTestLocation(currentLat, currentLng)
                handler.postDelayed(this, 1000L)
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
            currentLat = baseLat
            currentLng = baseLng
            moveByMeters(dNorth, dEast)
        }

        findViewById<Button>(R.id.btnStopMock).setOnClickListener {
            stopMockLocation()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initMapWebView() {
        webViewMap.settings.javaScriptEnabled = true
        webViewMap.settings.domStorageEnabled = true
        webViewMap.webViewClient = WebViewClient()
        webViewMap.addJavascriptInterface(MapJsBridge(), "AndroidBridge")

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    html, body, #map { height: 100%; margin: 0; padding: 0; }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map').setView([$baseLat, $baseLng], 16);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        maxZoom: 19,
                        attribution: '© OpenStreetMap'
                    }).addTo(map);

                    var marker = L.marker([$baseLat, $baseLng], {draggable: true}).addTo(map);

                    // 点击地图任意位置选点
                    map.on('click', function(e) {
                        var lat = e.latlng.lat;
                        var lng = e.latlng.lng;
                        marker.setLatLng([lat, lng]);
                        AndroidBridge.onMapLocationPicked(lat, lng);
                    });

                    // 拖动标记选点
                    marker.on('dragend', function(e) {
                        var pos = marker.getLatLng();
                        AndroidBridge.onMapLocationPicked(pos.lat, pos.lng);
                    });

                    // 供原生代码调用：更新地图中心和标记位置
                    function updateMarker(lat, lng) {
                        marker.setLatLng([lat, lng]);
                        map.panTo([lat, lng]);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        webViewMap.loadDataWithBaseURL("https://unpkg.com/", html, "text/html", "UTF-8", null)
    }

    inner class MapJsBridge {
        @JavascriptInterface
        fun onMapLocationPicked(lat: Double, lng: Double) {
            handler.post {
                baseLat = lat
                baseLng = lng
                currentLat = lat
                currentLng = lng
                if (startMockProviderIfNeeded()) {
                    pushTestLocation(currentLat, currentLng)
                    updateUI("已在地图选点并更新测试定位")
                }
            }
        }
    }

    private fun updateMapMarker(lat: Double, lng: Double) {
        webViewMap.evaluateJavascript("updateMarker($lat, $lng);", null)
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
            baseLat = loc.latitude
            baseLng = loc.longitude
            currentLat = baseLat
            currentLng = baseLng
            updateMapMarker(currentLat, currentLng)
            updateUI("已获取当前真实位置")
        } else {
            updateUI("暂未获取到缓存位置，请直接在地图上点击选点")
        }
    }

    private fun moveByMeters(northMeters: Double, eastMeters: Double) {
        val deltaLat = northMeters / 111320.0
        val deltaLng = eastMeters / (111320.0 * cos(Math.toRadians(currentLat)))

        currentLat += deltaLat
        currentLng += deltaLng

        updateMapMarker(currentLat, currentLng)

        if (startMockProviderIfNeeded()) {
            pushTestLocation(currentLat, currentLng)
            updateUI("测试位置已更新 (偏移: 北${northMeters.toInt()}m, 东${eastMeters.toInt()}m)")
        }
    }

    private fun startMockProviderIfNeeded(): Boolean {
        if (isMocking) return true
        return try {
            try {
                locationManager.removeTestProvider(providerName)
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    providerName,
                    false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    providerName,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }
            locationManager.setTestProviderEnabled(providerName, true)
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

    private fun pushTestLocation(lat: Double, lng: Double) {
        try {
            val mockLocation = Location(providerName).apply {
                latitude = lat
                longitude = lng
                altitude = 50.0
                accuracy = 5.0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(providerName, mockLocation)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopMockLocation() {
        isMocking = false
        handler.removeCallbacks(mockRunnable)
        try {
            locationManager.removeTestProvider(providerName)
        } catch (_: Exception) {}
        updateUI("已停止测试定位")
    }

    private fun updateUI(status: String) {
        tvInfo.text = """
            状态：$status
            基准坐标：${String.format("%.6f", baseLat)}, ${String.format("%.6f", baseLng)}
            当前测试坐标：${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMockLocation()
    }
}
