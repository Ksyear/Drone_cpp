package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Test

class MissionControllerTest {
    private class Rig {
        var now = 1_000L
        val controller = MissionController()
        val drone = SimulatedDrone()
        var feedPose = true
        var link = true
        var maxTilt = 0.0

        fun run(ms: Long, until: () -> Boolean = { false }): Boolean {
            val end = now + ms
            while (now < end) {
                now += 20
                drone.step(controller.output, now, 20)
                if (feedPose) controller.pose(drone.observe(now))
                if (link) controller.linkAlive(now)
                controller.tick(now)
                controller.output.rc?.let { maxTilt = maxOf(maxTilt, kotlin.math.abs(it.pitch - 125) * Rc150.DEGREES_PER_BYTE, kotlin.math.abs(it.roll - 125) * Rc150.DEGREES_PER_BYTE) }
                if (until()) return true
            }
            return false
        }

        fun ready() { run(200) }
    }

    @Test fun goalRequiresExplicitExecuteThenArrivesAndHovers() {
        val r = Rig().apply { ready() }
        val goal = Vec3(1.2, -0.6, 0.8)
        assertNull(r.controller.submitGoal("g1", goal, r.now))
        assertEquals(MissionState.PENDING, r.controller.state)
        r.run(2000)
        assertEquals("pending goal must not move the drone", 0.0, r.drone.position.norm(), 1e-9)
        assertEquals(3, r.controller.path().size)  // takeoff segment + leg
        assertEquals("goal-mismatch", r.controller.execute("other", r.now))
        assertNull(r.controller.execute("g1", r.now))
        assertTrue("did not arrive", r.run(40_000) { r.controller.state == MissionState.ARRIVED })
        assertTrue((r.drone.position - goal).norm() < r.controller.limits.arriveRadius + 0.05)
        assertTrue("tilt limit exceeded: ${r.maxTilt}", r.maxTilt <= r.controller.limits.maxTiltDeg + Rc150.DEGREES_PER_BYTE)
        r.run(3000)
        assertEquals(MissionState.ARRIVED, r.controller.state)
        assertTrue("hover drifted", (r.drone.position - goal).norm() < 0.2)
        assertNull(r.controller.land(r.now))
        assertTrue(r.run(20_000) { r.controller.state == MissionState.IDLE })
        assertEquals("landed", r.controller.reason)
        assertTrue(r.drone.position.z < 0.05)
    }

    @Test fun cancelHoldsAndIsIdempotent() {
        val r = Rig().apply { ready() }
        r.controller.submitGoal("g", Vec3(1.5, 1.5, 1.0), r.now)
        r.controller.execute("g", r.now)
        r.run(5000)
        assertNull(r.controller.cancel("g", r.now))
        assertNull(r.controller.cancel("g", r.now))
        assertEquals("goal-mismatch", r.controller.cancel("h", r.now))
        val at = r.drone.position
        r.run(4000)
        assertEquals(MissionState.CANCELLED, r.controller.state)
        assertTrue("cancel must stop progress", (r.drone.position - at).horizontal() < 0.35)
    }

    @Test fun stalePoseFaultsAndDoesNotKeepFlyingToGoal() {
        val r = Rig().apply { ready() }
        r.controller.submitGoal("g", Vec3(1.5, 0.0, 0.8), r.now)
        r.controller.execute("g", r.now)
        r.run(5000)
        r.feedPose = false
        r.run(400)
        assertEquals(MissionState.FAULT, r.controller.state)
        assertEquals("pose-stale", r.controller.reason)
        assertEquals(0.0, r.controller.output.forward, 0.0)
        assertEquals(0.0, r.controller.output.right, 0.0)
        assertEquals("busy-fault", r.controller.submitGoal("g2", Vec3(0.0, 0.0, 0.8), r.now))
    }

