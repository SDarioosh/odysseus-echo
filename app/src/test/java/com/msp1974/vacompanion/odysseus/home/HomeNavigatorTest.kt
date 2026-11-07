package com.msp1974.vacompanion.odysseus.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigatorTest {

    private var pages = 1
    private var enabled = true
    private var timeoutMs = 120_000L
    private val nav = HomeNavigator({ pages }, { enabled }, { timeoutMs })

    private val dashboard = HomeDestination.Dashboard
    private fun page(i: Int) = HomeDestination.Page(i)

    @Test
    fun startsOnTheClock() {
        assertEquals(page(0), nav.current())
    }

    @Test
    fun swipeRightFromTheClockShowsTheDashboard() {
        assertTrue(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertEquals(dashboard, nav.current())
    }

    @Test
    fun swipeRightOnTheDashboardIsNotConsumed() {
        nav.onSwipe(SwipeDirection.RIGHT, 0)
        assertFalse(nav.onSwipe(SwipeDirection.RIGHT, 10))
        assertEquals(dashboard, nav.current())
    }

    @Test
    fun swipeLeftOnTheDashboardGoesBackToTheClock() {
        nav.onSwipe(SwipeDirection.RIGHT, 0)
        assertTrue(nav.onSwipe(SwipeDirection.LEFT, 10))
        assertEquals(page(0), nav.current())
    }

    @Test
    fun swipeLeftOnTheLastPageIsNotConsumed() {
        assertFalse(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertEquals(page(0), nav.current())
    }

    @Test
    fun movesThroughSeveralPages() {
        pages = 3
        assertTrue(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertEquals(page(1), nav.current())
        assertTrue(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertEquals(page(2), nav.current())
        assertFalse(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertEquals(page(2), nav.current())
        assertTrue(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertEquals(page(1), nav.current())
        assertTrue(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertEquals(page(0), nav.current())
        assertTrue(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertEquals(dashboard, nav.current())
        assertTrue(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertEquals(page(0), nav.current())
    }

    @Test
    fun whenDisabledItIsAlwaysTheDashboardAndSwipesPassThrough() {
        enabled = false
        assertEquals(dashboard, nav.current())
        assertFalse(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertFalse(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertEquals(dashboard, nav.current())
        assertNull(nav.idleDeadlineMs())
        assertFalse(nav.checkIdle(Long.MAX_VALUE))
    }

    @Test
    fun turningItBackOnReturnsToWhereItWas() {
        enabled = false
        enabled = true
        assertEquals(page(0), nav.current())
    }

    @Test
    fun noPagesBehavesLikeDisabled() {
        pages = 0
        assertEquals(dashboard, nav.current())
        assertFalse(nav.onSwipe(SwipeDirection.LEFT, 0))
        assertFalse(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertNull(nav.idleDeadlineMs())
    }

    @Test
    fun pageIndexIsClampedWhenPagesAreRemoved() {
        pages = 3
        nav.onSwipe(SwipeDirection.LEFT, 0)
        nav.onSwipe(SwipeDirection.LEFT, 0)
        assertEquals(page(2), nav.current())
        pages = 2
        assertEquals(page(1), nav.current())
        assertTrue(nav.onSwipe(SwipeDirection.RIGHT, 0))
        assertEquals(page(0), nav.current())
    }

    @Test
    fun aPageThatComesBackDoesNotPullTheScreenToIt() {
        // On the Today page; it goes (unlinked), the clock shows; later it comes back (linked
        // again): the screen stays where it is rather than jumping to it.
        pages = 2
        nav.onSwipe(SwipeDirection.LEFT, 0)
        assertEquals(page(1), nav.current())
        pages = 1
        assertEquals(page(0), nav.current())
        pages = 2
        assertEquals(page(0), nav.current())
    }

    @Test
    fun noIdleDeadlineOnTheClock() {
        nav.onUserActivity(1_000)
        assertNull(nav.idleDeadlineMs())
        assertFalse(nav.checkIdle(1_000_000))
    }

    @Test
    fun noIdleDeadlineWhenTimeoutIsNever() {
        timeoutMs = 0
        nav.onSwipe(SwipeDirection.RIGHT, 1_000)
        assertNull(nav.idleDeadlineMs())
        assertFalse(nav.checkIdle(Long.MAX_VALUE))
        assertEquals(dashboard, nav.current())
    }

    @Test
    fun idleDeadlineIsLastActivityPlusTimeoutOnTheDashboard() {
        nav.onSwipe(SwipeDirection.RIGHT, 1_000)
        assertEquals(1_000, nav.lastActivityMs)
        assertEquals(121_000L, nav.idleDeadlineMs())
    }

    @Test
    fun activityPushesTheDeadlineOut() {
        nav.onSwipe(SwipeDirection.RIGHT, 1_000)
        nav.onUserActivity(60_000)
        assertEquals(180_000L, nav.idleDeadlineMs())
        assertFalse(nav.checkIdle(121_000))
        assertEquals(dashboard, nav.current())
    }

    @Test
    fun checkIdleReturnsToTheClockAtTheDeadline() {
        nav.onSwipe(SwipeDirection.RIGHT, 1_000)
        assertFalse(nav.checkIdle(120_999))
        assertEquals(dashboard, nav.current())
        assertTrue(nav.checkIdle(121_000))
        assertEquals(page(0), nav.current())
        assertNull(nav.idleDeadlineMs())
    }

    @Test
    fun idleReturnsFromALaterPageToo() {
        pages = 2
        nav.onSwipe(SwipeDirection.LEFT, 0)
        assertEquals(120_000L, nav.idleDeadlineMs())
        assertTrue(nav.checkIdle(120_000))
        assertEquals(page(0), nav.current())
    }

    @Test
    fun timeoutChangeTakesEffectAtOnce() {
        nav.onSwipe(SwipeDirection.RIGHT, 1_000)
        timeoutMs = 60_000
        assertEquals(61_000L, nav.idleDeadlineMs())
    }

    @Test
    fun goToJumpsAndCountsAsActivity() {
        nav.goTo(dashboard, 5_000)
        assertEquals(dashboard, nav.current())
        assertEquals(5_000, nav.lastActivityMs)
        assertEquals(125_000L, nav.idleDeadlineMs())
        nav.goTo(page(0), 6_000)
        assertEquals(page(0), nav.current())
    }

    // ---- idle exemption (HomePage.idleExempt: the Music page while music plays here) ----

    private var exemptPages = setOf<Int>()
    private val exemptNav = HomeNavigator({ pages }, { enabled }, { timeoutMs }, { it in exemptPages })

    @Test
    fun anExemptPageHasNoIdleDeadline() {
        pages = 3
        exemptPages = setOf(2)
        exemptNav.goTo(page(2), 1_000)
        assertNull(exemptNav.idleDeadlineMs(500_000))
        assertFalse(exemptNav.checkIdle(10_000_000))
        assertEquals(page(2), exemptNav.current())
    }

    @Test
    fun exemptionOnlyCountsForThePageShown() {
        pages = 3
        exemptPages = setOf(2)
        exemptNav.goTo(page(1), 1_000)
        assertEquals(121_000L, exemptNav.idleDeadlineMs(2_000))
        assertTrue(exemptNav.checkIdle(121_000))
        assertEquals(page(0), exemptNav.current())
    }

    @Test
    fun idleTimeRestartsWhenTheExemptionEnds() {
        pages = 3
        exemptPages = setOf(2)
        exemptNav.goTo(page(2), 1_000)
        assertNull(exemptNav.idleDeadlineMs(600_000))
        exemptPages = emptySet() // the music stopped
        assertEquals(800_000L + 120_000L, exemptNav.idleDeadlineMs(800_000))
        assertFalse(exemptNav.checkIdle(900_000))
        assertTrue(exemptNav.checkIdle(920_000))
        assertEquals(page(0), exemptNav.current())
    }

    @Test
    fun defaultNavigatorHasNoExemptPages() {
        pages = 2
        nav.goTo(page(1), 0)
        assertEquals(120_000L, nav.idleDeadlineMs(10))
    }
}
