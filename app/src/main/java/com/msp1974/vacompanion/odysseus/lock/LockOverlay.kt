package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: the lock screen of Lock mode, drawn over everything in MainActivity (dashboard,
// clock home screen, settings menu, cards) while LockMode.locked. Every touch lands here, so
// nothing underneath can be reached. A dim clock and when it opens; a tap shows the PIN pad, which
// hides again after 20 s without a touch. The right PIN unlocks until the next scheduled lock time.
// See docs/odysseus/lock-mode.md.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Dim = Color(0xFF8A9099)
private val Faint = Color(0xFF50555C)
private val KeyBg = Color(0xFF1E2126)
private val Bad = Color(0xFFFFB74D)
private const val PAD_IDLE_MS = 20_000L

@Composable
fun LockOverlay() {
    val locked by LockMode.locked.collectAsState()
    if (!locked) return
    val until by LockMode.until.collectAsState()

    var padShown by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var clock by remember { mutableStateOf(nowLabel()) }
    var waitSecs by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            clock = nowLabel()
            waitSecs = ((LockMode.waitRemainingMs() + 999) / 1000).toInt()
            if (padShown && System.currentTimeMillis() - lastTouch > PAD_IDLE_MS) {
                padShown = false; pin = ""; message = ""
            }
            delay(1000)
        }
    }

    fun touched() { lastTouch = System.currentTimeMillis() }

    fun submit() {
        when (LockMode.unlockWithPin(pin)) {
            LockMode.PinResult.OK -> { message = ""; padShown = false }
            LockMode.PinResult.WRONG -> message = "Wrong PIN"
            LockMode.PinResult.WAIT -> message = "Too many tries. Wait a moment."
            LockMode.PinResult.NO_PIN -> message = "No PIN set"
        }
        pin = ""
        waitSecs = ((LockMode.waitRemainingMs() + 999) / 1000).toInt()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow every touch and gesture so nothing underneath (dashboard, menu) gets it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        touched()
                        if (!padShown) padShown = true
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clock, color = Dim, fontSize = 72.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(8.dp))
                Text("Locked", color = Dim, fontSize = 22.sp)
                Text(
                    if (until != null) "Opens ${LockMode.label(until)}" else "Locked until unlocked",
                    color = Faint, fontSize = 16.sp,
                )
                if (!padShown) {
                    Spacer(Modifier.height(16.dp))
                    Text("Tap to enter the PIN", color = Faint, fontSize = 14.sp)
                }
            }
            if (padShown) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (pin.isEmpty()) "Enter PIN" else "●".repeat(pin.length),
                        color = Color.White, fontSize = 22.sp,
                    )
                    val note = if (waitSecs > 0) "Try again in $waitSecs s" else message
                    Text(note, color = Bad, fontSize = 14.sp, modifier = Modifier.height(22.dp))
                    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            row.forEach { key ->
                                Key(key) {
                                    touched()
                                    when (key) {
                                        "⌫" -> pin = pin.dropLast(1)
                                        "OK" -> if (pin.isNotEmpty() && waitSecs == 0) submit()
                                        else -> if (pin.length < LockRules.PIN_MAX_LENGTH) { pin += key; message = "" }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(KeyBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Text(label, color = Color.White, fontSize = if (label.length > 1) 16.sp else 24.sp)
    }
}

private fun nowLabel(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
