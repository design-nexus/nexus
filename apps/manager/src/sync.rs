//! Background reads: adb, the TV and local files. Nothing here blocks the
//! main thread; results land in [`state`] and trigger a redraw.

use crate::devices::{self, SavedTv};
use crate::ops;
use crate::state::{self, Found, LocalBuilds, Load, TvView};
use anyhow::{bail, Result};
use gtk::glib;
use nexus_adb::Adb;
use nexus_device::{apk_info, find_local_apk, Tv};
use std::cell::Cell;
use std::collections::HashSet;

/// Run `work` on a thread and hand its result to `done` on the main thread.
pub fn bg<T: Send + 'static>(
    work: impl FnOnce() -> T + Send + 'static,
    done: impl FnOnce(T) + 'static,
) {
    let (tx, rx) = async_channel::bounded::<T>(1);
    std::thread::spawn(move || {
        let _ = tx.send_blocking(work());
    });
    glib::spawn_future_local(async move {
        if let Ok(v) = rx.recv().await {
            done(v);
        }
    });
}

/// Find the serial a saved TV answers on now. Wireless debugging moves to a
/// new port after every restart, so try, in order: a serial adb already has,
/// the last address, then the port the TV advertises over mDNS.
pub fn resolve_serial(tv: &SavedTv) -> Result<String> {
    let list = Adb::devices()?;
    if let Some(d) = list
        .iter()
        .find(|d| d.state == "device" && tv.matches(&d.serial))
    {
        return Ok(d.serial.clone());
    }
    if devices::host_of(&tv.serial).is_some() && Adb::connect(&tv.serial).is_ok() {
        return Ok(tv.serial.clone());
    }
    for s in Adb::mdns_services().unwrap_or_default() {
        if !s.is_connect() {
            continue;
        }
        let same_host = !tv.host.is_empty() && s.host() == tv.host;
        let same_name = !tv.mdns_name.is_empty() && s.name == tv.mdns_name;
        if (same_host || same_name) && Adb::connect(&s.host_port).is_ok() {
            return Ok(s.host_port.clone());
        }
    }
    bail!(
        "{} isn't answering. Check that the TV is on and Wireless debugging is still enabled.",
        tv.name()
    )
}

/// Which saved TVs adb has, and the sidebar foot line.
pub fn refresh_online() {
    let saved = state::get(|s| s.devices.devices.clone());
    bg(
        move || {
            let list = Adb::devices();
            let ver = Adb::version_line()
                .ok()
                .and_then(|l| l.split_whitespace().last().map(str::to_string))
                .unwrap_or_default();
            (list, ver)
        },
        move |(list, ver)| {
            state::update(|s| match list {
                Ok(list) => {
                    let ready: Vec<_> = list.iter().filter(|d| d.state == "device").collect();
                    let online: HashSet<String> = saved
                        .iter()
                        .filter(|tv| ready.iter().any(|d| tv.matches(&d.serial)))
                        .map(|tv| tv.key().to_string())
                        .collect();
                    // The selected TV may be reachable even if adb lost it; keep
                    // what the last full refresh found.
                    s.online = online;
                    if s.connected()
                        && let Some(k) = s.selected_key() {
                            s.online.insert(k);
                        }
                    let n = ready.len();
                    let v = if ver.is_empty() { "adb".to_string() } else { format!("adb {ver}") };
                    s.adb_label = Some((
                        format!("{v} · {n} device{}", if n == 1 { "" } else { "s" }),
                        true,
                    ));
                }
                Err(_) => s.adb_label = Some(("adb not found".into(), false)),
            });
        },
    );
}

thread_local! {
    static TV_GEN: Cell<u64> = const { Cell::new(0) };
}

/// Read the selected TV. Keeps what's on screen until the answer arrives.
pub fn refresh_tv() {
    let Some(saved) = state::get(|s| s.selected().cloned()) else {
        state::update(|s| {
            s.tv = None;
            s.refreshing = false;
        });
        return;
    };
    let generation = TV_GEN.with(|g| {
        g.set(g.get() + 1);
        g.get()
    });
    state::update(|s| s.refreshing = true);
    let key = saved.key().to_string();
    bg(
        move || -> Result<TvView> {
            let serial = resolve_serial(&saved)?;
            let tv = Tv::new(&serial)?.with_root(devices::repo_root());
            let overview = tv.overview()?;
            let metrics = tv.quick_metrics().unwrap_or_default();
            Ok(TvView {
                serial,
                overview,
                metrics,
            })
        },
        move |r| {
            if TV_GEN.with(|g| g.get()) != generation {
                return;
            }
            if state::get(|s| s.selected_key().as_deref() != Some(key.as_str())) {
                return;
            }
            if let Ok(view) = &r {
                ops::remember_serial(&key, &view.serial);
            }
            state::update(|s| {
                s.refreshing = false;
                s.tv_checked = true;
                match r {
                    Ok(view) => {
                        s.tv = Some(view);
                        s.online.insert(key.clone());
                    }
                    Err(_) => {
                        s.tv = None;
                        s.online.remove(&key);
                    }
                }
            });
            refresh_for_section();
        },
    );
}

/// Release and debug APKs in this checkout.
pub fn refresh_local() {
    bg(
        || {
            let root = devices::repo_root();
            LocalBuilds {
                release: find_local_apk(&root, false).map(|p| apk_info(&p)),
                debug: find_local_apk(&root, true).map(|p| apk_info(&p)),
            }
        },
        |local| state::update(|s| s.local = local),
    );
}

fn selected_serial() -> Option<(String, String)> {
    state::get(|s| {
        let key = s.selected_key()?;
        let serial = s
            .tv
            .as_ref()
            .map(|t| t.serial.clone())
            .or_else(|| s.selected().map(|d| d.serial.clone()))?;
        Some((key, serial))
    })
}

