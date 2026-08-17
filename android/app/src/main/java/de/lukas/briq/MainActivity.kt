package de.lukas.briq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import de.lukas.briq.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val askNotify = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declined only costs the completion notification; the app still works. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val state by BriqStore.state.collectAsState()
            val palette = remember(state.currentProfile) { Palette(state.currentProfile) }

            Box(Modifier.fillMaxSize()) {
                when (val phase = state.phase) {
                    is Phase.Applying -> ApplyingScreen(state, phase.target, phase.startedAt)
                    else -> HomeScreen(
                        state = state,
                        onLongPress = { BriqStore.update { it.copy(showEscalate = true) } },
                        onOpenDetails = {
                            BriqStore.update { it.copy(showDetails = true) }
                            refresh()
                        },
                    )
                }

                // Tag scanned: every profile, because the tag is the permission.
                AnimatedVisibility(
                    visible = state.unlockChoices != null,
                    enter = fadeIn(tween(220, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(150)),
                ) {
                    PickerSheet(
                        palette = palette,
                        heading = "Tag scanned",
                        subheading = "The tag is in your hand, so any profile is " +
                            "available — including lifting restrictions.",
                        choices = state.unlockChoices.orEmpty(),
                        current = state.current,
                        onPick = { p ->
                            val token = state.unlockToken
                            BriqStore.clearUnlock()
                            if (token != null) ApplyService.start(this@MainActivity, p.name, token)
                        },
                        onCancel = { BriqStore.clearUnlock() },
                    )
                }

                // Long-press: stricter only, and no token involved at all.
                AnimatedVisibility(
                    visible = state.showEscalate,
                    enter = fadeIn(tween(220, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(150)),
                ) {
                    val stricter = state.profiles.filter { it.level > state.currentProfile.level }
                    PickerSheet(
                        palette = palette,
                        heading = "Restrict further",
                        subheading = if (stricter.isEmpty())
                            "Nothing stricter available. Scan the tag to change profile."
                        else "No tag needed to tighten. Lifting a restriction always needs the tag.",
                        choices = stricter,
                        current = state.current,
                        onPick = { p ->
                            BriqStore.update { it.copy(showEscalate = false) }
                            ApplyService.start(this@MainActivity, p.name, null)
                        },
                        onCancel = { BriqStore.update { it.copy(showEscalate = false) } },
                    )
                }

                AnimatedVisibility(
                    visible = state.showDetails,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    DetailsScreen(
                        state = state,
                        palette = palette,
                        onAddSchedule = {
                            BriqStore.update {
                                it.copy(showScheduleEditor = true, pendingScheduleDelete = null)
                            }
                        },
                        onRemoveSchedule = { sched ->
                            if (sched.armed) {
                                // The tag is the permission. Arm the row and
                                // wait for the scan; tapping again backs out.
                                BriqStore.update {
                                    it.copy(pendingScheduleDelete =
                                        if (it.pendingScheduleDelete == sched.id) null else sched.id)
                                }
                            } else {
                                removeSchedule(sched.id, null)
                            }
                        },
                        onTimerWhenUnbricked = { on ->
                            // Optimistic: the switch is a display toggle, and
                            // waiting a network round trip to move would feel
                            // broken. The refresh below is the correction.
                            BriqStore.update { it.copy(timerWhenUnbricked = on) }
                            lifecycleScope.launch {
                                when (val r = BriqApi.setTimerWhenUnbricked(on)) {
                                    is ApiResult.Ok -> BriqStore.update {
                                        it.copy(timerWhenUnbricked = r.value)
                                    }
                                    is ApiResult.Refused -> BriqStore.update {
                                        it.copy(timerWhenUnbricked = !on,
                                            phase = Phase.Failed(r.reason, r.detail))
                                    }
                                    ApiResult.Unreachable -> BriqStore.update {
                                        it.copy(timerWhenUnbricked = !on, phase = Phase.Offline)
                                    }
                                }
                            }
                        },
                        onBack = {
                            BriqStore.update {
                                it.copy(showDetails = false, pendingScheduleDelete = null)
                            }
                        },
                    )
                }

                AnimatedVisibility(
                    visible = state.showScheduleEditor,
                    enter = fadeIn(tween(220, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(150)),
                ) {
                    // The Pi says which profiles may be scheduled - one that
                    // blocks nothing would be a scheduled unbrick. Falling
                    // back to "anything restrictive" keeps the sheet usable
                    // before that list has arrived.
                    val allowed = state.schedules.schedulable
                    val choices = state.profiles.filter {
                        if (allowed.isEmpty()) it.level > 0 else it.name in allowed
                    }
                    ScheduleSheet(
                        palette = palette,
                        choices = choices,
                        onCancel = { BriqStore.update { it.copy(showScheduleEditor = false) } },
                        onCreate = { p, hour, minute, days ->
                            BriqStore.update { it.copy(showScheduleEditor = false) }
                            addSchedule(p.name, hour, minute, days)
                        },
                    )
                }

                // Last in the box, so it is on top of every sheet: a refusal
                // that lands while Details or the editor is open is exactly
                // the one you need to read. A scan that failed must say so.
                val phase = state.phase
                if (phase is Phase.Failed || phase is Phase.Offline) {
                    val (t, d) = when (phase) {
                        is Phase.Offline ->
                            "Pi not reachable" to
                                "Not on the home Wi-Fi, and Tailscale is not connected. Briq is unchanged."
                        is Phase.Failed -> when (phase.reason) {
                            "bad_token" -> "Tag not accepted" to
                                "The token on this tag is not valid any more. Rewrite it from the Pi."
                            "would_loosen" -> "That would lift a restriction" to
                                "Scan the tag to lift a restriction."
                            "throttled" -> "Too many changes" to "Wait a minute before switching again."
                            "busy" -> "Another change is running" to "The Pi is mid-apply. Try again shortly."
                            "needs_tag" -> "That schedule has already run" to
                                "Removing it lifts a restriction, so it needs the tag."
                            "not_restrictive" -> "Nothing to schedule there" to
                                "A schedule can only tighten. Unbricking stays manual."
                            "duplicate" -> "Already scheduled" to "That exact schedule exists."
                            "bad_days" -> "Pick at least one day" to
                                "A schedule needs a day to happen on."
                            "too_many" -> "Too many schedules" to "Remove one before adding another."
                            else -> "Could not reach the controller" to
                                phase.detail.ifBlank { "The Pi refused the request." }
                        }
                        else -> "" to ""
                    }
                    Box(Modifier.fillMaxSize().statusBarsPadding(), Alignment.TopCenter) {
                        StatusBanner(palette, t, d) {
                            BriqStore.update { it.copy(phase = Phase.Idle) }
                        }
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Foreground dispatch: a scan while the app is already open should act,
        // not relaunch through the OS chooser.
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag ->
                android.nfc.tech.Ndef.get(tag)?.let { ndef ->
                    try {
                        ndef.connect()
                        val msg = ndef.ndefMessage ?: ndef.cachedNdefMessage
                        ndef.close()
                        msg?.records?.firstNotNullOfOrNull { rec ->
                            runCatching { rec.toUri() }.getOrNull()
                        }?.let { uri -> runOnUiThread { consumeUri(uri) } }
                    } catch (_: Exception) { /* moved out of range mid-read */ }
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            Intent.ACTION_VIEW -> intent.data?.let { consumeUri(it) }
        }
    }

    /**
     * A short tick to confirm the tag registered.
     *
     * Reader mode is started with FLAG_READER_NO_PLATFORM_SOUNDS, which
     * suppresses the system's own scan chirp - without this the scan would
     * be silent and invisible until the sheet finishes loading, which is a
     * network round trip away.
     */
    private fun tick() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    /**
     * Turn a scanned tag URI into an action.
     *
     * Four shapes, and the scheme does not change what any of them mean:
     *
     *   unlock/<token>            the single master tag: opens the picker
     *   select/<profile>/<token>  that exact profile, immediately
     *   toggle/<profile>/<token>  that profile, or back to unbricked
     *   escalate/<profile>        tighten only, and carries no credential
     *
     * The last one is the tag you can leave lying about. There is nothing on
     * it to steal: the Pi refuses any escalation that is not strictly
     * stricter, so a stranger who clones it can only brick this phone harder.
     */
    private fun consumeUri(uri: Uri) {
        // briq://<verb>/<rest...> and http://host:port/<verb>/<rest...> are
        // the same instruction. The private scheme exists because nothing
        // else can claim it - an http tag is in principle every browser's,
        // which is what makes Android ask before it dispatches.
        val segs = if (uri.scheme == "briq") {
            listOfNotNull(uri.host) + uri.pathSegments.orEmpty()
        } else {
            uri.pathSegments ?: return
        }

        when {
            segs.size == 2 && segs[0] == "unlock" -> {
                val token = segs[1]
                tick()
                // A scan answers the question the app is currently asking. If
                // a schedule is waiting for the tag, that is what the token
                // is for - opening the profile picker instead would drop it.
                val awaiting = BriqStore.state.value.pendingScheduleDelete
                if (awaiting != null) {
                    removeSchedule(awaiting, token)
                    return
                }
                lifecycleScope.launch {
                    when (val r = BriqApi.unlock(token)) {
                        is ApiResult.Ok -> BriqStore.update {
                            it.copy(unlockToken = token, unlockChoices = r.value,
                                host = r.host, showEscalate = false, showDetails = false)
                        }
                        is ApiResult.Refused -> BriqStore.update {
                            it.copy(phase = Phase.Failed(r.reason, r.detail))
                        }
                        ApiResult.Unreachable -> BriqStore.update { it.copy(phase = Phase.Offline) }
                    }
                }
            }
            segs.size == 3 && (segs[0] == "toggle" || segs[0] == "select") -> {
                val profile = segs[1]
                val token = segs[2]
                val target = if (segs[0] == "toggle" && BriqStore.state.value.current == profile)
                    "unbricked" else profile
                tick()
                ApplyService.start(this, target, token)
            }
            segs.size == 2 && segs[0] == "escalate" -> {
                // No token: the same token-free path as a long-press, with
                // the profile named on the tag instead of picked from a
                // sheet. If it would loosen, the Pi says so and nothing
                // changes - which is why this tag is safe to leave on a desk.
                tick()
                ApplyService.start(this, segs[1], null)
            }
        }
    }

    private fun addSchedule(profile: String, hour: Int, minute: Int, days: Set<Int>) {
        lifecycleScope.launch {
            when (val r = BriqApi.addSchedule(profile, hour, minute, days)) {
                is ApiResult.Ok -> refreshSchedules()
                is ApiResult.Refused ->
                    BriqStore.update { it.copy(phase = Phase.Failed(r.reason, r.detail)) }
                ApiResult.Unreachable -> BriqStore.update { it.copy(phase = Phase.Offline) }
            }
        }
    }

    private fun removeSchedule(id: String, token: String?) {
        lifecycleScope.launch {
            when (val r = BriqApi.deleteSchedule(id, token)) {
                is ApiResult.Ok -> {
                    BriqStore.update { it.copy(pendingScheduleDelete = null) }
                    refreshSchedules()
                }
                is ApiResult.Refused -> {
                    // Most often `needs_tag` against a row the app still
                    // thinks is unarmed because it fired a moment ago. Reload
                    // so the row agrees with the Pi before the next tap.
                    BriqStore.update {
                        it.copy(phase = Phase.Failed(r.reason, r.detail),
                            pendingScheduleDelete = null)
                    }
                    refreshSchedules()
                }
                ApiResult.Unreachable -> BriqStore.update { it.copy(phase = Phase.Offline) }
            }
        }
    }

    private suspend fun refreshSchedules() {
        when (val s = BriqApi.schedules()) {
            is ApiResult.Ok -> BriqStore.update { it.copy(schedules = s.value, host = s.host) }
            else -> Unit
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            when (val p = BriqApi.profiles()) {
                is ApiResult.Ok -> BriqStore.update { it.copy(profiles = p.value) }
                else -> Unit
            }
            refreshSchedules()
            when (val s = BriqApi.status()) {
                is ApiResult.Ok -> BriqStore.update {
                    it.copy(
                        current = s.value.profile, status = s.value, host = s.host,
                        timerWhenUnbricked = s.value.timerWhenUnbricked,
                        phase = if (it.phase is Phase.Applying) it.phase else Phase.Idle,
                    ).since(s.value.sinceS)
                }
                ApiResult.Unreachable -> BriqStore.update {
                    if (it.phase is Phase.Applying) it else it.copy(phase = Phase.Offline)
                }
                is ApiResult.Refused -> Unit
            }
        }
    }
}
