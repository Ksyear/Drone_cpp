package kr.ac.rb022

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class MissionState { IDLE, PENDING, EXECUTING, ARRIVED, CANCELLED, LANDING, FAULT }

/** Simulation defaults. None of these are flight-validated limits for the real airframe. */
data class MissionLimits(
    val fence: Geofence = Geofence(),
    val minAltitude: Double = 0.3,
    val maxAltitude: Double = 1.5,
    val arriveRadius: Double = 0.15,
    val arriveSpeed: Double = 0.10,
    val arriveDwellMs: Long = 1000,
    val maxSpeedXY: Double = 0.3,
    val maxSpeedZ: Double = 0.2,
    val maxTiltDeg: Double = 8.0,
    val poseStaleMs: Long = 250,
    val maxSigma: Double = 0.05,
    val linkStaleMs: Long = 1500,
    val pendingTtlMs: Long = 30_000,
)

/** ageAtReceiptMs is measured on the sender's own clock (capture to send); receivedMs is hub-local monotonic. */
data class PoseSample(val w: Vec3, val yaw: Double, val sigma: Double, val receivedMs: Long, val ageAtReceiptMs: Long, val source: String)

data class ControlOutput(val forward: Double, val right: Double, val throttle: Double, val motorsActive: Boolean, val rc: Rc150?)

data class MissionSnapshot(
    val state: MissionState, val reason: String, val goalId: String?, val goal: Vec3?, val pose: PoseSample?,
    val poseAgeMs: Long?, val poseValid: Boolean, val velocity: Vec3, val remaining: Double?, val path: List<Vec3>,
    val output: ControlOutput, val limits: MissionLimits,
)

/** Mission state machine and position/velocity outer loop. Output only feeds the simulator; BLE TX stays locked. */
class MissionController(val limits: MissionLimits = MissionLimits(), private val hoverThrottle: Double = 0.5) {
    var state = MissionState.IDLE; private set
    var reason = ""; private set
    var goalId: String? = null; private set
    var goal: Vec3? = null; private set
    private var pose: PoseSample? = null
    private var estimate: Vec3? = null
    private var velocity = Vec3.ZERO
    private var hold: Vec3? = null
    private var stateSinceMs = 0L
    private var deadlineMs = 0L
    private var arrivedSinceMs: Long? = null
    private var linkSeenMs: Long? = null
    private var throttleIntegral = 0.0
    var output = ControlOutput(0.0, 0.0, 0.0, false, null); private set

    fun poseAgeMs(now: Long) = pose?.let { now - it.receivedMs + it.ageAtReceiptMs }
    fun poseValid(now: Long) = pose?.let { poseAgeMs(now)!! <= limits.poseStaleMs && it.sigma <= limits.maxSigma } ?: false
    private fun airborne() = (estimate?.z ?: 0.0) > 0.1

    fun linkAlive(now: Long) { linkSeenMs = now }
    private fun linkFresh(now: Long) = linkSeenMs?.let { now - it <= limits.linkStaleMs } ?: false

    fun pose(sample: PoseSample) {
        require(sample.w.finite() && sample.yaw.isFinite() && sample.sigma.isFinite() && sample.sigma >= 0) { "pose-non-finite" }
        val previous = pose
        val last = estimate
        if (previous == null || last == null || sample.receivedMs - previous.receivedMs !in 1..500) {
            estimate = sample.w; velocity = Vec3.ZERO
        } else {
            val dt = (sample.receivedMs - previous.receivedMs) / 1000.0
            val predicted = last + velocity * dt
            val residual = sample.w - predicted
            estimate = predicted + residual * 0.4
            velocity = velocity + residual * (0.08 / dt)
        }
        pose = sample
    }

    fun submitGoal(id: String, target: Vec3, now: Long): String? {
        if (state !in setOf(MissionState.IDLE, MissionState.PENDING, MissionState.ARRIVED, MissionState.CANCELLED)) return "busy-${state.name.lowercase()}"
        if (!target.finite()) return "non-finite"
        if (!limits.fence.contains(target)) return "outside-geofence"
        if (target.z !in limits.minAltitude..limits.maxAltitude) return "altitude-out-of-range"
        goalId = id; goal = target
        enter(MissionState.PENDING, "awaiting-execute", now)
        return null
    }

    fun execute(id: String, now: Long): String? {
        if (state != MissionState.PENDING) return "no-pending-goal"
        if (id != goalId) return "goal-mismatch"
        if (!linkFresh(now)) return "link-stale"
        if (!poseValid(now)) return "pose-invalid"
        val start = estimate!!
        val distance = (goal!! - start).norm()
        deadlineMs = now + (distance / min(limits.maxSpeedXY, limits.maxSpeedZ) * 3000).toLong() + 15_000
        arrivedSinceMs = null
        throttleIntegral = 0.0
        enter(MissionState.EXECUTING, "moving", now)
        return null
    }

