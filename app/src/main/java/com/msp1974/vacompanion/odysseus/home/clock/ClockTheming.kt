package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: the clock page's theme (colours and fonts from ClockTheme, handed to Material
// so shared pieces such as the weather badge, the Done rows and the status line follow it) and
// the background (solid, gradient, or the blurred cover art).

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import com.msp1974.vacompanion.R

object ClockFontFamilies {
    private val fira = FontFamily(
        Font(R.font.fira_code_light, FontWeight.Light),
        Font(R.font.fira_code_regular, FontWeight.Normal),
        Font(R.font.fira_code_medium, FontWeight.Medium),
        Font(R.font.fira_code_semibold, FontWeight.SemiBold),
        Font(R.font.fira_code_bold, FontWeight.Bold),
    )
    private val inter = FontFamily(
        Font(R.font.clock_inter_200, FontWeight.ExtraLight),
        Font(R.font.clock_inter_300, FontWeight.Light),
        Font(R.font.clock_inter_500, FontWeight.Medium),
    )
    private val manrope = FontFamily(
        Font(R.font.clock_manrope_300, FontWeight.Light),
        Font(R.font.clock_manrope_500, FontWeight.Medium),
        Font(R.font.clock_manrope_700, FontWeight.Bold),
    )
    private val outfit = FontFamily(
        Font(R.font.clock_outfit_200, FontWeight.ExtraLight),
        Font(R.font.clock_outfit_300, FontWeight.Light),
        Font(R.font.clock_outfit_500, FontWeight.Medium),
    )
    private val playfair = FontFamily(
        Font(R.font.clock_playfair_display_400, FontWeight.Normal),
        Font(R.font.clock_playfair_display_400_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.clock_playfair_display_600, FontWeight.SemiBold),
    )
    private val dmSerif = FontFamily(Font(R.font.clock_dm_serif_display_400, FontWeight.Normal))
    private val bebas = FontFamily(Font(R.font.clock_bebas_neue_400, FontWeight.Normal))

    fun family(id: String): FontFamily = when (id) {
        ClockFonts.INTER -> inter
        ClockFonts.MANROPE -> manrope
        ClockFonts.OUTFIT -> outfit
        ClockFonts.PLAYFAIR -> playfair
        ClockFonts.DM_SERIF -> dmSerif
        ClockFonts.BEBAS -> bebas
        else -> fira
    }
}

fun clockColor(hex: String?, fallback: Color): Color = ClockColor.parse(hex)?.let { Color(it.toInt()) } ?: fallback

/** Material's colours and type in the style's colours and text font, for everything inside. */
@Composable
fun ClockThemed(theme: ClockTheme, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = if (theme.useAppColors) base else {
        val bg = clockColor(theme.background, base.background)
        val text = clockColor(theme.text, base.onBackground)
        val muted = clockColor(theme.muted, base.onSurfaceVariant)
        val accent = clockColor(theme.accent, base.primary)
        val panel = clockColor(theme.panel, base.surfaceVariant)
        val alert = clockColor(theme.alert, base.error)
        base.copy(
            primary = accent, onPrimary = bg, secondary = accent, onSecondary = bg,
            secondaryContainer = panel, onSecondaryContainer = text,
            tertiary = clockColor(theme.highlight, accent),
            background = bg, onBackground = text, surface = bg, onSurface = text,
            surfaceVariant = panel, onSurfaceVariant = muted, error = alert,
            outline = muted.copy(alpha = 0.4f), outlineVariant = muted.copy(alpha = 0.25f),
        )
    }
    val family = ClockFontFamilies.family(theme.textFont)
    val t = MaterialTheme.typography
    val typography = remember(theme.textFont, t) {
        fun TextStyle.f() = copy(fontFamily = family, letterSpacing = 0.sp)
        Typography(
            displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
            headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
            titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
            bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
            labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = MaterialTheme.shapes, content = content)
}

/**
 * The page's background: the theme's colour or gradient, or (artworkBackground, while a song with
 * art plays) the cover art blurred by scaling a tiny copy up, which costs nothing on the Echo's
 * Android 11 (no RenderEffect there).
 */
@Composable
fun ClockBackground(theme: ClockTheme, artwork: Bitmap?, serverColor: Int) {
    val colors = MaterialTheme.colorScheme
    val bg = colors.background
    val second = if (theme.useAppColors) null else ClockColor.parse(theme.gradient)?.let { Color(it.toInt()) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val brush = if (second != null) {
            Brush.linearGradient(listOf(bg, second), start = Offset(w * 0.15f, 0f), end = Offset(w * 0.85f, h * 1.2f))
        } else null
        Box(Modifier.fillMaxSize().then(if (brush != null) Modifier.background(brush) else Modifier.background(bg)))
        if (theme.artworkBackground) {
            if (artwork != null) {
                val tiny = remember(artwork) { Bitmap.createScaledBitmap(artwork, 12, 12, true) }
                Image(
                    bitmap = tiny.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                    alpha = 0.85f, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)))
            } else if (serverColor != 0) {
                Box(Modifier.fillMaxSize().background(Color(serverColor).copy(alpha = 0.6f)))
            }
        }
    }
}
