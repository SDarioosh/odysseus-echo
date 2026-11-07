package com.msp1974.vacompanion.odysseus

// Odysseus fork: how the phone setup (EchoSetup.kt) waits for the Echo's network addresses.
// Right after boot Wi-Fi and Tailscale come up a few seconds after the app starts, so the
// setup looks again until it has an address, and swaps in an address that turns up later
// (usually Tailscale after Wi-Fi). No Android code here, so it's unit-tested on the JVM
// (app/src/test/.../odysseus/SetupAddressWaitTest.kt).

import kotlinx.coroutines.delay

object SetupAddressWait {
    /** The Echo's Tailscale and Wi-Fi/LAN IPv4 addresses at one moment; null when it has none. */
    data class Addresses(val tailscale: String?, val lan: String?) {
        val isEmpty get() = tailscale == null && lan == null
    }

    /** While setup is open and an address is missing, how often to look for it again. */
    const val WATCH_MS = 3_000L

    /** Wait before the next look: every second just after boot, then every 3 seconds. */
    fun retryDelayMs(attempt: Int): Long = if (attempt < 10) 1_000L else 3_000L

    /**
     * Calls [probe] until it finds an address. Returns null when [isCurrent] turns false (setup
     * closed or restarted) or [giveUpMs] has passed without one.
     */
    suspend fun await(
        probe: suspend () -> Addresses,
        isCurrent: () -> Boolean,
        giveUpMs: Long,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): Addresses? {
        val start = now()
        var attempt = 0
        while (true) {
            if (!isCurrent()) return null
            val found = probe()
            if (!found.isEmpty) return found
            if (now() - start >= giveUpMs) return null
            sleep(retryDelayMs(attempt++))
        }
    }

    /** True when [fresh] has a kind of address [old] lacked, so the QR code should be chosen again. */
    fun gained(old: Addresses, fresh: Addresses): Boolean =
        (old.tailscale == null && fresh.tailscale != null) || (old.lan == null && fresh.lan != null)

    /** Nothing left to wait for: both kinds of address are there. */
    fun complete(a: Addresses): Boolean = a.tailscale != null && a.lan != null
}
