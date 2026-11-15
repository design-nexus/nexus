# Installing Nyx TV

## On the TV (APK)

From a machine with `adb` paired to the TV:

```sh
cd android
./gradlew :app:installDebug          # debug package: io.github.design_nexus.nyx.tv.debug
# or
./gradlew :app:assembleRelease       # then install the APK under app/build/outputs/apk/
```

Or use **TV Manager → Install and update** on the laptop (below).

## TV Manager (laptop)

Manager direction A: TVs in the sidebar; pages for Overview, Install, Launcher, Backups, Diagnostics, and Debloat (live package catalog ∩ installed on the TV).

```sh
cd ~/Projects/nyx-tv
./install.sh manager
nyx-tv-manager
```

Requirements: Rust/`cargo`, GTK4, system `adb` (`android-tools` on Arch).

1. **Add TV** — wireless `host:port` (e.g. `192.168.1.237:5555`) or a USB serial from the discovered list.
2. **Install** — debug APK from this checkout, or pick any APK.
3. **Launcher** — Snapshot → Replace (sets Nyx as HOME and disables Google TV launcherx / setupwraith) → Verify. Restore uses the snapshot; fingerprints must match.
4. **Backups** — export/import `nyx-tv-settings.json` (layout and prefs only; never Plex tokens).
5. **Diagnostics** — read-only audit under `audit/`, or export a copy elsewhere.

CLI fallback (same ops as Manager’s Launcher page):

```sh
scripts/launcher.sh SERIAL snapshot
scripts/launcher.sh SERIAL set-home COMPONENT
scripts/launcher.sh SERIAL disable-stock
scripts/launcher.sh SERIAL verify COMPONENT
scripts/launcher.sh SERIAL restore SNAPSHOT
```

See [RECOVERY.md](RECOVERY.md) and [COMPATIBILITY.md](COMPATIBILITY.md).

## Signed release APK (local)

Never commit keystores or passwords.

1. Create a release keystore (once), e.g. `~/keys/nyx-tv-release.jks`.
2. In `android/keystore.properties` (gitignored) or via env:

```properties
storeFile=/home/you/keys/nyx-tv-release.jks
storePassword=…
keyAlias=nyx-tv
keyPassword=…
```

3. Wire those into `android/app/build.gradle.kts` `signingConfigs` when you add a release signing block, then:

```sh
cd android && ./gradlew :app:assembleRelease
```

CI release workflow builds release artifacts on `workflow_dispatch`. Signing steps run only when the repo secrets are present (`NYX_TV_KEYSTORE_BASE64`, `NYX_TV_KEYSTORE_PASSWORD`, `NYX_TV_KEY_ALIAS`, `NYX_TV_KEY_PASSWORD`); otherwise the job uploads an unsigned/debug artifact and says so in the log.

## Debloat catalogs

Per-model TOML lives in `data/debloat/<model>/<fingerprint-safe>.toml`. `./install.sh` copies them to `~/.local/share/nyx-tv-manager/debloat/`. Override with `NYX_TV_DEBLOAT_DIR`.
