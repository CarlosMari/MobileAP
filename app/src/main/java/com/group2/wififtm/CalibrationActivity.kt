package com.group2.wififtm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.sqrt

class CalibrationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CalibrationActivity"
        private const val SAMPLES_PER_POINT = 60
        private const val RANGING_INTERVAL_MS = 300L
        private val KNOWN_FTM_BSSIDS = setOf("b0:e4:d5:69:71:f3")

        // The 3 calibration positions (phone coordinates, metres)
        val CAL_POINTS = arrayOf(
            floatArrayOf(0f,   -4f),
            floatArrayOf(3.5f,  0f),
            floatArrayOf(3.5f, -4f)
        )

        // SharedPreferences keys — also used by MainActivity and FusionActivity
        const val PREFS_NAME    = "ftm_cal"
        const val KEY_SLOPE     = "slope"
        const val KEY_INTERCEPT = "intercept"

        // Defaults: identity (no correction) — cal = 1.0 * raw + 0.0
        // Run the Calibration screen to fit device-specific values.
        const val DEFAULT_SLOPE     = 1.0f
        const val DEFAULT_INTERCEPT = 0.0f

        /** Apply saved (or default) calibration to a raw FTM distance in metres.
         *  Formula: calibrated = slope * raw + intercept  (direct regression, no division) */
        fun calibrate(rawM: Float, context: Context): Float {
            val prefs     = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val slope     = prefs.getFloat(KEY_SLOPE,     DEFAULT_SLOPE)
            val intercept = prefs.getFloat(KEY_INTERCEPT, DEFAULT_INTERCEPT)
            return (slope * rawM + intercept).coerceAtLeast(0.1f)
        }
    }

    // UI
    private lateinit var tvCalStatus:    TextView
    private lateinit var etCalApX:       EditText
    private lateinit var etCalApY:       EditText
    private lateinit var btnCalSetAp:    Button
    private lateinit var btnCalScan:     Button
    private lateinit var spinnerCalAP:   Spinner
    private lateinit var tvCalResult:       TextView
    private lateinit var btnCalApply:       Button
    private lateinit var btnCalReset:       Button
    private lateinit var etManualSlope:     EditText
    private lateinit var etManualIntercept: EditText
    private lateinit var btnManualApply:    Button

    private val tvPointLabel    = arrayOfNulls<TextView>(3)
    private val tvPointProgress = arrayOfNulls<TextView>(3)
    private val btnCollect      = arrayOfNulls<Button>(3)

    // WiFi
    private lateinit var wifiManager: WifiManager
    private var wifiRttManager: WifiRttManager? = null
    private var selectedAP: ScanResult? = null
    private val ftmAPList = mutableListOf<ScanResult>()
    private val apNames   = mutableListOf<String>()
    private var apAdapter: ArrayAdapter<String>? = null

    // Calibration state
    private var apX = 0f
    private var apY = 0f
    private var collectingPoint = -1
    private val rawSamples = Array(3) { mutableListOf<Float>() }
    private val handler = Handler(Looper.getMainLooper())

    private var pendingSlope     = DEFAULT_SLOPE
    private var pendingIntercept = DEFAULT_INTERCEPT

    // -------------------------------------------------------------------------

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
            apAdapter?.notifyDataSetChanged()
            tvCalStatus.text = if (ftmAPList.isEmpty()) "No FTM APs found"
                               else "Found ${ftmAPList.size} AP(s) — select one."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        tvCalStatus    = findViewById(R.id.tvCalStatus)
        etCalApX       = findViewById(R.id.etCalApX)
        etCalApY       = findViewById(R.id.etCalApY)
        btnCalSetAp    = findViewById(R.id.btnCalSetAp)
        btnCalScan     = findViewById(R.id.btnCalScan)
        spinnerCalAP   = findViewById(R.id.spinnerCalAP)
        tvCalResult       = findViewById(R.id.tvCalResult)
        btnCalApply       = findViewById(R.id.btnCalApply)
        btnCalReset       = findViewById(R.id.btnCalReset)
        etManualSlope     = findViewById(R.id.etManualSlope)
        etManualIntercept = findViewById(R.id.etManualIntercept)
        btnManualApply    = findViewById(R.id.btnManualApply)

        tvPointLabel[0]    = findViewById(R.id.tvPoint1Label)
        tvPointLabel[1]    = findViewById(R.id.tvPoint2Label)
        tvPointLabel[2]    = findViewById(R.id.tvPoint3Label)
        tvPointProgress[0] = findViewById(R.id.tvPoint1Progress)
        tvPointProgress[1] = findViewById(R.id.tvPoint2Progress)
        tvPointProgress[2] = findViewById(R.id.tvPoint3Progress)
        btnCollect[0]      = findViewById(R.id.btnCollect1)
        btnCollect[1]      = findViewById(R.id.btnCollect2)
        btnCollect[2]      = findViewById(R.id.btnCollect3)

        wifiManager    = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiRttManager = getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager

        apAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apNames)
        spinnerCalAP.adapter = apAdapter
        spinnerCalAP.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                selectedAP = if (pos < ftmAPList.size) ftmAPList[pos] else null
            }
            override fun onNothingSelected(p: AdapterView<*>?) { selectedAP = null }
        }

        btnCalSetAp.setOnClickListener {
            apX = etCalApX.text.toString().toFloatOrNull() ?: 0f
            apY = etCalApY.text.toString().toFloatOrNull() ?: 0f
            updatePointLabels()
            tvCalStatus.text = "AP set to ($apX, $apY) m"
        }

        btnCalScan.setOnClickListener {
            ftmAPList.clear(); apNames.clear(); apAdapter?.notifyDataSetChanged()
            tvCalStatus.text = "Scanning..."
            if (!wifiManager.startScan()) scanReceiver.onReceive(this, null)
        }

        for (i in 0..2) {
            btnCollect[i]?.setOnClickListener { startCollecting(i) }
        }

        btnCalApply.setOnClickListener { applyCalibration() }

        btnManualApply.setOnClickListener {
            val s = etManualSlope.text.toString().toFloatOrNull()
            val i = etManualIntercept.text.toString().toFloatOrNull()
            if (s == null || i == null) {
                tvCalStatus.text = "Invalid slope or intercept value."
                return@setOnClickListener
            }
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_SLOPE,     s)
                .putFloat(KEY_INTERCEPT, i)
                .apply()
            tvCalStatus.text = "Saved manually: slope=${"%.4f".format(s)}, intercept=${"%.4f".format(i)}"
            showCurrentCalibration()
        }

        btnCalReset.setOnClickListener {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_SLOPE,     DEFAULT_SLOPE)
                .putFloat(KEY_INTERCEPT, DEFAULT_INTERCEPT)
                .apply()
            tvCalStatus.text = "Reset to defaults: ${DEFAULT_SLOPE}×raw + $DEFAULT_INTERCEPT"
            showCurrentCalibration()
        }

        updatePointLabels()
        showCurrentCalibration()
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------

    private fun trueDistance(pointIdx: Int): Float {
        val dx = CAL_POINTS[pointIdx][0] - apX
        val dy = CAL_POINTS[pointIdx][1] - apY
        return sqrt(dx * dx + dy * dy)
    }

    private fun updatePointLabels() {
        for (i in 0..2) {
            val px = CAL_POINTS[i][0]; val py = CAL_POINTS[i][1]
            val d  = trueDistance(i)
            tvPointLabel[i]?.text =
                "Point ${i+1}: (${"%.1f".format(px)}, ${"%.1f".format(py)})  →  true = ${"%.3f".format(d)} m"
        }
    }

    private fun startCollecting(pointIdx: Int) {
        if (selectedAP == null) { tvCalStatus.text = "Select an AP first"; return }
        val d = trueDistance(pointIdx)
        if (d < 0.3f) {
            tvCalStatus.text = "Point ${pointIdx+1} is too close to the AP (${"%.2f".format(d)} m). Move the AP or adjust coordinates."
            return
        }
        collectingPoint = pointIdx
        rawSamples[pointIdx].clear()
        for (b in btnCollect) b?.isEnabled = false
        tvPointProgress[pointIdx]?.text = "0 / $SAMPLES_PER_POINT"
        tvCalStatus.text = "Stand at point ${pointIdx+1} (true = ${"%.2f".format(d)} m) — collecting…"
        scheduleRanging()
    }

    private fun scheduleRanging() {
        if (collectingPoint >= 0) handler.postDelayed(::doRanging, RANGING_INTERVAL_MS)
    }

    private fun doRanging() {
        val ap  = selectedAP ?: return
        val rtt = wifiRttManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val builder = RangingRequest.Builder()
        when {
            ap.is80211mcResponder -> builder.addAccessPoint(ap)
            Build.VERSION.SDK_INT >= 33 -> {
                val config = android.net.wifi.rtt.ResponderConfig.Builder()
                    .setMacAddress(android.net.MacAddress.fromString(ap.BSSID))
                    .setFrequencyMhz(ap.frequency)
                    .setPreamble(2)
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
                            val idx  = collectingPoint
                            if (idx < 0) return
                            rawSamples[idx].add(rawM)
                            runOnUiThread {
                                val n = rawSamples[idx].size
                                tvPointProgress[idx]?.text = "$n / $SAMPLES_PER_POINT"
                                if (n >= SAMPLES_PER_POINT) {
                                    collectingPoint = -1
                                    for (b in btnCollect) b?.isEnabled = true
                                    if (allPointsDone()) {
                                        computeCalibration()
                                    } else {
                                        val remaining = (0..2).count { rawSamples[it].size < SAMPLES_PER_POINT }
                                        tvCalStatus.text = "Point ${idx+1} done. Collect the remaining $remaining point(s)."
                                    }
                                } else {
                                    scheduleRanging()
                                }
                            }
                        } else {
                            runOnUiThread { scheduleRanging() }
                        }
                    }
                }
                override fun onRangingFailure(code: Int) {
                    Log.w(TAG, "Ranging failure code=$code")
                    runOnUiThread { scheduleRanging() }
                }
            })
    }

    private fun allPointsDone() = (0..2).all { rawSamples[it].size >= SAMPLES_PER_POINT }

    /**
     * OLS fit of the direct model:  true = slope * raw + intercept
     *
     * We observe `raw` and want to predict `true`, so we regress true on raw
     * (not the other way around).  Outliers are rejected per-point using
     * IQR filtering before the regression, making the fit robust to the
     * occasional huge FTM spike.
     *
     * Calibrated distance = slope * raw + intercept  (no division needed).
     */
    private fun computeCalibration() {
        // x = raw (predictor), y = true (response)
        val rawVals  = mutableListOf<Double>()
        val trueVals = mutableListOf<Double>()

        val sb = StringBuilder()
        for (i in 0..2) {
            val d       = trueDistance(i).toDouble()
            val cleaned = iqrFilter(rawSamples[i])
            val median  = median(cleaned)
            val dropped = rawSamples[i].size - cleaned.size
            sb.appendLine("  Point ${i+1}: true=${"%.3f".format(d)} m  " +
                "median_raw=${"%.3f".format(median)} m  " +
                "(${cleaned.size} samples, $dropped outliers dropped)")
            for (r in cleaned) { rawVals.add(r.toDouble()); trueVals.add(d) }
        }

        // OLS: true = slope * raw + intercept
        val n     = rawVals.size.toDouble()
        val sumX  = rawVals.sum()
        val sumY  = trueVals.sum()
        val sumXY = rawVals.zip(trueVals).sumOf { (x, y) -> x * y }
        val sumX2 = rawVals.sumOf { it * it }
        val denom = n * sumX2 - sumX * sumX

        if (denom == 0.0) { tvCalStatus.text = "Fit failed — all raw values identical?"; return }

        val slope     = ((n * sumXY - sumX * sumY) / denom).toFloat()
        val intercept = ((sumY / n) - slope * (sumX / n)).toFloat()

        // R² (true vs predicted true)
        val meanY = sumY / n
        val ssTot = trueVals.sumOf { (it - meanY) * (it - meanY) }
        val ssRes = rawVals.zip(trueVals).sumOf { (x, y) ->
            val pred = slope * x + intercept
            (y - pred) * (y - pred)
        }
        val r2 = if (ssTot > 0) 1.0 - ssRes / ssTot else 1.0

        sb.insert(0, "OLS  cal = ${"%.4f".format(slope)} × raw + ${"%.4f".format(intercept)}\n" +
            "R² = ${"%.4f".format(r2)}\n\n")

        tvCalResult.text = sb.toString().trimEnd()
        pendingSlope     = slope
        pendingIntercept = intercept
        btnCalApply.isEnabled = true
        tvCalStatus.text = "Calibration ready — tap Apply to save."
    }

    /** Remove values outside [Q1 − 1.5·IQR, Q3 + 1.5·IQR]. */
    private fun iqrFilter(samples: List<Float>): List<Float> {
        if (samples.size < 4) return samples
        val sorted = samples.sorted()
        val q1 = sorted[sorted.size / 4].toDouble()
        val q3 = sorted[3 * sorted.size / 4].toDouble()
        val iqr = q3 - q1
        val lo = q1 - 1.5 * iqr
        val hi = q3 + 1.5 * iqr
        return samples.filter { it >= lo && it <= hi }
    }

    private fun median(samples: List<Float>): Double {
        if (samples.isEmpty()) return 0.0
        val s = samples.sorted()
        return if (s.size % 2 == 0) (s[s.size/2 - 1] + s[s.size/2]) / 2.0
               else s[s.size / 2].toDouble()
    }

    private fun showCurrentCalibration() {
        val prefs     = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val slope     = prefs.getFloat(KEY_SLOPE,     DEFAULT_SLOPE)
        val intercept = prefs.getFloat(KEY_INTERCEPT, DEFAULT_INTERCEPT)
        val isDefault = slope == DEFAULT_SLOPE && intercept == DEFAULT_INTERCEPT
        tvCalResult.text = "Active calibration${if (isDefault) " (default)" else " (custom)"}:\n" +
            "cal = ${"%.4f".format(slope)} × raw + ${"%.4f".format(intercept)}"
    }

    private fun applyCalibration() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SLOPE,     pendingSlope)
            .putFloat(KEY_INTERCEPT, pendingIntercept)
            .apply()
        tvCalStatus.text = "Saved: slope=${"%.4f".format(pendingSlope)}, intercept=${"%.4f".format(pendingIntercept)}"
        btnCalApply.isEnabled = false
        showCurrentCalibration()
    }
}
