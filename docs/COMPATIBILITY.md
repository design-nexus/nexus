# Compatibility

## Bedroom TCL Google TV (BeyondTV4 / G03_4K_US_NF_DB)

- Android 11 · fingerprint `TCL/G03_4K_US_NF_DB/BeyondTV4:11/RP1A.200622.001/AR26:user/release-keys`
- ADB: `192.168.1.237:5555`
- Stock home: `com.google.android.apps.tv.launcherx/.home.HomeActivity`
- Plex app: `com.plexapp.android` 2026.22.0 (signed in)

### Launcher replacement (2026-11-14)

Tested with `scripts/launcher.sh`, snapshot `audit/snapshot-Smart_TV-20261114-141630.env`, then restored.

| Step | Result |
|------|--------|
| `set-home` alone | Role becomes Nyx TV, but Home still opens Google TV home |
| `disable-stock` (launcherx only) | Home opens `setupwraith` `RecoveryActivity` (priority=1) |
| Disable launcherx **and** setupwraith | Home opens Nyx TV; Settings and Play Store still work; Home returns to Nyx |
| `restore` | Re-enables both; Google TV home is back |

`scripts/launcher.sh disable-stock` now disables setupwraith when present; `restore` re-enables it from the snapshot. Nothing is uninstalled.

### Plex deep links (same day)

Leanback launch and `plex://` VIEW intents open `tv.plex.app.MainActivity` (status ok). Whether a given URI starts the right title still needs a real `ratingKey` / machine id; treat direct links as experimental until then. Built-in Media3 playback is the default path.
