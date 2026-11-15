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

Optional **Nyx TV remote helper** (AccessibilityService): Settings → TV and inputs. Off by default. Remaps Settings/gear when the key reaches Accessibility; optionally reopens Nyx if launcherx/setupwraith steals Home. On Bedroom the physical gear is eaten by SystemUI before Accessibility — use the nav gear.

### Plex deep links (same day)

Leanback launch and `plex://` VIEW intents open `tv.plex.app.MainActivity` (status ok). Whether a given URI starts the right title still needs a real `ratingKey` / machine id; treat direct links as experimental until then. Built-in Media3 playback is the default path.

### Phase 5 checks (2026-11-15)

Nyx set as HOME on Bedroom (snapshot `audit/snapshot-Smart_TV-20261115-112717.env`; verify passed). Living Room not tested.

| Check | Result | Notes |
|-------|--------|-------|
| Home → Nyx (as HOME) | **Observed** | After `set-home` + `disable-stock` (launcherx + setupwraith); Settings/Play Store still open; Home returns to Nyx |
| Remote Settings key | **Nav gear** | Physical gear is `KEYCODE_NOTIFICATION` → SystemUI `TvNotificationPanel` with `config_notificationHandlerPackage` = launcherx (`DashboardHandler`). With launcherx disabled-user that resolve fails (key does nothing; many presses can still hit Realtek product info). Policy handles the key before Accessibility, so the remote helper cannot steal it on Bedroom. **Nav gear** opens Quick Settings. HomeActivity still handles `TOGGLE_NOTIFICATION_HANDLER_PANEL` / `KEYCODE_NOTIFICATION` when delivered. A later Manager option may keep launcherx’s dashboard handler without its Home activity. |
| Remote Input key | **Wired** | `KEYCODE_TV_INPUT` while Nyx is focused opens our Inputs menu (duplicate labels collapsed). Firmware `VIEW_INPUTS` is handled by TCL Quick Panel (`com.tcl.suspension`); we do not claim that intent (avoids a chooser) |
| Remote mic → Nyx Search | **No** | System Assist key opens Google Assistant; Nyx voice search is the Search-screen mic |
| Sleep timer (Nyx) | **In-app** | Quick Settings: 30/60/90/120 → Ambient black. Real standby needs privileged APIs; Google TV launcherx sleep timer is unavailable while launcherx is disabled. Tile can deep-link Power/Display settings when present |
| Standby / wake → Nyx | **Unverified** | Needs a manual sleep/wake with Nyx left as HOME |
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
