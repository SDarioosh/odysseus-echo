package com.msp1974.vacompanion.odysseus

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.R

/**
 * Odysseus fork: the look of the Odysseus web app (its "dark" and "light" themes in
 * static/js/theme.js and static/css/00-tokens.css of the hub): colours, the Fira Code font,
 * small corners. `ui/theme/Theme.kt` hands every screen to this theme, so upstream screens that
 * read `MaterialTheme` pick it up without changes.
 */
object OdysseusColors {
    // Dark theme (the web app's default).
    val Bg = Color(0xFF282C34)
    val Fg = Color(0xFF9CDEF2)
    val Panel = Color(0xFF111111)
    val Border = Color(0xFF355A66)
    val Red = Color(0xFFE06C75) // the brand colour: the logo and accents
    val Green = Color(0xFF50FA7B)
    val Error = Color(0xFFFF6666)

    // Syntax colours of the dark theme, used where a screen needs several accents.
    val Keyword = Color(0xFFC678DD)
    val StringYellow = Color(0xFFE5C07B)
    val Function = Color(0xFF61AFEF)
    val Number = Color(0xFFD19A66)
    val Builtin = Color(0xFF56B6C2)

    // Light theme.
    val LightBg = Color(0xFFF3F5F7)
    val LightFg = Color(0xFF26313A)
    val LightPanel = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFCBD3DA)
    val LightRed = Color(0xFFB34D5C)

    /** The web app's `color-mix(in srgb, fg 65%, transparent)` for secondary text. */
    fun muted(fg: Color, bg: Color) = fg.copy(alpha = 0.65f).compositeOver(bg)
}

object OdysseusTheme {
    private val fira = FontFamily(
        Font(R.font.fira_code_light, FontWeight.Light),
        Font(R.font.fira_code_regular, FontWeight.Normal),
        Font(R.font.fira_code_medium, FontWeight.Medium),
        Font(R.font.fira_code_semibold, FontWeight.SemiBold),
        Font(R.font.fira_code_bold, FontWeight.Bold),
    )

    private val dark: ColorScheme = with(OdysseusColors) {
        darkColorScheme(
            primary = Red,
            onPrimary = Bg,
            primaryContainer = Red.copy(alpha = 0.18f).compositeOver(Bg),
            onPrimaryContainer = Red,
            inversePrimary = LightRed,
            secondary = Fg,
            onSecondary = Bg,
            secondaryContainer = Border,
            onSecondaryContainer = Fg,
            tertiary = Keyword,
            onTertiary = Bg,
            tertiaryContainer = Keyword.copy(alpha = 0.18f).compositeOver(Bg),
            onTertiaryContainer = Keyword,
            background = Bg,
            onBackground = Fg,
            surface = Bg,
            onSurface = Fg,
            surfaceVariant = Color(0xFF1E2228),
            onSurfaceVariant = muted(Fg, Bg),
            surfaceTint = Color.Transparent, // flat panels, as on the web
            inverseSurface = Fg,
            inverseOnSurface = Bg,
            error = Error,
            onError = Bg,
            errorContainer = Error.copy(alpha = 0.18f).compositeOver(Bg),
            onErrorContainer = Error,
            outline = Border,
            outlineVariant = Border.copy(alpha = 0.6f).compositeOver(Bg),
            scrim = Color.Black,
            surfaceBright = Color(0xFF323843),
            surfaceDim = Panel,
            surfaceContainerLowest = Panel,
            surfaceContainerLow = Color(0xFF1A1D22),
            surfaceContainer = Color(0xFF1E2228),
            surfaceContainerHigh = Color(0xFF23272E),
            surfaceContainerHighest = Color(0xFF2C313A),
        )
    }

    private val light: ColorScheme = with(OdysseusColors) {
        lightColorScheme(
            primary = LightRed,
            onPrimary = Color.White,
            primaryContainer = LightRed.copy(alpha = 0.14f).compositeOver(LightBg),
            onPrimaryContainer = LightRed,
            inversePrimary = Red,
            secondary = Border,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCE8EC),
            onSecondaryContainer = LightFg,
            tertiary = Color(0xFF7928A1),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEFE3F5),
            onTertiaryContainer = Color(0xFF7928A1),
            background = LightBg,
            onBackground = LightFg,
            surface = LightBg,
            onSurface = LightFg,
            surfaceVariant = Color(0xFFE8ECEF),
            onSurfaceVariant = muted(LightFg, LightBg),
            surfaceTint = Color.Transparent,
            inverseSurface = LightFg,
            inverseOnSurface = LightBg,
            error = Color(0xFFC0392B),
            onError = Color.White,
            errorContainer = Color(0xFFF8E0DD),
            onErrorContainer = Color(0xFF8E2A20),
            outline = Color(0xFF9AA6B0),
            outlineVariant = LightBorder,
            scrim = Color.Black,
            surfaceBright = LightPanel,
            surfaceDim = Color(0xFFE2E6EA),
            surfaceContainerLowest = LightPanel,
            surfaceContainerLow = LightPanel,
            surfaceContainer = LightPanel,
            surfaceContainerHigh = Color(0xFFEEF1F4),
            surfaceContainerHighest = Color(0xFFE6EAEE),
        )
    }

    fun colorScheme(darkMode: Boolean): ColorScheme = if (darkMode) dark else light

    /** Material's type scale in Fira Code, without the extra letter spacing (a monospace font is wide enough). */
    val typography: Typography = Typography().let { t ->
        fun TextStyle.fira() = copy(fontFamily = fira, letterSpacing = 0.sp)
        Typography(
            displayLarge = t.displayLarge.fira(),
            displayMedium = t.displayMedium.fira(),
            displaySmall = t.displaySmall.fira(),
            headlineLarge = t.headlineLarge.fira(),
            headlineMedium = t.headlineMedium.fira(),
            headlineSmall = t.headlineSmall.fira(),
            titleLarge = t.titleLarge.fira().copy(fontWeight = FontWeight.SemiBold),
            titleMedium = t.titleMedium.fira().copy(fontWeight = FontWeight.SemiBold),
            titleSmall = t.titleSmall.fira().copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = t.bodyLarge.fira(),
            bodyMedium = t.bodyMedium.fira(),
            bodySmall = t.bodySmall.fira(),
            labelLarge = t.labelLarge.fira(),
            labelMedium = t.labelMedium.fira(),
            labelSmall = t.labelSmall.fira(),
        )
    }

    /** The web app's corners: 6 px buttons and inputs, 8 px cards, a little more for sheets and dialogs. */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(10.dp),
        extraLarge = RoundedCornerShape(12.dp),
    )

    /**
     * Accents for the menu tiles and Configuration sections, in place of upstream's rainbow: the
     * brand colour first, then the web app's syntax colours. Mid-tone, so they read on both themes.
     */
    val sectionColors = with(OdysseusColors) {
        listOf(Red, Function, Keyword, Number, Builtin, Color(0xFF98C379), StringYellow, Border)
    }
}

/**
 * The Odysseus logo: the sailboat over "Odysseus" in the brand colour, as in the web app's
 * sidebar, and "echo" under it. Replaces upstream's View Assist logo on the connection screen.
 */
@Composable
fun OdysseusLogo(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.odysseus_logo),
            contentDescription = "Odysseus",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(0.6f).heightIn(max = 160.dp),
        )
        Text(
            text = "Odysseus",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = "echo",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
