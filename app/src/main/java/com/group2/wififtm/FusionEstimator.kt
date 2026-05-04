package com.group2.wififtm

import android.hardware.SensorManager
import kotlin.math.*

/**
 * FTM + Gyro fusion via Extended Kalman Filter (EKF).
 *
 * State:   [x, y]  — 2D phone position
 *
 * Predict  (every GAME_ROTATION_VECTOR update, ~50 Hz):
 *   Heading φ = headingAtStart − relativeYaw.
 *   Process noise Q is a 2×2 ellipse aligned to heading:
 *     large variance along φ  (we expect motion in that direction)
 *     small variance ⊥ φ      (no lateral drift expected)
 *   P ← P + Q(φ)
 *
 * Update   (every FTM measurement, ~10 Hz):
 *   h(x,y) = √((x−apX)²+(y−apY)²)
 *   Jacobian H = [(x−apX)/r̂, (y−apY)/r̂]
 *   Standard EKF update: K, state, P.
 *
 * Calibration: first 10 s after Start the phone must be stationary.
 *   FTM samples are averaged to get a stable initial distance and tight P.
 */
class FusionEstimator(var apX: Float = 0f, var apY: Float = 0f) {

    // ---- Public outputs -------------------------------------------------------
    var estimatedX     = 0f;  private set
    var estimatedY     = 0f;  private set
    var currentFtmDist = 0f;  private set
    var isInitialized  = false; private set
    var hasStart       = false; private set
    var statusMessage  = "Enter positions, point toward AP, tap Start."; private set
    var thetaDeg       = 0f;  private set
    var headingUncertaintyDeg = 0f; private set
    var isCalibrating  = false; private set

    /** Room-frame heading φ = headingAtStart − relYaw, in degrees. 0° = +X axis, CCW positive. */
    val headingDeg get() = Math.toDegrees(heading.toDouble()).toFloat()
    /** Relative yaw since tracking start (degrees). */
    val relYawDeg  get() = Math.toDegrees(prevRelYaw.toDouble()).toFloat()
    /** Raw yaw from getOrientation (no remap) — matches OrientationActivity "Yaw (Z)". */
    var rawYawDeg = 0f; private set
    /** Raw GAME_ROTATION_VECTOR quaternion components [x, y, z, w]. */
    var gameRotVec = FloatArray(4); private set

    // ---- FTM state -----------------------------------------------------------
    private var r     = 0f

    // ---- Gyro state ----------------------------------------------------------
    private val R_current  = FloatArray(9)
    private val R_init     = FloatArray(9)   // initial rotation matrix for raw relative yaw display
    private val R_start    = FloatArray(9)   // rotation matrix at tracking start (for heading)
    private var hasRInit   = false
    private var hasRotation = false
    private var heading    = 0f
    private var headingAtStart = 0f           // room-frame heading when tracking started
    private var prevRelYaw = 0f               // previous relative yaw for dYaw computation

    private var trackingStarted = false

    // ---- EKF covariance (symmetric 2×2) --------------------------------------
    private var p00 = 1f; private var p01 = 0f; private var p11 = 1f

    // ---- Noise parameters ----------------------------------------------------
    private val Q_ALONG     = 0.012f     // m² std dev per gyro step along heading
    private val Q_LATERAL   = 0.002f     // m² std dev perpendicular to heading
    private val R_FTM_BASE  = 0.5f       // FTM variance at 0m (σ ≈ 0.7 m)
    private val R_FTM_SLOPE = 0.25f      // additional variance per metre of distance
    private val P_MAX       = 9f         // cap covariance diagonal at (3 m)²
    private val PREDICT_STEP = 0.012f    // m per gyro tick along heading (~0.6 m/s at 50 Hz)

    // ---- Calibration ---------------------------------------------------------
    private val CAL_DURATION_MS = 10_000L
    private val CAL_MIN_SAMPLES = 15
    private val calSamples = mutableListOf<Float>()
    private var calStartTime = 0L

    // ==========================================================================
    // Public API
    // ==========================================================================

