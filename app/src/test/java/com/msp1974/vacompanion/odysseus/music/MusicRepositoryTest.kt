package com.msp1974.vacompanion.odysseus.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** setSelfOverride (and probe) take this device's player from the reload, also while detached. */
    @Test
    fun reloadingTakesThisDevicesPlayer() = runBlocking {
        val ha = FakeHaApi().apply { templateAnswer = { MusicFixtures.text("rooms_template.json") } }
        var override: String? = null
        val b = HaMusicBackend(
            ha = ha, selfClientId = { null }, area = { null }, selfOverride = { override },
            isLocked = { false }, imageUrl = { it }, scope = scope,
        )
        MusicRepository.reloadSelf(b)
        assertEquals(null, MusicRepository.selfRoom)
        override = "media_player.kitchen_echo"
        MusicRepository.reloadSelf(b)
        assertEquals(SelfMatch("media_player.kitchen_echo", How.OVERRIDE), MusicRepository.selfRoom)
    }
}
