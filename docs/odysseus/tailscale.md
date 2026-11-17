# Tailscale: reaching Home Assistant from anywhere

Status: installed on the device by the owner (reported 2026-11-05). The steps below are the plan it followed; the open questions at the end still stand until the owner confirms them.

## Why and how it fits

- VACA has two connections, one in each direction:
  - **HA → device**: HA's VACA integration opens a Wyoming TCP connection to the device on
    port 10800 (no authentication on that port).
  - **device → HA**: the dashboard WebView, token refresh and custom-file downloads (wake
    words, sounds) go to the HA URL.
- At home both work over the LAN. Over Tailscale both work too, through the device's 100.x
  address. Android's VpnService hands incoming tailnet packets to the kernel, so a socket on
  `0.0.0.0:10800` accepts them. Tailscale's `AllowIncomingConnections` defaults to on.
- HA's zeroconf discovery doesn't cross the tailnet, so the device is added by hand
  (Settings → Devices → Add integration → View Assist Companion App → host = the device's
  MagicDNS name or 100.x IP, port 10800).
- VACA pairs with the first HA IP that starts its satellite and rejects every other IP. HA
  always connects from the same address for one config entry, so this stays stable. To move
  from LAN to tailnet addressing, use "Unpair" in the device's settings and add it again.

## App

- Official Tailscale Android app, GitHub release
  [1.104.4](https://github.com/tailscale/tailscale-android/releases/tag/1.104.4-t938213198-gb9d487fe6)
  (`tailscale-android-universal-1.104.4.apk`, sha256
  `47628cecde2de0ed03f4a69424635259023daf76fb5d9afeeddfa62761690984`). Package
  `com.tailscale.ipn`, minSdk 26, ships `armeabi-v7a`, works without Google Play services. It
  is also on F-Droid.
- Login: the app can show a login URL / QR code ("log in from another device"). The owner
  signs in on a phone, so no password is typed on the Echo Show. Unattended login with an
  `AuthKey` would need a device-owner app to set managed configuration. That is not worth
  it for one device.

## Privacy

- Traffic between the device and HA is end-to-end WireGuard between the owner's own nodes.
  Tailscale's coordination server sees node keys, IPs and endpoints, not traffic. That is the
  same as the owner's other Tailscale devices.
- The Tailscale client uploads its own debug logs (connection metadata, no traffic content)
  to `log.tailscale.com` by default. On Android this can't be turned off without managed
  configuration. **Owner decision:** accept, as on the owner's other devices, or block
  `log.tailscale.com` for this device on the router/DNS (Tailscale keeps working).
- Use split tunnelling so only the satellite app goes through Tailscale (Tailscale settings →
  App split tunnelling), and leave the exit node off. LAN access keeps working.

## adb steps (each undoable)

| # | Command | Why | Undo |
|---|---|---|---|
| 1 | `adb install tailscale-android-universal-1.104.4.apk` (check the sha256) | install | `adb uninstall com.tailscale.ipn` |
| 2 | `adb shell dumpsys deviceidle whitelist +com.tailscale.ipn` | keep the tunnel up in Doze | `… -com.tailscale.ipn` |
| 3 | open the app, log in from a phone with the QR code / URL | join the tailnet | log out in the app |
| 4 | `adb shell settings put secure always_on_vpn_app com.tailscale.ipn`; `adb shell settings put secure always_on_vpn_lockdown 0` | start at boot and reconnect; lockdown off, so the LAN and the rest still work if Tailscale is down | `settings delete secure always_on_vpn_app` |
| 5 | in HA: add the VACA device by MagicDNS name, port 10800 | HA → device over the tailnet | delete the config entry |
| 6 | Tailscale ACL: allow only the HA host to reach `tag:echo-show:10800` | port 10800 has no auth | edit the ACL |

Cost on a 1 GB device: about 40–60 MB RAM for the Tailscale service. Battery doesn't matter
(the device is on mains power).

## Open questions

- Accept or block `log.tailscale.com` (see Privacy).
- Which HA URL the device uses off-LAN: the HA host's MagicDNS name works on both the LAN
  and the tailnet, so it's the simplest single setting for `ha_url`.

## Music Assistant (Sendspin) over Tailscale

- The Sendspin player waits for Music Assistant to connect to it (port 8928). MA finds
  players by mDNS, which doesn't cross the tailnet, so add the device's 100.x address under
  MA → Settings → Players → Sendspin → **Manual IP addresses for discovery**.
- With one MA server, HA's VACA integration always pushes that server's URL as
  `sendspin_preferred_server` (the "Sendspin Music Assistant Server" select only appears with
  two or more). The URL is the LAN address, but over the tailnet MA connects from its
  Tailscale address, so the player refuses it:
  `refusing server '<name>' (<server id>): not the preferred server <LAN IP> or a trusted server id`.
- Fix (fork, since 0.14.0+odysseus.32, no setup): the player also accepts the address Home
  Assistant connects to the device from (`paired_device_id`). Music Assistant runs on the HA host,
  so over the tailnet it arrives from that same Tailscale address, and on the LAN from the LAN
  address in the URL. One build works for LAN-only and Tailscale devices and survives a data wipe.
  Logcat: `Sendspin: allowed server addresses '<LAN IP>,<HA tailnet IP>'`. If Music Assistant runs
  on another host than HA, use pairing or the trusted ids below.
- Older fix (fork): `sendspin_trusted_server_ids`, an on-device setting HA never touches. Needs a
  debug build (`run-as`) to set, and a data wipe removes it. Server ids
  in it (comma separated) are accepted from any address; every other server still has to
  match the preferred server. Set it with the id from that log line:

  ```sh
  P=com.msp1974.vacompanion.odysseus
  F=shared_prefs/${P}_preferences.xml
  adb shell am force-stop $P
  adb shell "run-as $P sed -i 's|</map>|    <string name=\"sendspin_trusted_server_ids\"><server id></string>\n</map>|' $F"
  adb shell monkey -p $P -c android.intent.category.LAUNCHER 1
  ```

  Undo: the same with the entry removed. Check: `adb shell run-as $P grep trusted $F`, then
  logcat shows no more "refusing server" and MA plays.
- If the Tailscale ACL limits who reaches the device, also allow the MA host to port 8928.
- Since 0.14.0+odysseus.24 (encrypted Sendspin): once Music Assistant is **paired** with the device
  it is accepted from any address, LAN or Tailscale, without a trusted id. The trusted-id setting
  above stays for an unpaired server. See `sendspin/README.md` "Encryption and pairing".
- Before that fix, and still working: trusted Music Assistant server ids
  (`sendspin_trusted_server_ids`, on-device only) are accepted from any address. A paired server
  is too (`sendspin/README.md`, "Encryption and pairing").
- Album art comes over the Sendspin connection itself, since MA's `artwork_url` is a LAN address
  (`sendspin/README.md`, "Album art pushed over the connection"). When the app stops, the player
  says goodbye with `restart`, so MA re-dials a manually added address ("Goodbye reason").
- Files: `sendspin/SendspinAllowedAddresses.kt` (which addresses pass), `sendspin/SendspinPlayer.kt`,
  `settings/Settings.kt` (the settings); in the `:sendspin` module `src/main/cpp/sendspin_jni.cpp`
  (the check on inbound sockets) and `NativeSendspin.kt`.