    fun startTracking(phoneStartX: Float = Float.NaN, phoneStartY: Float = Float.NaN) {
        trackingStarted = true
        isInitialized   = false
        currentFtmDist  = 0f
        headingUncertaintyDeg = 0f
        resetCovariance()

        val haveStart = !phoneStartX.isNaN() && !phoneStartY.isNaN()
        if (haveStart) {
            val dx   = phoneStartX - apX
            val dy   = phoneStartY - apY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 0.1f) {
                estimatedX = phoneStartX
                estimatedY = phoneStartY
                val theta0 = atan2(dy, dx)
                thetaDeg   = Math.toDegrees(theta0.toDouble()).toFloat()
                this.headingAtStart = theta0 + PI.toFloat()
                R_current.copyInto(R_start)
                prevRelYaw = 0f
                heading = this.headingAtStart
                hasStart = true
                // Enter calibration
                isCalibrating = true
                calSamples.clear()
                calStartTime = 0L
                statusMessage = "Calibrating — hold still…"
            } else {
                hasStart = false; statusMessage = "Start too close to AP."
            }
        } else {
            hasStart = false; statusMessage = "No start pos — distance only."
        }
    }

    fun reset() {
        trackingStarted = false; isInitialized = false; hasStart = false
        isCalibrating = false; calSamples.clear()
        r = 0f; heading = 0f; headingAtStart = 0f
        thetaDeg = 0f; currentFtmDist = 0f
        estimatedX = 0f; estimatedY = 0f
        headingUncertaintyDeg = 0f; prevRelYaw = 0f
        hasRInit = false; rawYawDeg = 0f
        resetCovariance()
        statusMessage = "Enter positions, point toward AP, tap Start."
    }

    // ---- Sensor feeds --------------------------------------------------------

    /** GAME_ROTATION_VECTOR — updates heading, EKF predict step. */
    fun onRotationVector(values: FloatArray) {
        // Store raw quaternion [x, y, z, w]
        for (i in 0 until minOf(values.size, 4)) gameRotVec[i] = values[i]

        SensorManager.getRotationMatrixFromVector(
            R_current, values.copyOf(minOf(values.size, 4))
        )
        hasRotation = true

        // Capture initial rotation matrix on first sample (for raw relative yaw)
        if (!hasRInit) { R_current.copyInto(R_init); hasRInit = true }

        // Raw relative yaw (no remap) — same as OrientationActivity "Yaw (Z)"
        rawYawDeg = Math.toDegrees(getRelativeYaw(R_current, R_init).toDouble()).toFloat()

        if (!trackingStarted) return

        // Relative yaw: R_start^T * R_current → getOrientation → azimuth.
        // No remapCoordinateSystem — works for any phone tilt.
        val relYaw = getRelativeYaw(R_current, R_start)
        var dYaw = relYaw - prevRelYaw
        while (dYaw >  PI.toFloat()) dYaw -= 2f * PI.toFloat()
        while (dYaw < -PI.toFloat()) dYaw += 2f * PI.toFloat()
        prevRelYaw = relYaw

        heading = headingAtStart - relYaw
        headingUncertaintyDeg += abs(Math.toDegrees(dYaw.toDouble()).toFloat()) * 0.05f

        // No predict during calibration — phone is stationary
        if (isInitialized && hasStart && !isCalibrating) ekfPredict()
    }

    /** FTM distance — EKF update step. */
    fun onFtmMeasurement(distMeters: Float) {
        if (distMeters <= 0f) return
        r = distMeters; currentFtmDist = distMeters

        // ---- Calibration phase: collect samples, don't run EKF ----
        if (isCalibrating) {
            if (calStartTime == 0L) calStartTime = System.currentTimeMillis()
            calSamples.add(distMeters)
            val elapsed = System.currentTimeMillis() - calStartTime
            if (elapsed >= CAL_DURATION_MS && calSamples.size >= CAL_MIN_SAMPLES) {
                finishCalibration()
            } else {
                val remaining = ((CAL_DURATION_MS - elapsed) / 1000).coerceAtLeast(0)
                statusMessage = "Calibrating — hold still… ${remaining}s (${calSamples.size} samples)"
            }
            return
        }

        if (!isInitialized) {
            isInitialized = true
            if (hasStart) {
                val dx = estimatedX - apX; val dy = estimatedY - apY
                val len = sqrt(dx*dx + dy*dy)
                if (len > 0.01f) { estimatedX = apX + dx/len*r; estimatedY = apY + dy/len*r }
            }
        }

        if (hasStart) {
            ekfUpdate(distMeters)
            val dx = estimatedX - apX; val dy = estimatedY - apY
            thetaDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            updateStatus()
        } else {
            statusMessage = "FTM: ${"%.2f".format(distMeters)} m (no start pos)"
        }
    }

    // ==========================================================================
    // Calibration
    // ==========================================================================

    private fun finishCalibration() {
        val meanDist = calSamples.average().toFloat()
        val ftmStd = if (calSamples.size > 1) {
            val mean = calSamples.average()
            sqrt(calSamples.map { (it - mean).let { d -> d * d } }.average()).toFloat()
        } else 0.15f

        // Place phone at calibrated distance along the known initial angle
        val dx = estimatedX - apX; val dy = estimatedY - apY
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0.01f) {
            estimatedX = apX + dx / len * meanDist
            estimatedY = apY + dy / len * meanDist
        }

        // Recalibrate heading: reset R_start to current rotation matrix,
        // so relative yaw restarts from 0 at this point.
        val dx2 = estimatedX - apX; val dy2 = estimatedY - apY
        val theta = atan2(dy2, dx2)
        headingAtStart = theta + PI.toFloat()  // facing toward AP
        R_current.copyInto(R_start)
        prevRelYaw = 0f
        heading = headingAtStart

        // Tight covariance from calibration — phone position is well-known
        val calVar = (ftmStd * ftmStd).coerceAtLeast(0.01f)
        p00 = calVar; p01 = 0f; p11 = calVar

        r = meanDist; currentFtmDist = meanDist
        isCalibrating = false
        isInitialized = true
        headingUncertaintyDeg = 0f

        thetaDeg = Math.toDegrees(theta.toDouble()).toFloat()
        statusMessage = "Calibrated: r=${"%.2f".format(meanDist)}m ±${"%.2f".format(ftmStd)}m — move!"
    }

    // ==========================================================================
    // EKF
    // ==========================================================================

    private fun ekfPredict() {
        val cosH = cos(heading).toFloat()
        val sinH = sin(heading).toFloat()

        // Predict step along heading, scaled by tangential factor with a floor.
        // Floor prevents stall when geometry makes heading appear radial.
        val dx = estimatedX - apX
        val dy = estimatedY - apY
        val rEst = sqrt(dx * dx + dy * dy)
        val tangential = if (rEst > 0.1f) {
            val cosAngle = abs(cosH * dx/rEst + sinH * dy/rEst)
            (1f - cosAngle).coerceAtLeast(0.3f)
        } else 1f
        val step = PREDICT_STEP * tangential
        estimatedX += cosH * step
        estimatedY += sinH * step

        val qa2  = Q_ALONG   * Q_ALONG
        val ql2  = Q_LATERAL * Q_LATERAL
        p00 = (p00 + cosH*cosH*qa2 + sinH*sinH*ql2).coerceAtMost(P_MAX)
        p11 = (p11 + sinH*sinH*qa2 + cosH*cosH*ql2).coerceAtMost(P_MAX)
        p01 += cosH * sinH * (qa2 - ql2)
    }

    private val NEAR_AP_DIST = 1.5f  // blend Jacobian toward heading below this distance

    private fun ekfUpdate(measDist: Float) {
        val dx   = estimatedX - apX
        val dy   = estimatedY - apY
        val rEst = sqrt(dx*dx + dy*dy)
        if (rEst < 0.05f) return

        var hx = dx / rEst
        var hy = dy / rEst

        // Near AP: position-derived angle is unreliable — a small position error
        // flips the Jacobian to the wrong side. Blend toward heading direction,
        // which is a much better proxy for "direction from AP to phone" near AP.
        if (rEst < NEAR_AP_DIST) {
            val cosH = cos(heading).toFloat()
            val sinH = sin(heading).toFloat()
            val blend = (NEAR_AP_DIST - rEst) / NEAR_AP_DIST  // 1 at AP, 0 at threshold
            hx = hx * (1f - blend) + cosH * blend
            hy = hy * (1f - blend) + sinH * blend
            val norm = sqrt(hx * hx + hy * hy)
            if (norm > 0.01f) { hx /= norm; hy /= norm }
        }

        // Distance-dependent R: FTM noise grows with range
        // Measured: σ≈0.7m at 0-2m, σ≈1.3m at 4-6m, σ≈1.7m at 6-8m
        val rFtmVar = R_FTM_BASE + R_FTM_SLOPE * rEst
        val S  = hx*hx*p00 + 2f*hx*hy*p01 + hy*hy*p11 + rFtmVar
        val kx = (p00*hx + p01*hy) / S
        val ky = (p01*hx + p11*hy) / S

        val innov = measDist - rEst
        estimatedX += kx * innov
        estimatedY += ky * innov

        val new00 = (p00 - kx*(hx*p00 + hy*p01)).coerceAtLeast(1e-4f)
        val new01 =  p01 - kx*(hx*p01 + hy*p11)
        val new11 = (p11 - ky*(hx*p01 + hy*p11)).coerceAtLeast(1e-4f)
        p00 = new00; p01 = new01; p11 = new11
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private fun resetCovariance() { p00 = 1f; p01 = 0f; p11 = 1f }

    private fun updateStatus() {
        statusMessage = "r=${"%.2f".format(r)} m | " +
            "φ=${Math.toDegrees(heading.toDouble()).toInt()}° ±${headingUncertaintyDeg.toInt()}° | " +
            "X=${"%.2f".format(estimatedX)} Y=${"%.2f".format(estimatedY)}"
    }

    /** Relative yaw via R_ref^T * R_cur → getOrientation (no remap).
     *  Works for any phone tilt — matches OrientationActivity "Yaw (Z)". */
    private fun getRelativeYaw(rCur: FloatArray, rRef: FloatArray): Float {
        val rRefT = FloatArray(9)
        for (i in 0..2) for (j in 0..2) rRefT[i * 3 + j] = rRef[j * 3 + i]
        val rRel = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += rRefT[i * 3 + k] * rCur[k * 3 + j]
            rRel[i * 3 + j] = s
        }
        val o = FloatArray(3)
        SensorManager.getOrientation(rRel, o)
        return o[0]  // radians
    }
}
