package com.msp1974.vacompanion.odysseus

// Odysseus fork: the Echo's side of the phone setup (EchoSetup.kt): the QR code and what the
// phone is doing. Shown on the connection screen while the Echo has no Home Assistant login
// (FirstRunSetup, hook in ui/layouts/ConnectLayout.kt) and in Menu → Odysseus. Sized for the
// Echo Show 5 (960×480 px, about 640×320 dp): while the QR shows, the connection screen gives the
// panel its whole width (setupQrShowing) and the QR shrinks to the screen height.

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel
import kotlinx.coroutines.delay

/** Connection screen: an Echo without a Home Assistant login shows the setup QR by itself. */
@Composable
fun FirstRunSetup() {
    if (LocalInspectionMode.current) return
    val vm: VAViewModel = viewModel()
    if (!EchoSetup.needsSetup(vm.config) && EchoSetup.state.collectAsState().value is EchoSetup.State.Idle) return
    EchoSetupPanel(vm.config, autoStart = EchoSetup.needsSetup(vm.config), qrSize = 200.dp)
}

/**
 * True while the setup QR is on screen. The connection screen then leaves out the logo and the
 * device details (hook in ui/layouts/ConnectLayout.kt), which don't fit beside it on an Echo Show 5.
 */
@Composable
fun setupQrShowing(): Boolean =
    !LocalInspectionMode.current && EchoSetup.state.collectAsState().value is EchoSetup.State.Waiting

/**
 * The QR code while the setup server runs, else a "Set up with phone" button. Leaving the screen
 * stops the server unless the Home Assistant part is already running.
 */
@Composable
fun EchoSetupPanel(config: APPConfig, autoStart: Boolean = false, qrSize: Dp = 220.dp) {
    val state by EchoSetup.state.collectAsState()
    LaunchedEffect(Unit) { if (autoStart && state is EchoSetup.State.Idle) EchoSetup.start(config) }
    DisposableEffect(Unit) { onDispose { EchoSetup.leave(); EchoSetup.reset() } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    when (val st = state) {
        is EchoSetup.State.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val qr = remember(st.url) { qrBitmap(st.url, 600) }
            // Room for the status line and settings button below; never so small a phone can't scan it.
            val fit = (LocalConfiguration.current.screenHeightDp - 120).dp.coerceAtLeast(120.dp)
            Image(bitmap = qr.asImageBitmap(), contentDescription = "Setup QR code",
                modifier = Modifier.size(minOf(qrSize, fit)).background(Color.White).padding(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text("Set up with your phone", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(st.phoneStep ?: "Scan the code. The phone must be on Tailscale or the same Wi-Fi.", fontSize = 13.sp, lineHeight = 17.sp, color = muted)
                Text(st.url.substringBefore("/setup/"), fontSize = 11.sp, lineHeight = 14.sp, color = muted)
                var left by remember { mutableLongStateOf(0L) }
                LaunchedEffect(st.expiresAt) {
                    while (true) { left = (st.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000; delay(1000) }
                }
                Text("Closes in ${left / 60}:${"%02d".format(left % 60)}", fontSize = 12.sp, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    if (st.hasOther) OutlinedButton(onClick = { EchoSetup.swapAddress() }, contentPadding = compact) {
                        Text("Other address", maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { EchoSetup.stop("Cancelled") }, contentPadding = compact) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        is EchoSetup.State.Running -> Steps("Setting up from your phone…", st.steps)
        is EchoSetup.State.Finished -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Steps(if (st.ok) "✓ Set up" else "Setup finished with problems (see the phone)", st.steps)
            if (!st.ok) Button(onClick = { EchoSetup.start(config) }) { Text("Set up again") }
        }
        EchoSetup.State.Starting -> Text("Starting setup…", fontSize = 15.sp, color = muted)
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { EchoSetup.start(config) }) { Text("Set up with phone (QR code)") }
            (st as? EchoSetup.State.Stopped)?.let { Text(it.reason, color = muted, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Steps(title: String, steps: List<EchoSetup.Step>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        steps.forEach { s ->
            val (mark, color) = when (s.status) {
                EchoSetup.StepStatus.OK -> "✓" to Color(0xFF43A047)
                EchoSetup.StepStatus.WARN -> "!" to Color(0xFFEF6C00)
                EchoSetup.StepStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
                EchoSetup.StepStatus.RUNNING -> "…" to MaterialTheme.colorScheme.primary
                else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text("$mark ${s.label}", fontSize = 13.sp, color = color)
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
