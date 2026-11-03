package com.msp1974.vacompanion.odysseus

// Odysseus fork: Menu → Odysseus. Links the device to Odysseus so it can show what voice turns
// created or completed (VoiceActionsCard). The base URL and token are the same ones Home
// Assistant's conversation agent uses; the token is stored encrypted (OdysseusLink).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
            Text(
                "Show what you add or complete by voice. Use the base URL and voice token from " +
                    "Odysseus → Settings → Integrations → Voice assistant (the same ones Home Assistant uses).",
                color = Color.DarkGray, fontSize = 15.sp,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = show, onCheckedChange = { show = it; OdysseusLink.showActions = it })
                Text("  Show added and completed items after a voice turn", fontSize = 16.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    OdysseusLink.baseUrl = url
                    if (token.isNotBlank()) OdysseusLink.setToken(token)
                    token = ""
                    status = "Saved. Testing…"
                    scope.launch {
                        status = when (val r = OdysseusLink.fetchActions(System.currentTimeMillis() - 86_400_000L, 1)) {
                            is OdysseusLink.Result.Ok -> "Linked: Odysseus answered"
                            is OdysseusLink.Result.Failed -> "Saved, but the test failed: ${r.message}"
                        }
                    }
                }) { Text("Save and test") }
                OutlinedButton(onClick = {
                    OdysseusLink.setToken(""); OdysseusLink.baseUrl = ""
                    url = ""; token = ""; status = "Not linked"
                }) { Text("Unlink") }
            }
            Text(status, fontSize = 15.sp, color = Color.DarkGray)
        }
    }
}
