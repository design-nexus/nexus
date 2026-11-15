#!/usr/bin/env bash
# Launcher replacement on an Android TV over adb, one reversible step at a time.
#
#   scripts/launcher.sh SERIAL snapshot              save the current home state (always first)
#   scripts/launcher.sh SERIAL set-home COMPONENT    make COMPONENT the preferred home app
#   scripts/launcher.sh SERIAL disable-stock         disable Google TV home (+ setupwraith on Google TV)
#   scripts/launcher.sh SERIAL verify COMPONENT      Home button, stock launcher process, Settings, Play Store
#   scripts/launcher.sh SERIAL restore SNAPSHOT      re-enable what was disabled and set the saved home back
#
# Nothing is uninstalled. TCL vendor services are never touched.
# On Google TV, launcherx alone is not enough: setupwraith RecoveryActivity has
# priority=1 and steals HOME until it is also disabled-user. restore re-enables both.
set -euo pipefail
serial=${1:?serial}; cmd=${2:?command}
cd "$(dirname "$0")/.."
a() { adb -s "$serial" shell "$@" 2>&1 | tr -d '\r'; }
STOCK=com.google.android.apps.tv.launcherx
STOCK_HOME=com.google.android.apps.tv.launcherx/.home.HomeActivity
SETUPWRAITH=com.google.android.tungsten.setupwraith

home_now() { a cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n1; }
role_now() { a dumpsys role | grep -A2 'name=android.app.role.HOME' | sed -n 's/.*holders=//p' | head -n1; }
enabled() { a pm list packages -d | grep -qx "package:$1" && echo disabled || echo enabled; }
resumed() { a dumpsys activity activities | sed -n 's/.*mResumedActivity: ActivityRecord{[^ ]* [^ ]* \([^ ]*\) .*/\1/p' | head -n1; }

case $cmd in
  snapshot)
    model=$(a getprop ro.product.model | tr ' ' '_'); fp=$(a getprop ro.build.fingerprint)
    out="audit/snapshot-${model}-$(date +%Y%m%d-%H%M%S).env"
    {
      echo "fingerprint=$fp"
      echo "home=$(home_now)"
      echo "role_home=$(role_now)"
      echo "stock_state=$(enabled $STOCK)"
      echo "setupwraith_state=$(enabled $SETUPWRAITH)"
      echo "disabled_packages=$(a pm list packages -d | sed 's/package://' | tr '\n' ' ')"
    } >"$out"
    cat "$out"; echo "saved $out" ;;
  set-home)
    comp=${3:?component}
    a cmd package set-home-activity "$comp"
    echo "home now: $(home_now)"; echo "role: $(role_now)" ;;
  disable-stock)
    a pm disable-user --user 0 "$STOCK"
    # Google TV: RecoveryActivity (priority=1) steals HOME once launcherx is gone.
    if a pm path "$SETUPWRAITH" >/dev/null 2>&1; then
      a pm disable-user --user 0 "$SETUPWRAITH"
      echo "setupwraith: $(enabled $SETUPWRAITH)"
    fi
    echo "stock: $(enabled $STOCK)"; echo "home now: $(home_now)" ;;
  verify)
    comp=${3:?component}; pkg=${comp%%/*}; ok=0
    a input keyevent KEYCODE_HOME; sleep 2
    r=$(resumed); echo "Home button -> $r"; [[ $r == "$comp" || $r == ${pkg}/* ]] || { echo "FAIL: Home didn't open $pkg"; ok=1; }
    sp=$(a pidof $STOCK || true); echo "stock launcher process: ${sp:-not running}"
    a am start -W -a android.settings.SETTINGS | grep -E 'Status|Activity:' ; sleep 2
    echo "Settings -> $(resumed)"
    a am start -W -n com.android.vending/com.google.android.finsky.tvmainactivity.TvMainActivity | grep -E 'Status' ; sleep 3
    echo "Play Store -> $(resumed)"
    a input keyevent KEYCODE_HOME; sleep 2
    r=$(resumed); echo "Home again -> $r"; [[ $r == ${pkg}/* ]] || { echo "FAIL: Home didn't return to $pkg"; ok=1; }
    exit $ok ;;
  restore)
    snap=${3:?snapshot file}
    # shellcheck disable=SC1090
    source <(grep -E '^(home|stock_state|setupwraith_state)=' "$snap")
    [[ ${setupwraith_state:-enabled} == enabled ]] && a pm enable "$SETUPWRAITH" >/dev/null || true
    [[ ${stock_state:-enabled} == enabled ]] && a pm enable "$STOCK" >/dev/null
    a cmd package set-home-activity "${home:-$STOCK_HOME}"
    echo "stock: $(enabled $STOCK)"; echo "setupwraith: $(enabled $SETUPWRAITH)"
    echo "home now: $(home_now)"; echo "role: $(role_now)" ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
