package de.lukas.briq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import de.lukas.briq.Palette

/**
 * The hero objects, drawn rather than shipped as images.
 *
 * The Figma comp used a photographed hourglass with its white background
 * knocked out by mixBlendMode:multiply. That only holds on the one blue it
 * was composed against - on the red offline or green unbricked ramp the
 * multiply tints the object to mud. Drawing them means the shading is
 * derived from the same hue as everything else, so all five read correctly.
 *
 * Everything is authored against a 200x200 box and scaled, so the geometry
 * matches the mock exactly.
 */

private const val BOX = 200f

private fun DrawScope.u(v: Float) = v / BOX * size.minDimension

/**
 * [progress] is a lambda, not a value, on purpose: an animating Float read in
 * composition recomposes this whole subtree every frame, which is what made
 * the idle motion stutter. Read inside the draw scope it invalidates drawing
 * only - no recomposition, no relayout.
 */
@Composable
fun ProfileObject(
    name: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 1f },
) {
    Canvas(modifier) {
        val r = progress()
        when (name) {
            "offline" -> wall(palette, r, opening = false)
            "unbricked" -> wall(palette, r, opening = true)
            "deep-focus" -> hourglass(palette, r)
            "social" -> wallWithTile(palette, r) { p, rect -> chatGlyph(p, rect) }
            "video" -> wallWithTile(palette, r) { p, rect -> playGlyph(p, rect) }
            else -> wall(palette, r, opening = false)
        }
    }
}

private fun DrawScope.groundShadow(dy: Float = 0f, spread: Float = 1f) {
    drawOval(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(u(38f + (1f - spread) * 10f), u(143f + dy)),
        size = Size(u(124f * spread), u(18f)),
    )
}

private fun DrawScope.briqFace(palette: Palette) = Brush.verticalGradient(
    listOf(palette.objLight, palette.objDark),
    startY = 0f, endY = size.height,
)

/**
 * One course of bricks. [reveal] is how much of the whole wall is built, so
 * bricks appear in laying order rather than all fading in together.
 */
private fun DrawScope.course(
    palette: Palette, y: Float, count: Int, startX: Float,
    w: Float, h: Float, gap: Float, indexBase: Int, total: Int, reveal: Float,
) {
    for (i in 0 until count) {
        val ordinal = indexBase + i
        val appear = ((reveal * total) - ordinal).coerceIn(0f, 1f)
        if (appear <= 0f) continue
        val x = startX + i * (w + gap)
        val rise = (1f - appear) * 10f
        drawRoundRect(
            brush = briqFace(palette),
            topLeft = Offset(u(x), u(y + rise)),
            size = Size(u(w), u(h)),
            cornerRadius = CornerRadius(u(2.5f)),
            alpha = appear,
        )
        // Top edge catches the light; bottom edge is the mortar shadow.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.45f * appear),
            topLeft = Offset(u(x), u(y + rise)),
            size = Size(u(w), u(2.4f)),
            cornerRadius = CornerRadius(u(1.2f)),
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.16f * appear),
            topLeft = Offset(u(x), u(y + h - 2.2f + rise)),
            size = Size(u(w), u(2.2f)),
            cornerRadius = CornerRadius(u(1.1f)),
        )
    }
}

private data class WallSpec(val w: Float = 30f, val h: Float = 15f, val gap: Float = 2.5f)

private fun DrawScope.wallCourses(
    palette: Palette, rows: Int, reveal: Float, spec: WallSpec = WallSpec(),
) {
    var total = 0
    for (r in 0 until rows) total += if (r % 2 == 1) 4 else 5
    var idx = 0
    for (r in 0 until rows) {
        val y = 128f - r * (spec.h + spec.gap)
        val half = r % 2 == 1
        val n = if (half) 4 else 5
        val startX = if (half) 20f + (spec.w + spec.gap) / 2f else 20f
        course(palette, y, n, startX, spec.w, spec.h, spec.gap, idx, total, reveal)
        idx += n
    }
}

private fun DrawScope.wall(palette: Palette, reveal: Float, opening: Boolean) {
    groundShadow()
    if (!opening) {
        wallCourses(palette, 6, reveal)
        return
    }
    // Archway: the wall is clipped around the opening, then the opening is
    // filled dark at the top with light spilling in at the far end. Lighter
    // than the wall would read as a closed panel, which is the opposite.
    val arch = Path().apply {
        moveTo(u(78f), u(143f))
        lineTo(u(78f), u(96f))
        cubicTo(u(78f), u(72f), u(122f), u(72f), u(122f), u(96f))
        lineTo(u(122f), u(143f))
        close()
    }
    clipPathInverse(arch) { wallCourses(palette, 6, reveal) }
    drawPath(
        path = arch,
        brush = Brush.verticalGradient(
            0f to palette.opening,
            0.55f to palette.opening.copy(alpha = 0.95f),
            1f to palette.openingLit,
            startY = u(72f), endY = u(143f),
        ),
        alpha = reveal,
    )
}

/** Draw [block] everywhere except inside [path]. */
private fun DrawScope.clipPathInverse(path: Path, block: DrawScope.() -> Unit) {
    val full = Path().apply { addRect(Rect(Offset.Zero, size)) }
    val cut = Path().apply { op(full, path, androidx.compose.ui.graphics.PathOperation.Difference) }
    clipPath(cut) { block() }
}