    @Test fun linkLossFaultsThenAutoLands() {
        val r = Rig().apply { ready() }
        r.controller.submitGoal("g", Vec3(0.5, 0.5, 0.8), r.now)
        r.controller.execute("g", r.now)
        r.run(4000)
        r.link = false
        r.run(1700)
        assertEquals(MissionState.FAULT, r.controller.state)
        assertEquals("link-lost", r.controller.reason)
        assertTrue(r.run(30_000) { r.controller.state == MissionState.IDLE })
        assertTrue(r.drone.position.z < 0.05)
    }

    @Test fun rejectsGoalsOutsideLimitsAndExecuteWithoutPreconditions() {
        val r = Rig()
        assertEquals("outside-geofence", r.controller.submitGoal("g", Vec3(5.0, 0.0, 1.0), r.now))
        assertEquals("altitude-out-of-range", r.controller.submitGoal("g", Vec3(0.0, 0.0, 0.1), r.now))
        assertEquals("non-finite", r.controller.submitGoal("g", Vec3(Double.NaN, 0.0, 1.0), r.now))
        assertEquals("no-pending-goal", r.controller.execute("g", r.now))
        assertNull(r.controller.submitGoal("g", Vec3(0.5, 0.0, 1.0), r.now))
        assertEquals("link-stale", r.controller.execute("g", r.now))
        r.controller.linkAlive(r.now)
        assertEquals("pose-invalid", r.controller.execute("g", r.now))
        r.controller.pose(PoseSample(Vec3.ZERO, 0.0, 0.5, r.now, 0, "test"))
        assertEquals("uncertain pose must block execution", "pose-invalid", r.controller.execute("g", r.now))
    }

    @Test fun pendingGoalExpires() {
        val r = Rig().apply { ready() }
        r.controller.submitGoal("g", Vec3(0.5, 0.0, 1.0), r.now)
        r.run(r.controller.limits.pendingTtlMs + 100)
        assertEquals(MissionState.IDLE, r.controller.state)
        assertEquals("goal-expired", r.controller.reason)
        assertEquals("no-pending-goal", r.controller.execute("g", r.now))
    }

    @Test fun secondGoalWhileHoveringKeepsAltitudeUntilExecuteAndReportsMetrics() {
        val r = Rig().apply { ready() }
        r.controller.submitGoal("a", Vec3(-1.0, 1.0, 1.0), r.now)
        r.controller.execute("a", r.now)
        val start = r.now
        assertTrue(r.run(40_000) { r.controller.state == MissionState.ARRIVED })
        val firstLegMs = r.now - start
        val goal = Vec3(1.0, -1.0, 0.6)
        assertNull(r.controller.submitGoal("b", goal, r.now))
        r.run(3000)
        assertEquals(MissionState.PENDING, r.controller.state)
        assertTrue("pending while airborne must hover, z=${r.drone.position.z}", r.drone.position.z > 0.8)
        r.controller.execute("b", r.now)
        val legStart = r.now
        var closest = Double.MAX_VALUE
        var overshoot = 0.0
        val toGoal = goal - r.drone.position
        assertTrue(r.run(40_000) {
            val d = goal - r.drone.position
            closest = minOf(closest, d.norm())
            overshoot = maxOf(overshoot, -(d.x * toGoal.x + d.y * toGoal.y) / toGoal.horizontal())
            r.controller.state == MissionState.ARRIVED
        })
        val error = (r.drone.position - goal).norm()
        // Simulation metrics only: kinematic model, 60 ms pose delay, 4 mm noise, no wind or battery sag.
        println("SIM_METRICS firstLegMs=$firstLegMs secondLegMs=${r.now - legStart} arrivalErrorM=%.3f overshootM=%.3f maxTiltDeg=%.2f"
            .format(error, overshoot, r.maxTilt))
        assertTrue(error < 0.2)
    }

    @Test fun oldPoseAgeIsCountedEvenWhenJustReceived() {
        val c = MissionController()
        c.pose(PoseSample(Vec3.ZERO, 0.0, 0.01, 1000, 300, "late"))
        assertFalse(c.poseValid(1000))
        assertThrows(IllegalArgumentException::class.java) { c.pose(PoseSample(Vec3(0.0, Double.POSITIVE_INFINITY, 0.0), 0.0, 0.01, 1000, 0, "x")) }
    }
}
