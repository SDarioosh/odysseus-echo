package com.msp1974.vacompanion.odysseus

// Odysseus fork: Menu → Odysseus, in sections (a rail on the left, the section on the right):
// Link (phone setup or by hand), Voice (turn card, voice check, thinking sound, follow-ups, wake
// word training), Home screen (the clock over the dashboard and what it reads), Clock face (how it
// looks: home/clock/ClockStyleSettingsSection.kt), Music, Alarms, Privacy (recording, the privacy
// button, lock mode), Kiosk and Updates. Each feature's own settings composable is called from
// its section with one line.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.msp1974.vacompanion.odysseus.home.players.OtherPlayers
import com.msp1974.vacompanion.odysseus.home.weather.WeatherRepository
import com.msp1974.vacompanion.odysseus.managed.ManagedSettings
import com.msp1974.vacompanion.odysseus.managed.ManagedSwitchRow
import com.msp1974.vacompanion.odysseus.managed.SecondsChips
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import kotlinx.coroutines.launch

/** The sections of Menu → Odysseus, in the order the rail lists them. */
enum class OdysseusSection(val title: String, val icon: ImageVector) {
    LINK("Link", Icons.Default.Link),
    VOICE("Voice", Icons.Default.RecordVoiceOver),
    HOME("Home screen", Icons.Default.Home),
    CLOCK("Clock face", Icons.Default.Palette),
    MUSIC("Music", Icons.Default.MusicNote),
    ALARMS("Alarms", Icons.Default.Alarm),
    PRIVACY("Privacy", Icons.Default.Shield),
    KIOSK("Kiosk", Icons.Default.Tv),
    UPDATES("Updates", Icons.Default.SystemUpdate),
}

/**
 * Menu → Odysseus: a rail of sections on the left (labelled on a wide screen, icons on a narrow
 * one, like Configuration) and the chosen section on the right. Opens on Link until the device
 * is linked, then on Home screen.
 */
