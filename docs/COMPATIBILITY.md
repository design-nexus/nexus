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

Optional **Nyx TV Home helper** (AccessibilityService): Settings → TV and inputs. Off by default. When enabled in system Accessibility, it reopens Nyx TV if launcherx or setupwraith comes to the foreground. Use only when Home still opens stock chrome after `set-home` / `disable-stock`.

### Plex deep links (same day)

Leanback launch and `plex://` VIEW intents open `tv.plex.app.MainActivity` (status ok). Whether a given URI starts the right title still needs a real `ratingKey` / machine id; treat direct links as experimental until then. Built-in Media3 playback is the default path.

### Phase 5 checks (2026-11-15)

Observed on Bedroom with Google TV still as HOME (Nyx installed). Living Room not tested.

| Check | Result | Notes |
|-------|--------|-------|
| Standby / wake → Nyx | **Unverified** | Needs Nyx as HOME + manual sleep/wake |
| Chromecast / mediashell installed | **Observed** | `com.google.android.apps.mediashell` present (incl. cast_browser splits). Cast *while Nyx is HOME* not re-tested this session |
| HDMI / TV inputs | **Observed** | `dumpsys tv_input` lists TCL passthrough HDMI ids + `TDTVInputService`; several ports state 0/1 |
| CEC | **Observed** | `dumpsys hdmi_control`: CEC enabled, display_name `Bedroom TV`, 4 HDMI_IN ports with cec:true. Power/input routing with Nyx as HOME **unverified** |
| DRM apps launch | **Observed** | YouTube TV `am start` COLD TotalTime 1452 ms; Plex `tv.plex.app.MainActivity` ok (~2.2 s). Netflix package present; play-through not timed |
| Debloat catalog | **Observed** | TOML under `data/debloat/BeyondTV4/…`; Manager ∩ `pm path`. Brief disable of 3 optional pkgs: see [BENCHMARKS.md](BENCHMARKS.md) |

Benchmarks: [BENCHMARKS.md](BENCHMARKS.md).

## Living Room

Deferred — no Phase 5 measurements yet.

## Manager

Desktop install and launcher replace/restore: [INSTALLATION.md](INSTALLATION.md), [RECOVERY.md](RECOVERY.md). Debloat catalogs: `data/debloat/` (copied to `~/.local/share/nyx-tv-manager/debloat` on install).
