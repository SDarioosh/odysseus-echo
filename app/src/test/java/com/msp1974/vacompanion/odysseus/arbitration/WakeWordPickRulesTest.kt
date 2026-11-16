package com.msp1974.vacompanion.odysseus.arbitration

import com.msp1974.vacompanion.broadcasts.WakeWordArbiter.Contender
import com.msp1974.vacompanion.odysseus.arbitration.WakeWordPickRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordPickRulesTest {

    private fun device(sender: String, level: Float?, score: Float = 0.97f, responding: Boolean = false) =
        Contender(id = "$sender-1", sender = sender, score = score, levelDb = level, responding = responding)

    private fun pick(vararg contenders: Contender, lastHandler: String? = null) =
        WakeWordPickRules.pick(contenders.toList(), lastHandler)

    // ---- which rule decided, for the log

    @Test
    fun aLoneReporterWinsAsTheOnlyOne() {
        val p = pick(device("a", -60f))
        assertEquals("a", p.winner.sender)
        assertEquals(Rule.ONLY_ONE, p.rule)
    }

    @Test
    fun aLoneRespondingDeviceWinsAsResponding() {
        val p = pick(device("a", -20f), device("b", -50f, responding = true))
        assertEquals("b", p.winner.sender)
        assertEquals(Rule.RESPONDING, p.rule)
    }

    @Test
    fun theLoudestWinsWithItsLead() {
        val p = pick(device("a", -31f), device("b", -28.6f), device("c", -40f))
        assertEquals("b", p.winner.sender)
        assertEquals(Rule.LOUDEST, p.rule)
        assertEquals(2.4f, p.leadDb!!, 0.001f)
    }

    // ---- same-room cases

    @Test
    fun aSmallButRealLeadWinsWhereUpstreamWouldHaveFallenBackToTheScore() {
        // Two devices 1 m and 1.5 m away: about 2 dB apart. Upstream: under 3 dB, so the score (b).
        val p = pick(device("a", -30f, score = 0.95f), device("b", -32f, score = 0.99f))
        assertEquals("a", p.winner.sender)
    }

    @Test
    fun walkingUpToTheOtherDeviceSwitchesToItDespiteBeingTheLastToAnswer() {
        // b answered last, but the speaker is now clearly nearer a.
        val p = pick(device("a", -28f), device("b", -30.5f), lastHandler = "b")
        assertEquals("a", p.winner.sender)
        assertEquals(Rule.LOUDEST, p.rule)
    }

    @Test
    fun standingBetweenTwoDevicesStaysWithTheOneThatAnsweredLast() {
        val p = pick(device("a", -30f), device("b", -31.2f), lastHandler = "b")
        assertEquals("b", p.winner.sender)
        assertEquals(Rule.LAST_ANSWERED, p.rule)
        assertEquals(-1.2f, p.leadDb!!, 0.001f)
    }

    @Test
    fun theStickyMarginIsInclusive() {
        assertEquals("b", pick(device("a", -30f), device("b", -30f - WakeWordPickRules.STICKY_MARGIN_DB), lastHandler = "b").winner.sender)
        assertEquals("a", pick(device("a", -30f), device("b", -30.01f - WakeWordPickRules.STICKY_MARGIN_DB), lastHandler = "b").winner.sender)
    }

    @Test
    fun theLastToAnswerBeingTheLoudestIsJustTheLoudest() {
        val p = pick(device("a", -30f), device("b", -31f), lastHandler = "a")
        assertEquals("a", p.winner.sender)
        assertEquals(Rule.LOUDEST, p.rule)
    }

    @Test
    fun threeDevicesTheLastToAnswerFarBehindIsIgnored() {
        val p = pick(device("a", -30f), device("b", -30.5f), device("c", -36f), lastHandler = "c")
        assertEquals("a", p.winner.sender)
    }

    @Test
    fun threeDevicesTheLastToAnswerCloseToTheLoudestKeepsIt() {
        val p = pick(device("a", -30f), device("b", -30.5f), device("c", -31f), lastHandler = "c")
        assertEquals("c", p.winner.sender)
    }

    // ---- ties

    @Test
    fun equalLevelsGoToTheHigherScore() {
        val p = pick(device("a", -30f, score = 0.90f), device("b", -30f, score = 0.99f))
        assertEquals("b", p.winner.sender)
        assertEquals(Rule.SCORE, p.rule)
    }

    @Test
    fun equalLevelsAndScoresGoToTheLowestId() {
        val p = pick(device("b", -30f), device("a", -30f))
        assertEquals("a", p.winner.sender)
        assertEquals(Rule.DEVICE_ID, p.rule)
    }

    @Test
    fun theOrderOfTheReportsDoesNotMatter() {
        val reports = listOf(device("a", -33f, score = 0.9f), device("b", -32f, score = 0.8f), device("c", -32f, score = 0.95f))
        val winners = listOf(reports, reports.reversed(), listOf(reports[1], reports[2], reports[0]))
            .map { WakeWordPickRules.pick(it, null).winner.sender }
        assertEquals(listOf("c", "c", "c"), winners)
    }

    // ---- no levels

    @Test
    fun withoutLevelsTheLastToAnswerThenTheScoreThenTheId() {
        assertEquals(Rule.LAST_ANSWERED_NO_LEVEL, pick(device("a", null), device("b", -30f), lastHandler = "a").rule)
        val byScore = pick(device("a", null, score = 0.8f), device("b", -30f, score = 0.9f))
        assertEquals("b", byScore.winner.sender)
        assertEquals(Rule.SCORE_NO_LEVEL, byScore.rule)
        assertNull(byScore.leadDb)
        val byId = pick(device("b", null), device("a", null))
        assertEquals("a", byId.winner.sender)
        assertEquals(Rule.DEVICE_ID_NO_LEVEL, byId.rule)
    }

    // ---- responding (rule 0) unchanged

    @Test
    fun aRespondingDeviceWinsOverAMuchLouderOneAndTheLastToAnswer() {
        val p = pick(device("a", -20f), device("b", -45f, responding = true), lastHandler = "a")
        assertEquals("b", p.winner.sender)
    }

    @Test
    fun severalRespondingTheUsualRulesChooseAmongThem() {
        val p = pick(device("a", -10f), device("b", -50f, responding = true), device("c", -40f, responding = true))
        assertEquals("c", p.winner.sender)
        assertEquals(Rule.LOUDEST, p.rule)
    }

    // ---- a device's own reports

    @Test
    fun aBargeInsOwnInterruptionCountsAsItsWin() {
        assertTrue(WakeWordPickRules.isOwnReport("1a2b3c4d-aaaa-bbbb-7", "1a2b3c4d-aaaa-bbbb"))
    }

    @Test
    fun anotherDevicesReportIsNotOwn() {
        assertFalse(WakeWordPickRules.isOwnReport("5e6f-3", "1a2b"))
        // A device whose id begins like ours but is longer is someone else.
        assertFalse(WakeWordPickRules.isOwnReport("1a2b3c-3", "1a2b"))
        assertFalse(WakeWordPickRules.isOwnReport("-3", ""))
    }
}
