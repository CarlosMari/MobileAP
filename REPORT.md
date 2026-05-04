# WifiFTM — Indoor Positioning System
## Project Report

---

## 1. Overview

WifiFTM is an Android application that estimates the 2D position of a phone inside a room using a single Wi-Fi Fine Timing Measurement (FTM) access point combined with the phone's onboard gyroscope. The project was built and tested by Group 2 as part of a positioning systems course.

The core challenge: FTM gives accurate **distance** to a known AP, but distance alone only places the phone on a circle. A second information source — the **heading direction** from the gyroscope — resolves the ambiguity and pins down a unique position on that circle.

---

## 2. Hardware & Environment

| Item | Detail |
|------|--------|
| Phone | Android device with Wi-Fi RTT (802.11mc) and GAME_ROTATION_VECTOR sensor |
| Access Point | Single FTM-capable AP at position (0, 0) m — BSSID `b0:e4:d5:69:71:f3` |
| Test environment | Indoor room, flat floor |
| Test path | Square: (3.5, 0) → (0, 0) → (0, 4) → (3.5, 4) → (3.5, 0) m |
| Phone mount | Trolley — phone held flat, pointing toward AP at start |

---

## 3. System Architecture

The app has four screens accessible from `MainActivity`:

| Screen | Purpose |
|--------|---------|
| **Calibration** | Collects FTM readings at 3 known distances, fits a linear correction `cal = slope × raw + intercept` |
| **Fusion** | Main positioning screen — FTM + gyro EKF, live trajectory, waypoint logging |
| **Motion** | IMU-only dead reckoning (LINEAR_ACCELERATION + ROTATION_VECTOR) for comparison |
| **Main** | Entry point and navigation |

---

## 4. Algorithm — Extended Kalman Filter (EKF)

### 4.1 State

The filter tracks a 2D position:

```
x = [px, py]
```

No velocity is tracked — motion is driven entirely by the FTM measurement updates.

### 4.2 Heading from Gyroscope

At **Start**, the user physically points the trolley toward the AP. The app records the gyro yaw `ψ_start` and the known AP→phone direction `θ₀ = atan2(startY − apY, startX − apX)`:

```
gyroOffset = (θ₀ + π) + ψ_start
```

At any later time:

```
φ(t) = −ψ(t) + gyroOffset
```

`φ` is the room-frame heading (direction the trolley is pointing). The negation is required because Android's `getOrientation` azimuth increases clockwise while standard math angles increase counter-clockwise.

Sensor used: `TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer, no magnetometer — immune to indoor magnetic interference).

### 4.3 EKF Predict Step (every gyro update, ~50 Hz)

The state mean does not change — there is no dead-reckoning velocity. Instead, the covariance grows along the heading direction:

```
Q = R(φ) · diag(σ_along², σ_lateral²) · R(φ)ᵀ

P ← P + Q
```

| Parameter | Value | Meaning |
|-----------|-------|---------|
| `σ_along` | 0.010 m | Expected motion per gyro step along heading |
| `σ_lateral` | 0.002 m | Expected motion perpendicular (near-zero) |

This encodes the constraint "position moves along heading, not sideways." When an FTM update arrives, the Kalman gain is naturally largest along the heading direction.

### 4.4 EKF Update Step (every FTM measurement, ~10 Hz)

FTM gives distance from AP — a nonlinear measurement:

```
h(x, y) = √((x − apX)² + (y − apY)²)
```

Linearised Jacobian:

```
H = [ (x − apX) / r̂,  (y − apY) / r̂ ]
```

Standard EKF update:

```
S  = H P Hᵀ + R_ftm
K  = P Hᵀ / S
x ← x + K · (z − h(x))
P ← (I − KH) P
```

| Parameter | Value | Meaning |
|-----------|-------|---------|
| `R_ftm` | 0.25² = 0.0625 m² | FTM measurement variance (~0.25 m std dev) |
| `P_max` | 9 m² | Covariance diagonal cap (3 m std dev) |

### 4.5 FTM Asymmetric IIR Filter (NLoS rejection)

Before the EKF update, each raw FTM reading is passed through an asymmetric filter in `FusionActivity`:

```
α = ALPHA_CLOSER  (1.0)  if new reading < current smoothed
α = ALPHA_FARTHER (0.6)  if new reading > current smoothed

