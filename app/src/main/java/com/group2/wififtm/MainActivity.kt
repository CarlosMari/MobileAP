package com.group2.wififtm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WiFiFTM"
        private const val RANGING_INTERVAL_MS = 500L
        private val KNOWN_FTM_BSSIDS = setOf("b0:e4:d5:69:71:f3")
    }

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvResults: TextView
    private lateinit var tvSessionCount: TextView
    private lateinit var btnScan: Button
    private lateinit var btnStartRanging: Button
    private lateinit var btnStopRanging: Button
    private lateinit var spinnerAP: Spinner       // session label: AP position
    private lateinit var spinnerTP: Spinner       // session label: test point
    private lateinit var spinnerAvg: Spinner      // averaging window size
    private lateinit var spinnerSelectedAP: Spinner  // the actual scanned AP to range to

    // System services
    private lateinit var wifiManager: WifiManager
    private var wifiRttManager: WifiRttManager? = null

    // Scanned AP list
    private val ftmAPList = mutableListOf<ScanResult>()
    private val apDisplayNames = mutableListOf<String>()
    private var apAdapter: ArrayAdapter<String>? = null
    private var selectedAP: ScanResult? = null

    // Ranging state
    private var isRanging = false
    private val handler = Handler(Looper.getMainLooper())
    // Single reused executor — avoids spawning a new thread pool every 500 ms
    private val rangingExecutor: Executor = Executors.newSingleThreadExecutor()

    // Averaging
    private val distanceBuffer = ArrayDeque<Int>()
    private var avgWindow = 20
    private var sampleCount = 0
    private var failCount = 0

    // Session labels
    private val apLabels = arrayOf(
        "AP1 (0,0)", "AP2 (3.5,0)", "AP3 (0,4)", "AP4 (3.5,4)",
        "AP5 (0,-4)", "AP6 (3.5,-4)", "AP custom"
    )
    private val tpLabels = arrayOf(
        "T1","T2","T3","T4","T5","T6","T7","T8","T9","T10",
        "T11","T12","T13","T14","T15"
    )
    private val avgOptions = arrayOf("1", "5", "10", "20", "50")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            tvStatus.text = if (result.all { it.value })
                "Permissions granted. Tap Scan."
            else
                "Permissions denied! App won't work."
        }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ftmAPList.clear()
            apDisplayNames.clear()

            for (sr in wifiManager.scanResults) {
                Log.d(TAG, "SCAN: ${sr.SSID} | ${sr.BSSID} | ${sr.frequency}MHz | FTM:${sr.is80211mcResponder}")
                val is5Ghz = sr.frequency >= 5000
                if (is5Ghz && (sr.is80211mcResponder || sr.BSSID.lowercase() in KNOWN_FTM_BSSIDS)) {
                    ftmAPList.add(sr)
                    val bw = when (sr.channelWidth) {
                        ScanResult.CHANNEL_WIDTH_20MHZ        -> "20MHz"
                        ScanResult.CHANNEL_WIDTH_40MHZ        -> "40MHz"
                        ScanResult.CHANNEL_WIDTH_80MHZ        -> "80MHz"
                        ScanResult.CHANNEL_WIDTH_160MHZ       -> "160MHz"
                        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80MHz"
                        else                                  -> "?"
                    }
                    apDisplayNames.add("${sr.SSID}  |  ${sr.BSSID}  |  ${sr.frequency}MHz  BW:$bw  RSSI:${sr.level}dBm")
                }
            }

            apAdapter?.notifyDataSetChanged()

            if (ftmAPList.isEmpty()) {
                tvStatus.text = "No FTM APs found! (${wifiManager.scanResults.size} total scanned)"
                btnStartRanging.isEnabled = false
            } else {
                tvStatus.text = "Found ${ftmAPList.size} FTM AP(s) — select one and tap Start Ranging."
                // Auto-select first AP
                selectedAP = ftmAPList[0]
                btnStartRanging.isEnabled = true
            }
        }
    }

    private val rangingRunnable = object : Runnable {
        override fun run() {
            if (isRanging) {
                performRanging()
                handler.postDelayed(this, RANGING_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus       = findViewById(R.id.tvStatus)
        tvResults      = findViewById(R.id.tvResults)
        tvSessionCount = findViewById(R.id.tvSessionCount)
        btnScan        = findViewById(R.id.btnScan)
        btnStartRanging = findViewById(R.id.btnStartRanging)
        btnStopRanging  = findViewById(R.id.btnStopRanging)
        spinnerAP          = findViewById(R.id.spinnerAP)
        spinnerTP          = findViewById(R.id.spinnerTP)
        spinnerAvg         = findViewById(R.id.spinnerAvg)
        spinnerSelectedAP  = findViewById(R.id.spinnerSelectedAP)

        spinnerAP.adapter  = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apLabels)
        spinnerTP.adapter  = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tpLabels)
        spinnerAvg.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, avgOptions)
        spinnerAvg.setSelection(3) // default 20

        spinnerAvg.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                avgWindow = avgOptions[pos].toInt()
                distanceBuffer.clear()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        apAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apDisplayNames)
        spinnerSelectedAP.adapter = apAdapter
        spinnerSelectedAP.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                selectedAP = if (pos < ftmAPList.size) ftmAPList[pos] else null
                btnStartRanging.isEnabled = selectedAP != null
            }
            override fun onNothingSelected(p: AdapterView<*>?) {
                selectedAP = null
                btnStartRanging.isEnabled = false
            }
        }

        wifiManager    = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiRttManager = getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            tvStatus.text = "ERROR: Wi-Fi RTT not supported on this device!"
            btnScan.isEnabled = false
            return
        }
        tvStatus.text = "Wi-Fi RTT supported. Requesting permissions..."

        requestPermissions()
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        btnScan.setOnClickListener {
            ftmAPList.clear(); apDisplayNames.clear()
            apAdapter?.notifyDataSetChanged()
            btnStartRanging.isEnabled = false
            selectedAP = null
            tvStatus.text = "Scanning..."
            if (!wifiManager.startScan()) scanReceiver.onReceive(this, null)
        }

        btnStartRanging.setOnClickListener { startRanging() }
        btnStopRanging.setOnClickListener  { stopRanging() }

        findViewById<Button>(R.id.btnMotion).setOnClickListener {
            startActivity(Intent(this, MotionActivity::class.java))
        }
        findViewById<Button>(R.id.btnFusion).setOnClickListener {
            startActivity(Intent(this, FusionActivity::class.java))
        }
        findViewById<Button>(R.id.btnCalibration).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.btnOrientation).setOnClickListener {
            startActivity(Intent(this, OrientationActivity::class.java))
        }
        findViewById<Button>(R.id.btnApLocator).setOnClickListener {
            startActivity(Intent(this, ApLocatorActivity::class.java))
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startRanging() {
        val ap = selectedAP
        if (ap == null) { tvStatus.text = "Select an AP first"; return }
        isRanging   = true
        sampleCount = 0
        failCount   = 0
        distanceBuffer.clear()
        btnStartRanging.isEnabled = false
        btnStopRanging.isEnabled  = true
        btnScan.isEnabled         = false
        tvStatus.text = "Ranging to ${ap.SSID} (${ap.BSSID})…"
        tvSessionCount.text = "  |  Samples: 0"
        handler.post(rangingRunnable)
    }

    private fun stopRanging() {
        isRanging = false
        handler.removeCallbacks(rangingRunnable)
        btnStartRanging.isEnabled = selectedAP != null
        btnStopRanging.isEnabled  = false
        btnScan.isEnabled         = true
        tvStatus.text = "Stopped. $sampleCount samples collected."
    }

    private fun getSessionLabel(): String {
        val ap = apLabels[spinnerAP.selectedItemPosition]
        val tp = tpLabels[spinnerTP.selectedItemPosition]
        return "$ap|$tp"
    }

    private fun performRanging() {
        val ap  = selectedAP ?: return
        val rtt = wifiRttManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { tvStatus.text = "Location permission missing! Restart app." }
            return
        }

        val builder = RangingRequest.Builder()
        when {
            ap.is80211mcResponder -> builder.addAccessPoint(ap)
            Build.VERSION.SDK_INT >= 33 -> {
                // Use actual AP channel width and preamble 2 (VHT/802.11ac) —
                // critical for accuracy on modern 5 GHz APs. Preamble 1 (HT)
                // on a VHT AP degrades time-of-flight resolution significantly.
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

        rtt.startRanging(builder.build(), rangingExecutor,
            object : RangingResultCallback() {
                override fun onRangingResults(results: List<RangingResult>) {
                    for (r in results) {
                        val bssid = r.macAddress.toString()
                        if (r.status == RangingResult.STATUS_SUCCESS) {
                            val distMm = r.distanceMm
                            val stdMm  = r.distanceStdDevMm
                            val rssi   = r.rssi

                            distanceBuffer.addLast(distMm)
                            if (distanceBuffer.size > avgWindow) distanceBuffer.removeFirst()

                            val avgM  = distanceBuffer.average() / 1000.0
                            val rawM  = distMm / 1000.0
                            val stdM  = stdMm  / 1000.0
                            val calM  = CalibrationActivity.calibrate(avgM.toFloat(), this@MainActivity).toDouble()

                            sampleCount++
                            val session = getSessionLabel()
                            logMeasurement(session, ap.SSID ?: "?", bssid, distMm, stdMm, rssi,
                                r.numAttemptedMeasurements, r.numSuccessfulMeasurements)

                            runOnUiThread {
                                tvResults.text =
                                    "[${ap.SSID}]\n" +
                                    "  Raw:     ${"%.3f".format(rawM)} m\n" +
                                    "  Avg(${"${distanceBuffer.size}"}): ${"%.3f".format(avgM)} m\n" +
                                    "  Cal:     ${"%.3f".format(calM)} m\n" +
                                    "  Std Dev: ${"%.3f".format(stdM)} m\n" +
                                    "  RSSI:    $rssi dBm\n" +
                                    "  Bursts:  ${r.numSuccessfulMeasurements}/${r.numAttemptedMeasurements}\n" +
                                    "  Fails:   $failCount"
                                tvSessionCount.text = "  |  Samples: $sampleCount"
                            }
                        } else {
                            failCount++
                            Log.w(TAG, "Ranging result failed for $bssid status=${r.status}")
                            runOnUiThread {
                                tvResults.text = tvResults.text.toString()
                                    .replace(Regex("  Fails:.*"), "  Fails:   $failCount")
                                    .ifBlank { "Last result: FAIL (${r.status})  Fails: $failCount" }
                            }
                        }
                    }
                }

                override fun onRangingFailure(code: Int) {
                    failCount++
                    Log.w(TAG, "onRangingFailure code=$code  total_fails=$failCount")
                    // Do NOT stop ranging — transient failures are normal.
                    // The runnable will retry on the next interval.
                }
            })
    }

    private fun logMeasurement(
        session: String, ssid: String, bssid: String,
        distMm: Int, stdMm: Int, rssi: Int, attempted: Int, successful: Int
    ) {
        try {
            val file  = File(getExternalFilesDir(null), "ftm_log.csv")
            val isNew = !file.exists()
            val writer = FileWriter(file, true)
            if (isNew) writer.append("timestamp,session,ssid,bssid,distance_mm,std_dev_mm,rssi,attempted,successful\n")
            writer.append("${System.currentTimeMillis()},$session,$ssid,$bssid,$distMm,$stdMm,$rssi,$attempted,$successful\n")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRanging()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }
}