private inline fun DrawScope.clipPath(path: Path, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipPath(path)
    block()
    drawContext.canvas.restore()
}

private fun DrawScope.wallWithTile(
    palette: Palette, reveal: Float, glyph: DrawScope.(Palette, Rect) -> Unit,
) {
    groundShadow()
    wallCourses(palette, 5, reveal)
    if (reveal < 0.9f) return
    val fade = ((reveal - 0.9f) / 0.1f).coerceIn(0f, 1f)
    val r = Rect(u(60f), u(58f), u(140f), u(114f))
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.3f * fade),
        topLeft = Offset(r.left, r.top + u(7f)),
        size = Size(r.width, r.height),
        cornerRadius = CornerRadius(u(14f)),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(palette.objTile, palette.objTileDark),
            startY = r.top, endY = r.bottom),
        topLeft = Offset(r.left, r.top),
        size = Size(r.width, r.height),
        cornerRadius = CornerRadius(u(14f)),
        alpha = fade,
    )
    glyph(palette, r)
}

private fun DrawScope.chatGlyph(palette: Palette, r: Rect) {
    val cy = r.top + r.height * 0.5f
    for (i in -1..1) {
        drawCircle(
            color = Color.White.copy(alpha = 0.78f),
            radius = u(4.6f),
            center = Offset(r.left + r.width / 2f + i * u(16f), cy),
        )
    }
}

private fun DrawScope.playGlyph(palette: Palette, r: Rect) {
    val cx = r.left + r.width / 2f
    val cy = r.top + r.height / 2f
    val p = Path().apply {
        moveTo(cx - u(9f), cy - u(12f))
        lineTo(cx + u(12f), cy)
        lineTo(cx - u(9f), cy + u(12f))
        close()
    }
    drawPath(p, Color.White.copy(alpha = 0.85f))
}

private fun DrawScope.hourglass(palette: Palette, reveal: Float) {
    groundShadow(dy = 15f, spread = 0.86f)
    val wood = Brush.verticalGradient(
        listOf(oklchWood(0.74f), oklchWood(0.58f)), startY = 0f, endY = size.height)

    // base and cap
    drawRoundRect(wood, Offset(u(62f), u(148f)), Size(u(76f), u(11f)), CornerRadius(u(5.5f)))
    drawOval(oklchWoodTop(), Offset(u(62f), u(142f)), Size(u(76f), u(12f)))
    drawRoundRect(wood, Offset(u(66f), u(41f)), Size(u(68f), u(10f)), CornerRadius(u(5f)))
    drawOval(oklchWoodTop(), Offset(u(66f), u(35.5f)), Size(u(68f), u(11f)))

    val glass = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.6f),
            palette.objLight.copy(alpha = 0.8f)),
        start = Offset(u(74f), u(51f)), end = Offset(u(126f), u(148f)),
    )
    val top = Path().apply {
        moveTo(u(74f), u(51f))
        cubicTo(u(74f), u(78f), u(96f), u(92f), u(100f), u(100f))
        cubicTo(u(104f), u(92f), u(126f), u(78f), u(126f), u(51f))
        close()
    }
    val bottom = Path().apply {
        moveTo(u(74f), u(148f))
        cubicTo(u(74f), u(121f), u(96f), u(108f), u(100f), u(100f))
        cubicTo(u(104f), u(108f), u(126f), u(121f), u(126f), u(148f))
        close()
    }
    drawPath(top, glass)
    drawPath(bottom, glass)
    val rim = androidx.compose.ui.graphics.drawscope.Stroke(width = u(1.7f))
    drawPath(top, color = Color.White.copy(alpha = 0.55f), style = rim)
    drawPath(bottom, color = Color.White.copy(alpha = 0.55f), style = rim)

    // Sand.
    //
    // At rest the hourglass must NOT be fully drained: an empty top bulb reads
    // as "time is up", which is the opposite of what "Deep Focus activated"
    // means. So a settled object sits mid-run, with sand in both bulbs, and
    // only an in-progress apply actually animates the drain.
    val drained = if (reveal >= 0.999f) 0.42f else reveal.coerceIn(0f, 1f)
    val sand = Brush.verticalGradient(listOf(sandLight(), sandDark()))
    clipPath(top) {
        drawRect(sand,
            topLeft = Offset(u(74f), u(51f) + u(50f) * drained),
            size = Size(u(52f), u(50f)))
    }
    clipPath(bottom) {
        drawRect(sand,
            topLeft = Offset(u(74f), u(148f) - u(40f) * drained),
            size = Size(u(52f), u(40f) * drained))
    }
    drawRect(sand, Offset(u(99.2f), u(100f)), Size(u(1.6f), u(20f)))

    drawPath(
        Path().apply {
            moveTo(u(82f), u(56f))
            cubicTo(u(83f), u(74f), u(93f), u(85f), u(97f), u(93f))
        },
        color = Color.White.copy(alpha = 0.5f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = u(2.6f)),
    )
}

private fun oklchWood(l: Float) = de.lukas.briq.oklch(l, 0.11f, 68f)
private fun oklchWoodTop() = de.lukas.briq.oklch(0.84f, 0.09f, 72f)
private fun sandLight() = de.lukas.briq.oklch(0.90f, 0.09f, 85f)
private fun sandDark() = de.lukas.briq.oklch(0.78f, 0.12f, 68f)
