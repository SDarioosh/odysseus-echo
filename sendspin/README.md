# :sendspin

Native [Sendspin](https://www.sendspin-audio.com/) player support. The protocol, clock sync, FLAC/Opus
decoding and playback scheduling come from [sendspin-cpp](https://github.com/Sendspin/sendspin-cpp)
(player, metadata, controller, colour and artwork roles), pinned in `src/main/cpp/CMakeLists.txt`. `sendspin_jni.cpp` is the JNI bridge and
`NativeSendspin.kt` its Kotlin surface. The app side lives in
`app/.../sendspin/` (audio output, player session, mDNS advertiser, manager).

## Build

The first native build fetches sendspin-cpp and its dependencies with CMake `FetchContent`, so it needs
`git` and network access. Only `arm64-v8a` and `armeabi-v7a` are built, matching `:app`.

## Third-party licenses

Fetched at build time and linked into `libsendspin_jni.so`:

- sendspin-cpp — Apache-2.0
- ArduinoJson — MIT
- micro-flac, micro-opus (libopus, libFLAC) — Apache-2.0 / BSD-3-Clause
- IXWebSocket — BSD-3-Clause
- noise-c (esphome-libs fork, reference crypto backend) — MIT

## Encryption and pairing (Odysseus fork, sendspin-cpp v0.9.5)

Up to v0.8.0 sendspin-cpp spoke the plaintext protocol, which Music Assistant shows as "connected
without encryption (legacy mode). Pairing is not available", and which it will stop accepting once its
"Allow legacy clients" option goes. From v0.9 every connection is encrypted (Noise KKpsk2, X25519 +
ChaChaPoly; noise-c's pure-C reference backend) and a server can **pair**: prove, in every later
handshake, that it is the server the owner approved.

- **Identity.** The library derives the client id from a static X25519 key (base64url of the public
  key) instead of the old `vaca-<uuid>`. Music Assistant therefore sees a **new player** the first time
  this build runs; the old `vaca-…` entry stays "unavailable" until removed. The id is shown in
  Configuration → Services → Sendspin player → Player id.
- **Key store.** The bridge is the library's `SendspinPersistenceProvider`, backed by `BlobStore`
  (`src/main/cpp/blob_store.h`): one owner-only file per key in `files/sendspin/` (the device key, the
  Pairing PSK, `rec_<n>` per paired server, `rec_order`, `last_played`, `output_delay`). Written to a
  temp file, fsynced and renamed; the directory is fsynced on `commit()`. Excluded from backup and
  device transfer. Clearing the app's data (`pm clear`) gives the device a new identity and forgets every
  pairing. Host test: `sendspin/src/test/cpp/blob_store_test.cpp` (the command is in its header).
- **Pairing.** Started from Music Assistant (Setup on the player). The device offers the dynamic
  six-digit code (`pairing_code_out_channels = DISPLAY`, `DIGITS`) plus the Pairing-PSK method every
  client has; no static code. `onPairingCode` shows the code (`odysseus/SendspinPairingOverlay.kt`, which
  also turns the screen on); the owner types it into MA. After 20 wrong rounds the library holds
  attempts until the owner allows pairing on the device (`onPairingWindow` → Allow, or Configuration →
  Allow pairing, which opens a 5-minute window). The code is never logged.
- **Trust.** `onTrustChanged(paired)` reports whether the connected server used a pairing record. A
  **paired server passes the preferred-server check from any address**, so with pairing the
  `sendspin_trusted_server_ids` workaround for Tailscale (`docs/odysseus/tailscale.md`) is no longer
  needed (it still works).
- **Unpaired access** (`sendspin_unpaired_access`, on-device, default **on**): whether a server that
  hasn't paired may play. The library defaults to off; the fork keeps it on so an existing setup keeps
  playing after the update. Turn it off once Music Assistant is paired: unpaired servers then only get
  an idle connection (enough to pair). Changing it applies live (`nativeSetUnpairedAccess`).
- State for the UI is `app/.../sendspin/SendspinSecurity.kt` (rules unit-tested in
  `SendspinSecurityStateTest`).
- API changes taken in the bridge: `config.client_id` is gone, `set_static_delay_adjustable` →
  `set_output_delay_adjustable`, `ControllerRole::send_command` returns `bool`. The source and
  visualizer roles are compiled out.

## Goodbye reason when the app stops (Odysseus fork)

`nativeDestroy(handle, restarting)`: when the app or its service stops (`SendspinManager`'s stop of the
background task), the bridge says goodbye with `restart` before `client->stop()`, whose own goodbye is
always `shutdown`. Music Assistant (aiosendspin) only re-dials a manually configured client address after
`restart` (or no goodbye at all); after `shutdown` it waits for mDNS, which doesn't cross Tailscale, so the
player stayed "not yet available" until the Sendspin provider was reloaded. Turning Sendspin off still
says `shutdown`.

## Album art pushed over the connection (Odysseus fork)

The metadata's `artwork_url` points at Music Assistant's LAN address (its image proxy on port 8095),
which a device reaching Music Assistant over Tailscale can't load, so the cover showed a placeholder.
The bridge therefore also adds the **artwork role** (one slot: album art, JPEG, 320x320): the server
pushes the image over the Sendspin connection itself.

- `onArtworkImage(bytes)` arrives on the library's decode thread (`SendspinPlayer` decodes it there),
  `onArtworkDisplay()` at the track change it belongs to, `onArtworkClear()` for a track without art or
  at stream end.
- `PushedArtwork` (app, unit-tested) decides what is shown: a clear while streaming drops the image; a
  stream-end clear (pause) keeps it until another track comes up without new art.
- `SendspinNowPlaying.artwork` carries it; the clock page and the now-playing overlay prefer it and
  fall back to fetching `artworkUrl` only when there is none (a server without the artwork role).
