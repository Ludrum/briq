package de.lukas.briq.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lukas.briq.*
import de.lukas.briq.R
import kotlinx.coroutines.withTimeoutOrNull

/* ==========================================================================
   Home — the glance. One object, one sentence, nothing to read.
   ========================================================================== */

@Composable
fun HomeScreen(
    state: BriqState,
    onLongPress: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val profile = state.currentProfile
    val palette = remember(profile) { Palette(profile) }

    // Kept as State, never read during composition. See the graphicsLayer below.
    val idle = rememberInfiniteTransition(label = "idle").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(
            tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "idleY",
    )

    Box(
        Modifier
            .fillMaxSize()
            .briqBackground(palette)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (profile.name == "unbricked") profile.title else "${profile.title} activated",
                fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                fontSize = 27.sp, lineHeight = 31.sp, letterSpacing = (-0.675).sp,
                color = palette.title, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = profile.desc,
                fontFamily = BodyFont, fontSize = 14.5.sp, lineHeight = 21.sp,
                color = palette.body, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))

            ProfileObject(
                name = profile.name,
                palette = palette,
                modifier = Modifier
                    .size(210.dp)
                    // Reading idle.value HERE, inside the layer block, keeps the
                    // animation in the draw phase. Reading it in composition (or
                    // via Modifier.offset) re-ran layout for the whole screen on
                    // every frame, which is what stuttered.
                    .graphicsLayer {
                        translationY = -7.dp.toPx() * idle.value
                    },
            )

            if (state.showTimer) {
                Spacer(Modifier.height(24.dp))
                BrickTimer(state, palette)
            }
        }

        FabButton(palette, Modifier.align(Alignment.BottomEnd), onOpenDetails)
    }
}

/**
 * How long you have been in this profile.
 *
 * The seconds come from the Pi and are extended locally with
 * elapsedRealtime(), so a brick triggered from the iPad reads the same here,
 * and the digits keep moving between refreshes without polling for each one.
 *
 * Its own composable so the per-second tick redraws one Text rather than the
 * scene behind it.
 */
@Composable
private fun BrickTimer(state: BriqState, palette: Palette) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(state.sinceS, state.sinceCapturedAt) {
        while (true) {
            val drift = (android.os.SystemClock.elapsedRealtime() - state.sinceCapturedAt) / 1000
            text = elapsedLabel(state.sinceS + drift)
            kotlinx.coroutines.delay(1000)
        }
    }
    Text(
        text,
        fontFamily = MonoFont, fontSize = 15.5.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
        color = palette.body.copy(alpha = 0.88f),
    )
}

/** 1:42:53. Minutes below the hour, days above the day. */
fun elapsedLabel(total: Long): String {
    if (total < 0) return ""
    val d = total / 86400
    val h = total % 86400 / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return when {
        d > 0 -> "%dd %d:%02d:%02d".format(d, h, m, s)
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%d:%02d".format(m, s)
    }
}

@Composable
private fun FabButton(palette: Palette, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(end = 22.dp, bottom = 34.dp)
            .size(50.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.5.dp, palette.title.copy(alpha = 0.38f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("i", fontFamily = DisplayFont, fontSize = 19.sp,
            fontWeight = FontWeight.Medium, color = palette.title)
    }
}

/* ==========================================================================
   Applying — the object assembles while the Pi verifies.
   ========================================================================== */

@Composable
fun ApplyingScreen(state: BriqState, target: String, startedAt: Long) {
    val profile = remember(target, state.profiles) { Profiles.of(state.profiles, target) }
    val palette = remember(profile) { Palette(profile) }

    val build = rememberInfiniteTransition(label = "build").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(
            tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "buildProgress",
    )
    val breathe = rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 1f, targetValue = 0.62f,
        animationSpec = InfiniteRepeatableSpec(
            tween(2900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breatheAlpha",
    )

    Box(Modifier.fillMaxSize().briqBackground(palette)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Switching to ${profile.title}", fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
                letterSpacing = (-0.65).sp, color = palette.title,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(9.dp))
            Text(profile.desc, fontFamily = BodyFont, fontSize = 14.5.sp,
                lineHeight = 21.sp, color = palette.body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            ProfileObject(
                name = profile.name, palette = palette,
                progress = { build.value },
                modifier = Modifier
                    .size(210.dp)
                    .graphicsLayer { alpha = breathe.value },
            )
            Spacer(Modifier.height(26.dp))
            ElapsedLabel(startedAt, palette)
        }
        Text(
            "Usually under a minute. You can close the app — it finishes anyway and notifies you.",
            fontFamily = BodyFont, fontSize = 11.5.sp, lineHeight = 17.sp,
            color = palette.faint, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 38.dp),
        )
    }
}

