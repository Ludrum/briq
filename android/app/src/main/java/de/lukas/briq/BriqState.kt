package de.lukas.briq

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the app is doing right now. */
sealed interface Phase {
    data object Idle : Phase
    data object Loading : Phase
    /** An apply is running; [target] is where we are heading. */
    data class Applying(val target: String, val startedAt: Long) : Phase
    data class Failed(val reason: String, val detail: String) : Phase
    data object Offline : Phase
}

data class BriqState(
    val profiles: List<Profile> = Profiles.FALLBACK,
    val current: String? = null,
    val phase: Phase = Phase.Loading,
    val status: Status? = null,
    val host: Host? = null,
    /**
     * Held ONLY between a tag scan and the selection it authorises, then
     * cleared. Never written to disk, never in a Bundle. The app is not
     * meant to be able to lift a restriction on its own - that is the entire
     * point of the tag, and persisting this would quietly undo it.
     */
    val unlockToken: String? = null,
    val unlockChoices: List<Profile>? = null,
    val showEscalate: Boolean = false,
    val showDetails: Boolean = false,
    val schedules: Schedules = Schedules(),
    val showScheduleEditor: Boolean = false,
    /**
     * A schedule that has already run once, waiting for the tag that
     * authorises removing it. Held here rather than in the row so a scan
     * arriving from anywhere in the app knows what it is answering.
     */
    val pendingScheduleDelete: String? = null,
    /**
     * Seconds in the current profile as of [sinceCapturedAt], which is an
     * elapsedRealtime() stamp. The running total is that sum, so the timer
     * neither drifts against the Pi nor cares what this phone thinks the
     * wall clock is. -1 means the Pi has not told us yet.
     */
    val sinceS: Long = -1,
    val sinceCapturedAt: Long = 0,
    val timerWhenUnbricked: Boolean = true,
    val lastResult: String? = null,
) {
    val currentProfile: Profile get() = Profiles.of(profiles, current)

    /** Unbricked is a state you can be in for weeks; counting it is a choice. */
    val showTimer: Boolean
        get() = sinceS >= 0 && (current != "unbricked" || timerWhenUnbricked)

    fun since(seconds: Long) =
        copy(sinceS = seconds, sinceCapturedAt = SystemClock.elapsedRealtime())
}

object BriqStore {
    private val _state = MutableStateFlow(BriqState())
    val state: StateFlow<BriqState> = _state

    fun update(f: (BriqState) -> BriqState) { _state.value = f(_state.value) }

    /** Wipe the token the moment it has been spent or the sheet is dismissed. */
    fun clearUnlock() = update { it.copy(unlockToken = null, unlockChoices = null) }
}
