package com.example.gpslocator

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
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

    private var baseGcjLat = 39.908823
    private var baseGcjLng = 116.397470

    // 当前地图上选中的预览坐标（点击“确认定位到此位置”按钮后正式生效）
    private var currentGcjLat = baseGcjLat
    private var currentGcjLng = baseGcjLng

    private var mockWgsLat = 39.9074
    private var mockWgsLng = 116.3912

    private var systemVerifiedText = "未启动模拟（选好位置后请点绿色确认按钮）"
    private val handler = Handler(Looper.getMainLooper())

    // 实时回读系统当前生效的 GPS 坐标，验证是否真的修改成功
    private val verifyListener = LocationListener { loc: Location ->
        val gcj = wgs84ToGcj02(loc.latitude, loc.longitude)
        systemVerifiedText = String.format(
            "✅ 系统已生效(%s): %.6f, %.6f",
            loc.provider, gcj[0], gcj[1]
        )
        updateUI("悬浮窗保活定位中")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startVerifyListener()
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
        requestNeededPermissions()

        // 点击绿色大按钮：正式把当前地图选点设为模拟位置并开启悬浮窗
        findViewById<Button>(R.id.btnConfirmLocation).setOnClickListener {
            baseGcjLat = currentGcjLat
            baseGcjLng = currentGcjLng
            syncWgsFromGcj()
            applyMockLocation()
        }

        findViewById<Button>(R.id.btnGetRealLocation).setOnClickListener {
            fetchCurrentRealLocation()
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

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startVerifyListener() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    verifyListener
                )
            }
        } catch (_: Exception) {}
    }

    private fun isMockLocationEnabled(): Boolean {
        return try {
            val opsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                opsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                opsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun applyMockLocation() {
        // 1. 先检查悬浮窗权限（确保切换到其他 App 时不会被系统冻结）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "请先开启【悬浮窗/显示在其他应用上层】权限，开启后返回再点一次确认按钮！",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }

        // 2. 再检查开发者选项模拟位置权限
        if (!isMockLocationEnabled()) {
            Toast.makeText(
                this,
                "未授权！请在【开发者选项 -> 选择模拟位置信息应用】中选择本软件",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {}
            return
        }

        val serviceIntent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_UPDATE_LOCATION
            putExtra(MockLocationService.EXTRA_WGS_LAT, mockWgsLat)
            putExtra(MockLocationService.EXTRA_WGS_LNG, mockWgsLng)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startVerifyListener()
        Toast.makeText(this, "定位已锁定并启动悬浮窗！现在可切换到其他App", Toast.LENGTH_SHORT).show()
        updateUI("已锁定该位置并开启悬浮窗保活")
    }

    private fun openBaiduMapApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.baidu.BaiduMap")
            if (intent != null) {
                startActivity(intent)
            } else {
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

                    L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
                        subdomains: ['1', '2', '3', '4'],
                        minZoom: 3,
                        maxZoom: 18,
                        attribution: '中文电子地图'
                    }).addTo(map);

                    var redDotIcon = L.divIcon({
                        className: '',
                        html: '<div class="custom-pin"></div>',
                        iconSize: [30, 30],
                        iconAnchor: [15, 15]
                    });

                    // 禁止拖动标记本身，改为只通过点击地图选点 + 点击下方绿色确认按钮生效
                    var marker = L.marker([$baseGcjLat, $baseGcjLng], {
                        icon: redDotIcon,
                        draggable: false
                    }).addTo(map);

                    // 点击地图仅移动红点预览坐标，不直接触发模拟
                    map.on('click', function(e) {
                        var lat = e.latlng.lat;
                        var lng = e.latlng.lng;
                        marker.setLatLng([lat, lng]);
                        AndroidBridge.onMapLocationPreview(lat, lng);
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
        fun onMapLocationPreview(gcjLat: Double, gcjLng: Double) {
            handler.post {
                currentGcjLat = gcjLat
                currentGcjLng = gcjLng
                syncWgsFromGcj()
                updateUI("已选点预览（请点击下方绿色【确认定位到此位置】按钮生效）")
            }
        }
    }

    private fun updateMapMarker(gcjLat: Double, gcjLng: Double) {
        webViewMap.evaluateJavascript("updateMarker($gcjLat, $gcjLng);", null)
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentRealLocation() {
        stopMockLocation()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNeededPermissions()
            return
        }

        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (loc != null) {
            val gcj = wgs84ToGcj02(loc.latitude, loc.longitude)
            baseGcjLat = gcj[0]
            baseGcjLng = gcj[1]
            currentGcjLat = baseGcjLat
            currentGcjLng = baseGcjLng
            syncWgsFromGcj()
            updateMapMarker(currentGcjLat, currentGcjLng)
            updateUI("已回到当前真实位置（选好点后点绿色按钮生效）")
        } else {
            updateUI("请在地图上点击选点，然后点击绿色确认按钮")
        }
    }

    private fun moveByMeters(northMeters: Double, eastMeters: Double) {
        val deltaLat = northMeters / 111320.0
        val deltaLng = eastMeters / (111320.0 * cos(Math.toRadians(currentGcjLat)))

        currentGcjLat += deltaLat
        currentGcjLng += deltaLng
        syncWgsFromGcj()

        updateMapMarker(currentGcjLat, currentGcjLng)
        applyMockLocation()
    }

    private fun syncWgsFromGcj() {
        val wgs = gcj02ToWgs84(currentGcjLat, currentGcjLng)
        mockWgsLat = wgs[0]
        mockWgsLng = wgs[1]
    }

    private fun stopMockLocation() {
        val serviceIntent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP_MOCK
        }
        startService(serviceIntent)
        systemVerifiedText = "已停止模拟"
        updateUI("已停止测试定位（悬浮窗已关闭）")
    }

    private fun updateUI(status: String) {
        tvInfo.text = """
            状态：$status
            当前选点(GCJ02)：${String.format("%.6f", currentGcjLat)}, ${String.format("%.6f", currentGcjLng)}
            $systemVerifiedText
        """.trimIndent()
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(verifyListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ==================== GCJ-02 与 WGS-84 坐标互转 ====================
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