/** Isolated so the per-second tick recomposes one Text, not the whole screen. */
@Composable
private fun ElapsedLabel(startedAt: Long, palette: Palette) {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    Text(
        "Waiting for the rules to go live · ${elapsed / 60}:${"%02d".format(elapsed % 60)}",
        fontFamily = BodyFont, fontSize = 12.5.sp, color = palette.body,
    )
}

/* ==========================================================================
   Picker — one grammar, two meanings.
   ========================================================================== */

@Composable
fun PickerSheet(
    palette: Palette,
    heading: String,
    subheading: String,
    choices: List<Profile>,
    current: String?,
    onPick: (Profile) -> Unit,
    onCancel: () -> Unit,
) {
    // One driver for the whole sheet; each row derives its own slice, so the
    // stagger costs a single animation object rather than one per row.
    //
    // The driver is LINEAR on purpose. Easing it globally and then clipping
    // per-row slices out of it gave each row a hard-edged ramp - that is what
    // felt bulky. The easing belongs on each row's own slice instead.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(360 + choices.size * 38, easing = LinearEasing))
    }
    val rows = choices.size + 2

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    val t = slice(appear.value, 0, rows)
                    alpha = t
                    translationY = (1f - t) * 9.dp.toPx()
                },
            ) {
                Text(heading, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp, letterSpacing = (-0.38).sp, color = palette.title)
                Spacer(Modifier.height(6.dp))
                Text(subheading, fontFamily = BodyFont, fontSize = 12.5.sp, lineHeight = 18.sp,
                    color = palette.body, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
            Spacer(Modifier.height(20.dp))

            choices.forEachIndexed { i, p ->
                ProfilePill(
                    palette = palette,
                    p = p,
                    isCurrent = p.name == current,
                    layer = {
                        val t = slice(appear.value, i + 1, rows)
                        alpha = t
                        translationY = (1f - t) * 11.dp.toPx()
                        val sc = 0.985f + 0.015f * t
                        scaleX = sc
                        scaleY = sc
                    },
                ) { onPick(p) }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val t = slice(appear.value, choices.size + 1, rows)
                        alpha = t
                        translationY = (1f - t) * 11.dp.toPx()
                    }
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, palette.title.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
                    .clickable(onClick = onCancel)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", fontFamily = DisplayFont, fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium, color = palette.title)
            }
        }
    }
}

/**
 * Where row [i] is in its own entrance, 0..1.
 *
 * Windows OVERLAP - each row takes 60% of the run and they start a fraction
 * apart - so the sheet arrives as one movement rather than a queue of
 * separate ones. Ease-out cubic on the way in.
 */
private fun slice(appear: Float, i: Int, rows: Int): Float {
    val start = i * (0.40f / rows.coerceAtLeast(1))
    val raw = ((appear - start) / 0.60f).coerceIn(0f, 1f)
    val inv = 1f - raw
    return 1f - inv * inv * inv
}

/** Falls back to the wall glyph for a profile the app has not seen before. */
private fun iconFor(name: String): Int = when (name) {
    "unbricked" -> R.drawable.ic_p_unbricked
    "social" -> R.drawable.ic_p_social
    "video" -> R.drawable.ic_p_video
    "deep-focus" -> R.drawable.ic_p_deep_focus
    "offline" -> R.drawable.ic_p_offline
    else -> R.drawable.ic_stat_briq
}

