package de.lukas.briq.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import de.lukas.briq.R

/**
 * The three families from the Figma comp, bundled rather than fetched:
 * the app must render correctly on the exact network where the Pi is
 * unreachable, so it cannot depend on downloadable fonts.
 */
val DisplayFont = FontFamily(
    Font(R.font.wix_madefor_display, FontWeight.Normal),
    Font(R.font.wix_madefor_display, FontWeight.Medium),
    Font(R.font.wix_madefor_display, FontWeight.SemiBold),
    Font(R.font.wix_madefor_display, FontWeight.Bold),
)

val BodyFont = FontFamily(Font(R.font.koho_regular, FontWeight.Normal))

val UiFont = FontFamily(Font(R.font.outfit, FontWeight.Normal))

val MonoFont = FontFamily.Monospace
