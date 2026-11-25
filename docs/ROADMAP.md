# Roadmap

## Done locally (Phases 0–4 + Manager overhaul)

- Mockups, toolchain, Bedroom audit / launcher replace notes
- Android launcher (home, Plex, themes, ambient, accessibility helper)
- TV Manager (GTK) Option A pages, live Debloat ∩ TV packages
- Manager polish (HOME honesty, Verify, Install rescan, Debloat layout)

Not committed until Ken says **completed**.

## Phase 5 — Bedroom (this pass)

- [x] `data/debloat/` TOML for Bedroom BeyondTV4 fingerprint + load path
- [x] `scripts/bench.sh` + [BENCHMARKS.md](BENCHMARKS.md)
- [x] Compatibility notes for cast / HDMI / CEC / DRM launches ([COMPATIBILITY.md](COMPATIBILITY.md))
- [x] Debloat before/after PSS sample
- [x] CI check workflow + release scaffold
- [ ] Living Room TV (deferred)
- [x] Home-button cold/warm with Nexus as HOME (bench `audit/bench-20261115-112840/`)

## Later

- Windows / macOS Manager bundles
- Pure-Rust adb client
- Signed Play-style distribution if needed
