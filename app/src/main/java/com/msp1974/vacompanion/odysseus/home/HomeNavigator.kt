package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the home screen's navigation rules, as plain Kotlin (no Android, no Compose) so
// they are unit-tested (HomeNavigatorTest). HomeScreenController wraps it in Compose state.
//
// Layout, left to right:  [Dashboard]  [page 0 = clock]  [page 1]  …
// Swiping right (finger moves right) shows what is to the left; swiping left shows what is to
// the right. With nothing to show the swipe is not consumed, so it still reaches Home Assistant.
// After `idleTimeoutMs` without a touch the screen goes back to page 0 (the clock).

/** Where the home screen is. */
sealed interface HomeDestination {
    /** The Home Assistant dashboard (the WebView). */
    data object Dashboard : HomeDestination

    /** One of the native pages, by index into [HomePages.all]; 0 is the clock. */
    data class Page(val index: Int) : HomeDestination
}

/** Direction the finger moved. */
enum class SwipeDirection { LEFT, RIGHT }

class HomeNavigator(
    /** Number of native pages. 0 behaves like the home screen being off. */
    private val pageCount: () -> Int,
    /** Whether the home screen is on at all; off = always the dashboard, no swipes consumed. */
    private val enabled: () -> Boolean,
    /** Idle time before going back to page 0; 0 or less = never. */
    private val idleTimeoutMs: () -> Long,
) {
    var destination: HomeDestination = HomeDestination.Page(0)
        private set

    /** Time of the last touch or navigation, in the caller's clock (elapsedRealtime). */
    var lastActivityMs: Long = 0L
        private set

    private val active: Boolean get() = enabled() && pageCount() > 0

    /**
     * What should be shown now: [destination], corrected for settings changed meanwhile. A page
     * index past the end (its page went) is moved to the last page for good, so the page coming
     * back later doesn't pull the screen back to it.
     */
    fun current(): HomeDestination {
        if (!active) return HomeDestination.Dashboard
        val d = destination
        if (d is HomeDestination.Page && d.index >= pageCount()) {
            return HomeDestination.Page(pageCount() - 1).also { destination = it }
        }
        return d
    }

    /** Any touch on the screen: restarts the idle timer. */
    fun onUserActivity(nowMs: Long) {
        lastActivityMs = nowMs
    }

    /** Applies a swipe. Returns true if it moved the home screen (the caller then consumes it). */
    fun onSwipe(direction: SwipeDirection, nowMs: Long): Boolean {
        onUserActivity(nowMs)
        if (!active) return false
        val target: HomeDestination = when (val d = current()) {
            HomeDestination.Dashboard -> when (direction) {
                SwipeDirection.LEFT -> HomeDestination.Page(0)
                SwipeDirection.RIGHT -> return false
            }
            is HomeDestination.Page -> when (direction) {
                SwipeDirection.RIGHT ->
                    if (d.index == 0) HomeDestination.Dashboard else HomeDestination.Page(d.index - 1)
                SwipeDirection.LEFT ->
                    if (d.index + 1 < pageCount()) HomeDestination.Page(d.index + 1) else return false
            }
        }
        destination = target
        return true
    }

    /** Jumps straight to [target] (e.g. a future "show dashboard" command from Home Assistant). */
    fun goTo(target: HomeDestination, nowMs: Long) {
        destination = target
        onUserActivity(nowMs)
    }

    /**
     * When the idle timer will send the screen back to page 0, or null if it won't (already
     * there, home screen off, or timeout "never").
     */
    fun idleDeadlineMs(): Long? {
        val timeout = idleTimeoutMs()
        if (!active || timeout <= 0 || current() == HomeDestination.Page(0)) return null
        return lastActivityMs + timeout
    }

    /** Goes back to page 0 if the idle deadline has passed. Returns true if it moved. */
    fun checkIdle(nowMs: Long): Boolean {
        val deadline = idleDeadlineMs() ?: return false
        if (nowMs < deadline) return false
        destination = HomeDestination.Page(0)
        return true
    }
}
