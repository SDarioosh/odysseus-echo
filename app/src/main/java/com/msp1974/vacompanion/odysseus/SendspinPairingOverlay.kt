package com.msp1974.vacompanion.odysseus

// Odysseus fork: pairing prompt for the Sendspin player (sendspin-cpp v0.9).
//
// When Music Assistant pairs with this device (Setup on the player), the device shows a six-digit
// code for that attempt, which the owner types into Music Assistant. After too many wrong codes
// the library holds further attempts until the owner allows pairing here. This overlay shows
// both, and the outcome for a few seconds. Drawn over any layout (dashboard, home screen, blank
// screen); SendspinPlayer also turns the screen on when a prompt appears.

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.sendspin.SendspinSecurity
import com.msp1974.vacompanion.sendspin.SendspinSecurityState
import kotlinx.coroutines.delay

private val CardBg = Color(0xF0202228)
private val Muted = Color(0xFFB0B8C0)
private val Good = Color(0xFF81C784)
private val Bad = Color(0xFFFFB74D)

@Composable
fun SendspinPairingOverlay() {
    val state by SendspinSecurity.state.collectAsState()
    // Re-evaluated while a result is showing, so it goes away on its own.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.lastResult) {
        val result = state.lastResult ?: return@LaunchedEffect
        now = SystemClock.elapsedRealtime()
        val left = result.atMs + SendspinSecurityState.RESULT_SHOWN_MS - now
        if (left > 0) delay(left)
        now = SystemClock.elapsedRealtime()
    }
    if (!state.overlayVisible(now)) return

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(CardBg, RoundedCornerShape(12.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val code = state.pairingCode
            val result = state.lastResult
            when {
                code != null -> {
                    Text("Pair with Music Assistant", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        code.chunked(3).joinToString(" "),
                        color = Color.White, fontSize = 64.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                    )
                    Text(
                        "Enter this code in Music Assistant${state.serverName?.let { " ($it)" } ?: ""}.",
                        color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center,
                    )
                }
                state.windowRequested -> {
                    Text("Allow pairing?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${state.serverName ?: "A Music Assistant server"} wants to pair with this device. " +
                            "Only allow it if you started pairing in Music Assistant.",
                        color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { SendspinSecurity.answerPairingWindow(false) }) { Text("Not now") }
                        Button(onClick = { SendspinSecurity.answerPairingWindow(true) }) { Text("Allow") }
                    }
                }
                result != null && result.succeeded -> {
                    Text("Paired", color = Good, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Music Assistant${state.serverName?.let { " ($it)" } ?: ""} is now paired with this device.",
                        color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center,
                    )
                }
                result != null -> {
                    Text("Pairing didn't finish", color = Bad, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Not paired: ${SendspinSecurityState.reasonText(result.reason)}. Start again from Music Assistant.",
                        color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
