package com.msp1974.vacompanion.sendspin

/**
 * Odysseus fork: the addresses a Sendspin server may connect from when a preferred server is set.
 *
 * HA pushes the Music Assistant URL it knows (usually the LAN address) as the preferred server. A
 * device that reaches home over Tailscale sees Music Assistant arrive from its Tailscale address
 * instead, so the player also accepts the address Home Assistant connects to this device from
 * (`paired_device_id`). Music Assistant normally runs on the same host as Home Assistant, so that
 * one address works for devices on the LAN and devices on the tailnet alike, needs no setup and
 * survives a data wipe (HA pairs again from the address it uses).
 *
 * Plain Kotlin (no Android) so it is unit-tested.
 */
object SendspinAllowedAddresses {
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    fun isIpv4(value: String): Boolean = IPV4.matches(value)

    /**
     * Comma separated dotted IPv4 addresses for the native filter, or blank to accept any server.
     * Blank [preferredIp] (Auto) stays blank: adding HA's address would turn Auto into a filter.
     */
    fun of(preferredIp: String, homeAssistantIp: String): String {
        val preferred = preferredIp.trim()
        if (preferred.isEmpty()) return ""
        val ha = homeAssistantIp.trim()
        return if (isIpv4(ha) && ha != preferred) "$preferred,$ha" else preferred
    }
}
