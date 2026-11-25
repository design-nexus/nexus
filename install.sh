#!/usr/bin/env bash
# Install TV Manager from this checkout.
#
#   ./install.sh manager
#   ./install.sh          # same
set -euo pipefail

REPO="design-nexus/nexus"
ALL=(manager)
(($#)) || set -- manager

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m==>\033[0m %s\n' "$*" >&2; exit 1; }

app_info() {
  dir=$1 pkg="" bins="" deps="" ids=""
  case $1 in
    manager)
      dir=manager
      pkg=nexus-manager
      bins=nexus-manager
      ids=Manager
      deps="gtk4 android-tools"
      ;;
    *) return 1 ;;
  esac
}

apps=()
for a in "$@"; do
  case $a in
    all) apps=("${ALL[@]}") ;;
    --list) printf '%s\n' "${ALL[@]}"; exit 0 ;;
    -h | --help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) app_info "$a" >/dev/null || die "No app called '$a'. Apps: ${ALL[*]}"; apps+=("$a") ;;
  esac
done
((${#apps[@]})) || die "Name the apps to install: ${ALL[*]}"

here=""
[[ -n ${BASH_SOURCE[0]:-} && -f ${BASH_SOURCE[0]} ]] && here=$(cd "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ -z $here || ! -f $here/Cargo.toml || ! -d $here/crates ]]; then
  die "Run install.sh from a Nexus checkout that has Cargo.toml and crates/."
fi
src=$here

if command -v pacman >/dev/null; then
  need=()
  wanted="gtk4 pkgconf gcc git"
  for a in "${apps[@]}"; do app_info "$a"; wanted+=" $deps"; done
  for p in $wanted; do pacman -Qq "$p" >/dev/null 2>&1 || need+=("$p"); done
  if ((${#need[@]})); then
    say "Need packages: ${need[*]} (install with pacman if missing)"
    warn "Skipping pacman install from this script — install gtk4 / android-tools yourself if needed."
  fi
fi

command -v cargo >/dev/null || die "Rust (cargo) is needed to build TV Manager."
command -v adb >/dev/null || warn "adb not on PATH; Manager needs android-tools."

pkgs=()
for a in "${apps[@]}"; do app_info "$a"; pkgs+=(-p "$pkg"); done
say "Building ${apps[*]}"
(cd "$src" && cargo build --release "${pkgs[@]}")

bin="$HOME/.local/bin"
share="${XDG_DATA_HOME:-$HOME/.local/share}"
desk="$share/applications"
icons="$share/icons/hicolor/scalable/apps"
mkdir -p "$bin" "$desk" "$icons"

target="${CARGO_TARGET_DIR:-$src/target}/release"
for a in "${apps[@]}"; do
  app_info "$a"
  d="$src/apps/$dir"
  say "Installing $a"
  for b in $bins; do
    install -Dm755 "$target/$b" "$bin/$b"
  done
  if [[ -n $ids ]]; then
    for id in $ids; do
      desktop="io.github.design_nexus.nexus.$id.desktop"
      [[ -f $d/data/$desktop ]] && install -Dm644 "$d/data/$desktop" "$desk/$desktop"
      icon="io.github.design_nexus.nexus.$id.svg"
      [[ -f $d/data/icons/$icon ]] && install -Dm644 "$d/data/icons/$icon" "$icons/$icon"
    done
  fi
done

if command -v update-desktop-database >/dev/null; then
  update-desktop-database "$desk" >/dev/null 2>&1 || true
fi
if command -v gtk-update-icon-cache >/dev/null; then
  gtk-update-icon-cache -f -t "$share/icons/hicolor" >/dev/null 2>&1 || true
fi

# Per-model Debloat catalogs (Phase 5) — Manager falls back to built-in if missing.
if [[ -d $src/data/debloat ]]; then
  debloat_dst="$share/nexus-manager/debloat"
  mkdir -p "$debloat_dst"
  cp -a "$src/data/debloat/." "$debloat_dst/"
  say "Installed Debloat catalogs → $debloat_dst"
fi

say "Installed. Run: nexus-manager"
