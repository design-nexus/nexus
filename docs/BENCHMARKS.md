# Benchmarks

Measurements are tied to a method. Numbers without a method are not claims.

## Bedroom TCL (BeyondTV4) — 2026-11-15

- Serial: `192.168.1.237:5555`
- Fingerprint: `TCL/G03_4K_US_NF_DB/BeyondTV4:11/RP1A.200622.001/AR26:user/release-keys`
- Nyx package: `io.github.design_nexus.nyx.tv.debug` 0.1.0
- **HOME at measurement time:** Google TV (`launcherx`) — Nyx was installed but not set as home
- Raw dump: `audit/bench-20261115-110251/`

| Metric | Value | Method |
|--------|-------|--------|
| Nyx cold start | **3081 ms** | `am start -W` of `HomeActivity` after `force-stop` (not Home button — Nyx was not HOME) |
| Nyx warm start | **0 ms** ThisTime | `am start -W` while already top-most; activity delivered to running instance |
| Nyx TOTAL PSS | **93 MB** | `dumpsys meminfo` ~2 s after warm launch |
| Stock launcherx TOTAL PSS | **75 MB** | From audit `2026-11-14` (`meminfo-com.google.android.apps.tv.launcherx.txt`); **not** re-measured this session (no stock restore) |
| Idle CPU (Nyx in top) | — | `top -n 1` sample; Nyx not in top row during that sample |
| Frame timing | See gfxinfo | `dumpsys gfxinfo` during/after `am start` — first frames look janky (17/17 janky in that window). **Not** a steady Home-as-launcher sample |

### Debloat before / after (optional packages)

Disabled `com.tcl.esticker`, `com.tcl.messagebox`, `com.tcl.bi` with `pm disable-user`, then re-enabled.

| | Nyx TOTAL PSS |
|--|--|
| Before | 90 MB |
| After disable | 83 MB |
| After re-enable | (packages restored; PSS not re-sampled) |

Δ ≈ **−7 MB** on this one sample while Nyx stayed in foreground. Not a claim that Debloat always saves 7 MB.

### How to re-run

```sh
./scripts/bench.sh 192.168.1.237:5555
```

For Home-button cold/warm, set Nyx as home in TV Manager first. This script does **not** restore stock.

## Living Room

Deferred — not measured in Phase 5 Bedroom pass.
