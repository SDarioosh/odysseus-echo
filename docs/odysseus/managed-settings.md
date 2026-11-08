# Settings managed by Odysseus

The owner can set some of an Echo's settings from Odysseus (the hub's admin page, per device),
instead of on each device's screen. A setting Odysseus sets is **managed**: the device uses the
hub's value, and Menu → Odysseus shows it with "Set by Odysseus" and its control disabled. A
setting Odysseus leaves alone stays the device's own. Plan:
`docs/odysseus/plans/2026-11-done-followup-settings-verify.md`, phases H6 (hub) and E9 (this),
contract C6. Code: `app/src/main/java/com/msp1974/vacompanion/odysseus/managed/`.

## The request

The device asks; the hub never needs to reach it.

```
GET <base>/devices/{device_id}/settings        Authorization: Bearer <voice token>
-> {"version": 7, "settings": {"kiosk.return_seconds": 600, "today.enabled": true, ...}}
```

`<base>` is the voice base URL (Menu → Odysseus), `device_id` is VACA's device id
(`APPConfig.uuid`, the id the hello sends).

When it asks:

- at start (the background task starts the announcements, which start this),
- after each hello (`POST /satellite/hello`: at start, hourly, and when the name, area or link
  changes; an older hub without the hello route counts too),
- when the announcements feed has a new item with `"kind": "settings"` (the hub sends one when the
  device's settings change, so a change arrives within 30 s). That item never shows a card.

A failed request (network, any HTTP error but 401/403) is tried again after 1, 5 and 15 min, then
at the next hello. A refused token (401/403) keeps the last settings as they are. Nothing is asked
while the device isn't linked.

## Keys

Only these; any other key is ignored and logged.

| Key | Type | Device setting | Store |
|---|---|---|---|
| `home.enabled` | bool | Clock home screen | `HomeSettings.enabled` |
| `home.idle_seconds` | int, 0 = never (0..86400) | Back to the clock after | `HomeSettings.idleSeconds` |
| `home.weather_enabled` | bool | Weather | `HomeSettings.weatherEnabled` |
| `today.enabled` | bool | Today page | `HomeSettings.todayEnabled` |
| `kiosk.return_seconds` | int, 0 = never (0..86400) | Back from other apps after | `KioskSettings.returnSeconds` |
| `kiosk.auto_reload` | bool | Reload a dashboard that failed to load | `KioskSettings.autoReload` |
| `record.enabled` | bool | Recording | `RecorderSettings.enabled` |
| `record.clock_button` | bool | Record button on the clock | `RecorderSettings.clockButton` |
| `record.privacy_button` | `"record"` / `"mute"` | Privacy button | `RecorderSettings.privacyButton` |
| `followup.enabled` | bool | Answer without the wake word | `FollowUpSettings.enabled` |
| `followup.seconds` | int 3..15 | How long it listens | `FollowUpSettings.seconds` |
| `music.enabled` | bool | Music page (C7, `docs/odysseus/music.md`) | `MusicSettings.enabled` |
| `music.stay_while_playing` | bool | Music page stays while this room plays (C7) | `MusicSettings.stayWhilePlaying` |

- Absent or `null`: the device decides.
- A value of the wrong JSON type (`"true"`, `60.5`, `1` for a bool) or out of range is ignored
  and logged (`Managed settings: ignored <key>: bad value`); the device keeps its own value for it.
- An answer without a numeric `version` or with `settings` not an object is ignored as a whole.
- An int that isn't one of the menu's choices (e.g. 900 s) is used as it is; the menu then shows
  it as one more chip, selected, and "Set by Odysseus: 15 min" (`SecondsChips`).

## What the device does with them

`ManagedEngine` (pure, `ManagedEngineTest`):

- A key the hub sets is written into its store with the same setter the settings screen uses, so
  it behaves exactly as if the owner had chosen it on the device (turning recording off stops a
  recording, a new privacy-button mode re-reads the mute switch).
- **The device's own value is kept.** When a key becomes managed, the store's value at that moment
  is saved. When the hub drops the key again (absent or `null`), that value is written back and
  forgotten. While a key is managed its control is disabled, so the saved value can't go stale.
- Everything is persisted in SharedPreferences `odysseus_managed` (`version`, `values`, `own`), so
  the managed settings hold offline and after a restart, and are applied again at start.
- **Unlink** (Menu → Odysseus) gives every managed setting back to the device at once (its own
  value is written back; `Managed settings (unlinked): back to the device: …`): no hub would ever
  release them otherwise, and their controls would stay disabled. Offline, or with the token
  refused, they hold.
- The same answer again writes nothing. Changes are logged with the keys only
  (`Managed settings: version 8 (feed): now set: …; changed: …; back to the device: …`).
- `version` is the hub's; the device keeps the last one it applied (logged) but always follows the
  latest answer.

## Adding a managed setting

Each setting is declared once, in its store (`odysseus/settings/PrefStore.kt`): `managed = "<key>"`
makes it one Odysseus may set. The declaration gives `ManagedKeys.all` the key, its type and its
range, and `ManagedBindings` how to read and write the store's own value (the same setter the
settings screen uses, so `onSet` hooks such as recording's run for the hub too).

```kotlin
object FollowUpSettings {
    internal val store = PrefStore("odysseus_followup")      // declared before the fields
    fun init(context: Context) = store.init(context)

    var enabled by store.bool("followup.enabled", true, managed = "followup.enabled")
    var seconds by store.int("followup.seconds", 8, FollowUpRules.SECONDS_RANGE, managed = "followup.seconds")
}
```

- `bool`, `int` (kept within its range, which is also the range the hub may set) and `codec`
  (a value stored as a string, managed as one of `choices`: the privacy button's `"record"` /
  `"mute"`) can be managed; `float` and `string` are on-device only. The first argument is the
  SharedPreferences key: never change one of an existing setting, or installed devices lose it.
- A store with its first managed field goes into `ManagedBindings.stores`.
- A new key also needs a line in the table above and in `ManagedBindingsTest`, and the hub's H6
  table must offer it.
- In the settings screen use `ManagedSwitchRow(checked, title, detail, managedKey = key)` or
  `SecondsChips(choices, value, key)` (`ManagedControls.kt`): they disable the control while
  `ManagedSettings.isManaged(key)` (Compose state) and show "Set by Odysseus" beneath it. Any other
  control does the same by hand with `ManagedNote(key)`.
- A key the hub sets before its store registers is kept and applied the moment it does (and an
  own value the hub released meanwhile is given back then); `ManagedSettings.register(key, get, set)`
  binds a setting that isn't a PrefStore field.

## Files

- `ManagedKeys.kt`: C6's keys (from the declarations), value checks and the answer's parsing (pure,
  `ManagedKeysTest`; the key set is pinned in `ManagedBindingsTest`).
- `ManagedEngine.kt`: applying, keeping and giving back own values (pure, `ManagedEngineTest`).
- `ManagedSettings.kt`: the process-wide registry, Compose state, persistence and the fetch loop.
- `ManagedBindings.kt`: the stores with managed fields (`HomeSettings`, `KioskSettings`,
  `RecorderSettings`, `FollowUpSettings`, `MusicSettings`), registered at start.
- `ManagedControls.kt`: "Set by Odysseus" (`ManagedNote`), `ManagedSwitchRow`, `SecondsChips`.
- `odysseus/settings/PrefStore.kt`: the stores' fields and their `managed` declarations
  (`PrefStoreTest`, `StoreKeysTest`).
- Hooks: `odysseus/announce/Announcements.kt` (start/stop, fetch after hello, "settings" items),
  `AnnouncementFeed.kt` / `AnnouncementApi.kt` / `AnnouncementRules.kt` (a "settings" item is no
  card); settings screen: `odysseus/OdysseusSettingsLayout.kt`, `record/RecordingSettingsSection.kt`,
  `home/today/TodaySettingsSection.kt`, `followup/FollowUpSettingsSection.kt`,
  `home/music/MusicSettingsSection.kt`. No upstream file is changed.

## Checking on a device

```bash
adb logcat -s Timber | grep "Managed settings"      # or: adb logcat | grep "Managed settings"
adb shell run-as com.msp1974.vacompanion.odysseus cat shared_prefs/odysseus_managed.xml
```

1. In Odysseus, set `kiosk.return_seconds` to 1 min for this device: within 30 s the log shows
   `now set: kiosk.return_seconds`, and Menu → Odysseus → Kiosk shows "Set by Odysseus: 1 min"
   with the chips disabled.
2. Leave it to the device again: the chips come back with the value chosen before.
3. Wi-Fi off, restart the app: the managed value still holds.
