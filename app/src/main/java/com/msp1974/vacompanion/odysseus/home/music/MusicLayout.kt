package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: how the Music page splits its space (docs/odysseus/music.md, "Tablets"). Plain
// Kotlin, unit-tested (MusicLayoutTest). From 1100×600 dp the page has two panes: the selected
// tab on the left and a compact Now playing for the selected room on the right. The Echo Show 5
// (960×480 dp at mdpi, 721×360 dp at tvdpi) stays one pane: its height leaves no room for both.

object MusicLayout {
    /** Two panes from this page width (dp, the rail included)… */
    const val TWO_PANES_MIN_WIDTH_DP = 1100f

    /** …and this height (dp). */
    const val TWO_PANES_MIN_HEIGHT_DP = 600f

    private const val PANE_SHARE = 0.34f
    private const val PANE_MIN_DP = 380f
    private const val PANE_MAX_DP = 560f

    /** Whether a page of [widthDp] × [heightDp] has room for the Now playing pane beside a tab. */
    fun twoPanes(widthDp: Float, heightDp: Float): Boolean =
        widthDp >= TWO_PANES_MIN_WIDTH_DP && heightDp >= TWO_PANES_MIN_HEIGHT_DP

    /**
     * Whether the Now playing pane shows beside [selectedTab]: on two-pane screens, for every tab
     * but Playing itself (which then has the whole width: the pane would only repeat it).
     */
    fun sidePane(widthDp: Float, heightDp: Float, selectedTab: String): Boolean =
        twoPanes(widthDp, heightDp) && selectedTab != MusicTabs.NOW_PLAYING

    /** The Now playing pane's width (dp) on a page [widthDp] wide: a third, within 380–560 dp. */
    fun paneWidthDp(widthDp: Float): Float = (widthDp * PANE_SHARE).coerceIn(PANE_MIN_DP, PANE_MAX_DP)

    /**
     * Columns of the Rooms grid beside the pane, for a list [widthDp] wide: room cards stay at
     * least ~340 dp so a card's play, volume and menu fit beside its text; at most 3.
     */
    fun roomColumnsBesidePane(widthDp: Float): Int = (widthDp / 360f).toInt().coerceIn(1, 3)
}
