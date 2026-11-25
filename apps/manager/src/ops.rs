//! Operations that change something (on the TV or on disk). One runs at a
//! time: while it runs, the status strip shows its current step, its output
//! goes to the log, and the pages draw their action buttons insensitive.

use crate::devices;
use crate::state::{self, Busy};
use crate::sync;
use crate::window;
use anyhow::Result;
use gtk::glib;
use nexus_device::Tv;

enum Msg {
    Step(String),
    Log(String),
    Serial(String, String),
    Done(Result<String>),
}

/// Handed to the worker thread to report progress.
pub struct Ctx {
    tx: async_channel::Sender<Msg>,
}

impl Ctx {
    /// A new step: shown in the status strip and written to the log.
    pub fn step(&self, text: &str) {
        let _ = self.tx.send_blocking(Msg::Step(text.to_string()));
    }

    /// A line of output for the log.
    pub fn log(&self, text: &str) {
        let _ = self.tx.send_blocking(Msg::Log(text.to_string()));
    }
}

/// Run `work` off the main thread. Returns false (and says so) when another
/// operation is still running. `after` runs on the main thread with success.
pub fn run(
    name: &str,
    work: impl FnOnce(&Ctx) -> Result<String> + Send + 'static,
    after: impl FnOnce(bool) + 'static,
) -> bool {
    if let Some(busy) = state::get(|s| s.busy.clone()) {
        window::toast(&format!("Wait for “{}” to finish", busy.name));
        return false;
    }
    let name = name.to_string();
    state::update(|s| {
        s.busy = Some(Busy {
            name: name.clone(),
            step: format!("{name}…"),
        });
        s.op_log = format!("▸ {name}\n");
    });
    window::op_log_changed();

    let (tx, rx) = async_channel::unbounded::<Msg>();
    std::thread::spawn(move || {
        let ctx = Ctx { tx: tx.clone() };
        let r = work(&ctx);
        let _ = tx.send_blocking(Msg::Done(r));
    });
    glib::spawn_future_local(async move {
        let mut after = Some(after);
        while let Ok(msg) = rx.recv().await {
            match msg {
                Msg::Step(text) => {
                    state::update(|s| {
                        if let Some(b) = s.busy.as_mut() {
                            b.step = text.clone();
                        }
                        s.op_log.push_str(&format!("▸ {text}\n"));
                    });
                    window::op_log_changed();
                }
                Msg::Log(text) => {
                    state::update_quiet(|s| {
                        s.op_log.push_str(&text);
                        s.op_log.push('\n');
                    });
                    window::op_log_changed();
                }
                Msg::Serial(key, serial) => remember_serial(&key, &serial),
                Msg::Done(r) => {
                    let ok = r.is_ok();
                    state::update(|s| s.busy = None);
                    match r {
                        Ok(msg) => {
                            state::update_quiet(|s| s.op_log.push_str(&format!("✓ {msg}\n")));
                            window::toast(&msg);
                            state::log_activity(&msg);
                        }
                        Err(e) => {
                            let full = format!("{e:#}");
                            state::update_quiet(|s| s.op_log.push_str(&format!("✕ {full}\n")));
                            let first = full.lines().next().unwrap_or("").to_string();
                            window::toast_action(
                                &format!("{name} failed: {first}"),
                                "Details",
                                window::show_op_log,
                            );
                        }
                    }
                    window::op_log_changed();
                    if let Some(a) = after.take() {
                        a(ok);
                    }
                    sync::refresh_tv();
                    break;
                }
            }
        }
    });
    true
}

/// Like [`run`], with the selected TV connected first (reconnecting on a
/// new wireless-debugging port if it moved).
pub fn run_tv(
    name: &str,
    work: impl FnOnce(&Ctx, Tv) -> Result<String> + Send + 'static,
    after: impl FnOnce(bool) + 'static,
) -> bool {
    let Some(saved) = state::get(|s| s.selected().cloned()) else {
        window::toast("Add a TV first");
        return false;
    };
    run(
        name,
        move |ctx| {
            let serial = sync::resolve_serial(&saved)?;
            if serial != saved.serial {
                let _ = ctx
                    .tx
                    .send_blocking(Msg::Serial(saved.key().to_string(), serial.clone()));
            }
            let tv = Tv::new(&serial)?.with_root(devices::repo_root());
            work(ctx, tv)
        },
        after,
    )
}

/// The TV answered on a new serial: keep it for next time.
pub fn remember_serial(key: &str, serial: &str) {
    let changed = state::get(|s| {
        s.devices
            .find(key)
            .map(|d| d.serial != serial)
            .unwrap_or(false)
    });
    if changed {
        state::update_quiet(|s| {
            if let Some(d) = s.devices.find_mut(key) {
                d.serial = serial.to_string();
            }
        });
        state::save_devices();
    }
}
