# Third-party notices

Nyx TV and TV Manager include or interact with:

## On the TV (Android)

- **AndroidX / Material / Leanback** — Apache 2.0 (via Android Gradle dependencies)
- **Coil** — Apache 2.0 (artwork loading)
- **Media3** — Apache 2.0 (built-in playback)
- **Plex** — the official Plex Android TV app (`com.plexapp.android`) is a separate product. Nyx may open it via intents; Plex tokens stay in the TV’s secure store and are never exported by TV Manager backups
- **Google TV / TCL system packages** — stock components (launcherx, setupwraith, mediashell, TCL TV input, etc.) remain on the device; Manager only `disable-user` / `enable`s catalogued packages with confirmation

## On the laptop (TV Manager)

- **GTK 4** — LGPL
- **nyx-theme / nyx-ui / nyx-core** — Design Nexus shared crates (see those repos)
- **adb** (Android SDK platform-tools) — Apache 2.0; system `adb` is required

## Reference launchers reviewed in Phase 0

- **FLauncher** — GPL; reviewed for behaviour; Nyx TV is not a fork
- **Projectivy** — closed source; behaviour-only reference
- **AOSP TvLauncher / Leanback forks** — behaviour reference only

Exact dependency versions for the Android tree: `./gradlew :app:dependencies`. For the Manager workspace: `Cargo.lock`.
