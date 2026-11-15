#!/usr/bin/env bash
# Read-mostly benchmarks for a connected Android TV.
# Usage: scripts/bench.sh [serial]
# Does not restore stock or disable packages. Stock numbers come from a prior
# audit dump when present, or are marked unverified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL=${1:-}
if [[ -z $SERIAL ]]; then
  SERIAL=$(adb devices | awk '/\tdevice$/{print $1; exit}')
fi
[[ -n $SERIAL ]] || { echo "No adb device. Pass serial or connect one." >&2; exit 1; }

adb=(adb -s "$SERIAL")
out="$ROOT/audit/bench-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

say() { printf '==> %s\n' "$*"; }
run() { "${adb[@]}" "$@"; }

say "TV $SERIAL → $out"
fp=$(run shell getprop ro.build.fingerprint | tr -d '\r')
model=$(run shell getprop ro.product.model | tr -d '\r')
home=$(run shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | tail -1 || true)
printf 'fingerprint=%s\nmodel=%s\nhome=%s\n' "$fp" "$model" "$home" >"$out/meta.env"

# Resolve Nyx package if installed.
nyx_pkg=
for p in io.github.design_nexus.nyx.tv.debug io.github.design_nexus.nyx.tv; do
  if run shell pm path "$p" 2>/dev/null | grep -q package:; then
    nyx_pkg=$p
    break
  fi
done
printf 'nyx_package=%s\n' "$nyx_pkg" >>"$out/meta.env"

pss_mb() {
  local pkg=$1
  local dump
  dump=$(run shell dumpsys meminfo "$pkg" 2>/dev/null || true)
  printf '%s\n' "$dump" >"$out/meminfo-${pkg//\//_}.txt"
  python3 - "$dump" <<'PY'
import re, sys
text = sys.argv[1]
for line in text.splitlines():
    t = line.strip()
    if t.startswith("TOTAL PSS:") or (t.startswith("TOTAL:") and "SWAP" not in t):
        m = re.search(r"(\d+)", t)
        if m:
            print((int(m.group(1)) + 512) // 1024)
            break
PY
}

# Nyx HomeActivity component (debug vs release).
nyx_home=
case $nyx_pkg in
  *.debug) nyx_home="$nyx_pkg/io.github.design_nexus.nyx.tv.ui.HomeActivity" ;;
  io.github.design_nexus.nyx.tv) nyx_home="$nyx_pkg/io.github.design_nexus.nyx.tv.ui.HomeActivity" ;;
esac
nyx_is_home=0
[[ $home == *"$nyx_pkg"* ]] && nyx_is_home=1
printf 'nyx_is_home=%s\nnyx_home=%s\n' "$nyx_is_home" "$nyx_home" >>"$out/meta.env"

# Cold / warm: prefer Home button when Nyx is HOME; else am start -W.
cold_ms=
warm_ms=
if [[ -n $nyx_pkg && -n $nyx_home ]]; then
  say "Cold start ($nyx_pkg) — Home button=$nyx_is_home"
  run shell am force-stop "$nyx_pkg" >/dev/null 2>&1 || true
  sleep 1
  start=$(date +%s%3N)
  if [[ $nyx_is_home -eq 1 ]]; then
    run shell input keyevent KEYCODE_HOME >/dev/null
    for i in $(seq 1 40); do
      sleep 0.25
      resumed=$(run shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -o 'mResumedActivity: ActivityRecord{[^}]*}' | head -1 || true)
      if [[ $resumed == *"$nyx_pkg"* ]]; then
        end=$(date +%s%3N)
        cold_ms=$((end - start))
        break
      fi
    done
  else
    # ThisTotalTime from am start -W (ms).
    cold_ms=$(run shell am start -W -n "$nyx_home" 2>/dev/null | tr -d '\r' | awk -F': ' '/ThisTime|TotalTime/{print $2; exit}')
  fi
  printf 'cold_ms=%s\n' "${cold_ms:-}" >"$out/cold.txt"
  say "Cold start → ${cold_ms:-timeout} ms"

  say "Warm start"
  if [[ $nyx_is_home -eq 1 ]]; then
    # Leave Home first so the second Home press is a real return, not a no-op.
    run shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
    sleep 1
    start=$(date +%s%3N)
    run shell input keyevent KEYCODE_HOME >/dev/null
    for i in $(seq 1 40); do
      sleep 0.1
      resumed=$(run shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -o 'mResumedActivity: ActivityRecord{[^}]*}' | head -1 || true)
      if [[ $resumed == *"$nyx_pkg"* ]]; then
        end=$(date +%s%3N)
        warm_ms=$((end - start))
        break
      fi
    done
  else
    start=$(date +%s%3N)
    warm_ms=$(run shell am start -W -n "$nyx_home" 2>/dev/null | tr -d '\r' | awk -F': ' '/ThisTime|TotalTime/{print $2; exit}')
  fi
  printf 'warm_ms=%s\n' "${warm_ms:-}" >"$out/warm.txt"
  say "Warm start → ${warm_ms:-timeout} ms"

  say "Nyx PSS (after warm launch)"
  sleep 2
  # Ensure process is up for meminfo.
  run shell am start -n "$nyx_home" >/dev/null 2>&1 || true
  sleep 2
  nyx_pss=$(pss_mb "$nyx_pkg" || true)
  printf 'nyx_pss_mb=%s\n' "${nyx_pss:-}" >"$out/nyx-pss.txt"
  say "Nyx TOTAL PSS → ${nyx_pss:-?} MB"
else
  say "Nyx TV not installed — skipping cold/warm/PSS for Nyx"
fi

# Idle CPU sample (top once).
say "Idle CPU sample"
run shell top -n 1 -d 1 2>/dev/null | tr -d '\r' >"$out/top.txt" || true
if [[ -n $nyx_pkg ]]; then
  grep -F "$nyx_pkg" "$out/top.txt" | head -3 >"$out/nyx-top.txt" || true
fi

# Frame timing if available.
say "gfxinfo (may be empty on leanback)"
if [[ -n $nyx_pkg ]]; then
  run shell dumpsys gfxinfo "$nyx_pkg" 2>/dev/null | tr -d '\r' | head -80 >"$out/gfxinfo.txt" || true
fi

# Stock baseline from prior audit if present (no restore in this script).
stock_audit="$ROOT/audit/Smart_TV-20261114-133945/meminfo-com.google.android.apps.tv.launcherx.txt"
stock_pss=
if [[ -f $stock_audit ]]; then
  stock_pss=$(python3 - "$stock_audit" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
for line in text.splitlines():
    t = line.strip()
    if t.startswith("TOTAL PSS:"):
        m = re.search(r"(\d+)", t)
        if m:
            print((int(m.group(1)) + 512) // 1024)
            break
PY
)
fi
printf 'stock_pss_mb_from_audit=%s\n' "${stock_pss:-}" >"$out/stock-from-audit.txt"

cat >"$out/summary.txt" <<EOF
serial=$SERIAL
fingerprint=$fp
model=$model
home=$home
nyx_package=$nyx_pkg
cold_ms=${cold_ms:-}
warm_ms=${warm_ms:-}
nyx_pss_mb=${nyx_pss:-}
stock_pss_mb_audit_2026-11-14=${stock_pss:-}
notes=Stock PSS from audit dump only; this script does not restore stock.
EOF

say "Wrote $out/summary.txt"
cat "$out/summary.txt"
