# Home Assistant client (`odysseus/ha/`)

The app's one way for new features to reach Home Assistant (HA): REST for single requests, and
a websocket for live states and actions while something watches them. Built for the Music page
(plan `docs/odysseus/plans/2026-11-music.md`, contract I1). Its users:

- the **Music page** (`music/MusicRepository` over `HaMusicBackend`): templates, service calls,
  and the only `watch()` (every media player), so the websocket is open only while the Music page,
  the clock's selected room or the other speakers watch;
- **music on other speakers** (`home/players/OtherPlayers`): no calls of its own, it reads the
  Music backend's list and live states and controls through it
  ([home-screen.md](home-screen.md#music-on-other-speakers));
- the **clock's weather** (`home/weather/WeatherClient`): templates and `weather.get_forecasts`
  with its response.

Upstream's HA code (the Wyoming satellite, the dashboard login, `AuthenticationManager`) is
unchanged; only the phone setup (`HaSetupClient`, with the owner's token from the phone, not the
Echo's) keeps its own REST calls over the same `HaHttp`.

## Using it

```kotlin
val ha: HaApi = HaClient.get(deviceManager)          // process-wide, one websocket
ha.template("{{ states.media_player | count }}")     // POST /api/template
ha.call("media_player", "media_pause", buildJsonObject { put("entity_id", id) })
ha.call("music_assistant", "get_queue", data, returnResponse = true)   // the response
ha.states { it.entityId.startsWith("media_player.") }                  // GET /api/states
ha.statesOf(setOf(id))                               // GET /api/states/<id> each
ha.watch(setOf(id)).collect { states -> … }          // live, while collected
ha.bytes("/api/media_player_proxy/…")                // ≤ 8 MB, bearer header
```

Errors are `HaException` (`code` = the HTTP status for REST, 401 (`HA_AUTH_FAILED`) when the
token or the sign-in is refused, 0 otherwise). An answer larger than the body cap is an
`HaException` ("… is larger than N KB"), never a cut-short body. Never put user text in a template.

## Files

| File | What |
|---|---|
| `HaApi.kt` | contract I1: `HaState`, `HaException`, `HaApi` (shared with the Music backend, E12) |
| `HaHttp.kt` | `HaHttp`, `HaResponse`, `UrlConnectionHaHttp` (moved from `HaSetupClient.kt`; body cap now a parameter: 256 KB default, `withMaxBody(n)`), `HaBytesHttp` |
| `HaAuth.kt` | `HaAuth` (base URL, token) and `withAuthRetry`, the 401 rule |
| `HaRestApi.kt` | `HaApi` over REST (2 MB body cap); its `watch()` only polls |
| `HaSocket.kt` | the websocket: auth, `subscribe_entities`, `call_service`, ping, reconnect |
| `HaEntities.kt` | REST states and `subscribe_entities` events into `HaState` (pure) |
| `HaBackoff.kt` | reconnect waits (pure) |
| `KtorHaSocketTransport.kt` | the real socket: Ktor's WebSockets plugin on the OkHttp engine |
| `HaClient.kt` | `HaClient : HaApi` (socket or REST), `HaClient.get(deviceManager)`, `DeviceHaAuth` |

Outside `odysseus/ha/`: `odysseus/HaSetupClient.kt` and `home/weather/WeatherClient.kt` import
`HaHttp` from here. No upstream file is changed. Tests: below.

## Rules

- **Token and URL** come from the device's HA sign-in, as the weather does:
  `config.refreshToken` set (else "not signed in"), `AuthenticationManager.ensureValidSession()`,
  `config.accessToken`, `getBaseUrl()`.
- **401**: one retry with `ensureValidSession(forceRefresh = true)`; a second 401 goes to the
  caller. Never more than one retry per call: repeated 401s count toward HA's IP ban. No retry
  when the token was obtained less than a minute ago (`HaAuth.isFresh`, `TokenFreshness`): then
  the 401 is a permission (`/api/template` for a non-admin user), not the token. A refresh HA
  refuses (HTTP 4xx, e.g. `invalid_grant`) is a 401 too (`signInFailure`), and isn't tried again
  in the same call; no answer to it is code 0 (HA unreachable). On the
  websocket, `auth_invalid` gets one immediate retry with a refreshed token; a second one stops
  reconnecting until the next `watch()` starts, and so does a refused sign-in; REST polling then
  waits 5 min after a 401.
- **REST service calls** add `?return_response` only when asked: HA answers 400 to it for a
  service that has no response.
- **Service calls** go over the websocket while it is open (someone watches), else REST. A
  call whose answer was lost when the socket dropped is reported as an error, not repeated over
  REST (it may have run).
- **`watch(ids)`** opens the websocket while collected (one socket for all watchers, one
  `subscribe_entities` each; the last collector leaving closes it) and polls
  `GET /api/states/<id>` for each watched id (`statesOf`, not the whole list) every 5 s while the socket can't deliver (not connected yet, down, reconnecting). The first
  value usually comes from REST, then the socket takes over. Equal maps aren't repeated.
- **Websocket**: `auth_required` → `auth` → `auth_ok` (logged as `HA ws: auth_ok`, debug level);
  message ids go up per connection; `ping` every 30 s, no `pong` in 30 s reconnects; a dropped
  connection is reopened after 1, 2, 4, 8, 16, 30, 30 … s, back to 1 s after an `auth_ok`. A
  `CancellationException` (or `ClosedSendChannelException`) from the transport while the loop
  is still active is a closed socket, not the end of the loop; a send that failed sent nothing
  (the call goes over REST). A watcher that leaves while its subscribe waits to be sent sends
  none, and one that leaves just after is unsubscribed.
- **Certificates** are checked by the platform as usual, on REST and the websocket (the
  "ignore SSL errors" setting of upstream's HTTP client isn't used here).
- `bytes()` takes a path on HA only (starts with one `/`, no scheme, host or `..`).

## Tests

`app/src/test/java/com/msp1974/vacompanion/odysseus/ha/`: `HaEntitiesTest` (the `a`/`c`/`r`
decoding), `HaBackoffTest`, `HaAuthRetryTest`, `HaRestApiTest` (requests, the 401 rule),
`HaSocketTest` (handshake, subscribe, calls, ping, reconnect, auth_invalid; a fake transport),
`HaClientTest` (socket or REST, polling fallback), `HaHardeningTest` (refused sign-in, non-admin
401s, body cap, per-entity polling, transport cancellations, the queued-subscribe leak). `HaLiveTest` runs against a real HA only when
`HA_URL` and `HA_TOKEN` are set (read-only):

```bash
HA_URL=http://<ha>:8123 HA_TOKEN=<long-lived token> \
  ./gradlew :app:testDebugUnitTest --tests '*HaLiveTest*' -i | grep '^HA '
```
