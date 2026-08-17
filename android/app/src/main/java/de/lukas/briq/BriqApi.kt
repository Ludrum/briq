package de.lukas.briq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Where the controller can be. LAN first; Tailscale when away from home. */
data class Host(val label: String, val authority: String) {
    fun url(path: String) = "http://$authority$path"
}

/**
 * Set in `android/briq.properties` (see `briq.properties.example`), not here.
 * The Tailscale entry disappears if that property is left blank.
 */
val HOSTS = listOfNotNull(
    Host("Home Wi-Fi", "${BuildConfig.LAN_HOST}:${BuildConfig.BRIQ_PORT}"),
    BuildConfig.TAILSCALE_HOST.takeIf { it.isNotEmpty() }
        ?.let { Host("Tailscale", "$it:${BuildConfig.BRIQ_PORT}") },
)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val host: Host) : ApiResult<T>
    /** [reason] is the controller's stable slug, not prose. */
    data class Refused(val code: Int, val reason: String, val detail: String) : ApiResult<Nothing>
    data object Unreachable : ApiResult<Nothing>
}

data class Status(
    val profile: String,
    val backendOk: Boolean,
    val dnsOk: Boolean,
    val managedRules: Int,
    val otherRules: String,
    val clients: List<String>,
    /** Seconds in the current profile, on the Pi's clock. -1 if unknown. */
    val sinceS: Long,
    val timerWhenUnbricked: Boolean,
)

/**
 * The cheap read: which profile, and how long it has been that way.
 *
 * The elapsed seconds come from the Pi rather than from a timestamp this
 * phone remembers, because the brick can be triggered from the iPad, a tag,
 * or a schedule while the phone is asleep - and all of them have to agree.
 */
data class Live(val profile: String, val sinceS: Long)

data class ApplyOutcome(
    val profile: String,
    val previous: String,
    val changed: Boolean,
    val debounced: Boolean = false,
    val inFlight: Boolean = false,
)

/**
 * A standing escalation. [time] is the Pi's local clock, not the phone's:
 * the controller owns the schedule and fires it whether or not any client is
 * awake, so a phone in another timezone must show the hour that will actually
 * happen. [daysLabel] and [nextLabel] arrive pre-rendered for the same
 * reason - one implementation of "Mon-Fri", on the side that decides it.
 */
data class Schedule(
    val id: String,
    val profile: String,
    val time: String,
    val days: List<Int>,
    val daysLabel: String,
    val nextLabel: String,
    /** It has run at least once, so removing it now lifts a restriction. */
    val armed: Boolean,
)

data class Schedules(
    val items: List<Schedule> = emptyList(),
    /** Profiles the Pi will accept: the ones that actually block something. */
    val schedulable: List<String> = emptyList(),
)

object BriqApi {