smoothed ← α · raw + (1 − α) · smoothed
```

**Rationale:** FTM over-estimates distance when the signal is reflected or the body blocks line-of-sight (NLoS). Readings that increase (phone moving away, or NLoS spike) are smoothed more aggressively than readings that decrease (phone moving closer, almost certainly LoS).

### 4.6 Outlier Rejection

FTM jumps larger than `MAX_FTM_JUMP_M = 3.0 m` between consecutive readings are discarded entirely.

---

## 5. FTM Calibration

The `CalibrationActivity` collects 60 FTM samples at each of 3 known positions and fits a linear regression:

```
calibrated = slope × raw + intercept
```

Calibration points (phone coordinates relative to AP at origin):

| Point | X (m) | Y (m) | True distance (m) |
|-------|-------|-------|-------------------|
| 1 | 0 | −4 | 4.00 |
| 2 | 3.5 | 0 | 3.50 |
| 3 | 3.5 | −4 | 5.32 |

Calibration is stored in `SharedPreferences` and applied to every ranging result before it reaches the estimator.

---

## 6. Sensors Used

| Sensor | Rate | Used for |
|--------|------|---------|
| `TYPE_GAME_ROTATION_VECTOR` | SENSOR_DELAY_GAME (~50 Hz) | Gyro heading φ |
| Wi-Fi RTT (FTM) | 100 ms interval | Distance to AP |

**Sensors tried and rejected:**

- `TYPE_ROTATION_VECTOR` (magnetometer fusion) — magnetic interference indoors caused mean error to jump from 1.15 m to 3.45 m. Disabled.
- `TYPE_LINEAR_ACCELERATION` (IMU heading correction) — velocity frame transform on a moving trolley produced spurious heading nudges, degrading results by 15–200% depending on the run. Disabled.

---

## 7. Approaches Tried

| Approach | Result | Notes |
|----------|--------|-------|
| Polar model: `pos = AP + r·(cosθ, sinθ)`, θ updated by Δψ | Broken | Rotating in place incorrectly moved estimated position |
| Heading-ray ∩ FTM-circle intersection | **Best baseline** | Correct geometry; minimum-displacement root |
| Minimum-of-20 FTM filter | Good NLoS rejection | 2 s lag when moving away from AP — discarded |
| Asymmetric IIR filter (closer=1.0, farther=0.6) | Good | Fast LoS response, NLoS damping — kept |
| Compass correction (TYPE_ROTATION_VECTOR) | Worse | Mean 3.45 m vs 1.15 m — indoor mag interference |
| IMU displacement (replace position update with dead reckoning) | Worse | Misunderstood intent; discarded |
| IMU heading correction (velocity direction nudges gyroOffset) | Marginal / inconsistent | Sometimes helped, sometimes sent Y to −6 m |
| Extended Kalman Filter (heading-aligned process noise) | **Best current** | Principled uncertainty; max error reduced |

---

## 8. Results

All runs on the square path: (3.5, 0) → (0, 0) → (0, 4) → (3.5, 4) → (3.5, 0).
AP at (0, 0). Errors are Euclidean distance between estimated and true waypoint.

### Best run — pure EKF, no IMU (fusion_20250503_101120)

| Waypoint | True (m) | Estimated (m) | Error (m) |
|----------|----------|---------------|-----------|
| WP1 — Start (3.5, 0) | (3.5, 0.0) | (4.06, 0.00) | **0.56** |
| WP2 — Corner 1 (0, 0) | (0.0, 0.0) | (0.67, −0.13) | **0.68** |
| WP3 — Corner 2 (0, 4) | (0.0, 4.0) | (1.66, 5.23) | **2.07** |
| WP4 — Corner 3 (3.5, 4) | (3.5, 4.0) | (7.69, 3.03) | **4.30** |
| WP5 — End (3.5, 0) | (3.5, 0.0) | (4.79, 0.23) | **1.31** |
| | | **Mean** | **1.78 m** |
| | | **Max** | **4.30 m** |

### Latest run — pure EKF, tighter R_FTM (fusion_20250503_104427)

| Waypoint | True (m) | Estimated (m) | Error (m) |
|----------|----------|---------------|-----------|
| WP1 | (3.5, 0.0) | (3.15, −0.74) | **0.82** |
| WP2 | (0.0, 0.0) | (1.11, −0.11) | **1.11** |
| WP3 | (0.0, 4.0) | (1.56, 5.13) | **1.93** |
| WP4 | (3.5, 4.0) | (7.18, 5.47) | **3.96** |
| WP5 | (3.5, 0.0) | (3.62, 1.43) | **1.43** |
| | | **Mean** | **1.85 m** |
| | | **Max** | **3.96 m** |

### IMU correction run (for reference — fusion_20250503_103620)

| Waypoint | Error (m) |
|----------|-----------|
| WP1 | 1.16 |
| WP2 | 1.03 |
| WP3 | 10.60 |
| WP4 | 11.47 |
| WP5 | 5.47 |
| **Mean** | **5.95 m** |

---

## 9. Error Analysis

### WP1 / WP2 error (~0.6–1.1 m)
Dominated by **initial heading calibration**. If the trolley is a few degrees off when Start is tapped, the entire trajectory rotates accordingly. WP1 also carries any FTM bias at the starting distance.

### WP3 / WP4 error (2–4 m)
Dominated by **NLoS at the back wall**. At (0, 4) and (3.5, 4) the user's body is between the phone and the AP (AP is at (0,0), back wall is at Y=4). FTM over-estimated true distance 5.32 m as 6.78 m at WP4 — a 1.46 m NLoS bias. Heading drift also accumulates over the longer legs far from the AP.

### Loop closure (WP5 vs WP1)
The end position returns to ~1.3–1.4 m of the start, indicating the trajectory shape is broadly correct — the error is a combination of heading drift and FTM NLoS bias, not a fundamental algorithmic failure.

---

## 10. Key Tuning Parameters

| Parameter | Location | Current Value | Effect |
|-----------|----------|--------------|--------|
| `Q_ALONG` | FusionEstimator | 0.010 m | Process noise along heading per gyro step |
| `Q_LATERAL` | FusionEstimator | 0.002 m | Lateral process noise (keep small) |
| `R_FTM_VAR` | FusionEstimator | 0.25² m² | FTM measurement noise — lower = trust FTM more |
| `MAX_FTM_JUMP_M` | FusionEstimator | 3.0 m | Outlier rejection threshold |
| `ALPHA_CLOSER` | FusionActivity | 1.0 | IIR gain when FTM decreases (LoS) |
| `ALPHA_FARTHER` | FusionActivity | 0.6 | IIR gain when FTM increases (NLoS damping) |
| `RANGING_INTERVAL_MS` | FusionActivity | 100 ms | FTM poll rate |

---

## 11. Limitations & Future Work

1. **Single AP** — distance-only from one AP is geometrically ambiguous without a good heading reference. A second AP would eliminate heading dependence entirely.
2. **Body blockage (NLoS)** — the largest error source on the back leg. Mitigations: carry phone to the side, use a higher AP mounting, or add an NLoS classifier.
3. **Heading sensitivity** — a few degrees of error at Start propagates across the entire run. A short walk before logging could allow auto-calibration of `gyroOffset` from FTM geometry.
4. **FTM calibration range** — current calibration was done at 3.5–5.3 m. Calibrating at longer distances would correct the bias observed at WP4 (~6–9 m range).
5. **No velocity state** — adding velocity to the EKF state (constant-velocity model) would allow better dead-reckoning between FTM updates and smoother trajectories.
