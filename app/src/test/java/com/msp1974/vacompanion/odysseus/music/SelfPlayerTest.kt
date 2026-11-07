package com.msp1974.vacompanion.odysseus.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfPlayerTest {
    private val uuid = "0f1e2d3c-fict-ion0-al00-uuid00000001"
    private val clientId = "Zk3vQ-fictional-client-id"

    private fun c(
        id: String, name: String = id, area: String = "", type: String? = "player",
        playerId: String? = null, available: Boolean = true,
    ) = Candidate(
        entityId = "media_player.$id",
        ids = listOfNotNull(playerId?.let { listOf("music_assistant", it) }),
        name = name, area = area, type = type, available = available,
    )

    private val kitchen = c("kitchen_echo", "Kitchen Echo", "Kitchen", playerId = clientId)
    private val living = c("living", "Living Room", "Living Room", playerId = "ma_living01")
    private val all = listOf(kitchen, living)

    @Test
    fun idMatchIsTheNormalCase() {
        assertEquals(SelfMatch("media_player.kitchen_echo", How.ID), matchSelf(clientId, uuid, "Kitchen", null, all))
    }

    @Test
    fun aRenameKeepsTheIdMatch() {
        val renamed = listOf(kitchen.copy(entityId = "media_player.breakfast_bar", name = "Breakfast Bar", area = "Dining"), living)
        assertEquals(SelfMatch("media_player.breakfast_bar", How.ID), matchSelf(clientId, uuid, "Kitchen", null, renamed))
    }

    @Test
    fun idMustBeMusicAssistantsIdentifier() {
        val other = listOf(kitchen.copy(ids = listOf(listOf("sendspin", clientId))), living)
        assertNull(matchSelf(clientId, uuid, null, null, other))
    }

    @Test
    fun overrideWinsWhileItIsACandidate() {
        assertEquals(
            SelfMatch("media_player.living", How.OVERRIDE),
            matchSelf(clientId, uuid, "Kitchen", "media_player.living", all),
        )
    }

    @Test
    fun staleOverrideCarriesOnAndIsReported() {
        val m = matchSelf(clientId, uuid, "Kitchen", "media_player.gone", all)
        assertEquals(SelfMatch("media_player.kitchen_echo", How.ID, staleOverride = true), m)
        assertTrue(isStaleOverride("media_player.gone", all))
        assertFalse(isStaleOverride("media_player.living", all))
        assertFalse(isStaleOverride("", all))
        assertFalse(isStaleOverride(null, all))
    }

    @Test
    fun legacyIdOnlyWhileAvailable() {
        val legacy = c("old_echo", "Old Echo", playerId = "vaca-$uuid")
        assertEquals(
            SelfMatch("media_player.old_echo", How.LEGACY_ID),
            matchSelf("new-key-id", uuid, null, null, listOf(legacy, living)),
        )
        assertNull(matchSelf("new-key-id", uuid, null, null, listOf(legacy.copy(available = false), living)))
    }

    @Test
    fun aNewKeyFallsBackToTheDefaultName() {
        val byName = c("vaca_sendspin", "vaca $uuid sendspin player", playerId = "some-older-id")
        assertEquals(
            SelfMatch("media_player.vaca_sendspin", How.NAME),
            matchSelf("a-new-key-id", uuid, "Kitchen", null, listOf(byName, living)),
        )
    }

    @Test
    fun twoWithTheDefaultNameIsNoMatchByName() {
        val a = c("a", "VACA $uuid Sendspin Player")
        val b = c("b", "VACA $uuid Sendspin Player")
        assertNull(matchSelf(null, uuid, null, null, listOf(a, b)))
    }

    @Test
    fun aNewKeyFallsBackToTheOnePlayerInTheArea() {
        val group = c("kitchen_group", "Kitchen Group", "Kitchen", type = "group")
        assertEquals(
            SelfMatch("media_player.kitchen_echo", How.AREA),
            matchSelf("a-new-key-id", uuid, "kitchen", null, listOf(kitchen, living, group)),
        )
    }

    @Test
    fun twoPlayersInOneAreaIsNull() {
        val second = c("kitchen_radio", "Kitchen Radio", "Kitchen")
        assertNull(matchSelf("a-new-key-id", uuid, "Kitchen", null, listOf(kitchen, second, living)))
    }

    @Test
    fun nothingKnownIsNull() {
        assertNull(matchSelf(null, "", null, null, all))
        assertNull(matchSelf(null, uuid, "", null, all))
        assertNull(matchSelf(clientId, uuid, "Kitchen", null, emptyList()))
    }
}
