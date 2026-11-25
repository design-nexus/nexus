# Recovering the stock launcher

TV Manager and `scripts/launcher.sh` never uninstall Google TV’s launcher. They only `pm disable-user` / `pm enable` and change the preferred HOME activity.

## From Manager

1. Select the TV.
2. **Launcher → Restore from last snapshot** (uses the session snapshot or the newest `audit/snapshot-*.env`).
3. Confirm. The TV’s firmware fingerprint must match the snapshot.

## From the CLI

```sh
# List snapshots
ls audit/snapshot-*.env

scripts/launcher.sh SERIAL restore audit/snapshot-MODEL-TIMESTAMP.env
```

That re-enables `com.google.android.apps.tv.launcherx` (and `setupwraith` when the snapshot said it was enabled) and restores the saved HOME component.

## If there is no snapshot

```sh
adb -s SERIAL shell pm enable com.google.android.apps.tv.launcherx
adb -s SERIAL shell pm enable com.google.android.tungsten.setupwraith   # Google TV
adb -s SERIAL shell cmd package set-home-activity \
  com.google.android.apps.tv.launcherx/.home.HomeActivity
```

Then press Home. If Nexus still appears, clear its default in the TV’s app settings or run `set-home-activity` again after confirming the stock package is enabled.

## After a firmware update

Stock packages are often re-enabled. Use **Launcher → Repair** in Manager (or snapshot + set-home + disable-stock again) once Nexus is still installed.
