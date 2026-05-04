package com.group2.wififtm

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MotionPositionEstimator {

    val position = FloatArray(3)
    val velocity = FloatArray(3)
    val linearAccelerationWorld = FloatArray(3)

    var statusMessage: String = "Ready"
        private set

    private val rotationMatrix = FloatArray(9)
    private var hasRotation = false

    private var lastTimestampNs: Long = 0L
    private val prevAccelWorld = FloatArray(3)

    // Bias calibration (updated both manually and adaptively during ZUPT)
    private val accelBiasDevice = FloatArray(3)
    private var calibrationRequested = false
    private var calibrationSampleCount = 0
    private val calibrationAccumulator = FloatArray(3)
    private val calibrationTargetSamples = 100

    // ZUPT: variance over a sliding window of accel magnitudes
    private val zuptWindowSize = 25
    private val accelMagWindow = ArrayDeque<Float>()

    // Require N consecutive "still" frames before committing to ZUPT,
    // to avoid false triggers from a single quiet sample mid-motion.
    private var stillFrameCount = 0
    private val stillFramesRequired = 10

    // Thresholds — tuned for typical Android TYPE_LINEAR_ACCELERATION noise floor.
    // Exposed as var so callers (e.g. FusionEstimator) can tune for their motion type.
    var varianceThreshold = 0.006f   // (m/s²)²  — noise floor is ~0.002–0.004
    var meanMagThreshold  = 0.15f    // m/s²     — backup check on mean

    // During confirmed still: aggressively decay velocity to zero
    private val velocityDecayStill = 0.80f

    // Adaptive bias EMA factor — continuously nudges bias toward "true zero"
    // during stationary periods so drift is self-correcting.
    private val biasAdaptAlpha = 0.03f

    // Time-consistent velocity damping: fraction retained per second.
    // At ~50 Hz this means ~0.5% loss per sample, but it scales with dt
    // so fast/slow polling doesn't change behaviour.
    private val velocityDampingPerSecond = 0.97f

    fun reset() {
        position.fill(0f)
        velocity.fill(0f)
        linearAccelerationWorld.fill(0f)
        prevAccelWorld.fill(0f)
        lastTimestampNs = 0L
        accelMagWindow.clear()
        stillFrameCount = 0
        statusMessage = "Reset complete"
    }

    fun startCalibration() {
        calibrationRequested = true
        calibrationSampleCount = 0
        calibrationAccumulator.fill(0f)
        statusMessage = "Calibrating..."
    }

    fun onRotationVector(rotationVectorValues: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValues)
        hasRotation = true
    }

    fun onLinearAcceleration(ax: Float, ay: Float, az: Float, timestampNs: Long) {
        if (!hasRotation) {
            statusMessage = "Waiting for rotation vector..."
            lastTimestampNs = timestampNs
            return
        }

        val rawDevice = floatArrayOf(ax, ay, az)

        // --- Manual calibration pass ---
        if (calibrationRequested) {
            calibrationAccumulator[0] += rawDevice[0]
            calibrationAccumulator[1] += rawDevice[1]
            calibrationAccumulator[2] += rawDevice[2]
            calibrationSampleCount++
            if (calibrationSampleCount >= calibrationTargetSamples) {
                accelBiasDevice[0] = calibrationAccumulator[0] / calibrationSampleCount
                accelBiasDevice[1] = calibrationAccumulator[1] / calibrationSampleCount
                accelBiasDevice[2] = calibrationAccumulator[2] / calibrationSampleCount
                calibrationRequested = false
                statusMessage = "Calibration complete"
            } else {
                statusMessage = "Calibrating... $calibrationSampleCount/$calibrationTargetSamples"
            }
            lastTimestampNs = timestampNs
            return
        }

        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            statusMessage = "Tracking"
            return
        }

        val dt = (timestampNs - lastTimestampNs) * 1e-9f
        lastTimestampNs = timestampNs
        if (dt <= 0f || dt > 0.5f) return

        // Apply current bias estimate
        val correctedDevice = floatArrayOf(
            rawDevice[0] - accelBiasDevice[0],
            rawDevice[1] - accelBiasDevice[1],
            rawDevice[2] - accelBiasDevice[2]
        )

        // Rotate to world frame
        val world = multiplyMatrixVector(rotationMatrix, correctedDevice)

        linearAccelerationWorld[0] = world[0]
        linearAccelerationWorld[1] = world[1]
        linearAccelerationWorld[2] = world[2]

        // --- ZUPT detection via sliding-window variance ---
        val mag = magnitude(world[0], world[1], world[2])
        accelMagWindow.addLast(mag)
        if (accelMagWindow.size > zuptWindowSize) accelMagWindow.removeFirst()

        val isCurrentlyStill = windowIndicatesStill()
        stillFrameCount = if (isCurrentlyStill) stillFrameCount + 1 else 0
        val confirmedStill = stillFrameCount >= stillFramesRequired

        if (confirmedStill) {
            // Smoothly decay velocity to zero (avoids position jump)
            velocity[0] *= velocityDecayStill
            velocity[1] *= velocityDecayStill
            velocity[2] *= velocityDecayStill
            if (magnitude(velocity[0], velocity[1], velocity[2]) < 0.004f) {
                velocity.fill(0f)
            }

            // Adaptive bias update: nudge toward the current raw reading
            // (which should be near-zero when still)
            accelBiasDevice[0] += biasAdaptAlpha * (rawDevice[0] - accelBiasDevice[0])
            accelBiasDevice[1] += biasAdaptAlpha * (rawDevice[1] - accelBiasDevice[1])
            accelBiasDevice[2] += biasAdaptAlpha * (rawDevice[2] - accelBiasDevice[2])

            prevAccelWorld.fill(0f)
            statusMessage = "Still"
            return
        }

        // --- Trapezoidal integration: average current + previous sample ---
        val ax_w = (prevAccelWorld[0] + world[0]) * 0.5f
        val ay_w = (prevAccelWorld[1] + world[1]) * 0.5f
        val az_w = (prevAccelWorld[2] + world[2]) * 0.5f
        prevAccelWorld[0] = world[0]
        prevAccelWorld[1] = world[1]
        prevAccelWorld[2] = world[2]

        // Integrate acceleration → velocity
        velocity[0] += ax_w * dt
        velocity[1] += ay_w * dt
        velocity[2] += az_w * dt

        // Time-consistent damping: same behaviour regardless of polling rate
        val damping = velocityDampingPerSecond.toDouble().pow(dt.toDouble()).toFloat()
        velocity[0] *= damping
        velocity[1] *= damping
        velocity[2] *= damping

        // Dead-band: clamp tiny velocities that are just noise
        if (abs(velocity[0]) < 0.004f) velocity[0] = 0f
        if (abs(velocity[1]) < 0.004f) velocity[1] = 0f
        if (abs(velocity[2]) < 0.004f) velocity[2] = 0f

        // Integrate velocity → position
        position[0] += velocity[0] * dt
        position[1] += velocity[1] * dt
        position[2] += velocity[2] * dt

        statusMessage = "Tracking"
    }

    /**
     * Returns true when the sliding window variance of accel magnitude is low
     * (device is stationary). Variance catches micro-vibrations that a simple
     * magnitude check misses.
     */
    private fun windowIndicatesStill(): Boolean {
        if (accelMagWindow.size < zuptWindowSize / 2) return false
        val mean = accelMagWindow.average().toFloat()
        if (mean > meanMagThreshold) return false
        val variance = accelMagWindow.fold(0f) { acc, v ->
            val diff = v - mean; acc + diff * diff
        } / accelMagWindow.size
        return variance < varianceThreshold
    }

    private fun multiplyMatrixVector(m: FloatArray, v: FloatArray): FloatArray {
        return floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        )
    }

    private fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
}