@Composable
private fun ProfilePill(
    palette: Palette,
    p: Profile,
    isCurrent: Boolean,
    layer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer(layer)
            .clip(shape)
            .background(Brush.linearGradient(listOf(palette.pillTop(p), palette.pillBottom(p))))
            .border(
                width = if (isCurrent) 2.dp else 1.dp,
                color = if (isCurrent) palette.pillInk(p).copy(alpha = 0.75f) else palette.pillEdge(p),
                shape = shape,
            )
            .clickable(enabled = enabled && !isCurrent, onClick = onClick)
            // Generous start inset: at a 26dp corner radius, text set tight to
            // the edge collides with the curve.
            .padding(start = 20.dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconFor(p.name)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(palette.pillInk(p)),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, fontFamily = DisplayFont, fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium, color = palette.pillInk(p))
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Text("NOW", fontFamily = BodyFont, fontSize = 10.sp,
                        letterSpacing = 0.5.sp, color = palette.pillInkDim(p))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(p.desc, fontFamily = BodyFont, fontSize = 11.5.sp, lineHeight = 15.5.sp,
                color = palette.pillInkDim(p))
        }
        Spacer(Modifier.width(12.dp))
        // On and off differ in width as well as brightness: three equal dashes
        // read as an ellipsis rather than a level.
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(Profiles.MAX_LEVEL) { i ->
                val on = i < p.level
                Box(
                    Modifier
                        .padding(horizontal = 1.5.dp)
                        .size(width = if (on) 11.dp else 5.dp, height = 3.5.dp)
                        .background(
                            if (on) palette.pillInk(p) else Color.White.copy(alpha = 0.22f),
                            RoundedCornerShape(2.dp),
                        )
                )
            }
        }
    }
}

/* ==========================================================================
   Details — the one screen allowed to be a readout.
   ========================================================================== */

@Composable
fun DetailsScreen(
    state: BriqState,
    palette: Palette,
    onAddSchedule: () -> Unit,
    onRemoveSchedule: (Schedule) -> Unit,
    onTimerWhenUnbricked: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.sheetSurface, palette.sheetSurfaceDim)))
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(46.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 20.sp, color = palette.title) }
            Spacer(Modifier.width(12.dp))
            Text("Details", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp, color = palette.title)
        }
        Spacer(Modifier.height(14.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            val s = state.status
            Section("Controller", palette) {
                Row2("Host", state.host?.authority?.substringBefore(':') ?: "—", palette, mono = true)
                Row2("Port", "8088", palette, mono = true)
                Row2("Reached via", state.host?.label ?: "not connected", palette)
            }
            Section("Health", palette) {
                Row2("Backend", if (s?.backendOk == true) "reachable" else "DOWN", palette,
                    tint = if (s?.backendOk == true) palette.good else palette.bad)
                Row2("DNS", if (s?.dnsOk == true) "answering" else "not answering", palette,
                    tint = if (s?.dnsOk == true) palette.good else palette.bad)
            }
            Section("Rules", palette) {
                Row2("Briq rules", s?.managedRules?.toString() ?: "—", palette)
                Row2("Your other rules", s?.otherRules ?: "—", palette)
                Row2("Profile", state.current ?: "—", palette, mono = true)
            }
            Section("Timer", palette) {
                ToggleRow(
                    label = "Show while unbricked",
                    hint = "How long since the last change, on the home screen.",
                    on = state.timerWhenUnbricked,
                    palette = palette,
                ) { onTimerWhenUnbricked(!state.timerWhenUnbricked) }
            }
            Section("Scheduled bricks", palette) {
                val items = state.schedules.items
                if (items.isEmpty()) {
                    Row2("Schedules", if (s == null) "—" else "none", palette)
                } else items.forEach { sched ->
                    ScheduleRow(
                        sched = sched,
                        profile = Profiles.of(state.profiles, sched.profile),
                        palette = palette,
                        waitingForTag = state.pendingScheduleDelete == sched.id,
                    ) { onRemoveSchedule(sched) }
                }
                AddScheduleRow(palette, onAddSchedule)
            }
            Section("Blocked devices", palette) {
                val clients = s?.clients.orEmpty()
                if (clients.isEmpty()) Row2("Devices", "none", palette)
                else clients.forEach { Row2(it, "", palette) }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "The Pi is the source of truth. If the phone is off the network Briq " +
                    "keeps running unchanged — this screen just stops updating.",
                fontFamily = BodyFont, fontSize = 11.sp, lineHeight = 17.sp,
                color = palette.body.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun Section(title: String, palette: Palette, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), fontFamily = BodyFont, fontSize = 10.5.sp,
        letterSpacing = 1.05.sp, color = palette.body.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .padding(horizontal = 18.dp),
        content = content,
    )
}

@Composable
private fun Row2(
    k: String, v: String, palette: Palette,
    mono: Boolean = false, tint: Color? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k, fontFamily = BodyFont, fontSize = 13.5.sp, color = palette.body.copy(alpha = 0.85f))
        if (v.isNotEmpty()) {
            Text(v, fontFamily = if (mono) MonoFont else BodyFont, fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium, color = tint ?: palette.title)
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    on: Boolean,
    palette: Palette,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = BodyFont, fontSize = 13.5.sp,
                color = palette.body.copy(alpha = 0.85f))
            Spacer(Modifier.height(1.dp))
            Text(hint, fontFamily = BodyFont, fontSize = 11.sp, lineHeight = 15.sp,
                color = palette.body.copy(alpha = 0.6f))
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, palette)
    }
}

