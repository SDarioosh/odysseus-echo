# Odysseus look

The app looks like the rest of Odysseus: the web app's colours (dark and light theme, following
the `dark_mode` setting), the Fira Code font and small corners. The Odysseus sailboat is the
launcher icon, the splash, the notification icon and the connection-screen logo. The app's name is
"Odysseus Echo".

## Files

- `odysseus/OdysseusTheme.kt`: colours, type and shapes; used by `AppTheme`.
- `res/values/odysseus_colors.xml`; `res/drawable/odysseus_*.xml` (logo, launcher layers,
  notification; the `odysseus_mdi_*` weather icons belong to the clock, [home-screen.md](home-screen.md));
  `res/font/fira_code_*.ttf` (Fira Code 6.2, OFL-1.1, licence in `assets/licenses/FiraCode-OFL.txt`).
- Hooks: `ui/theme/Theme.kt`, `ui/layouts/ConnectLayout.kt` (the logo), `SettingsLayout.kt`,
  `ConfigurationLayout.kt` (section colours), `CustomFilesLayout.kt`, `ui/components/SettingsTiles.kt`,
  `service/ForegroundService.kt` (notification icon), `res/values/themes.xml`,
  `res/values-v29/themes.xml`, `res/values/strings.xml` (the name), `res/mipmap-*`.
- Removed: upstream's `main_logo.png`, `splash_image.png` and launcher layers.
