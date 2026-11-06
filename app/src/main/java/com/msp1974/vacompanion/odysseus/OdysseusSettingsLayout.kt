package com.msp1974.vacompanion.odysseus

// Odysseus fork: Menu → Odysseus. "Set up with phone" runs the whole setup (EchoSetup.kt); the
// fields below link the device to Odysseus by hand so it can show what voice turns did
// (TurnCard), tell Odysseus where a turn was asked and who asked it (OdysseusTurn), and play
// the thinking sound. The base URL and token are the same ones Home Assistant's conversation
// agent uses; the token is stored encrypted (OdysseusLink).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.kiosk.KioskSettings
import com.msp1974.vacompanion.odysseus.home.weather.WeatherClient
import com.msp1974.vacompanion.odysseus.home.weather.WeatherEntity
import com.msp1974.vacompanion.odysseus.home.weather.WeatherRepository
import com.msp1974.vacompanion.ui.VAViewModel
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
            // Set up the whole Echo (Odysseus link, Home Assistant login and entry) from a phone.
            EchoSetupPanel(viewModel<VAViewModel>().config)
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
            HomeScreenSettings()
            KioskSettingsSection()
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

/** Menu → Odysseus → Home screen: the clock over the dashboard (odysseus/home/). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreenSettings() {
    HomeSettings.init(LocalContext.current)
    // HomeSettings is Compose state, so these recompose when it changes.
    val enabled = HomeSettings.enabled
    val idle = HomeSettings.idleSeconds
    Text(
        "Home screen", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Setting(
        enabled, "Clock home screen",
        "A clock and the playing song over the dashboard. Swipe right for the dashboard, left for the clock.",
    ) { HomeSettings.enabled = it }
    Text("Back to the clock after", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeSettings.IDLE_CHOICES_SECONDS.forEach { seconds ->
            FilterChip(
                selected = idle == seconds,
                onClick = { HomeSettings.idleSeconds = seconds },
                label = { Text(HomeSettings.idleLabel(seconds)) },
                enabled = enabled,
            )
        }
    }

    // Weather in the clock's top left (odysseus/home/weather/).
    val weatherOn = HomeSettings.weatherEnabled
    val chosen = HomeSettings.weatherEntity
    val entities = WeatherRepository.entities
    Setting(
        weatherOn, "Weather",
        "Icon, temperature and today's high and low in the clock's top left, from a Home Assistant weather entity.",
    ) { HomeSettings.weatherEnabled = it }
    Text("Weather entity", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    val auto = WeatherClient.autoPick(entities)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = chosen.isBlank(),
            onClick = { HomeSettings.weatherEntity = "" },
            label = { Text(if (auto != null) "Auto (${auto.name})" else "Auto") },
            enabled = enabled && weatherOn,
        )
        // The chosen entity stays listed even when Home Assistant no longer reports it.
        val shown = if (chosen.isBlank() || entities.any { it.entityId == chosen }) entities
        else entities + WeatherEntity(chosen, chosen, false)
        shown.forEach { e ->
            FilterChip(
                selected = chosen == e.entityId,
                onClick = { HomeSettings.weatherEntity = e.entityId },
                label = { Text(if (e.available) e.name else "${e.name} (unavailable)") },
                enabled = enabled && weatherOn,
            )
        }
    }
    if (entities.isEmpty()) {
        Text(
            "No weather entities seen yet. They are read from Home Assistant while the clock shows.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Menu → Odysseus → Kiosk: coming back from other apps, reloading a failed dashboard (odysseus/kiosk/). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KioskSettingsSection() {
    KioskSettings.init(LocalContext.current)
    // KioskSettings is Compose state, so these recompose when it changes.
    val returnSeconds = KioskSettings.returnSeconds
    Text(
        "Kiosk", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Setting(
        KioskSettings.autoReload, "Reload a dashboard that failed to load",
        "Tries again after 5 s, then waits longer each time, up to every 5 minutes. Soon after Wi-Fi comes back.",
    ) { KioskSettings.autoReload = it }
    Text("Back from other apps after", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KioskSettings.RETURN_CHOICES_SECONDS.forEach { seconds ->
            FilterChip(
                selected = returnSeconds == seconds,
                onClick = { KioskSettings.returnSeconds = seconds },
                label = { Text(KioskSettings.returnLabel(seconds)) },
            )
        }
    }
    Text(
        "When another app has been in front this long, this app comes back. Not while the screen is off.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
