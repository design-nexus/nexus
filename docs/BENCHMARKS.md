# Benchmarks

Measurements are tied to a method. Numbers without a method are not claims.

## Bedroom TCL (BeyondTV4) — 2026-11-15 (Nexus as HOME)

- Serial: `192.168.1.237:5555`
- Fingerprint: `TCL/G03_4K_US_NF_DB/BeyondTV4:11/RP1A.200622.001/AR26:user/release-keys`
- Nexus package: `io.github.design_nexus.nexus.debug` 0.1.0
- **HOME:** Nexus (`io.github.design_nexus.nexus.debug/…HomeActivity`)
- Snapshot before replace: `audit/snapshot-Smart_TV-20261115-112717.env`
- Verify: Home → Nexus; Settings + Play Store open; Home returns to Nexus; stock process not running
- Raw dump: `audit/bench-20261115-112840/`

| Metric | Value | Method |
|--------|-------|--------|
| Nexus cold start (Home) | **1569 ms** | `force-stop`, then `KEYCODE_HOME` until `mResumedActivity` is Nexus (wall clock; includes adb/dumpsys poll) |
| Nexus warm start (Home) | **1599 ms** | Open Settings, then `KEYCODE_HOME` until Nexus resumed (same wall-clock method) |
| Nexus TOTAL PSS | **75 MB** | `dumpsys meminfo` ~2 s after warm return |
| Stock launcherx TOTAL PSS | **75 MB** | From audit `2026-11-14` only — stock not restored this run |
| Idle CPU (Nexus in top) | — | `top -n 1`; Nexus not in the sampled top rows |
| Frame timing | See gfxinfo | 7/7 frames janky in the short post-launch window — not a steady-state sample |

Warm method note: earlier double-Home-while-already-on-Nexus timed near the poll timeout (~3.2 s) and was discarded. `scripts/bench.sh` now leaves Home via Settings first.

### Earlier same-day (Nexus installed, Google TV still HOME)

Raw dump: `audit/bench-20261115-110251/`. Cold **3081 ms** / warm **0 ms** ThisTime via `am start -W` (not Home button). Nexus TOTAL PSS **93 MB**.

### Debloat before / after (optional packages)

Disabled `com.tcl.esticker`, `com.tcl.messagebox`, `com.tcl.bi` with `pm disable-user`, then re-enabled.

| | Nexus TOTAL PSS |
|--|--|
| Before | 90 MB |
| After disable | 83 MB |
| After re-enable | (packages restored; PSS not re-sampled) |

Δ ≈ **−7 MB** on this one sample while Nexus stayed in foreground. Not a claim that Debloat always saves 7 MB.

### How to re-run

```sh
./scripts/bench.sh 192.168.1.237:5555
```

Uses Home-button cold/warm when Nexus is the preferred HOME. Does **not** restore stock.

Restore Google TV home:

```sh
./scripts/launcher.sh 192.168.1.237:5555 restore audit/snapshot-Smart_TV-20261115-112717.env
```

## Living Room

Deferred — not measured in Phase 5 Bedroom pass.
