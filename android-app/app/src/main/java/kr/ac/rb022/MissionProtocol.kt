package kr.ac.rb022

import java.security.SecureRandom

/**
 * Mission channel v1, separate from MSP. One paired client; every command carries session + strictly increasing seq.
 * Freshness uses only this hub's monotonic clock plus the sender-measured ageMs; peer timestamps are never compared.
 */
class MissionSession(
    private val controller: MissionController,
    private val clock: () -> Long,
    random: SecureRandom = SecureRandom(),
) {
    private val rng = random
    val pairingCode: String = "%06d".format(rng.nextInt(1_000_000))
    private var session: String? = null
    private var lastSeq = 0L
    private var lastResponse = ""
    private var failures = 0
    private var lockedUntil = 0L
    var alignment: Alignment? = null; private set
    var external: PoseSample? = null; private set
    var commandCount = 0L; private set

    @Synchronized
    fun hello(body: String): Pair<Int, String> {
        val now = clock()
        if (now < lockedUntil) return 429 to error("pairing-locked")
        val message = try { MissionJson.parseObject(body) } catch (e: IllegalArgumentException) { return 400 to error(e.message ?: "bad-json") }
        if (message["v"] != 1.0 || message["type"] != "hello") return 400 to error("bad-hello")
        if (message["code"] != pairingCode) {
            if (++failures >= 5) { failures = 0; lockedUntil = now + 30_000 }
            return 403 to error("bad-code")
        }
        if (controller.state in setOf(MissionState.EXECUTING, MissionState.LANDING)) return 409 to error("busy-mission-active")
        failures = 0
        session = ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
        lastSeq = 0
        lastResponse = ""
        controller.linkAlive(now)
        return 200 to "{\"ok\":true,\"v\":1,\"session\":${MissionJson.quote(session!!)}}"
    }

    @Synchronized
    fun authorized(header: String?): Boolean = session != null && header == session

    /** Authenticated state polls double as the client heartbeat. */
    @Synchronized
    fun touch() = controller.linkAlive(clock())

    @Synchronized
    fun command(header: String?, body: String): Pair<Int, String> {
        val now = clock()
        if (!authorized(header)) return 401 to error("unauthorized")
        val message = try { MissionJson.parseObject(body) } catch (e: IllegalArgumentException) { return 400 to error(e.message ?: "bad-json") }
        if (message["v"] != 1.0) return 400 to error("unsupported-version")
        if (message["session"] != session) return 401 to error("session-mismatch")
        val seq = (message["seq"] as? Double)?.takeIf { it % 1.0 == 0.0 && it in 1.0..9.0e15 }?.toLong()
            ?: return 400 to error("bad-seq")
        if (seq == lastSeq) return 200 to lastResponse
        if (seq < lastSeq) return 409 to error("stale-seq")
        controller.linkAlive(now)
        val response = try { dispatch(message, now) } catch (e: IllegalArgumentException) { reject(message, e.message ?: "invalid") }
        lastSeq = seq
        lastResponse = response
        commandCount++
        return 200 to response
    }

    private fun dispatch(m: Map<String, Any?>, now: Long): String {
        val type = m["type"] as? String ?: throw IllegalArgumentException("bad-type")
        return when (type) {
            "heartbeat" -> ack(m, null)
            "goal" -> {
                require(m["arrival"] == null || m["arrival"] == "hover") { "unsupported-arrival" }
                var target = Vec3.of(m["position"])
                when (m["frame"]) {
                    "W" -> Unit
                    "Q" -> {
                        val active = alignment ?: throw IllegalArgumentException("no-alignment")
                        require(m["alignmentId"] == active.id) { "alignment-mismatch" }
                        target = active.toW(target)
                    }
                    else -> throw IllegalArgumentException("bad-frame")
                }
                (m["altitude"] as? Double)?.let { target = target.copy(z = it) }
                ack(m, controller.submitGoal(id(m["goalId"]), target, now))
            }
            "execute" -> ack(m, controller.execute(id(m["goalId"]), now))
            "cancel" -> ack(m, controller.cancel(id(m["goalId"]), now))
            "land" -> ack(m, controller.land(now))
            "align" -> {
                require(controller.state !in setOf(MissionState.EXECUTING, MissionState.PENDING)) { "busy-mission-active" }
                val pairs = (m["pairs"] as? List<*>)?.map { row ->
                    val v = (row as? List<*>)?.map { it as? Double ?: throw IllegalArgumentException("bad-pair") }
                    require(v != null && v.size == 6) { "bad-pair" }
                    Vec3(v[0], v[1], v[2]) to Vec3(v[3], v[4], v[5])
                } ?: throw IllegalArgumentException("bad-pairs")
                alignment = Alignment.estimate(id(m["alignmentId"]), pairs)
                ack(m, null)
            }
            "pose" -> {
                // Recorded for evaluation only: control keeps the simulator until a position source is validated.
                var w = Vec3.of(m["position"])
                if (m["frame"] == "Q") {
                    val active = alignment ?: throw IllegalArgumentException("no-alignment")
                    require(m["alignmentId"] == active.id) { "alignment-mismatch" }
                    w = active.toW(w)
                } else require(m["frame"] == "W") { "bad-frame" }
                val sigma = m["sigma"] as? Double ?: throw IllegalArgumentException("bad-sigma")
                val age = (m["ageMs"] as? Double)?.takeIf { it in 0.0..1000.0 } ?: throw IllegalArgumentException("bad-age")
                require(sigma in 0.0..1.0) { "bad-sigma" }
                external = PoseSample(w, (m["yaw"] as? Double) ?: 0.0, sigma, now, age.toLong(), "external-observation")
                ack(m, null)
            }
            else -> throw IllegalArgumentException("unknown-type")
        }
    }

    private fun id(value: Any?): String {
        val text = value as? String ?: throw IllegalArgumentException("bad-id")
        require(Regex("[A-Za-z0-9._-]{1,40}").matches(text)) { "bad-id" }
        return text
    }

    private fun ack(m: Map<String, Any?>, rejection: String?): String = if (rejection == null)
        "{\"ok\":true,\"type\":${MissionJson.quote(m["type"] as String)},\"result\":\"accepted\",${echo(m)}\"state\":\"${controller.state}\"}"
    else reject(m, rejection)

    private fun reject(m: Map<String, Any?>, why: String) =
        "{\"ok\":false,\"result\":\"rejected\",${echo(m)}\"reason\":${MissionJson.quote(why)},\"state\":\"${controller.state}\"}"

    private fun echo(m: Map<String, Any?>) = (m["goalId"] as? String)?.takeIf { Regex("[A-Za-z0-9._-]{1,40}").matches(it) }
        ?.let { "\"goalId\":${MissionJson.quote(it)}," } ?: ""

    private fun error(why: String) = "{\"ok\":false,\"reason\":${MissionJson.quote(why)}}"

    @Synchronized
    fun stateJson(videoAgeMs: Long?, lockReasons: List<String>): String {
        val now = clock()
        val s = controller.snapshot(now)
        val pose = s.pose
        val a = alignment
        val ext = external
        return buildString {
            append("{\"v\":1,\"state\":\"${s.state}\",\"reason\":${MissionJson.quote(s.reason)},\"mode\":\"simulation\"")
            append(",\"goalId\":").append(s.goalId?.let(MissionJson::quote) ?: "null")
            append(",\"goal\":").append(s.goal?.let(MissionJson::vector) ?: "null")
            append(",\"pose\":").append(if (pose == null) "null" else
                "{\"w\":${MissionJson.vector(pose.w)},\"source\":${MissionJson.quote(pose.source)},\"ageMs\":${s.poseAgeMs},\"sigma\":${MissionJson.number(pose.sigma)},\"valid\":${s.poseValid}}")
            append(",\"speed\":${MissionJson.number(s.velocity.norm())}")
            append(",\"remaining\":").append(s.remaining?.let(MissionJson::number) ?: "null")
            append(",\"path\":[${s.path.joinToString(",", transform = MissionJson::vector)}]")
            append(",\"rcPreview\":").append(s.output.rc?.let { "[${it.roll},${it.pitch},${it.yaw},${it.throttle},${it.aux}]" } ?: "null")
            append(",\"alignment\":").append(if (a == null) "null" else "{\"id\":${MissionJson.quote(a.id)},\"rms\":${MissionJson.number(a.rms)}}")
            append(",\"external\":").append(if (ext == null) "null" else
                "{\"w\":${MissionJson.vector(ext.w)},\"ageMs\":${now - ext.receivedMs + ext.ageAtReceiptMs},\"sigma\":${MissionJson.number(ext.sigma)}}")
            append(",\"fence\":{\"min\":${MissionJson.vector(s.limits.fence.min)},\"max\":${MissionJson.vector(s.limits.fence.max)}}")
            append(",\"altitude\":[${MissionJson.number(s.limits.minAltitude)},${MissionJson.number(s.limits.maxAltitude)}]")
            append(",\"videoAgeMs\":").append(videoAgeMs?.toString() ?: "null")
            append(",\"realOutputLocked\":[${lockReasons.joinToString(",", transform = MissionJson::quote)}]")
            append(",\"lastSeq\":$lastSeq}")
        }
    }
}
