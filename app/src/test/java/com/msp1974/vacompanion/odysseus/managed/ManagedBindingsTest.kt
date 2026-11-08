package com.msp1974.vacompanion.odysseus.managed

import org.junit.Assert.assertEquals
import org.junit.Test

/** C6's keys come from the stores' declarations: none may go missing, change type or range. */
class ManagedBindingsTest {

    /** ManagedKeys.all as it was written by hand before the stores declared their keys. */
    private val contract: Map<String, String> = mapOf(
        "home.enabled" to "bool",
        "home.idle_seconds" to "int 0..86400",
        "home.weather_enabled" to "bool",
        "today.enabled" to "bool",
        "kiosk.return_seconds" to "int 0..86400",
        "kiosk.auto_reload" to "bool",
        "record.enabled" to "bool",
        "record.clock_button" to "bool",
        "record.privacy_button" to "choice [mute, record]",
        "followup.enabled" to "bool",
        "followup.seconds" to "int 3..15",
        "music.enabled" to "bool",
        "music.stay_while_playing" to "bool",
    )

    private fun describe(k: ManagedKey): String = when (k) {
        is ManagedKey.Bool -> "bool"
        is ManagedKey.IntRange -> "int ${k.min}..${k.max}"
        is ManagedKey.Choice -> "choice ${k.choices.sorted()}"
    }

    @Test
    fun theDeclarationsMakeExactlyTheContractsKeys() {
        assertEquals(contract, ManagedKeys.all.mapValues { describe(it.value) })
    }

    @Test
    fun everyKeyIsBoundToAStoreOnce() {
        assertEquals(contract.keys.toList().sorted(), ManagedBindings.all.map { it.key }.sorted())
    }
}
