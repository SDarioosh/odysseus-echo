package com.msp1974.vacompanion.screenshots

// Odysseus fork: the helper every screenshot test uses (docs/odysseus/screenshots.md). Renders a
// composable on the JVM (Robolectric, native graphics) in the app's theme and saves it as a PNG
// with Roborazzi. Run only with -Pscreenshots; see the doc.

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import com.msp1974.vacompanion.ui.theme.AppTheme
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * A screen to render on: its size in dp and its density bucket. [EchoShow5]: 960x480 px at
 * mdpi (160 dpi, so 960x480 dp; the panel is 195 dpi and LineageOS reports mdpi-sized dp, see the
 * doc). [Tablet]: a 1280x800 px tablet at mdpi.
 */
data class Screen(val name: String, val widthDp: Int, val heightDp: Int, val density: String = "mdpi") {
    companion object {
        val EchoShow5 = Screen("echo", 960, 480)
        val Tablet = Screen("tablet", 1280, 800)
        val all = listOf(EchoShow5, Tablet)
    }
}

/** Light and dark of the app's theme (ui/theme/Theme.kt → odysseus/OdysseusTheme.kt). */
enum class Mode(val dark: Boolean) { DARK(true), LIGHT(false) }

/**
 * Renders [content] in a window of [widthDp] x [heightDp] dp at [density], inside the app's theme
 * ([mode]) on its background colour, and saves it as `<roborazzi output dir>/<name>.png`
 * (`app/build/outputs/roborazzi/` by default). [name] may contain "/" for a sub-folder.
 */
fun shot(
    name: String,
    widthDp: Int,
    heightDp: Int,
    mode: Mode = Mode.DARK,
    density: String = "mdpi",
    content: @Composable () -> Unit,
) {
    val orientation = if (widthDp >= heightDp) "land" else "port"
    val night = if (mode.dark) "night" else "notnight"
    RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-$orientation-$night-$density")
    val file = File(roborazziSystemPropertyOutputDirectory(), "$name.png")
    file.parentFile?.mkdirs()
    captureRoboImage(file.path) {
        AppTheme(darkMode = mode.dark, dynamicColor = false) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
        }
    }
}

/** [shot] on a [Screen], named `<name>-<screen>-<mode>`. */
fun shot(name: String, screen: Screen, mode: Mode = Mode.DARK, content: @Composable () -> Unit) =
    shot("$name-${screen.name}-${mode.name.lowercase()}", screen.widthDp, screen.heightDp, mode, screen.density, content)

/** [shot] on every [Screen] in both [Mode]s. */
fun shots(name: String, screens: List<Screen> = Screen.all, content: @Composable () -> Unit) {
    for (screen in screens) for (mode in Mode.entries) shot(name, screen, mode, content)
}

/** Fictional cover art: a diagonal gradient, [size] px square. */
fun gradientArt(size: Int = 256, from: Int = 0xFFE06C75.toInt(), to: Int = 0xFF61AFEF.toInt()): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), from, to, Shader.TileMode.CLAMP) }
    Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return bitmap
}
