# The device keeps its settings

An update (or any restart) used to bring an Echo back with upstream's default configuration:
screen timeout 5 min, brightness 50 %, wake-the-screen options, zoom, microphone gain and so on.
Upstream VACA keeps most of its configuration in memory only and waits for Home Assistant to send
it again (the `settings` event, `APPConfig.processSettings`). Whatever HA didn't send back, or sent
back from before a change made on the device, was lost. An update restarts the app, so it looked
like the update wiped the device.

Now the device keeps its own copy of its whole configuration and starts with it, so an update is
just a restart. Android itself already keeps the app's data over an update signed with the same
key ([updates](updates.md)).

## What is kept

Every setting in HA's `settings` event that `APPConfig` holds in memory only
(`DeviceSettingsRules.KEPT`): screen and display, wake-the-screen, motion and bump detection,
wake word, wake sound, alarm sound, threshold, microphone gain, mute, ducking volume, continue
conversation, diagnostics, quick actions, RTSP, audio input and output, HA's URL and dashboard,
custom files. Settings `APPConfig` already stored on the device (area, ducking scope, dark mode,
Sendspin, Bluetooth proxy, pipeline timeout, …: `ON_DEVICE`) stay where they were.

Not kept, because they aren't configuration (`NOT_SETTINGS`): whether the screen is on or blanked
right now, Do Not Disturb and the volumes (Android keeps those itself), HA's integration version
and the app version it requires.

`DeviceSettingsRulesTest` reads `settings/Settings.kt` and fails when `processSettings` reads a
key that is in none of the three lists, so a setting added upstream isn't silently forgotten.

## Who wins

- **At start** the kept values are applied before anything else reads the configuration, so the
  screen timeout, brightness etc. are right before Home Assistant connects.
- **A setting changed on the device** (its settings screen, the mute button) is the device's own.
  When HA connects (or the device asks it for its settings) and sends a different value for it,
  the device keeps its own and tells HA its value again. Log:
  `Device settings: kept the device's own [screen_timeout] over Home Assistant's; telling HA`.
- **A change made in Home Assistant while it is connected** always wins, is kept, and makes the
  setting HA's again.
- Anything else HA sends is applied as before and kept.

So a change made in HA while the device is off or updating is replaced by the device's own value
when they reconnect; change it again in HA.

Stored in SharedPreferences `odysseus_device_settings` (key `kept`: the values in HA's units and
keys, and which were set on the device). Never sent anywhere but HA.

## Checking on a device

```bash
adb logcat | grep "Device settings"
adb shell run-as com.msp1974.vacompanion.odysseus cat shared_prefs/odysseus_device_settings.xml
```

1. Settings → Display: set Screen timeout to 600 s, Brightness to 30 %.
2. Install an update (or `adb shell am force-stop com.msp1974.vacompanion.odysseus` and open it).
3. The log shows `Device settings: restored N (… set on the device)`; Settings → Display still
   shows 600 s and 30 %, also after HA has connected, and HA's entities show the same.

## Files

- `odysseus/settings/DeviceSettingsRules.kt`: which keys, who wins, the stored form (pure,
  `DeviceSettingsRulesTest`).
- `odysseus/settings/DeviceSettings.kt`: the file, restore at start, the two hooks below.
- Hooks: `device/DeviceManager.kt` (restore right after `APPConfig` is made; the initial mute
  status from the config), `ui/VAViewModel.kt` `sendSettingToHA` (a change on the device),
  `satellite/Satellite.kt` `handleSettings` through `odysseus/OdysseusSatellite.kt` `haSettings`
  (HA's settings), `MainActivity.kt` (applies the text size when the dashboard loads, beside the
  zoom level: upstream only applied it when it changed).
