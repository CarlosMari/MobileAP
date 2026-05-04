package com.group2.wififtm

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class OrientationActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var sensorGame: Sensor? = null
    private var sensorGeo:  Sensor? = null
    private var sensorRv:   Sensor? = null

    // Initial rotation matrices — captured on first event for each sensor
    private val initGame = FloatArray(9)
    private val initGeo  = FloatArray(9)
    private val initRv   = FloatArray(9)
    private var hasInitGame = false
    private var hasInitGeo  = false
    private var hasInitRv   = false

    private lateinit var tvStatus:   TextView
    private lateinit var tvGame:     TextView
    private lateinit var tvGeo:      TextView
    private lateinit var tvRv:       TextView
    private lateinit var tvGameNed:  TextView
    private lateinit var tvGeoNed:   TextView
    private lateinit var tvRvNed:    TextView
    private lateinit var btnReset:   Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orientation)

        tvStatus  = findViewById(R.id.tvOrientationStatus)
        tvGame    = findViewById(R.id.tvGameRv)
        tvGeo     = findViewById(R.id.tvGeoRv)
        tvRv      = findViewById(R.id.tvRotVec)
        tvGameNed = findViewById(R.id.tvGameNed)
        tvGeoNed  = findViewById(R.id.tvGeoNed)
        tvRvNed   = findViewById(R.id.tvRotVecNed)
        btnReset  = findViewById(R.id.btnResetOrientation)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorGame = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        sensorGeo  = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        sensorRv   = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensorGame == null) tvGame.text = "GAME_ROTATION_VECTOR\nNot available on this device"
        if (sensorGeo  == null) tvGeo.text  = "GEOMAGNETIC_ROTATION_VECTOR\nNot available on this device"
        if (sensorRv   == null) tvRv.text   = "ROTATION_VECTOR\nNot available on this device"

        btnReset.setOnClickListener { resetInitial() }
    }

    override fun onResume() {
        super.onResume()
        resetInitial()
        val rate = SensorManager.SENSOR_DELAY_UI
        sensorGame?.let { sensorManager.registerListener(this, it, rate) }
        sensorGeo?.let  { sensorManager.registerListener(this, it, rate) }
        sensorRv?.let   { sensorManager.registerListener(this, it, rate) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun resetInitial() {
        hasInitGame = false
        hasInitGeo  = false
        hasInitRv   = false
        tvStatus.text = "Capturing initial orientation…"
    }

    override fun onSensorChanged(event: SensorEvent) {
        // SensorManager.getRotationMatrixFromVector requires at most 4 elements
        val values = event.values.copyOf(minOf(event.values.size, 4))
        val R = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(R, values)

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (!hasInitGame) { R.copyInto(initGame); hasInitGame = true }
                tvGame.text    = formatRelative("Game Rotation Vector (rel. to initial)", R, initGame)
                tvGameNed.text = formatNed("NED (arbitrary yaw — no magnetometer)", R)
            }
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                if (!hasInitGeo) { R.copyInto(initGeo); hasInitGeo = true }
                tvGeo.text    = formatRelative("Geomagnetic Rotation Vector (rel. to initial)", R, initGeo)
                tvGeoNed.text = formatNed("NED (magnetic north reference)", R)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!hasInitRv) { R.copyInto(initRv); hasInitRv = true }
                tvRv.text    = formatRelative("Rotation Vector (rel. to initial)", R, initRv)
                tvRvNed.text = formatNed("NED (magnetic north reference)", R)
            }
        }

        if (hasInitGame && hasInitGeo && hasInitRv && tvStatus.text != "Tracking") {
            tvStatus.text = "Tracking"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /**
     * Compute R_rel = R_init^T * R_current, then extract yaw/pitch/roll via
     * SensorManager.getOrientation. R_rel maps current-device-frame vectors into
     * the initial-device frame (treating the initial orientation as world), so the
     * extracted angles represent how much the device has rotated since t=0.
     */
    private fun formatRelative(label: String, rCur: FloatArray, rInit: FloatArray): String {
        val rRel = MatUtils.multiply(MatUtils.transpose(rInit, 3, 3), 3, 3, rCur, 3)
        val angles = FloatArray(3)
        SensorManager.getOrientation(rRel, angles)
        val yaw   = Math.toDegrees(angles[0].toDouble())
        val pitch = Math.toDegrees(angles[1].toDouble())
        val roll  = Math.toDegrees(angles[2].toDouble())
        return "$label\n" +
               "  Yaw   (Z): ${"%+7.1f".format(yaw)}°\n" +
               "  Pitch (X): ${"%+7.1f".format(pitch)}°\n" +
               "  Roll  (Y): ${"%+7.1f".format(roll)}°"
    }

    /**
     * Extract yaw/pitch/roll in the NED-aligned world frame directly from the
     * raw rotation matrix (no relative-to-initial subtraction).
     *
     * Android's getOrientation convention:
     *   angles[0] azimuth — rotation of device Y-axis from North, clockwise positive
     *                       (0 = North, 90 = East, 180 = South, -90 = West)
     *                       For GAME_ROTATION_VECTOR this is relative to an arbitrary
     *                       starting direction (no magnetometer), not true North.
     *   angles[1] pitch   — tilt around X-axis (-90 = face up, +90 = face down)
     *   angles[2] roll    — tilt around Y-axis
     */
    private fun formatNed(label: String, r: FloatArray): String {
        val angles = FloatArray(3)
        SensorManager.getOrientation(r, angles)
        val yaw   = Math.toDegrees(angles[0].toDouble())
        val pitch = Math.toDegrees(angles[1].toDouble())
        val roll  = Math.toDegrees(angles[2].toDouble())
        return "$label\n" +
               "  Azimuth (N=0°): ${"%+7.1f".format(yaw)}°\n" +
               "  Pitch   (X):    ${"%+7.1f".format(pitch)}°\n" +
               "  Roll    (Y):    ${"%+7.1f".format(roll)}°"
    }


}
