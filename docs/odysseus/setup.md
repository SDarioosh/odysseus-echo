# Set up an Echo with your phone

A new Echo is set up with one QR code. You type nothing on the Echo.

## Before the first Echo

- In Odysseus: Settings → Integrations → Voice assistant → **Set up Home Assistant**. Odysseus
  then knows the Home Assistant address and hands it to each Echo.
- Home Assistant has the View Assist Companion App integration (v0.14.0) installed.
- The phone reaches the Echo on the same Wi-Fi or over Tailscale. Home Assistant reaches the
  Echo on port 10800 the same way.

## Steps

1. Start the app on the Echo. While it has no Home Assistant login, the connection screen shows
   a QR code (with the logo and device details hidden, so it fits the Echo Show 5's screen). On
   an Echo that is already set up: a two-finger swipe up, then **Menu → Odysseus →
   Set up with phone (QR code)**.
2. Scan the code with your phone. Enter a **Name** (for example Kitchen) and check the
   **Odysseus address**. Tap **Continue**.
3. Odysseus asks: "Link Echo 'Kitchen'?". Tap **Approve**. If Odysseus asks you to sign in
   first, sign in, go back to the Echo's page and tap **Continue** again.
4. The phone opens Home Assistant's sign-in. Sign in with an **administrator** account.
5. The phone shows each step:
   - Link to Odysseus (tested; a failed test is a warning, the link is kept)
   - Sign in to Home Assistant
   - Add this Echo to Home Assistant (port 10800)
   - Give the Echo the Home Assistant address (the integration's `ha_url` option)
   - Wake word: Hey Athena
   - Wake word threshold: 9.2
   - Wake sound: Ding
6. When Home Assistant connects, the Echo shows the dashboard. It doesn't ask for a login.

The assistant (pipeline) stays "preferred". Odysseus's "Set up Home Assistant" makes the
Odysseus pipeline the preferred one.

If a step fails, the steps before it are kept. Fix the cause and run setup again: an Echo
already in Home Assistant is found again (by its name "VACA <name>"), not added twice.

Leave the Odysseus address empty to set up only Home Assistant. Without an address from
Odysseus, the page asks for the Home Assistant address.

## Which address the QR code uses

1. The Echo's Tailscale name (MagicDNS), when it has a Tailscale address and a reverse lookup
   gives a name ending in `.ts.net`, `.local`, `.lan` or `.home.arpa`.
2. Else its Wi-Fi address.
3. Else its Tailscale IP. Home Assistant refuses a sign-in for a 100.x address, so the page
   then asks for the Echo's Tailscale name (Tailscale admin console → Machines).

Just after boot Wi-Fi and Tailscale often have no address yet. The Echo then shows "Waiting
for a network address" and looks again every second (every 3 seconds after 10 tries) until one
turns up; it gives up only after 10 minutes. An address that turns up after the QR code is shown
(usually Tailscale, a few seconds after Wi-Fi) replaces it if it's better, until a phone opens
the page or you tap **Other address** (`odysseus/SetupAddressWait.kt`).

**Other address** on the Echo switches between them. Home Assistant gets the same address
for the Echo. If it can't connect to a name, the Echo tries its Tailscale IP.

Over Wi-Fi the page and the Odysseus token travel as plain HTTP on your home network. Over
Tailscale they travel inside WireGuard.

## What the Echo keeps

- The Odysseus link: base URL and voice token, encrypted with an Android Keystore key
  (`odysseus/OdysseusLink.kt`).
- The Home Assistant login, stored as VACA stores its own, plus the client id
  `http://<echo-host>:10801/` it was issued to. Home Assistant refreshes a token only with
  that client id, so VACA's token refresh uses it (`odysseus/HaClientId.kt`). A login on the
  Echo's own screen switches back to VACA's client id.
- The name becomes VACA's device id, so Home Assistant shows "VACA Kitchen".

## Security

- The setup server runs on port 10801 only while the setup screen is open.
- The link carries a one-time 128-bit code. A wrong code returns 404; the 5th wrong request
  stops the server.
- The server stops after 10 minutes, when you leave the screen (unless the Home Assistant
  part is running), and a minute after setup finishes.
- Odysseus hands the token over in the URL fragment, which never reaches a server or a log.
  The Echo's page removes it from the address bar and posts it to the Echo.
- The Echo takes that link only while it waits for Odysseus, and only once: someone else on
  the network who learns the code can't swap the token or the Home Assistant address later.
- Home Assistant sign-in uses a random `state` that the Echo checks.
- Pages are `Cache-Control: no-store` and `Referrer-Policy: no-referrer`, every value is
  escaped, and tokens are never logged.

## The setup server

| Request | What it does |
|---|---|
| `GET /setup/<code>` | name and Odysseus address form, or the progress |
| `POST /setup/<code>` | saves them, sends the phone to `<odysseus>/echo/link?return=…&name=…` |
| `GET /setup/<code>/odysseus` | takes `#token=…&base_url=…&ha_url=…` from Odysseus and posts it |
| `POST /setup/<code>/odysseus` | saves and tests the link, sends the phone to Home Assistant's sign-in |
| `POST /setup/<code>/ha-url` | the Home Assistant address, when Odysseus sent none |
| `GET /setup/<code>/ha` | Home Assistant's sign-in callback |
| `GET /setup/<code>/status` | progress as JSON |

Home Assistant calls (REST, with the admin token): `POST /auth/token`,
`GET /api/config/config_entries/entry?domain=vaca`, `POST /api/config/config_entries/flow`
(step `user`: host, port 10800), the options flow (`ha_url`, keeping `default_dashboard`),
`POST /api/template` (the entry's entities) and the `select.select_option` and
`number.set_value` services.

## Build default

Build with `-PodysseusDefaultUrl=https://odysseus.example.ts.net` to prefill the Odysseus
address on a new Echo (`BuildConfig.ODYSSEUS_DEFAULT_URL`, blank by default). An Echo that
was linked before prefills its own.

## Files

- `odysseus/EchoSetup.kt`: the server and the Home Assistant job.
- `odysseus/EchoSetupPages.kt`: the phone pages.
- `odysseus/EchoSetupRules.kt`: address choice, URL and form checks, entity matching (unit-tested).
- `odysseus/HaSetupClient.kt`: the Home Assistant calls (unit-tested with a fake).
- `odysseus/EchoSetupPanel.kt`: the QR code on the Echo.
- `odysseus/HaClientId.kt`: the stored client id.
- `odysseus/SetupAddressWait.kt`: waits for Wi-Fi or Tailscale after boot before the QR code
  picks an address (`SetupAddressWaitTest`).
- Hooks: `ui/layouts/ConnectLayout.kt` (QR on the connection screen),
  `device/authentication/AuthenticationManager.kt` (stored client id for refresh),
  `app/build.gradle.kts` (`odysseusDefaultUrl`, below).
- ZXing core (Apache-2.0) for the QR code, in `gradle/libs.versions.toml`.
- Tests: `EchoSetupRulesTest`, `HaSetupClientTest`, `SetupAddressWaitTest`.
