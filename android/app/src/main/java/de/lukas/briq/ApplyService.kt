package de.lukas.briq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs one profile change to completion.
 *
 * This is a foreground service rather than a coroutine in the Activity for
 * one reason: applying takes 20-60s and the user is expected to pocket the
 * phone during it. A background process would be frozen or killed and the
 * request would die halfway; a foreground service is allowed to finish and
 * to notify when it does.
 *
 * The POST is only half the story. If the connection drops mid-apply the Pi
 * carries on regardless - the handler thread is already committed - so a
 * poll of /profile.json runs alongside and is treated as the real answer.
 */
class ApplyService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        const val CHANNEL_PROGRESS = "briq.progress"
        const val CHANNEL_DONE = "briq.done"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_RESULT = 2

        const val EXTRA_TARGET = "target"
        const val EXTRA_TOKEN = "token"

        fun start(ctx: Context, target: String, token: String?) {
            val i = Intent(ctx, ApplyService::class.java)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_TOKEN, token)
            ctx.startForegroundService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Applying", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the Pi switches profile." }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Profile changed", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tells you the new profile is actually live." }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY.also { stopSelf() }
        val token = intent.getStringExtra(EXTRA_TOKEN)

        startForeground(NOTIF_ONGOING, ongoing(target), fgType())
        BriqStore.update {
            it.copy(phase = Phase.Applying(target, System.currentTimeMillis()), lastResult = null)
        }

        scope.launch {
            val poller = launch { pollUntil(target) }
            val result = if (token != null) BriqApi.select(target, token) else BriqApi.escalate(target)
            poller.cancel()
            finish(target, result)
        }
        return START_REDELIVER_INTENT
    }

    /**
     * Watches the Pi's own view of the world. This is what makes a dropped
     * connection survivable: if /profile.json reports the target before the
     * POST returns, the change really did happen.
     */
    private suspend fun pollUntil(target: String) {
        delay(6_000)
        while (scope.isActive) {
            when (val r = BriqApi.profile()) {
                is ApiResult.Ok -> {
                    BriqStore.update {
                        it.copy(current = r.value.profile, host = r.host)
                            .since(r.value.sinceS)
                    }
                    if (r.value.profile == target) return
                }
                else -> Unit
            }
            delay(3_000)
        }
    }

    private suspend fun finish(target: String, result: ApiResult<ApplyOutcome>) {
        // Whatever happened, the Pi is the authority on where we ended up.
        val settled = (BriqApi.profile() as? ApiResult.Ok)?.value

        val (title, text) = when (result) {
            is ApiResult.Ok -> {
                val o = result.value
                BriqStore.update {
                    it.copy(current = settled?.profile ?: o.profile, phase = Phase.Idle,
                        unlockToken = null, unlockChoices = null, lastResult = null)
                        // The clock restarts here, so the home screen is not
                        // still counting the profile you just left.
                        .since(settled?.sinceS ?: 0L)
                }
                when {
                    o.debounced && o.inFlight ->
                        "Already switching" to "That scan was a repeat — the first one is still applying."
                    o.debounced ->
                        "Repeat scan ignored" to "A double tap cannot undo itself."
                    !o.changed ->
                        "${prettify(o.profile)} was already set" to descOf(o.profile)
                    else ->
                        "${prettify(o.profile)} is live" to descOf(o.profile)
                }
            }
            is ApiResult.Refused -> {
                BriqStore.update {
                    it.copy(current = settled?.profile ?: it.current,
                        phase = Phase.Failed(result.reason, result.detail), unlockToken = null,
                        unlockChoices = null)
                        .let { s -> if (settled != null) s.since(settled.sinceS) else s }
                }
                when (result.reason) {
                    // A rollback is not a crash: the block is intact and healthy.
                    "rolled_back" ->
                        "Could not switch to ${prettify(target)}" to
                            "Rules did not go live in time. Rolled back — you are still on ${prettify(settled?.profile ?: "the old profile")}."
                    "busy" ->
                        "Another change is running" to "The Pi is already applying something. Try again in a moment."
                    "would_loosen" ->
                        "That would lift a restriction" to "Scan the tag to lift a restriction."
                    "throttled" ->
                        "Too many changes" to "Wait a minute before switching again."
                    "bad_token" ->
                        "Tag not accepted" to "The token on the tag is not valid any more."
                    else ->
                        "Could not switch to ${prettify(target)}" to result.detail.ifBlank { "The Pi refused the change." }
                }
            }
            ApiResult.Unreachable -> {
                BriqStore.update {
                    it.copy(phase = Phase.Offline, unlockToken = null, unlockChoices = null)
                }
                "Pi not reachable" to "Not on the home Wi-Fi and Tailscale is not connected. Briq is unchanged."
            }
        }

        notifyResult(title, text)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun prettify(name: String) =
        name.split('-').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }

    private fun descOf(name: String) =
        BriqStore.state.value.profiles.firstOrNull { it.name == name }?.desc ?: ""

    private fun fgType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(target: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_briq)
            .setContentTitle("Switching to ${prettify(target)}")
            .setContentText("Waiting for the rules to go live…")
            .setProgress(0, 0, true)          // genuinely indeterminate: no fake percentage
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent())
            .build()

    private fun notifyResult(title: String, text: String) {
        // The in-app UI has already shown this outcome. Posting it as well
        // would buzz the phone the user is holding and reading.
        if (AppForeground.visible) return
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_briq)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_RESULT, n)
        } catch (_: SecurityException) {
            // Notification permission refused. The in-app state is still correct.
        }
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}
