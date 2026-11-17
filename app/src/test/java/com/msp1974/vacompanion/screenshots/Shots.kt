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
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import com.msp1974.vacompanion.ui.theme.AppTheme
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
    setScreenQualifiers(widthDp, heightDp, mode, density)
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

private fun setScreenQualifiers(widthDp: Int, heightDp: Int, mode: Mode, density: String) {
    val orientation = if (widthDp >= heightDp) "land" else "port"
    val night = if (mode.dark) "night" else "notnight"
    RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-$orientation-$night-$density")
}

/**
 * For screens that load their data in effects (LaunchedEffect, produceState collecting a flow):
 * [shot] captures the first frame, before any effect ran, and Compose's UI dispatcher is
 * process-wide, so after the first test of a JVM a plain activity no longer recomposes at all.
 * This rule renders through the Compose test rule (its own frame clock) instead: one [screen] and
 * [mode] per test (use it with ParameterizedRobolectricTestRunner over [Screen.all] x [Mode]).
 *
 * ```
 * @get:Rule val shots = SettledShot(screen, mode)
 * @Test fun page() = shots.capture("music/page") { MusicPage.Content(...) }
 * ```
 */
class SettledShot(private val screen: Screen, private val mode: Mode) : TestRule {
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>()

    private val setup = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                setScreenQualifiers(screen.widthDp, screen.heightDp, mode, screen.density)
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    override fun apply(base: Statement, description: org.junit.runner.Description): Statement =
        RuleChain.outerRule(setup).around(compose).apply(base, description)

    /**
     * Sets [content] in the app's theme, lets [settleMs] of frame time pass (effects run, flows
     * emit, the content recomposes) and saves `<name>-<screen>-<mode>.png`.
     */
    fun capture(name: String, settleMs: Long = 500, content: @Composable () -> Unit) {
        val file = File(roborazziSystemPropertyOutputDirectory(), "$name-${screen.name}-${mode.name.lowercase()}.png")
        file.parentFile?.mkdirs()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(darkMode = mode.dark, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(settleMs)
        compose.onRoot().captureRoboImage(file)
    }

    companion object {
        /** Parameters for ParameterizedRobolectricTestRunner: every [Screen] in both [Mode]s. */
        fun parameters(): List<Array<Any>> = Screen.all.flatMap { s -> Mode.entries.map { m -> arrayOf<Any>(s, m) } }
    }
}
