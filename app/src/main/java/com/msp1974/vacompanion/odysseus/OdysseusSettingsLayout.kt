package com.msp1974.vacompanion.odysseus

// Odysseus fork: Menu → Odysseus. Links the device to Odysseus so it can show what voice turns
// did (TurnCard), tell Odysseus where a turn was asked and who asked it (OdysseusTurn), and play
// the thinking sound. The base URL and token are the same ones Home Assistant's conversation
// agent uses; the token is stored encrypted (OdysseusLink).

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.ui.components.MenuLayout
import kotlinx.coroutines.launch

@Composable
fun OdysseusSettingsLayout(onBack: () -> Unit) {
    OdysseusLink.init(LocalContext.current)
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(OdysseusLink.baseUrl) }
    var token by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(OdysseusLink.showActions) }
    var checkVoice by remember { mutableStateOf(OdysseusLink.checkVoice) }
    var thinking by remember { mutableStateOf(OdysseusLink.thinkingSound) }
    var deviceName by remember { mutableStateOf(OdysseusLink.deviceName) }
    var status by remember {
        mutableStateOf(if (OdysseusLink.isConfigured) "Linked (token saved)" else "Not linked")
    }

    MenuLayout(title = "Odysseus", level = 1, onClose = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PairWithPhone(onPaired = { status = it; url = OdysseusLink.baseUrl })
            Text(
                "Or type them here. Use the base URL and voice token from " +
                    "Odysseus → Settings → Integrations → Voice assistant (the same ones Home Assistant uses).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("Base URL (…/api/chat/voice/v1)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true,
                label = { Text(if (OdysseusLink.hasToken) "Voice token (saved; type to replace)" else "Voice token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Setting(show, "Show what a voice turn did", "Added and completed items, its sources, and questions waiting for a yes") {
                show = it; OdysseusLink.showActions = it
            }
            Setting(checkVoice, "Check my voice", "Send the spoken command to Odysseus so Athena can tell if it's you. Not stored.") {
                checkVoice = it; OdysseusLink.checkVoice = it
            }
            Setting(thinking, "Thinking sound", "A soft tone once when an answer takes longer than 5 seconds") {
                thinking = it; OdysseusLink.thinkingSound = it
            }
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it; OdysseusLink.deviceName = it },
                singleLine = true,
                label = { Text("Device name told to Odysseus (e.g. Kitchen Echo; blank: VACA <id>)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    OdysseusLink.baseUrl = url
                    if (token.isNotBlank()) OdysseusLink.setToken(token)
                    token = ""
                    VoiceTurns.resetFeed()
                    status = "Saved. Testing…"
                    scope.launch { status = OdysseusLink.testLink().second }
                }) { Text("Save and test") }
                OutlinedButton(onClick = {
                    OdysseusLink.setToken(""); OdysseusLink.baseUrl = ""
                    url = ""; token = ""; status = "Not linked"
                }) { Text("Unlink") }
            }
            Text(status, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Setting(checked: Boolean, title: String, detail: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairWithPhone(onPaired: (String) -> Unit) {
    val state by OdysseusPairing.state.collectAsState()
    DisposableEffect(Unit) { onDispose { OdysseusPairing.stop(); OdysseusPairing.reset() } }
    LaunchedEffect(state) { (state as? OdysseusPairing.State.Paired)?.let { onPaired(it.message) } }

    when (val st = state) {
        is OdysseusPairing.State.Waiting -> Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val qr = remember(st.url) { qrBitmap(st.url, 600) }
            Image(bitmap = qr.asImageBitmap(), contentDescription = "Pairing QR code",
                modifier = Modifier.size(230.dp).background(Color.White).padding(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Text("Scan with your phone", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Your phone must be on Tailscale or the same Wi-Fi. Paste the base URL and voice token on the page that opens.",
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(st.url, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                st.otherUrl?.let { other ->
                    val toWifi = st.url.contains("://100.")
                    Text((if (toWifi) "Or on home Wi-Fi: " else "Or over Tailscale: ") + other,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var left by remember { mutableStateOf(0L) }
                LaunchedEffect(st.expiresAt) {
                    while (true) { left = (st.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000; delay(1000) }
                }
                Text("Closes in ${left / 60}:${"%02d".format(left % 60)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (st.otherUrl != null) OutlinedButton(onClick = { OdysseusPairing.swapAddress() }) {
                        Text(if (st.url.contains("://100.")) "Use Wi-Fi address" else "Use Tailscale address")
                    }
                    OutlinedButton(onClick = { OdysseusPairing.stop("Cancelled") }) { Text("Cancel") }
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { OdysseusPairing.start() }) { Text("Pair with phone (QR code)") }
            when (st) {
                is OdysseusPairing.State.Paired -> Text(
                    if (st.ok) "✓ ${st.message}" else st.message,
                    color = if (st.ok) Color(0xFF43A047) else MaterialTheme.colorScheme.error, fontSize = 15.sp,
                )
                is OdysseusPairing.State.Stopped -> Text(st.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                else -> Unit
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