@Composable
fun OdysseusSettingsLayout(onBack: () -> Unit, initial: OdysseusSection? = null) {
    OdysseusLink.init(LocalContext.current)
    HomeSettings.init(LocalContext.current)
    var selected by rememberSaveable {
        mutableStateOf(initial ?: if (OdysseusLink.isConfigured) OdysseusSection.HOME else OdysseusSection.LINK)
    }
    val colors = MaterialTheme.colorScheme

    MenuLayout(title = "Odysseus", level = 1, onClose = onBack) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(if (compact) 84.dp else 200.dp)
                        .fillMaxHeight()
                        .background(colors.surfaceContainer)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    OdysseusSection.entries.forEachIndexed { i, section ->
                        RailItem(section, OdysseusTheme.sectionColors[i % OdysseusTheme.sectionColors.size], section == selected, compact) {
                            selected = section
                        }
                    }
                }
                VerticalDivider(color = colors.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (selected) {
                        // The clock face lays out its own preview and scrolling.
                        OdysseusSection.CLOCK -> com.msp1974.vacompanion.odysseus.home.clock.ClockFaceSettings(HomeSettings.enabled)
                        else -> SectionPage { SectionContent(selected) { selected = it } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionContent(section: OdysseusSection, go: (OdysseusSection) -> Unit) {
    when (section) {
        OdysseusSection.LINK -> LinkSettings()
        OdysseusSection.VOICE -> VoiceSettings()
        OdysseusSection.HOME -> HomeScreenSettings(onClockFace = { go(OdysseusSection.CLOCK) })
        OdysseusSection.CLOCK -> Unit
        OdysseusSection.MUSIC -> com.msp1974.vacompanion.odysseus.home.music.MusicSettingsSection() // home/music/
        OdysseusSection.ALARMS -> com.msp1974.vacompanion.odysseus.alarms.AlarmsSettingsSection() // this device's alarms and timers (odysseus/alarms/)
        OdysseusSection.PRIVACY -> {
            com.msp1974.vacompanion.odysseus.record.RecordingSettingsSection() // recording and the privacy button (odysseus/record/)
            com.msp1974.vacompanion.odysseus.lock.LockSettingsSection() // locked outside work hours (odysseus/lock/)
        }
        OdysseusSection.KIOSK -> KioskSettingsSection()
        OdysseusSection.UPDATES -> com.msp1974.vacompanion.odysseus.update.UpdateSettingsSection()
    }
}

/** A section's page: one scrolling column. */
@Composable
private fun SectionPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A line under a section's name in the rail: what it is set to now. */
@Composable
private fun railStatus(section: OdysseusSection): String? = when (section) {
    OdysseusSection.LINK -> if (OdysseusLink.isConfigured) "Linked" else "Not linked"
    OdysseusSection.HOME -> if (HomeSettings.enabled) "Clock on" else "Off"
    OdysseusSection.CLOCK -> com.msp1974.vacompanion.odysseus.home.clock.ClockPresets.label(HomeSettings.clockStyle.preset) +
        if (HomeSettings.clockStyle.isPresetUnchanged) "" else " (changed)"
    OdysseusSection.UPDATES -> when (com.msp1974.vacompanion.odysseus.update.UpdateManager.state) {
        is com.msp1974.vacompanion.odysseus.update.UpdateState.Ready -> "Update ready"
        is com.msp1974.vacompanion.odysseus.update.UpdateState.Waiting -> "Update available"
        else -> null
    }
    else -> null
}

@Composable
private fun RailItem(section: OdysseusSection, color: Color, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val textColor = if (selected) colors.onSurface else colors.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.2f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        if (compact) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Icon(section.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Text(
                    section.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = textColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Icon(section.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        section.title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = textColor,
                    )
                    railStatus(section)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, detail: String? = null) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(text, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
        if (detail != null) Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Menu → Odysseus → Link. "Set up with phone" runs the whole setup (EchoSetup.kt); the fields
 * below link the device to Odysseus by hand. The base URL and token are the same ones Home
 * Assistant's conversation agent uses; the token is stored encrypted (OdysseusLink).
 */
@Composable
private fun LinkSettings() {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(OdysseusLink.baseUrl) }
    var token by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(OdysseusLink.deviceName) }
    var status by remember {
        mutableStateOf(if (OdysseusLink.isConfigured) "Linked (token saved)" else "Not linked")
    }
    SectionTitle("Link to Odysseus", "Odysseus shows what voice turns did, sends updates, alarms and the Today page.")
    // Set up the whole Echo (Odysseus link, Home Assistant login and entry) from a phone.
    EchoSetupPanel(viewModel<VAViewModel>().config)
    SectionTitle(
        "Or type them here",
        "Use the base URL and voice token from Odysseus → Settings → Integrations → Voice assistant " +
            "(the same ones Home Assistant uses).",
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
    OutlinedTextField(
        value = deviceName,
        onValueChange = { deviceName = it; OdysseusLink.deviceName = it },
        singleLine = true,
        label = { Text("Device name told to Odysseus (e.g. Kitchen Echo; blank: VACA <id>)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            OdysseusLink.baseUrl = url
            if (token.isNotBlank()) OdysseusLink.setToken(token)
            token = ""
            status = "Saved. Testing…"
            scope.launch { status = OdysseusLink.testLink().second }
        }) { Text("Save and test") }
        OutlinedButton(onClick = {
            OdysseusLink.setToken(""); OdysseusLink.baseUrl = ""
            ManagedSettings.releaseAll("unlinked") // Odysseus fork: settings back to the device (odysseus/managed/)
            url = ""; token = ""; status = "Not linked"
        }) { Text("Unlink") }
    }
    Text(status, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Menu → Odysseus → Voice: what a voice turn shows and sends, follow-ups, wake word training. */
@Composable
private fun VoiceSettings() {
    var show by remember { mutableStateOf(OdysseusLink.showActions) }
    var checkVoice by remember { mutableStateOf(OdysseusLink.checkVoice) }
    var thinking by remember { mutableStateOf(OdysseusLink.thinkingSound) }
    SectionTitle("Voice turns")
    ManagedSwitchRow(show, "Show what a voice turn did", "Added and completed items, its sources, and questions waiting for a yes") {
        show = it; OdysseusLink.showActions = it
    }
    ManagedSwitchRow(checkVoice, "Check my voice", "Send the spoken command to Odysseus so Athena can tell if it's you. Not stored.") {
        checkVoice = it; OdysseusLink.checkVoice = it
    }
    ManagedSwitchRow(thinking, "Thinking sound", "A soft tone once when an answer takes longer than 5 seconds") {
        thinking = it; OdysseusLink.thinkingSound = it
    }
    SectionTitle("Follow-up answers")
    com.msp1974.vacompanion.odysseus.followup.FollowUpSettingsSection() // answer without the wake word (odysseus/followup/)
    com.msp1974.vacompanion.odysseus.wakeword.WakeWordTrainingSection() // real "Hey Athena" clips for retraining (odysseus/wakeword/)
}

/**
 * Menu → Odysseus → Home screen: the clock over the dashboard (odysseus/home/), what it reads
 * (weather, other speakers, Today). How it looks is the Clock face section.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreenSettings(onClockFace: () -> Unit) {
    // HomeSettings is Compose state, so these recompose when it changes.
    val enabled = HomeSettings.enabled
    val idle = HomeSettings.idleSeconds
    SectionTitle("Home screen")
    ManagedSwitchRow(
        enabled, "Clock home screen",
        "A clock and the playing song over the dashboard. Swipe right for the dashboard, left for the clock.",
        managedKey = "home.enabled",
    ) { HomeSettings.enabled = it }
    Text("Back to the clock after", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    SecondsChips(HomeSettings.IDLE_CHOICES_SECONDS, idle, "home.idle_seconds", enabled = enabled) { HomeSettings.idleSeconds = it }
    OutlinedButton(onClick = onClockFace, enabled = enabled) {
        Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  Change how the clock looks")
    }

    SectionTitle("Weather")

    // Weather in the clock's top left (odysseus/home/weather/).
    val weatherOn = HomeSettings.weatherEnabled
    val chosen = HomeSettings.weatherEntity
    val entities = WeatherRepository.entities
    ManagedSwitchRow(
        weatherOn, "Weather",
        "Icon, temperature and today's high and low in the clock's top left, from a Home Assistant weather entity.",
        managedKey = "home.weather_enabled",
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
    SectionTitle("Other speakers")
    OtherPlayersSettings()
    SectionTitle("Today")
    com.msp1974.vacompanion.odysseus.home.today.TodaySettingsSection()
}

/** Music on other speakers (odysseus/home/players/): on/off and which Home Assistant media player. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OtherPlayersSettings() {
    val on = HomeSettings.otherPlayersEnabled
    val chosen = HomeSettings.otherPlayersEntity
    val seen = OtherPlayers.seen
    ManagedSwitchRow(
        on, "Music on other speakers",
        "While this Echo plays nothing, show the song another speaker plays, with play/pause, next and previous. " +
            "Read from Home Assistant's media players (the Music page's list).",
    ) { HomeSettings.otherPlayersEnabled = it }
    Text("Speaker to follow", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = chosen.isBlank(),
            onClick = { HomeSettings.otherPlayersEntity = "" },
            label = { Text("Auto (this room first)") },
            enabled = on,
        )
        // The chosen player stays listed while it plays nothing.
        val names = seen.associate { it.entityId to it.name }.toMutableMap()
        if (chosen.isNotBlank() && chosen !in names) names[chosen] = chosen
        names.forEach { (id, name) ->
            FilterChip(
                selected = chosen == id,
                onClick = { HomeSettings.otherPlayersEntity = id },
                label = { Text(name) },
                enabled = on,
            )
        }
    }
    Text(
        if (seen.isEmpty()) "Speakers appear here while the clock or the dashboard shows."
        else "Listed: Music Assistant's players and the other media players that are on now.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Menu → Odysseus → Kiosk: coming back from other apps, reloading a failed dashboard (odysseus/kiosk/). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KioskSettingsSection() {
    KioskSettings.init(LocalContext.current)
    // KioskSettings is Compose state, so these recompose when it changes.
    val returnSeconds = KioskSettings.returnSeconds
    SectionTitle("Kiosk")
    ManagedSwitchRow(
        KioskSettings.autoReload, "Reload a dashboard that failed to load",
        "Tries again after 5 s, then waits longer each time, up to every 5 minutes. Soon after Wi-Fi comes back.",
        managedKey = "kiosk.auto_reload",
    ) { KioskSettings.autoReload = it }
    Text("Back from other apps after", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    SecondsChips(KioskSettings.RETURN_CHOICES_SECONDS, returnSeconds, "kiosk.return_seconds") { KioskSettings.returnSeconds = it }
    Text(
        "When another app has been in front this long, this app comes back. Not while the screen is off.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ManagedSwitchRow(
        KioskSettings.screenFullOff, "Screensaver turns the screen fully off",
        "Instead of black at the lowest backlight. Needs device admin. A touch can't wake an off screen: " +
            "the wake word, Home Assistant, announcements or the power button do. " +
            "Without this, it goes fully off only when neither a touch nor camera motion is set to end the screensaver.",
    ) { KioskSettings.screenFullOff = it }
}
