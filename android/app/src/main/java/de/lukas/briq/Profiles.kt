package de.lukas.briq

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Profile presentation, mirroring PROFILES in controller.py.
 *
 * These values are the fallback only. The live set comes from
 * /profiles.json, so adding a profile on the Pi does not require an app
 * release - the hue and chroma travel with it.
 */
data class Profile(
    val name: String,
    val level: Int,
    val hue: Float,
    val chroma: Float,
    val desc: String,
) {
    /** "deep-focus" -> "Deep Focus". The Pi's names are ids, not labels. */
    val title: String = name.split('-').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }
}

object Profiles {
    const val MAX_LEVEL = 3

    val LEVEL_WORDS = listOf("unrestricted", "limited", "focused", "locked down")

    val FALLBACK = listOf(
        Profile("unbricked", 0, 168f, 0.10f, "Everything allowed."),
        Profile("social", 1, 72f, 0.13f, "Instagram, Facebook, TikTok, X and Reddit blocked."),
        Profile("video", 1, 305f, 0.13f, "YouTube blocked. Search, Gmail and Maps still work."),
        Profile("deep-focus", 2, 268f, 0.14f, "Social and YouTube both blocked."),
        Profile("offline", 3, 20f, 0.15f, "Everything blocked but a small emergency allowlist."),
    )

    val UNKNOWN = Profile("unknown", 0, 250f, 0.02f, "")

    fun of(list: List<Profile>, name: String?): Profile =
        list.firstOrNull { it.name == name } ?: UNKNOWN
}

/**
 * OKLCH -> sRGB.
 *
 * The whole palette is authored in OKLCH on the Pi because it keeps
 * perceived lightness constant across hues - a red and a green at L=0.48 are
 * equally dark, which is what makes one set of lightness rules work for five
 * different colours. Compose has no oklch(), so the conversion lives here
 * rather than in a table of hexes that would drift the moment a hue changes.
 *
 * Straight Björn Ottosson: OKLCh -> OKLab -> LMS -> linear sRGB -> sRGB.
 */
fun oklch(l: Float, c: Float, hueDeg: Float, alpha: Float = 1f): Color {
    val h = hueDeg * (Math.PI.toFloat() / 180f)
    val a = c * cos(h)
    val b = c * sin(h)

    val lp = l + 0.3963377774f * a + 0.2158037573f * b
    val mp = l - 0.1055613458f * a - 0.0638541728f * b
    val sp = l - 0.0894841775f * a - 1.2914855480f * b

    val l3 = lp * lp * lp
    val m3 = mp * mp * mp
    val s3 = sp * sp * sp

    val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
    val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
    val bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3

    return Color(gamma(r), gamma(g), gamma(bl), alpha)
}

private fun gamma(v: Float): Float {
    // Out-of-gamut components are clamped, which is what a browser does too.
    val x = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    return x.coerceIn(0f, 1f)
}

/**
 * The per-profile colour set. Derived, never hand-picked, so every profile is
 * automatically as legible as every other one.
 */
class Palette(val profile: Profile) {
    private val h = profile.hue
    private val c = profile.chroma

    // Full-bleed background, from the Figma comp's four-stop 160deg ramp.
    val bgTop = oklch(0.26f, c * 0.85f, h)
    val bgUpperMid = oklch(0.38f, c, h)
    val bgLowerMid = oklch(0.53f, c * 1.08f, h)
    val bgBottom = oklch(0.33f, c, h)

    val glow = oklch(0.78f, 0.10f, h, 0.30f)
    val dots = oklch(0.86f, 0.05f, h, 0.25f)

    val title = oklch(0.975f, 0.018f, h)
    val body = oklch(0.87f, 0.055f, h)
    val faint = oklch(0.90f, 0.05f, h, 0.62f)

    // The object: lit face to shadowed face.
    val objLight = oklch(0.93f, 0.045f, h)
    val objDark = oklch(0.80f, 0.075f, h)
    val objTile = oklch(0.96f, 0.03f, h)
    val objTileDark = oklch(0.82f, 0.08f, h)
    val opening = oklch(0.22f, 0.05f, h)
    val openingLit = oklch(0.68f, 0.13f, h)

    val scrim = oklch(0.09f, 0.025f, h, 0.86f)
    val sheetSurface = oklch(0.22f, 0.05f, h)
    val sheetSurfaceDim = oklch(0.16f, 0.03f, h)

    /**
     * A profile's own colour on a dark surface, for the places a profile is
     * named inside a readout rather than shown as a pill - a schedule's time,
     * say. Matches the controller's dark `--accent`.
     */
    fun accent(p: Profile) = oklch(0.82f, minOf(p.chroma, 0.13f), p.hue)

    // Pills carry their OWN profile's colour, not the current one.
    fun pillTop(p: Profile) = oklch(0.55f, 0.15f, p.hue, 0.86f)
    fun pillBottom(p: Profile) = oklch(0.42f, 0.13f, p.hue, 0.78f)
    fun pillEdge(p: Profile) = oklch(0.85f, 0.09f, p.hue, 0.42f)
    fun pillInk(p: Profile) = oklch(0.99f, 0.01f, p.hue)
    fun pillInkDim(p: Profile) = oklch(0.95f, 0.03f, p.hue, 0.82f)

    val good = oklch(0.84f, 0.15f, 155f)
    val bad = oklch(0.78f, 0.15f, 25f)
}
