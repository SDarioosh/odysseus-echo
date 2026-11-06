package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: Menu → Odysseus → "Lock outside work hours" (LockSettings, LockMode). Called with
// one line from OdysseusSettingsLayout. Off by default. Once a PIN is set, every change here
// (turning it off, the times, a new PIN) asks for the PIN first, so someone at the device during
// the work hours can't turn it off. "Lock now" needs no PIN. See docs/odysseus/lock-mode.md.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LockSettingsSection() {
    LockSettings.init(LocalContext.current)
    val colors = MaterialTheme.colorScheme
    // LockSettings is Compose state, so this recomposes when it changes.
    val enabled = LockSettings.enabled
    val hasPin = LockSettings.hasPin
    val open = LockSettings.openMinute
    val close = LockSettings.closeMinute
    val until by LockMode.until.collectAsState()
    // The PIN was typed in this visit to the menu; forgotten when the menu closes.
    var authorized by remember { mutableStateOf(false) }
    val canChange = !hasPin || authorized
    var switchNote by remember { mutableStateOf("") }

    Text(
        "Lock outside work hours", fontSize = 18.sp, color = colors.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = enabled,
            // Tappable without a PIN too, so it can say why it doesn't turn on.
            onCheckedChange = {
                if (!LockSettings.hasPin) switchNote = "Set a PIN first, just below."
                else { switchNote = ""; LockMode.setEnabled(it) }
            },
            enabled = canChange,
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text("Lock mode", fontSize = 16.sp, color = colors.onSurface)
            Text(
                "Monday to Friday, open from ${LockRules.timeLabel(open)} to ${LockRules.timeLabel(close)}; " +
                    "locked the rest of the time and all weekend. While locked the screen shows only a clock " +
                    "and a PIN pad, and the microphone, music, announcements, recording and this menu are off. " +
                    "The PIN unlocks it until the next lock time. Takes effect as soon as it is turned on.",
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            Text(
                when {
                    !hasPin -> "Set a PIN below to turn it on."
                    !enabled -> "Off."
                    else -> "On. Now unlocked until ${LockMode.label(until)}."
                },
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            if (switchNote.isNotEmpty()) Text(switchNote, fontSize = 13.sp, color = colors.error)
        }
    }

    if (!canChange) {
        PinGate(onOk = { authorized = true })
    } else {
        // No PIN yet: its fields come first, right under the switch. A PIN saved here counts as
        // typed for this visit, so the switch works at once.
        if (!hasPin) PinSetter(false) { authorized = true; switchNote = "" }
        TimeRow("Opens at", open) { m -> LockSettings.setTimes(m, close); LockMode.evaluate("times changed") }
        TimeRow("Locks at", close) { m -> LockSettings.setTimes(open, m); LockMode.evaluate("times changed") }
        if (hasPin) PinSetter(true) { authorized = true }
    }

    if (enabled) {
        OutlinedButton(onClick = { LockMode.lockNow("Lock now") }) {
            Text("Lock now (until ${LockRules.timeLabel(open)} next work day)")
        }
    }
    Text(
        "From Home Assistant: action vaca.send_event on this device, event navigate, value " +
            "{\"url\": \"odysseus://lock\"} or {\"url\": \"odysseus://unlock\"}. A lock holds until the " +
            "next opening, an unlock until the next lock time.",
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
}

@Composable
private fun TimeRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(90.dp))
        OutlinedButton(onClick = { onChange(minute - LockRules.STEP_MINUTES) }, enabled = minute >= LockRules.STEP_MINUTES) { Text("−") }
        Text(LockRules.timeLabel(minute), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        OutlinedButton(onClick = { onChange(minute + LockRules.STEP_MINUTES) }, enabled = minute + LockRules.STEP_MINUTES < 24 * 60) { Text("+") }
    }
}

@Composable
private fun PinGate(onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    Text("Enter the PIN to change these settings.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PinField(pin, "PIN") { pin = it }
        Button(onClick = {
            message = when (LockMode.checkPin(pin)) {
                LockMode.PinResult.OK -> { onOk(); "" }
                LockMode.PinResult.WRONG -> "Wrong PIN"
                LockMode.PinResult.WAIT -> "Too many tries. Try again in ${(LockMode.waitRemainingMs() + 999) / 1000} s."
                LockMode.PinResult.NO_PIN -> ""
            }
            pin = ""
        }) { Text("OK") }
    }
    if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun PinSetter(hasPin: Boolean, onSaved: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    Text(if (hasPin) "Change the PIN" else "PIN (4 to 8 digits)", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PinField(first, "New PIN") { first = it }
        PinField(second, "Again") { second = it }
        Button(onClick = {
            message = when {
                !LockRules.validPin(first) -> "Use 4 to 8 digits"
                first != second -> "The two PINs differ"
                LockSettings.setPin(first) -> { onSaved(); if (hasPin) "PIN saved" else "PIN saved. Now turn Lock mode on above." }
                else -> "Couldn't save the PIN"
            }
            first = ""; second = ""
        }) { Text("Save PIN") }
    }
    if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(LockRules.PIN_MAX_LENGTH)) },
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.width(150.dp),
    )
}
