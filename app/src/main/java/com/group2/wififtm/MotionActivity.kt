package com.group2.wififtm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedWriter
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MotionActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var linearAccelerationSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null

    private lateinit var estimator: MotionPositionEstimator

    private lateinit var statusText:       TextView
    private lateinit var positionText:     TextView
    private lateinit var velocityText:     TextView
    private lateinit var accelerationText: TextView
    private lateinit var tvMotionLog:      TextView
    private lateinit var tvWaypointNext:   TextView
    private lateinit var btnMarkWaypoint:  Button
    private lateinit var resetButton:      Button
    private lateinit var calibrateButton:  Button
    private lateinit var trajectoryView:   TrajectoryView

    // Known square waypoints: (true_x, true_y, label)
    private val WAYPOINTS = arrayOf(
        Triple(3.5f, 0.0f, "START"),
        Triple(0.0f, 0.0f, "Corner 1"),
        Triple(0.0f, 4.0f, "Corner 2"),
        Triple(3.5f, 4.0f, "Corner 3"),
        Triple(3.5f, 0.0f, "END")
    )
    private var waypointIndex = 0

    // CSV logging
    private var csvWriter: BufferedWriter? = null
    private var csvPath = ""
    private var lastLogMs = 0L
    private val LOG_INTERVAL_MS = 100L   // write at most 10 rows/sec

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_motion)

        statusText       = findViewById(R.id.statusText)
        positionText     = findViewById(R.id.positionText)
        velocityText     = findViewById(R.id.velocityText)
        accelerationText = findViewById(R.id.accelerationText)
        tvMotionLog      = findViewById(R.id.tvMotionLog)
        tvWaypointNext   = findViewById(R.id.tvWaypointNext)
        btnMarkWaypoint  = findViewById(R.id.btnMarkWaypoint)
        resetButton      = findViewById(R.id.resetButton)
        calibrateButton  = findViewById(R.id.calibrateButton)
        trajectoryView   = findViewById(R.id.trajectoryView)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccelerationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVectorSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        estimator = MotionPositionEstimator()

        resetButton.setOnClickListener {
            estimator.reset()
            trajectoryView.clear()
            waypointIndex = 0
            updateWaypointUI()
            updateUi("Reset complete")
        }

        calibrateButton.setOnClickListener {
            estimator.startCalibration()
            updateUi("Calibrating — hold phone still for ~1 s")
        }

        btnMarkWaypoint.setOnClickListener {
            if (waypointIndex >= WAYPOINTS.size) return@setOnClickListener
            val wp = WAYPOINTS[waypointIndex]
            val p  = estimator.position
            val now = System.currentTimeMillis()
            logWaypoint(now, waypointIndex + 1, wp.first, wp.second, p)
            waypointIndex++
            updateWaypointUI()
        }

        updateWaypointUI()

        val hasLinAcc = linearAccelerationSensor != null
        val hasRotVec = rotationVectorSensor != null
        statusText.text = when {
            !hasLinAcc && !hasRotVec -> "Missing TYPE_LINEAR_ACCELERATION and TYPE_ROTATION_VECTOR"
            !hasLinAcc               -> "Missing TYPE_LINEAR_ACCELERATION"
            !hasRotVec               -> "Missing TYPE_ROTATION_VECTOR"
            else                     -> "Sensors ready"
        }

        openCsv()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVectorSensor?.let     { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        closeCsv()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR ->
                estimator.onRotationVector(event.values)

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                estimator.onLinearAcceleration(
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                    timestampNs = event.timestamp
                )
                val p = estimator.position
                // Throttled canvas + CSV update
                val now = System.currentTimeMillis()
                if (now - lastLogMs >= LOG_INTERVAL_MS) {
                    lastLogMs = now
                    trajectoryView.addPoint(p[0], p[1])
                    logCsvRow(now, p, estimator.velocity)
                }
                updateUi()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateUi(customStatus: String? = null) {
        val p = estimator.position
        val v = estimator.velocity
        val a = estimator.linearAccelerationWorld

        statusText.text = customStatus ?: estimator.statusMessage

        positionText.text = "X: ${"%.3f".format(p[0])} m   Y: ${"%.3f".format(p[1])} m   Z: ${"%.3f".format(p[2])} m"
        velocityText.text = "Vx: ${"%.3f".format(v[0])}   Vy: ${"%.3f".format(v[1])}   Vz: ${"%.3f".format(v[2])} m/s"
        accelerationText.text = "Ax: ${"%.3f".format(a[0])}   Ay: ${"%.3f".format(a[1])}   Az: ${"%.3f".format(a[2])} m/s²"
    }

    // ---- Waypoint helpers ---------------------------------------------------

    private fun updateWaypointUI() {
        if (waypointIndex >= WAYPOINTS.size) {
            tvWaypointNext.text = "All waypoints marked — run complete!"
            btnMarkWaypoint.isEnabled = false
        } else {
            val wp = WAYPOINTS[waypointIndex]
            val label = if (waypointIndex == 0) "START — tap when at start position"
                        else "tap when you reach this corner"
            tvWaypointNext.text = "Next: WP ${waypointIndex + 1} → " +
                "(${"%.1f".format(wp.first)}, ${"%.1f".format(wp.second)})  [${wp.third} — $label]"
            btnMarkWaypoint.isEnabled = true
        }
    }

    // ---- CSV helpers --------------------------------------------------------

    private fun openCsv() {
        try {
            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = java.io.File(getExternalFilesDir(null), "imu_$ts.csv")
            csvWriter = BufferedWriter(FileWriter(file))
            csvWriter!!.write("time_ms,x_m,y_m,z_m,vx,vy,vz,status,waypoint,true_x,true_y\n")
            csvPath = file.absolutePath
            tvMotionLog.text = "Logging: imu_$ts.csv"
        } catch (e: Exception) {
            tvMotionLog.text = "CSV open failed: ${e.message}"
        }
    }

    private fun logCsvRow(timeMs: Long, p: FloatArray, v: FloatArray) {
        try {
            csvWriter?.write("$timeMs,${p[0]},${p[1]},${p[2]},${v[0]},${v[1]},${v[2]},${estimator.statusMessage},,,\n")
        } catch (_: Exception) {}
    }

    private fun logWaypoint(timeMs: Long, wpNum: Int, trueX: Float, trueY: Float,
                             p: FloatArray) {
        try {
            // Columns: time_ms,x_m,y_m,z_m,vx,vy,vz,status,waypoint,true_x,true_y
            csvWriter?.write("$timeMs,${p[0]},${p[1]},${p[2]},,,,," +
                "WP$wpNum,$trueX,$trueY\n")
            csvWriter?.flush()
        } catch (_: Exception) {}
    }

    private fun closeCsv() {
        try { csvWriter?.flush(); csvWriter?.close() } catch (_: Exception) {}
        csvWriter = null
    }
}