    fun cancel(id: String, now: Long): String? = when {
        id != goalId -> "goal-mismatch"
        state == MissionState.PENDING -> { rest("cancelled", now); null }
        state == MissionState.EXECUTING -> { hold = estimate; enter(MissionState.CANCELLED, "cancelled", now); null }
        state == MissionState.CANCELLED || (state == MissionState.IDLE && reason == "cancelled") -> null
        else -> "not-cancellable"
    }

    /** Leave a goal without moving: hover in place when airborne, otherwise stay on the ground. */
    private fun rest(why: String, now: Long) {
        if (airborne()) { hold = hold ?: estimate; enter(MissionState.CANCELLED, why, now) } else enter(MissionState.IDLE, why, now)
    }

    fun land(now: Long): String? {
        if (state !in setOf(MissionState.ARRIVED, MissionState.CANCELLED, MissionState.FAULT)) return "not-hovering"
        hold = estimate ?: hold
        enter(MissionState.LANDING, "user-landing", now)
        return null
    }

    fun tick(now: Long): ControlOutput {
        val valid = poseValid(now)
        val hovering = state in setOf(MissionState.ARRIVED, MissionState.CANCELLED) || (state == MissionState.PENDING && airborne())
        when {
            hovering && !valid -> fault(poseFault(now), now)
            // shortcut: auto-landing on link loss is the simulation policy; the user can no longer command a landing.
            hovering && !linkFresh(now) -> enter(MissionState.LANDING, "link-lost-auto-landing", now)
            state == MissionState.FAULT && reason == "link-lost" && now - stateSinceMs > limits.linkStaleMs && !linkFresh(now) ->
                enter(MissionState.LANDING, "link-lost-auto-landing", now)
        }
        when (state) {
            MissionState.PENDING -> when {
                now - stateSinceMs > limits.pendingTtlMs -> rest("goal-expired", now)
                !linkFresh(now) -> rest("link-lost", now)
            }
            MissionState.EXECUTING -> {
                val p = estimate
                when {
                    !valid -> fault(poseFault(now), now)
                    !linkFresh(now) -> fault("link-lost", now)
                    // floor-level pose noise below z=0 is not a fence breach
                    p != null && !limits.fence.contains(p.copy(z = max(p.z, limits.fence.min.z))) -> fault("geofence", now)
                    now > deadlineMs -> fault("timeout", now)
                    p != null && (goal!! - p).norm() < limits.arriveRadius && velocity.norm() < limits.arriveSpeed -> {
                        val since = arrivedSinceMs ?: now.also { arrivedSinceMs = it }
                        if (now - since >= limits.arriveDwellMs) { hold = goal; enter(MissionState.ARRIVED, "hovering-await-user-landing", now) }
                    }
                    else -> arrivedSinceMs = null
                }
            }
            MissionState.LANDING -> if (valid && (estimate?.z ?: 1.0) < 0.05 && abs(velocity.z) < 0.05) {
                goalId = null; goal = null; enter(MissionState.IDLE, "landed", now)
            }
            else -> Unit
        }
        output = control(now, poseValid(now))
        return output
    }

    private fun fault(why: String, now: Long) { hold = estimate; enter(MissionState.FAULT, why, now) }

    private fun poseFault(now: Long) = if (pose == null || poseAgeMs(now)!! > limits.poseStaleMs) "pose-stale" else "pose-uncertain"

    private fun enter(next: MissionState, why: String, now: Long) { state = next; reason = why; stateSinceMs = now }

