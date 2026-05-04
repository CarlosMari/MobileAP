package com.group2.wififtm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class ApLocatorActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "ApLocator"
        private const val RANGING_INTERVAL_MS = 100L
        private const val MIN_MOVE_M = 0.3f
        private const val MIN_MEASUREMENTS = 5
        private const val UNKNOWN_WARMUP_SAMPLES = 10
        private const val UNKNOWN_MAX_JUMP_M = 3.0f
        private val KNOWN_FTM_BSSIDS = setOf("b0:e4:d5:69:71:f3")
    }

    // -- UI --
    private lateinit var tvStatus:          TextView
    private lateinit var tvPosition:        TextView
    private lateinit var tvDistance:         TextView
    private lateinit var tvHeading:          TextView
    private lateinit var tvLog:             TextView
    private lateinit var etApX:             EditText
    private lateinit var etApY:             EditText
    private lateinit var etDeltaZ:          EditText
    private lateinit var etPhoneX:          EditText
    private lateinit var etPhoneY:          EditText
    private lateinit var btnSetAp:          Button
    private lateinit var btnScan:           Button
    private lateinit var spinnerAnchor:     Spinner
    private lateinit var spinnerUnknown:    Spinner
    private lateinit var btnStart:          Button
    private lateinit var btnStop:           Button
    private lateinit var btnReset:          Button
    private lateinit var tvMeasurementLog:  TextView
    private lateinit var svMeasurementLog:  ScrollView
    private lateinit var tvEstimateResult:  TextView
    private lateinit var trajectoryView:    TrajectoryView

    // -- WiFi --
    private lateinit var wifiManager:    WifiManager
    private var wifiRttManager:          WifiRttManager? = null
    private val ftmAPList  = mutableListOf<ScanResult>()
    private val apNames    = mutableListOf<String>()
    private var anchorApAdapter:  ArrayAdapter<String>? = null
    private var unknownApAdapter: ArrayAdapter<String>? = null
    private var anchorAP:  ScanResult? = null
    private var unknownAP: ScanResult? = null

    // -- Sensors --
    private lateinit var sensorManager:  SensorManager
    private var rotVecSensor: Sensor? = null

    // -- Phone localisation (gyro EKF with anchor AP) --
    private val estimator = FusionEstimator()
    private var lastAnchorDistM = Float.NaN

    // Anchor FTM: asymmetric IIR (same as FusionActivity)
    private var anchorSmoothed = Float.NaN
    private var anchorDeltaZ = 1.2f
    private val ANCHOR_ALPHA_CLOSER  = 1.0f
    private val ANCHOR_ALPHA_FARTHER = 0.6f

    // -- Continuous unknown AP measurements --
    private data class Measurement(
        val x: Float, val y: Float,
        var horizDistM: Float,
        val rawDistM: Float, val calDistM: Float,
        val timestampMs: Long,
        var numSamples: Int = 1,
        var horizDistSum: Float = horizDistM
    )
    private val measurements   = mutableListOf<Measurement>()
    private var lastMeasX      = Float.NaN
    private var lastMeasY      = Float.NaN
    private var unknownFtmSmoothed = Float.NaN
    private val UNKNOWN_ALPHA_CLOSER  = 1.0f
    private val UNKNOWN_ALPHA_FARTHER = 0.6f
    private var totalUnknownSamples   = 0

    // -- Live estimate --
    private var estimatedApX   = Float.NaN
    private var estimatedApY   = Float.NaN

    // -- CSV logging --
    private var csvTrajectory: BufferedWriter? = null
    private var csvMeasurements: BufferedWriter? = null
    private var lastLogMs      = 0L
    private val LOG_INTERVAL_MS = 100L
    private var csvBaseName    = ""

    // -- Ranging loops --
    private var isRunning = false
    private val handler   = Handler(Looper.getMainLooper())

    private val anchorRunnable = object : Runnable {
        override fun run() {
            if (isRunning) { performAnchorRanging(); handler.postDelayed(this, RANGING_INTERVAL_MS) }
        }
    }
    private val unknownRunnable = object : Runnable {
        override fun run() {
            if (isRunning) { performUnknownRanging(); handler.postDelayed(this, RANGING_INTERVAL_MS) }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            tvStatus.text = if (result.all { it.value })
                "Permissions granted -- set AP, scan, then Start."
            else
                "Permissions denied! App won't work."
        }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ftmAPList.clear(); apNames.clear()
            for (sr in wifiManager.scanResults) {
                if (sr.frequency >= 5000 &&
                    (sr.is80211mcResponder || sr.BSSID.lowercase() in KNOWN_FTM_BSSIDS)) {
                    ftmAPList.add(sr)
                    apNames.add("${sr.SSID} | ${sr.BSSID}")
                }
            }
            anchorApAdapter?.notifyDataSetChanged()
            unknownApAdapter?.notifyDataSetChanged()
            tvStatus.text = if (ftmAPList.isEmpty()) "No FTM APs found"
                            else "Found ${ftmAPList.size} AP(s) -- select anchor and unknown."
        }
    }

    // ==========================================================================
    // Lifecycle
    // ==========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ap_locator)

        tvStatus         = findViewById(R.id.tvApLocStatus)
        tvPosition       = findViewById(R.id.tvApLocPosition)
        tvDistance        = findViewById(R.id.tvApLocDistance)
        tvHeading        = findViewById(R.id.tvApLocHeading)
        tvLog            = findViewById(R.id.tvApLocLog)
        etApX            = findViewById(R.id.etApLocApX)
        etApY            = findViewById(R.id.etApLocApY)
        etDeltaZ         = findViewById(R.id.etApLocDeltaZ)
        etPhoneX         = findViewById(R.id.etApLocPhoneX)
        etPhoneY         = findViewById(R.id.etApLocPhoneY)
        btnSetAp         = findViewById(R.id.btnApLocSetAp)
        btnScan          = findViewById(R.id.btnApLocScan)
        spinnerAnchor    = findViewById(R.id.spinnerApLocAnchor)
        spinnerUnknown   = findViewById(R.id.spinnerApLocUnknown)
        btnStart         = findViewById(R.id.btnApLocStart)
        btnStop          = findViewById(R.id.btnApLocStop)
        btnReset         = findViewById(R.id.btnApLocReset)
        tvMeasurementLog = findViewById(R.id.tvApLocWaypointLog)
        svMeasurementLog = findViewById(R.id.svApLocWaypointLog)
        tvEstimateResult = findViewById(R.id.tvApLocEstimateResult)
        trajectoryView   = findViewById(R.id.apLocTrajectoryView)

        // Hide waypoint-era buttons
        findViewById<Button>(R.id.btnApLocCalibrateUnknown).visibility = android.view.View.GONE
        findViewById<Button>(R.id.btnApLocSetWaypoint).visibility = android.view.View.GONE
        findViewById<Button>(R.id.btnApLocEstimate).visibility = android.view.View.GONE

        wifiManager    = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiRttManager = getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        sensorManager  = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotVecSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        anchorApAdapter  = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apNames)
        unknownApAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apNames)
        spinnerAnchor.adapter  = anchorApAdapter
        spinnerUnknown.adapter = unknownApAdapter

        spinnerAnchor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                anchorAP = if (pos < ftmAPList.size) ftmAPList[pos] else null
            }
            override fun onNothingSelected(p: AdapterView<*>?) { anchorAP = null }
        }
        spinnerUnknown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                unknownAP = if (pos < ftmAPList.size) ftmAPList[pos] else null
            }
            override fun onNothingSelected(p: AdapterView<*>?) { unknownAP = null }
        }

        btnSetAp.setOnClickListener {
            val x = etApX.text.toString().toFloatOrNull() ?: 0f
            val y = etApY.text.toString().toFloatOrNull() ?: 0f
            estimator.apX = x; estimator.apY = y
            trajectoryView.setAp(x, y)
            tvStatus.text = "Anchor AP set to ($x, $y) m"
        }

        btnScan.setOnClickListener {
            tvStatus.text = "Scanning..."
            ftmAPList.clear(); apNames.clear()
            anchorApAdapter?.notifyDataSetChanged()
            unknownApAdapter?.notifyDataSetChanged()
            if (!wifiManager.startScan()) scanReceiver.onReceive(this, null)
        }

        btnStart.setOnClickListener {
            if (anchorAP == null) { tvStatus.text = "Select anchor AP first"; return@setOnClickListener }
            if (unknownAP == null) { tvStatus.text = "Select unknown AP first"; return@setOnClickListener }
            if (anchorAP == unknownAP) { tvStatus.text = "Anchor and unknown must differ"; return@setOnClickListener }
            val phoneX = etPhoneX.text.toString().toFloatOrNull() ?: Float.NaN
            val phoneY = etPhoneY.text.toString().toFloatOrNull() ?: Float.NaN
            anchorDeltaZ = etDeltaZ.text.toString().toFloatOrNull() ?: 1.2f
            estimator.startTracking(phoneX, phoneY)
            trajectoryView.setAp(estimator.apX, estimator.apY)
            trajectoryView.clear()
            if (!phoneX.isNaN() && !phoneY.isNaN()) trajectoryView.addPoint(phoneX, phoneY)
            anchorSmoothed = Float.NaN
            unknownFtmSmoothed = Float.NaN
            totalUnknownSamples = 0
            measurements.clear()
            lastMeasX = Float.NaN; lastMeasY = Float.NaN
            estimatedApX = Float.NaN; estimatedApY = Float.NaN
            openCsvFiles()
            isRunning = true
            btnStart.isEnabled = false; btnStop.isEnabled = true
            handler.post(anchorRunnable)
            handler.postDelayed(unknownRunnable, 50L)
            tvStatus.text = "Running — walk around to collect measurements."
        }

        btnStop.setOnClickListener { stopTracking() }

        btnReset.setOnClickListener {
            stopTracking()
            estimator.reset()
            lastAnchorDistM = Float.NaN
            anchorSmoothed = Float.NaN
            unknownFtmSmoothed = Float.NaN
            totalUnknownSamples = 0
            measurements.clear()
            lastMeasX = Float.NaN; lastMeasY = Float.NaN
            estimatedApX = Float.NaN; estimatedApY = Float.NaN
            trajectoryView.clear()
            tvMeasurementLog.text = "Walk around to collect data..."
            tvEstimateResult.text = ""
            updateUI()
        }

        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        rotVecSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        val prefs = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val slope = prefs.getFloat(CalibrationActivity.KEY_SLOPE, CalibrationActivity.DEFAULT_SLOPE)
        val inter = prefs.getFloat(CalibrationActivity.KEY_INTERCEPT, CalibrationActivity.DEFAULT_INTERCEPT)
        tvLog.text = "FTM cal: ${"%.3f".format(slope)}*raw+${"%.3f".format(inter)}"
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    // ==========================================================================
    // Sensor callbacks
    // ==========================================================================

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR ->
                estimator.onRotationVector(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ==========================================================================
    // Anchor AP ranging — asymmetric IIR smoothing → EKF
    // ==========================================================================

    private fun performAnchorRanging() {
        val ap  = anchorAP ?: return
        val rtt = wifiRttManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        rtt.startRanging(buildRequest(ap), Executors.newSingleThreadExecutor(),
            object : RangingResultCallback() {
                override fun onRangingResults(results: List<RangingResult>) {
                    for (r in results) {
                        if (r.status == RangingResult.STATUS_SUCCESS) {
                            val rawM = r.distanceMm / 1000f
                            val calM = CalibrationActivity.calibrate(rawM, this@ApLocatorActivity)
                            val smoothed = if (anchorSmoothed.isNaN()) {
                                calM
                            } else {
                                val alpha = if (calM < anchorSmoothed) ANCHOR_ALPHA_CLOSER else ANCHOR_ALPHA_FARTHER
                                alpha * calM + (1f - alpha) * anchorSmoothed
                            }
                            anchorSmoothed = smoothed
                            lastAnchorDistM = smoothed
                            // Height-correct slant range → horizontal distance
                            val dz = anchorDeltaZ
                            val horiz = if (smoothed > dz) sqrt(smoothed * smoothed - dz * dz) else smoothed
                            estimator.onFtmMeasurement(horiz)
                            runOnUiThread { updateUI() }
                        }
                    }
                }
                override fun onRangingFailure(code: Int) {
                    Log.w(TAG, "Anchor ranging failure code=$code")
                }
            })
    }

    // ==========================================================================
    // Unknown AP ranging — continuous collection for multilateration
    // ==========================================================================

    private fun performUnknownRanging() {
        val ap  = unknownAP ?: return
        val rtt = wifiRttManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        rtt.startRanging(buildRequest(ap), Executors.newSingleThreadExecutor(),
            object : RangingResultCallback() {
                override fun onRangingResults(results: List<RangingResult>) {
                    for (r in results) {
                        if (r.status != RangingResult.STATUS_SUCCESS) continue
                        totalUnknownSamples++
                        val rawM = r.distanceMm / 1000f
                        if (rawM <= 0f) continue  // negative raw = meaningless
                        val calM = CalibrationActivity.calibrate(rawM, this@ApLocatorActivity)
                        if (!unknownFtmSmoothed.isNaN() &&
                            abs(calM - unknownFtmSmoothed) > UNKNOWN_MAX_JUMP_M) continue
                        val smoothed = if (unknownFtmSmoothed.isNaN()) calM
                            else {
                                val a = if (calM < unknownFtmSmoothed) UNKNOWN_ALPHA_CLOSER else UNKNOWN_ALPHA_FARTHER
                                a * calM + (1f - a) * unknownFtmSmoothed
                            }
                        unknownFtmSmoothed = smoothed
                        if (totalUnknownSamples > UNKNOWN_WARMUP_SAMPLES) {
                            handleUnknownApMeasurement(smoothed, rawM, calM)
                        }
                    }
                    runOnUiThread { updateUI() }
                }
                override fun onRangingFailure(code: Int) {
                    Log.w(TAG, "Unknown ranging failure code=$code")
                }
            })
    }

    private fun handleUnknownApMeasurement(smoothedM: Float, rawM: Float, calM: Float) {
        if (!estimator.isInitialized || !estimator.hasStart) return
        if (smoothedM <= 0.1f) return

        val px = estimator.estimatedX
        val py = estimator.estimatedY
        val dz = runOnUiThreadBlocking { etDeltaZ.text.toString().toFloatOrNull() ?: 1.2f }
        val horizM = sqrt(max(0f, smoothedM * smoothedM - dz * dz))

        val moved = lastMeasX.isNaN() || lastMeasY.isNaN() ||
                sqrt((px - lastMeasX) * (px - lastMeasX) + (py - lastMeasY) * (py - lastMeasY)) >= MIN_MOVE_M

        if (moved) {
            lastMeasX = px; lastMeasY = py
            val m = Measurement(px, py, horizM, rawM, calM, System.currentTimeMillis())
            measurements.add(m)
            logMeasurement(m)
        } else if (measurements.isNotEmpty()) {
            val last = measurements.last()
            last.horizDistSum += horizM
            last.numSamples++
            last.horizDistM = last.horizDistSum / last.numSamples
        }

        if (measurements.size >= MIN_MEASUREMENTS) {
            val est = multilaterate()
            if (est != null) {
                estimatedApX = est.first
                estimatedApY = est.second
                runOnUiThread { trajectoryView.setUnknownAp(estimatedApX, estimatedApY) }
            }
        }
    }

    private fun <T> runOnUiThreadBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post { result = block(); latch.countDown() }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ==========================================================================
    // Multilateration -- OLS on linearised circle equations
    // ==========================================================================

    private fun multilaterate(): Pair<Float, Float>? {
        val n = measurements.size
        if (n < MIN_MEASUREMENTS) return null
        val x0 = measurements[0].x; val y0 = measurements[0].y; val d0 = measurements[0].horizDistM
        var ata00 = 0f; var ata01 = 0f; var ata11 = 0f
        var atb0  = 0f; var atb1  = 0f
        for (i in 1 until n) {
            val m = measurements[i]
            val a0 = 2f * (m.x - x0)
            val a1 = 2f * (m.y - y0)
            val bi = d0*d0 - m.horizDistM*m.horizDistM + m.x*m.x - x0*x0 + m.y*m.y - y0*y0
            ata00 += a0*a0;  ata01 += a0*a1;  ata11 += a1*a1
            atb0  += a0*bi;  atb1  += a1*bi
        }
        val det = ata00 * ata11 - ata01 * ata01
        if (abs(det) < 1e-4f) return null
        val ex = (ata11 * atb0 - ata01 * atb1) / det
        val ey = (ata00 * atb1 - ata01 * atb0) / det

        val maxDist = measurements.maxOf { it.horizDistM }
        val centX = measurements.map { it.x }.average().toFloat()
        val centY = measurements.map { it.y }.average().toFloat()
        val distFromCentroid = sqrt((ex - centX) * (ex - centX) + (ey - centY) * (ey - centY))
        if (distFromCentroid > maxDist + 10f) return null

        return Pair(ex, ey)
    }

    // ==========================================================================
    // UI + helpers
    // ==========================================================================

    private fun stopTracking() {
        isRunning = false
        handler.removeCallbacks(anchorRunnable)
        handler.removeCallbacks(unknownRunnable)
        btnStart.isEnabled = true; btnStop.isEnabled = false
        closeCsvFiles()
    }

    private fun updateUI() {
        tvStatus.text = estimator.statusMessage
        if (estimator.isInitialized && estimator.hasStart) {
            val x = estimator.estimatedX; val y = estimator.estimatedY
            tvPosition.text = "X=${f(x)}  Y=${f(y)} m"
            val now = System.currentTimeMillis()
            if (now - lastLogMs >= LOG_INTERVAL_MS) {
                lastLogMs = now
                trajectoryView.addPoint(x, y)
                trajectoryView.setFtmRadius(estimator.currentFtmDist)
                logTrajectoryRow(now)
            }
        } else if (estimator.isInitialized) {
            tvPosition.text = "r=${f(estimator.currentFtmDist)} m  (no start pos)"
            trajectoryView.setFtmRadius(estimator.currentFtmDist)
        } else {
            tvPosition.text = "Waiting for first FTM..."
        }

        tvDistance.text = if (!lastAnchorDistM.isNaN()) "Anchor: ${f(lastAnchorDistM)}m" else "Anchor: ---"
        val unknownStr = if (!unknownFtmSmoothed.isNaN()) "  Unk: ${f(unknownFtmSmoothed)}m" else ""
        tvHeading.text = "phi=${estimator.headingDeg.toInt()}  psi_rel=${estimator.relYawDeg.toInt()}$unknownStr"

        val n = measurements.size
        tvMeasurementLog.text = "Positions: $n  |  FTM samples: $totalUnknownSamples\n" +
                if (n > 0) {
                    val last = measurements.last()
                    "Last: (${f(last.x)}, ${f(last.y)}) r=${f(last.horizDistM)}m  avg=${last.numSamples}smp"
                } else "Walk around to collect data..."

        if (!estimatedApX.isNaN()) {
            tvEstimateResult.text = "Unknown AP:  X=${f(estimatedApX)}  Y=${f(estimatedApY)} m  ($n pts)"
        } else if (n > 0) {
            tvEstimateResult.text = "Need $MIN_MEASUREMENTS measurements (have $n)..."
        }
    }

    private fun buildRequest(ap: ScanResult): RangingRequest {
        val b = RangingRequest.Builder()
        when {
            ap.is80211mcResponder -> b.addAccessPoint(ap)
            Build.VERSION.SDK_INT >= 33 -> {
                val cfg = android.net.wifi.rtt.ResponderConfig.Builder()
                    .setMacAddress(android.net.MacAddress.fromString(ap.BSSID))
                    .setFrequencyMhz(ap.frequency)
                    .setPreamble(2)
                    .setChannelWidth(ap.channelWidth)
                    .build()
                b.addResponder(cfg)
            }
            else -> b.addAccessPoint(ap)
        }
        return b.build()
    }

    private fun f(v: Float) = "%.2f".format(v)

    // ==========================================================================
    // CSV logging
    // ==========================================================================

    private fun openCsvFiles() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            csvBaseName = "aploc_$ts"

            val trajFile = java.io.File(getExternalFilesDir(null), "${csvBaseName}_trajectory.csv")
            csvTrajectory = BufferedWriter(FileWriter(trajFile))
            csvTrajectory!!.write("time_ms,est_x,est_y,anchor_ftm_m,heading_deg,rel_yaw_deg," +
                    "unknown_ftm_smoothed_m,anchor_bssid,unknown_bssid,anchor_ap_x,anchor_ap_y,delta_z\n")

            val measFile = java.io.File(getExternalFilesDir(null), "${csvBaseName}_measurements.csv")
            csvMeasurements = BufferedWriter(FileWriter(measFile))
            csvMeasurements!!.write("time_ms,phone_x,phone_y,unknown_ftm_raw_m,unknown_ftm_cal_m," +
                    "unknown_ftm_horiz_m,est_ap_x,est_ap_y,num_measurements\n")

            tvLog.text = "Logging: $csvBaseName"
        } catch (e: Exception) {
            tvLog.text = "CSV open failed: ${e.message}"
        }
    }

    private fun logTrajectoryRow(timeMs: Long) {
        try {
            val dz = etDeltaZ.text.toString().toFloatOrNull() ?: 1.2f
            csvTrajectory?.write("$timeMs,${estimator.estimatedX},${estimator.estimatedY}," +
                    "$lastAnchorDistM,${estimator.headingDeg},${estimator.relYawDeg}," +
                    "$unknownFtmSmoothed," +
                    "${anchorAP?.BSSID ?: ""},${unknownAP?.BSSID ?: ""}," +
                    "${estimator.apX},${estimator.apY},$dz\n")
        } catch (_: Exception) {}
    }

    private fun logMeasurement(m: Measurement) {
        try {
            csvMeasurements?.write("${m.timestampMs},${m.x},${m.y}," +
                    "${m.rawDistM},${m.calDistM},${m.horizDistM}," +
                    "$estimatedApX,$estimatedApY,${measurements.size}\n")
            csvMeasurements?.flush()
        } catch (_: Exception) {}
    }

    private fun closeCsvFiles() {
        try { csvTrajectory?.flush(); csvTrajectory?.close() } catch (_: Exception) {}
        try { csvMeasurements?.flush(); csvMeasurements?.close() } catch (_: Exception) {}
        csvTrajectory = null; csvMeasurements = null
    }
}