pub fn refresh_snapshots() {
    let Some((key, serial)) = selected_serial() else {
        return;
    };
    state::update(|s| {
        if s.snapshots.ready().is_none() {
            s.snapshots = Load::Loading;
        }
    });
    bg(
        move || -> Result<_> {
            Tv::new(&serial)?
                .with_root(devices::repo_root())
                .list_snapshots()
        },
        move |r| {
            if state::get(|s| s.selected_key().as_deref() != Some(key.as_str())) {
                return;
            }
            state::update(|s| {
                s.snapshots = match r {
                    Ok(v) => Load::Ready(v),
                    Err(e) => Load::Failed(format!("{e:#}")),
                }
            });
        },
    );
}

pub fn refresh_debloat() {
    let Some((key, serial)) = selected_serial() else {
        return;
    };
    if !state::get(|s| s.connected()) {
        return;
    }
    state::update(|s| {
        s.debloat = Load::Loading;
        s.debloat_pending.clear();
    });
    bg(
        move || -> Result<_> {
            Tv::new(&serial)?
                .with_root(devices::repo_root())
                .debloat_packages()
        },
        move |r| {
            if state::get(|s| s.selected_key().as_deref() != Some(key.as_str())) {
                return;
            }
            state::update(|s| {
                s.debloat = match r {
                    Ok(v) => Load::Ready(v),
                    Err(e) => Load::Failed(format!("{e:#}")),
                }
            });
        },
    );
}

/// Metrics and the Nexus log for Diagnostics.
pub fn refresh_diag() {
    let Some((key, serial)) = selected_serial() else {
        return;
    };
    if !state::get(|s| s.connected()) {
        return;
    }
    state::update(|s| {
        if s.diag_log.ready().is_none() {
            s.diag_log = Load::Loading;
        }
    });
    bg(
        move || -> Result<_> {
            let tv = Tv::new(&serial)?.with_root(devices::repo_root());
            let m = tv.quick_metrics()?;
            let log = tv.recent_log(80)?;
            Ok((m, log))
        },
        move |r| {
            if state::get(|s| s.selected_key().as_deref() != Some(key.as_str())) {
                return;
            }
            state::update(|s| match r {
                Ok((m, log)) => {
                    if let Some(t) = s.tv.as_mut() {
                        t.metrics = m;
                    }
                    s.diag_log = Load::Ready(log);
                }
                Err(e) => s.diag_log = Load::Failed(format!("{e:#}")),
            });
        },
    );
}

thread_local! {
    static SCANNING: Cell<bool> = const { Cell::new(false) };
}

/// Look for TVs over mDNS and adb (Add a TV).
pub fn discover() {
    if SCANNING.with(|s| s.replace(true)) {
        return;
    }
    bg(scan, |found| {
        SCANNING.with(|s| s.set(false));
        state::update(|s| {
            s.found = found;
            s.scanned_once = true;
        });
    });
}

fn scan() -> Vec<Found> {
    let devices = Adb::devices().unwrap_or_default();
    let mdns = Adb::mdns_services().unwrap_or_default();
    let mut out: Vec<Found> = Vec::new();

    for s in &mdns {
        let host = s.host().to_string();
        let port = s.host_port.rsplit_once(':').map(|(_, p)| p.to_string());
        let idx = match out.iter().position(|f| f.host == host) {
            Some(i) => i,
            None => {
                out.push(Found {
                    title: String::new(),
                    host: host.clone(),
                    mdns_name: String::new(),
                    connect_port: None,
                    pair_port: None,
                    connected_serial: None,
                });
                out.len() - 1
            }
        };
        let f = &mut out[idx];
        if s.is_pairing() {
            f.pair_port = port;
        } else if s.is_connect() {
            f.connect_port = port;
            f.mdns_name = s.name.clone();
        }
    }

    for d in devices.iter().filter(|d| d.state == "device") {
        let host = devices::host_of(&d.serial);
        let idx = out.iter().position(|f| {
            Some(&f.host) == host.as_ref()
                || (!f.mdns_name.is_empty() && d.serial.starts_with(&f.mdns_name))
        });
        let idx = match idx {
            Some(i) => i,
            None => {
                out.push(Found {
                    title: String::new(),
                    host: host.clone().unwrap_or_else(|| d.serial.clone()),
                    mdns_name: String::new(),
                    connect_port: d.serial.rsplit_once(':').map(|(_, p)| p.to_string()),
                    pair_port: None,
                    connected_serial: None,
                });
                out.len() - 1
            }
        };
        out[idx].connected_serial = Some(d.serial.clone());
        if let Some(m) = &d.model {
            out[idx].title = m.replace('_', " ");
        }
    }

    for f in &mut out {
        if f.title.is_empty() {
            f.title = format!("TV at {}", f.host);
        }
    }
    out.sort_by(|a, b| {
        b.connected_serial
            .is_some()
            .cmp(&a.connected_serial.is_some())
            .then(a.title.cmp(&b.title))
    });
    out
}

/// Load what the visible page needs.
pub fn refresh_for_section() {
    let section = state::get(|s| s.section.clone());
    match section.as_str() {
        "add" => discover(),
        "install" => refresh_local(),
        "home" => refresh_snapshots(),
        "diagnostics" => {
            if state::get(|s| s.diag_log.ready().is_none()) {
                refresh_diag();
            }
        }
        "debloat"
            if state::get(|s| matches!(s.debloat, Load::Idle | Load::Failed(_))) => {
                refresh_debloat();
            }
        _ => {}
    }
}