    private fun control(now: Long, valid: Boolean): ControlOutput {
        val p = estimate
        val flying = state in setOf(MissionState.EXECUTING, MissionState.ARRIVED, MissionState.CANCELLED, MissionState.LANDING, MissionState.FAULT) ||
            (state == MissionState.PENDING && airborne())
        if (!flying || (p == null && state != MissionState.FAULT)) return ControlOutput(0.0, 0.0, 0.0, false, null)
        if (!valid || p == null) {
            // shortcut: open-loop level descent is a simulation policy only; real loss-of-position behaviour is unverified.
            if (state == MissionState.FAULT && !airborne()) return ControlOutput(0.0, 0.0, 0.0, false, null)
            val thr = hoverThrottle - 0.05
            return ControlOutput(0.0, 0.0, thr, true, Rc150.fromAcceleration(0.0, 0.0, thr, limits.maxTiltDeg))
        }
        if (state == MissionState.LANDING && p.z < 0.05) return ControlOutput(0.0, 0.0, 0.0, false, null)
        val target = if (state == MissionState.EXECUTING) goal!! else hold ?: p
        val error = target - p
        var vx = 0.8 * error.x; var vy = 0.8 * error.y
        val h = sqrt(vx * vx + vy * vy)
        if (h > limits.maxSpeedXY) { vx *= limits.maxSpeedXY / h; vy *= limits.maxSpeedXY / h }
        var vz = (0.8 * error.z).coerceIn(-limits.maxSpeedZ, limits.maxSpeedZ)
        if (state == MissionState.LANDING) vz = -0.15
        if (p.z < 0.15 && state != MissionState.LANDING) { vx = 0.0; vy = 0.0 }
        val maxAccel = 9.81 * tan(Math.toRadians(limits.maxTiltDeg))
        var ax = 2.0 * (vx - velocity.x); var ay = 2.0 * (vy - velocity.y)
        val a = sqrt(ax * ax + ay * ay)
        if (a > maxAccel) { ax *= maxAccel / a; ay *= maxAccel / a }
        val yaw = pose!!.yaw
        val forward = ax * cos(yaw) + ay * sin(yaw)
        val right = ax * sin(yaw) - ay * cos(yaw)
        throttleIntegral = (throttleIntegral + 0.004 * (vz - velocity.z)).coerceIn(-0.15, 0.15)
        val throttle = (hoverThrottle + 0.25 * (vz - velocity.z) + throttleIntegral).coerceIn(0.2, 0.8)
        return ControlOutput(forward, right, throttle, true, Rc150.fromAcceleration(forward, right, throttle, limits.maxTiltDeg))
    }

    fun path(): List<Vec3> {
        val g = goal ?: return emptyList()
        val p = estimate ?: return listOf(g)
        return if (p.z < 0.15 && state in setOf(MissionState.PENDING, MissionState.EXECUTING)) listOf(p, Vec3(p.x, p.y, g.z), g) else listOf(p, g)
    }

    fun snapshot(now: Long) = MissionSnapshot(state, reason, goalId, goal, pose, poseAgeMs(now), poseValid(now), velocity,
        goal?.let { g -> estimate?.let { (g - it).norm() } }, path(), output, limits)
}

/** Kinematic quadrotor stand-in: tilt lag, drag, ground contact, delayed noisy pose. Not an aerodynamic model. */
class SimulatedDrone(seed: Long = 7, private val trueHover: Double = 0.52, private val delayMs: Long = 60, private val noise: Double = 0.004) {
    var position = Vec3.ZERO; private set
    private var velocity = Vec3.ZERO
    private var pitch = 0.0
    private var roll = 0.0
    private val random = Random(seed)
    private val history = ArrayDeque<Pair<Long, Vec3>>()

    fun step(output: ControlOutput, now: Long, dtMs: Long) {
        val dt = dtMs / 1000.0
        val wantPitch = if (output.motorsActive) kotlin.math.atan(output.forward / 9.81) else 0.0
        val wantRoll = if (output.motorsActive) kotlin.math.atan(output.right / 9.81) else 0.0
        val k = min(1.0, dt / 0.12)
        pitch += (wantPitch - pitch) * k
        roll += (wantRoll - roll) * k
        val onGround = position.z <= 0.0
        val az = if (output.motorsActive) (output.throttle - trueHover) * 20.0 - 1.0 * velocity.z else -9.81
        // yaw fixed at 0: body forward = +X_W, right = -Y_W
        var ax = 9.81 * tan(pitch) - 0.4 * velocity.x
        var ay = -9.81 * tan(roll) - 0.4 * velocity.y
        if (onGround && az <= 0) { ax = 0.0; ay = 0.0 }
        velocity = Vec3(velocity.x + ax * dt, velocity.y + ay * dt, velocity.z + az * dt)
        position += velocity * dt
        if (position.z <= 0.0) { position = Vec3(position.x, position.y, 0.0); velocity = if (az <= 0) Vec3.ZERO else Vec3(velocity.x, velocity.y, max(0.0, velocity.z)) }
        history.addLast(now to position)
        while (history.size > 2 && history[1].first <= now - delayMs) history.removeFirst()
    }

    /** Pose as an external tracker would report it: delayMs late with Gaussian noise. */
    fun observe(now: Long): PoseSample {
        val seen = history.firstOrNull()?.second ?: position
        val noisy = Vec3(seen.x + random.nextGaussian() * noise, seen.y + random.nextGaussian() * noise, seen.z + random.nextGaussian() * noise)
        return PoseSample(noisy, 0.0, noise * 3, now, delayMs, "simulation")
    }
}