    /**
     * An apply blocks until the Pi has verified the rules are live in the
     * filtering engine, which is tens of seconds and occasionally two
     * minutes. Read and call timeouts are sized for that, not for a normal
     * request; connect stays short so an unreachable host fails fast enough
     * to try the next one.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(160, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Short-deadline client for polling and status, so the UI never hangs. */
    private val quick = client.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private suspend fun <T> attempt(
        slow: Boolean,
        build: (Host) -> Request,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        var refusal: ApiResult.Refused? = null
        for (host in HOSTS) {
            try {
                val call = (if (slow) client else quick).newCall(build(host))
                call.execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        return@withContext ApiResult.Ok(parse(body), host)
                    }
                    // A refusal means we REACHED the Pi. Remember it, but keep
                    // trying other hosts: a stale Tailscale route should not
                    // mask a working LAN. If nothing succeeds, report it.
                    val (reason, detail) = readError(body)
                    refusal = ApiResult.Refused(resp.code, reason, detail)
                }
            } catch (_: Exception) {
                // Wrong network, host down, timeout - try the next address.
            }
        }
        refusal ?: ApiResult.Unreachable
    }

    private fun readError(body: String): Pair<String, String> = try {
        val o = JSONObject(body)
        o.optString("error", "unknown") to o.optString("detail", "")
    } catch (_: Exception) {
        "unknown" to ""
    }

    private fun get(host: Host, path: String) = Request.Builder()
        .url(host.url(path))
        .header("Accept", "application/json")
        .get().build()

    /** Cheap: reads the state file only, no DNS probe, no AdGuard call. */
    suspend fun profile(): ApiResult<Live> =
        attempt(false, { get(it, "/profile.json") }) { body ->
            val o = JSONObject(body)
            Live(o.getString("profile"), o.optLong("since_s", -1L))
        }

    suspend fun status(): ApiResult<Status> =
        attempt(false, { get(it, "/status.json") }) { body ->
            val o = JSONObject(body)
            val arr = o.optJSONArray("clients")
            Status(
                profile = o.optString("profile", "unknown"),
                backendOk = o.optBoolean("backend_ok"),
                dnsOk = o.optBoolean("dns_ok"),
                managedRules = o.optInt("managed_rules"),
                otherRules = o.opt("other_user_rules")?.toString() ?: "?",
                clients = buildList { if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i)) },
                sinceS = o.optLong("since_s", -1L),
                timerWhenUnbricked = o.optJSONObject("settings")
                    ?.optBoolean("timer_when_unbricked", true) ?: true,
            )
        }

    /** Display only. Nonce, no token: it cannot change the brick. */
    suspend fun setTimerWhenUnbricked(on: Boolean): ApiResult<Boolean> {
        val nonce = when (val n = nonce()) {
            is ApiResult.Ok -> n.value
            is ApiResult.Refused -> return n
            ApiResult.Unreachable -> return ApiResult.Unreachable
        }
        return attempt(false, { host ->
            Request.Builder()
                .url(host.url("/settings"))
                .header("Accept", "application/json")
                .post(
                    FormBody.Builder()
                        .add("nonce", nonce)
                        .add("timer_when_unbricked", if (on) "on" else "off")
                        .build()
                )
                .build()
        }) { JSONObject(it).optBoolean("timer_when_unbricked", on) }
    }

    suspend fun profiles(): ApiResult<List<Profile>> =
        attempt(false, { get(it, "/profiles.json") }) { body ->
            val o = JSONObject(body).getJSONObject("profiles")
            o.keys().asSequence().map { name ->
                val p = o.getJSONObject(name)
                Profile(
                    name = name,
                    level = p.getInt("level"),
                    hue = p.getDouble("hue").toFloat(),
                    chroma = p.getDouble("chroma").toFloat(),
                    desc = p.optString("desc"),
                )
            }.sortedWith(compareBy({ it.level }, { it.name })).toList()
        }

    /**
     * What the scanned tag authorises. Returns every profile, including
     * looser ones: holding the tag is the permission.
     */
    suspend fun unlock(token: String): ApiResult<List<Profile>> =
        attempt(false, { get(it, "/unlock/$token") }) { body ->
            val arr = JSONObject(body).getJSONArray("profiles")
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Profile(
                    name = p.getString("name"),
                    level = p.getInt("level"),
                    hue = p.getDouble("hue").toFloat(),
                    chroma = p.getDouble("chroma").toFloat(),
                    desc = p.optString("desc"),
                )
            }
        }

    suspend fun schedules(): ApiResult<Schedules> =
        attempt(false, { get(it, "/schedules.json") }) { body ->
            val o = JSONObject(body)
            val arr = o.getJSONArray("schedules")
            val can = o.optJSONArray("schedulable")
            Schedules(
                items = (0 until arr.length()).map { i -> readSchedule(arr.getJSONObject(i)) },
                schedulable = buildList {
                    if (can != null) for (i in 0 until can.length()) add(can.getString(i))
                },
            )
        }

    /**
     * Arm a future escalation. Token-free like [escalate], and for the same
     * reason: a schedule can only ever tighten. The Pi refuses one that
     * would not.
     */
    suspend fun addSchedule(
        profile: String, hour: Int, minute: Int, days: Set<Int>,
    ): ApiResult<Schedule> {
        val nonce = when (val n = nonce()) {
            is ApiResult.Ok -> n.value
            is ApiResult.Refused -> return n
            ApiResult.Unreachable -> return ApiResult.Unreachable
        }
        return attempt(false, { host ->
            Request.Builder()
                .url(host.url("/schedules"))
                .header("Accept", "application/json")
                .post(
                    FormBody.Builder()
                        .add("nonce", nonce)
                        .add("profile", profile)
                        .add("time", "%02d:%02d".format(hour, minute))
                        .add("days", days.sorted().joinToString(","))
                        .build()
                )
                .build()
        }) { readSchedule(JSONObject(it)) }
    }

    /**
     * Remove one. [token] is needed only once the schedule has run - the Pi
     * decides that, and answers `needs_tag` when it is missing, so the app
     * never has to predict the rule.
     */
    suspend fun deleteSchedule(id: String, token: String?): ApiResult<Unit> {
        val form = FormBody.Builder()
        val path = if (token != null) {
            "/schedules/$id/delete/$token"
        } else {
            when (val n = nonce()) {
                is ApiResult.Ok -> form.add("nonce", n.value)
                is ApiResult.Refused -> return n
                ApiResult.Unreachable -> return ApiResult.Unreachable
            }
            "/schedules/$id/delete"
        }
        return attempt(false, { host ->
            Request.Builder()
                .url(host.url(path))
                .header("Accept", "application/json")
                .post(form.build())
                .build()
        }) { }
    }

    private fun readSchedule(o: JSONObject): Schedule {
        val d = o.optJSONArray("days")
        return Schedule(
            id = o.getString("id"),
            profile = o.getString("profile"),
            time = o.optString("time"),
            days = buildList { if (d != null) for (i in 0 until d.length()) add(d.getInt(i)) },
            daysLabel = o.optString("days_label"),
            nextLabel = o.optString("next_label"),
            armed = o.optBoolean("armed"),
        )
    }

    /** Single-use, and worthless on its own: it cannot authorise a loosening. */
    private suspend fun nonce(): ApiResult<String> =
        attempt(false, { get(it, "/nonce") }) { JSONObject(it).getString("nonce") }

    /** Token-free, and the Pi refuses anything that is not strictly stricter. */
    suspend fun escalate(target: String): ApiResult<ApplyOutcome> {
        val nonce = when (val n = nonce()) {
            is ApiResult.Ok -> n.value
            is ApiResult.Refused -> return n
            ApiResult.Unreachable -> return ApiResult.Unreachable
        }
        return attempt(true, { host ->
            Request.Builder()
                .url(host.url("/escalate/$target"))
                .header("Accept", "application/json")
                .post(FormBody.Builder().add("nonce", nonce).build())
                .build()
        }, ::parseApply)
    }

    /** Absolute selection. Needs the token, so only ever after a tag scan. */
    suspend fun select(target: String, token: String): ApiResult<ApplyOutcome> =
        attempt(true, { host ->
            Request.Builder()
                .url(host.url("/select/$target/$token"))
                .header("Accept", "application/json")
                .post(FormBody.Builder().add("confirm", "yes").build())
                .build()
        }, ::parseApply)

    private fun parseApply(body: String): ApplyOutcome {
        val o = JSONObject(body)
        return ApplyOutcome(
            profile = o.optString("profile", "unknown"),
            previous = o.optString("previous", ""),
            changed = o.optBoolean("changed", false),
            debounced = o.optBoolean("debounced", false),
            inFlight = o.optBoolean("in_flight", false),
        )
    }
}
