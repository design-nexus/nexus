#!/usr/bin/env bash
# Read-only audit of an Android TV over adb. Changes nothing on the TV.
# Usage: scripts/audit.sh SERIAL      (e.g. 192.168.1.237:5555)
# Writes audit/<model>-<date>/ (git-ignored: it holds serial numbers and the full package list).
set -euo pipefail
serial=${1:?usage: scripts/audit.sh SERIAL}
cd "$(dirname "$0")/.."
a() { adb -s "$serial" "$@"; }
sh() { a shell "$@" 2>&1 || true; }

model=$(a shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')
out="audit/${model:-tv}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
save() { local name=$1; shift; sh "$@" >"$out/$name.txt"; echo "  $name"; }
echo "Auditing $serial into $out"

save getprop getprop
save features pm list features
save packages-system pm list packages -s -U
save packages-third-party pm list packages -3 -U
save packages-disabled pm list packages -d
save packages-uninstalled-for-user pm list packages -u
save packages-paths pm list packages -f
save home-resolve cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
save home-candidates cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
save home-role sh -c "cmd role get-role-holders android.app.role.HOME 2>/dev/null || dumpsys role | grep -A3 'android.app.role.HOME'"
save home-preferred sh -c "dumpsys package preferred-xml 2>/dev/null | grep -B2 -A6 -i home"
save users pm list users
save leanback-launchers cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER
save resumed-activity sh -c "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'"
save tv-inputs dumpsys tv_input
save display sh -c "wm size 2>/dev/null | head -2; wm density | head -2; getprop sys.display-size"
save cpu sh -c "nproc; grep -E 'Hardware|model name|Processor' /proc/cpuinfo | sort -u; getprop ro.board.platform"
save meminfo-system cat /proc/meminfo
save cpuinfo dumpsys cpuinfo
save top top -b -n 1 -m 25
save accessibility settings get secure enabled_accessibility_services
save screensaver sh -c "settings get secure screensaver_enabled; settings get secure screensaver_components; settings get system screen_off_timeout"

# Launcher and setup packages: present, enabled, version, activities.
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.leanbacklauncher \
         com.google.android.tvlauncher com.google.android.tvrecommendations com.plexapp.android com.spocky.projengmenu; do
  if a shell pm path "$p" >/dev/null 2>&1; then
    save "pkg-$p" dumpsys package "$p"
    save "meminfo-$p" dumpsys meminfo "$p"
  fi
done
# Every system package with its enabled state and version, for the debloat catalog.
disabled=$(a shell pm list packages -d | tr -d '\r' | sed 's/^package://')
a shell pm list packages -s | tr -d '\r' | sed 's/^package://' | sort | while read -r p; do
  st=enabled
  grep -qx "$p" <<<"$disabled" && st=disabled
  ver=$(a shell dumpsys package "$p" </dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -n1)
  echo "$p $st ${ver:-?}"
done >"$out/system-packages.txt" || true
echo "  system-packages"
echo "Done: $out"