/**
 * Position carries the state, not just colour: the knob is at one end or the
 * other whether or not you can tell the two fills apart.
 */
@Composable
private fun Switch(on: Boolean, palette: Palette) {
    val t by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = tween(170, easing = FastOutSlowInEasing),
        label = "switch",
    )
    Box(
        Modifier
            .size(width = 46.dp, height = 27.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (on) palette.good.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.10f)
            )
            .border(
                1.dp,
                palette.title.copy(alpha = if (on) 0.45f else 0.18f),
                RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.5.dp)
                .graphicsLayer { translationX = 19.dp.toPx() * t }
                .size(20.dp)
                .clip(CircleShape)
                .background(palette.title.copy(alpha = if (on) 1f else 0.62f))
        )
    }
}

@Composable
private fun ScheduleRow(
    sched: Schedule,
    profile: Profile,
    palette: Palette,
    waitingForTag: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sched.time, fontFamily = MonoFont, fontSize = 15.sp,
            fontWeight = FontWeight.Medium, color = palette.accent(profile))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.title, fontFamily = BodyFont, fontSize = 13.5.sp,
                color = palette.title)
            Spacer(Modifier.height(1.dp))
            Text(
                // The waiting row says what is expected of you, not what the
                // schedule is: you already know what you just tapped.
                if (waitingForTag) "Hold the tag to remove this"
                else "${sched.daysLabel} · next ${sched.nextLabel}",
                fontFamily = BodyFont, fontSize = 11.sp, lineHeight = 15.sp,
                color = if (waitingForTag) palette.body else palette.body.copy(alpha = 0.62f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (waitingForTag) "Cancel" else "Remove",
            fontFamily = BodyFont, fontSize = 11.5.sp,
            color = palette.body.copy(alpha = 0.85f),
            modifier = Modifier
                .clip(RoundedCornerShape(13.dp))
                .clickable(onClick = onRemove)
                .padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun AddScheduleRow(palette: Palette, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+", fontFamily = DisplayFont, fontSize = 17.sp,
            fontWeight = FontWeight.Medium, color = palette.title.copy(alpha = 0.85f))
        Spacer(Modifier.width(11.dp))
        Text("Add a schedule", fontFamily = BodyFont, fontSize = 13.5.sp,
            color = palette.title.copy(alpha = 0.9f))
    }
}

