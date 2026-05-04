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
import java.io.BufferedWriter
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class FusionActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "FusionActivity"
        private const val RANGING_INTERVAL_MS = 100L   // faster bursts → more samples for NLoS rejection
        private val KNOWN_FTM_BSSIDS = setOf("b0:e4:d5:69:71:f3")
    }

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvHeading: TextView
    private lateinit var etApX: EditText
    private lateinit var etApY: EditText
    private lateinit var etPhoneX: EditText
    private lateinit var etPhoneY: EditText
    private lateinit var btnSetAp: Button
    private lateinit var btnScan: Button
    private lateinit var spinnerAP: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnReset: Button
    private lateinit var tvWaypointNext: TextView
    private lateinit var btnMarkWaypoint: Button
    private lateinit var trajectoryView: TrajectoryView
    private lateinit var tvFusionLog: TextView

    // Square calibration waypoints
    private val WAYPOINTS = arrayOf(
        Triple(3.5f, 0.0f, "START"),
        Triple(0.0f, 0.0f, "Corner 1"),
        Triple(0.0f, 4.0f, "Corner 2"),
        Triple(3.5f, 4.0f, "Corner 3"),
        Triple(3.5f, 0.0f, "END")
    )
    private var waypointIndex = 0

    // WiFi
    private lateinit var wifiManager: WifiManager
    private var wifiRttManager: WifiRttManager? = null
    private var selectedAP: ScanResult? = null
    private val ftmAPList = mutableListOf<ScanResult>()
    private val apNames = mutableListOf<String>()
    private var apAdapter: ArrayAdapter<String>? = null

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var rotVecSensor: Sensor? = null   // GAME_ROTATION_VECTOR — for frame transform
    private var compassSensor: Sensor? = null  // disabled
    private var linearAccSensor: Sensor? = null

    // Fusion estimator
    private val estimator = FusionEstimator()
    private var lastFtmM = Float.NaN

    // FTM asymmetric filter: respond fast when getting closer (likely real LoS),
    // respond slowly when getting farther (could be NLoS inflation).
    // ftmSmoothed tracks the filtered distance.
    private var ftmSmoothed = Float.NaN
    private val ALPHA_CLOSER  = 1.0f   // instant when getting closer — almost always real LoS
    private val ALPHA_FARTHER = 0.6f   // moderate smoothing moving away — rejects spikes, tracks genuine movement

    // Trajectory + logging
    private var csvWriter: BufferedWriter? = null
    private var lastLogMs = 0L
    private val LOG_INTERVAL_MS = 100L

    // Ranging loop
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val rangingRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                performRanging()
                handler.postDelayed(this, RANGING_INTERVAL_MS)
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            tvStatus.text = if (result.all { it.value })
                "Permissions granted — enter AP position, scan, then Start."
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
                    apNames.add("${sr.SSID} | ${sr.BSSID} | ${sr.frequency} MHz")
                }
            }
            apAdapter?.notifyDataSetChanged()
            tvStatus.text = if (ftmAPList.isEmpty()) "No FTM APs found"
                            else "Found ${ftmAPList.size} AP(s) — select one above."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fusion)

        tvStatus      = findViewById(R.id.tvFusionStatus)
        tvPosition    = findViewById(R.id.tvFusionPosition)
        tvDistance    = findViewById(R.id.tvFusionDistance)
        tvHeading     = findViewById(R.id.tvFusionParticles)
        etApX         = findViewById(R.id.etApX)
        etApY         = findViewById(R.id.etApY)
        etPhoneX      = findViewById(R.id.etPhoneX)
        etPhoneY      = findViewById(R.id.etPhoneY)
        btnSetAp      = findViewById(R.id.btnSetAp)
        btnScan       = findViewById(R.id.btnFusionScan)
        spinnerAP     = findViewById(R.id.spinnerFusionAP)
        btnStart        = findViewById(R.id.btnFusionStart)
        btnStop         = findViewById(R.id.btnFusionStop)
        btnReset        = findViewById(R.id.btnFusionReset)
        tvWaypointNext  = findViewById(R.id.tvFusionWaypointNext)
        btnMarkWaypoint = findViewById(R.id.btnFusionMarkWaypoint)
        trajectoryView  = findViewById(R.id.fusionTrajectoryView)
        tvFusionLog     = findViewById(R.id.tvFusionLog)

        // Hide the calibrate button — no IMU calibration in polar mode
        findViewById<Button>(R.id.btnFusionCalibrate).visibility = android.view.View.GONE

        // Show active FTM calibration
        val prefs = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val slope = prefs.getFloat(CalibrationActivity.KEY_SLOPE, CalibrationActivity.DEFAULT_SLOPE)
        val intercept = prefs.getFloat(CalibrationActivity.KEY_INTERCEPT, CalibrationActivity.DEFAULT_INTERCEPT)
        tvFusionLog.text = "FTM cal: ${"%.3f".format(slope)}×raw + ${"%.3f".format(intercept)}"

        wifiManager    = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiRttManager = getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        sensorManager  = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        rotVecSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccSensor = null
        compassSensor   = null

        apAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apNames)
        spinnerAP.adapter = apAdapter
        spinnerAP.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                selectedAP = if (pos < ftmAPList.size) ftmAPList[pos] else null
            }
            override fun onNothingSelected(p: AdapterView<*>?) { selectedAP = null }
        }

        btnSetAp.setOnClickListener {
            val x = etApX.text.toString().toFloatOrNull() ?: 0f
            val y = etApY.text.toString().toFloatOrNull() ?: 0f
            estimator.apX = x; estimator.apY = y
            tvStatus.text = "AP set to ($x, $y) m"
        }

        btnScan.setOnClickListener {
            tvStatus.text = "Scanning..."
            ftmAPList.clear(); apNames.clear()
            apAdapter?.notifyDataSetChanged()
            if (!wifiManager.startScan()) scanReceiver.onReceive(this, null)
        }

        btnStart.setOnClickListener {
            if (selectedAP == null) { tvStatus.text = "Select an AP first"; return@setOnClickListener }
            val phoneX = etPhoneX.text.toString().toFloatOrNull() ?: Float.NaN
            val phoneY = etPhoneY.text.toString().toFloatOrNull() ?: Float.NaN
            estimator.startTracking(phoneX, phoneY)
            trajectoryView.setAp(estimator.apX, estimator.apY)
            trajectoryView.clear()
            if (!phoneX.isNaN() && !phoneY.isNaN()) trajectoryView.addPoint(phoneX, phoneY)
            openCsv()
            isRunning = true
            waypointIndex = 0
            updateWaypointUI()
            btnMarkWaypoint.isEnabled = true
            btnStart.isEnabled = false; btnStop.isEnabled = true
            handler.post(rangingRunnable)
        }

        btnStop.setOnClickListener { stopTracking() }

        btnReset.setOnClickListener {
            estimator.reset()
            lastFtmM = Float.NaN
            ftmSmoothed = Float.NaN
            trajectoryView.clear()
            waypointIndex = 0
            updateWaypointUI()
            btnMarkWaypoint.isEnabled = false
            updateUI()
        }

        btnMarkWaypoint.setOnClickListener {
            if (waypointIndex >= WAYPOINTS.size) return@setOnClickListener
            val wp  = WAYPOINTS[waypointIndex]
            val now = System.currentTimeMillis()
            logWaypointRow(now, waypointIndex + 1, wp.first, wp.second)
            waypointIndex++
            updateWaypointUI()
        }

        updateWaypointUI()

        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        rotVecSensor?.let   { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAccSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Sensor callbacks
    // -------------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                estimator.onRotationVector(event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // -------------------------------------------------------------------------
    // WiFi RTT ranging
    // -------------------------------------------------------------------------

    private fun performRanging() {
        val ap  = selectedAP ?: return
        val rtt = wifiRttManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val builder = RangingRequest.Builder()
        when {
            ap.is80211mcResponder -> builder.addAccessPoint(ap)
            Build.VERSION.SDK_INT >= 33 -> {
                val config = android.net.wifi.rtt.ResponderConfig.Builder()
                    .setMacAddress(android.net.MacAddress.fromString(ap.BSSID))
                    .setFrequencyMhz(ap.frequency)
                    .setPreamble(2)           // VHT — matches modern 5 GHz APs
                    .setChannelWidth(ap.channelWidth)
                    .build()
                builder.addResponder(config)
            }
            else -> builder.addAccessPoint(ap)
        }

        rtt.startRanging(builder.build(), Executors.newSingleThreadExecutor(),
            object : RangingResultCallback() {
                override fun onRangingResults(results: List<RangingResult>) {
                    for (r in results) {
                        if (r.status == RangingResult.STATUS_SUCCESS) {
                            val rawM = r.distanceMm / 1000f
                            val calM = CalibrationActivity.calibrate(rawM, this@FusionActivity)
                            // Asymmetric IIR: trust decreases (closer) more than increases (NLoS)
                            val smoothed = if (ftmSmoothed.isNaN()) {
                                calM
                            } else {
                                val alpha = if (calM < ftmSmoothed) ALPHA_CLOSER else ALPHA_FARTHER
                                alpha * calM + (1f - alpha) * ftmSmoothed
                            }
                            ftmSmoothed = smoothed
                            lastFtmM = smoothed
                            estimator.onFtmMeasurement(smoothed)
                            runOnUiThread { updateUI() }
                        }
                    }
                }
                override fun onRangingFailure(code: Int) {
                    Log.w(TAG, "Ranging failure code=$code")
                }
            })
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateWaypointUI() {
        if (waypointIndex >= WAYPOINTS.size) {
            tvWaypointNext.text = "All waypoints marked — run complete!"
            btnMarkWaypoint.isEnabled = false
        } else {
            val wp = WAYPOINTS[waypointIndex]
            val hint = if (waypointIndex == 0) "tap when at start" else "tap when you reach this corner"
            tvWaypointNext.text = "Next: WP${waypointIndex + 1} → " +
                "(${"%.1f".format(wp.first)}, ${"%.1f".format(wp.second)})  [${wp.third} — $hint]"
        }
    }

    private fun stopTracking() {
        isRunning = false
        handler.removeCallbacks(rangingRunnable)
        btnStart.isEnabled = true; btnStop.isEnabled = false
        closeCsv()
    }

    private fun updateUI() {
        tvStatus.text = estimator.statusMessage

        if (estimator.isInitialized && estimator.hasStart) {
            val x = estimator.estimatedX
            val y = estimator.estimatedY
            tvPosition.text = "X=${"%.2f".format(x)} Y=${"%.2f".format(y)} m"
            val now = System.currentTimeMillis()
            if (now - lastLogMs >= LOG_INTERVAL_MS) {
                lastLogMs = now
                trajectoryView.addPoint(x, y)
                trajectoryView.setFtmRadius(estimator.currentFtmDist)
                logCsvRow(now)
            }
        } else if (estimator.isInitialized) {
            tvPosition.text = "r=${"%.2f".format(estimator.currentFtmDist)} m  (no start pos — X/Y unknown)"
            trajectoryView.setFtmRadius(estimator.currentFtmDist)
        } else {
            tvPosition.text = "Waiting for first FTM measurement..."
        }

        tvDistance.text = if (!lastFtmM.isNaN()) "FTM: ${"%.2f".format(lastFtmM)} m" else "FTM: ---"
        tvHeading.text  = "φ=${estimator.headingDeg.toInt()}°  ψ_rel=${estimator.relYawDeg.toInt()}°  raw=${estimator.rawYawDeg.toInt()}°  ±${estimator.headingUncertaintyDeg.toInt()}°"
    }

    // ---- CSV helpers --------------------------------------------------------

    private fun openCsv() {
        try {
            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = java.io.File(getExternalFilesDir(null), "fusion_$ts.csv")
            csvWriter = BufferedWriter(FileWriter(file))
            csvWriter!!.write("time_ms,est_x,est_y,ftm_dist_m,theta_deg,heading_uncertainty_deg,heading_deg,raw_yaw_deg,grv_x,grv_y,grv_z,grv_w,waypoint,true_x,true_y\n")
            tvFusionLog.text = "Logging: fusion_$ts.csv"
        } catch (e: Exception) {
            tvFusionLog.text = "CSV open failed: ${e.message}"
        }
    }

    private fun logCsvRow(timeMs: Long) {
        try {
            val g = estimator.gameRotVec
            csvWriter?.write("$timeMs,${estimator.estimatedX},${estimator.estimatedY}," +
                "${estimator.currentFtmDist},${estimator.thetaDeg},${estimator.headingUncertaintyDeg}," +
                "${estimator.headingDeg},${estimator.rawYawDeg}," +
                "${g[0]},${g[1]},${g[2]},${g[3]},,,\n")
        } catch (_: Exception) {}
    }

    private fun logWaypointRow(timeMs: Long, wpNum: Int, trueX: Float, trueY: Float) {
        try {
            val g = estimator.gameRotVec
            csvWriter?.write("$timeMs,${estimator.estimatedX},${estimator.estimatedY}," +
                "${estimator.currentFtmDist},${estimator.thetaDeg},${estimator.headingUncertaintyDeg}," +
                "${estimator.headingDeg},${estimator.rawYawDeg}," +
                "${g[0]},${g[1]},${g[2]},${g[3]}," +
                "WP$wpNum,$trueX,$trueY\n")
            csvWriter?.flush()
        } catch (_: Exception) {}
    }

    private fun closeCsv() {
        try { csvWriter?.flush(); csvWriter?.close() } catch (_: Exception) {}
        csvWriter = null
    }
}