/* ==========================================================================
   Schedule editor.

   Same grammar as the two picker sheets: the pill IS the verb. Set the time
   and the days, then tap what it should switch to - so there is no confirm
   button to press and no selected-but-not-committed state to explain.
   ========================================================================== */

@Composable
fun ScheduleSheet(
    palette: Palette,
    choices: List<Profile>,
    onCancel: () -> Unit,
    onCreate: (Profile, Int, Int, Set<Int>) -> Unit,
) {
    var hour by remember { mutableIntStateOf(6) }
    var minute by remember { mutableIntStateOf(0) }
    var days by remember { mutableStateOf(setOf(0, 1, 2, 3, 4)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp)
                .verticalScroll(rememberScrollState())
                // Swallows taps that miss a control. Without this, a thumb
                // landing beside a chevron closes the sheet and loses the
                // time you had just dialled in.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Schedule a brick", fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                letterSpacing = (-0.38).sp, color = palette.title)
            Spacer(Modifier.height(6.dp))
            Text(
                "It tightens at the time you set and holds until you unbrick " +
                    "it by hand. It never lifts a restriction on its own.",
                fontFamily = BodyFont, fontSize = 12.5.sp, lineHeight = 18.sp,
                color = palette.body, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(20.dp))

            TimeField(palette, hour, minute,
                onHour = { hour = (hour + it + 24) % 24 },
                onMinute = { minute = (minute + it * 5 + 60) % 60 })
            Spacer(Modifier.height(12.dp))
            DayChips(palette, days) { days = it }
            Spacer(Modifier.height(20.dp))

            SheetLabel(
                if (days.isEmpty()) "PICK AT LEAST ONE DAY" else "SWITCH TO",
                palette,
            )
            Spacer(Modifier.height(8.dp))
            choices.forEach { p ->
                ProfilePill(
                    palette = palette,
                    p = p,
                    isCurrent = false,
                    layer = { alpha = if (days.isEmpty()) 0.4f else 1f },
                    enabled = days.isNotEmpty(),
                ) { onCreate(p, hour, minute, days) }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, palette.title.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
                    .clickable(onClick = onCancel)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", fontFamily = DisplayFont, fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium, color = palette.title)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SheetLabel(text: String, palette: Palette) {
    Text(text, fontFamily = BodyFont, fontSize = 10.5.sp, letterSpacing = 1.05.sp,
        color = palette.body.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
}

@Composable
private fun TimeField(
    palette: Palette,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimePart(palette, hour, onHour)
        Text(":", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp, color = palette.title.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 2.dp))
        TimePart(palette, minute, onMinute)
    }
}

/** Fixed width so the digits do not shuffle as they change. */
@Composable
private fun TimePart(palette: Palette, value: Int, onStep: (Int) -> Unit) {
    Column(
        Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Chevron(up = true, palette = palette) { onStep(1) }
        Text("%02d".format(value), fontFamily = DisplayFont,
            fontWeight = FontWeight.SemiBold, fontSize = 33.sp,
            letterSpacing = (-0.8).sp, color = palette.title)
        Chevron(up = false, palette = palette) { onStep(-1) }
    }
}

/**
 * Drawn rather than typed: a chevron glyph would come from whichever fallback
 * font the system picks, and the three bundled faces do not carry one.
 *
 * Holding repeats, because 06:00 to 22:00 is sixteen taps otherwise.
 */
@Composable
private fun Chevron(up: Boolean, palette: Palette, onStep: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onStep()
                    if (withTimeoutOrNull(400) { tryAwaitRelease() } == null) {
                        while (withTimeoutOrNull(90) { tryAwaitRelease() } == null) {
                            onStep()
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp, 9.dp)) {
            val path = Path().apply {
                if (up) {
                    moveTo(0f, size.height); lineTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                } else {
                    moveTo(0f, 0f); lineTo(size.width / 2f, size.height)
                    lineTo(size.width, 0f)
                }
            }
            drawPath(
                path, palette.title.copy(alpha = 0.85f),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round,
                    join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
private fun DayChips(palette: Palette, days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    // Monday first, matching the Pi's weekday numbering.
    val names = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        names.forEachIndexed { i, n ->
            val on = i in days
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (on) 0.20f else 0.06f))
                    .border(
                        1.dp,
                        palette.title.copy(alpha = if (on) 0.55f else 0.16f),
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onChange(if (on) days - i else days + i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(n, fontFamily = BodyFont, fontSize = 11.5.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) palette.title else palette.body.copy(alpha = 0.55f))
            }
        }
    }
}

/* ==========================================================================
   Shared background.

   drawWithCache builds the brushes once per size instead of per frame, and
   the dot field is a tiled bitmap shader rather than ~360 drawCircle calls
   on every draw.
   ========================================================================== */

fun Modifier.briqBackground(palette: Palette): Modifier = this.drawWithCache {
    val ramp = Brush.linearGradient(
        0f to palette.bgTop,
        0.30f to palette.bgUpperMid,
        0.65f to palette.bgLowerMid,
        1f to palette.bgBottom,
        start = Offset(size.width * 0.18f, 0f),
        end = Offset(size.width * 0.82f, size.height),
    )
    val glowRadius = size.width * 0.62f
    val glowCentre = Offset(size.width / 2f, size.height * 0.52f)
    val glow = Brush.radialGradient(
        listOf(palette.glow, Color.Transparent),
        center = glowCentre, radius = glowRadius,
    )

    val step = 32.dp.toPx().coerceAtLeast(2f)
    val cellPx = step.toInt().coerceAtLeast(2)
    val cell = ImageBitmap(cellPx, cellPx)
    androidx.compose.ui.graphics.Canvas(cell).drawCircle(
        Offset(cellPx / 2f, cellPx / 2f),
        1.dp.toPx(),
        Paint().apply { color = palette.dots.copy(alpha = palette.dots.alpha * 0.35f) },
    )
    val dots = ShaderBrush(ImageShader(cell, TileMode.Repeated, TileMode.Repeated))

    // Slight vignette: pulls the eye to the object in the middle and stops the
    // 160-degree ramp's bright corner from competing with it. Drawn last so it
    // darkens the dot field too, otherwise the dots stay bright in the corners
    // and the falloff reads as a smudge rather than light.
    // Shares the glow's centre, not the geometric one: two focal points a few
    // percent apart read as a mistake rather than as depth.
    val centre = glowCentre
    val corner = kotlin.math.hypot(size.width / 2f, size.height * 0.52f)
    val vignette = Brush.radialGradient(
        0.0f to Color.Transparent,
        0.55f to Color.Black.copy(alpha = 0.04f),
        1.0f to Color.Black.copy(alpha = 0.30f),
        center = centre,
        radius = corner * 1.02f,
    )

    onDrawBehind {
        drawRect(ramp)
        drawCircle(glow, radius = glowRadius, center = glowCentre)
        drawRect(dots)
        drawRect(vignette)
    }
}

/* ==========================================================================
   Failure banner.

   Phase.Failed and Phase.Offline previously rendered nothing, so a stale
   token or a scan off the home network was indistinguishable from the scan
   not registering at all - the one outcome a tag must never have.
   ========================================================================== */

@Composable
fun StatusBanner(palette: Palette, title: String, detail: String, onDismiss: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(title, detail) {
        appear.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
        kotlinx.coroutines.delay(5000)
        appear.animateTo(0f, tween(220))
        onDismiss()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * -14.dp.toPx()
            }
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, palette.bad.copy(alpha = 0.42f), RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(7.dp)
                    .background(palette.bad, CircleShape)
            )
            Spacer(Modifier.width(11.dp))
            Column {
                Text(title, fontFamily = DisplayFont, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, color = palette.title)
                Spacer(Modifier.height(2.dp))
                Text(detail, fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 16.5.sp,
                    color = palette.body)
            }
        }
    }
}
