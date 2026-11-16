//! Everything the pages draw from. Pages never keep their own copy: a change
//! goes through [`update`], which redraws the sidebar and the visible page on
//! the next idle (so a handler never re-enters a render).

use crate::activity;
use crate::devices::{self, DeviceFile, SavedTv};
use nyx_tv_device::{ApkInfo, DebloatPackage, Overview, QuickMetrics, Snapshot};
use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, HashSet};
use std::path::PathBuf;

pub const SECTIONS: &[&str] = &[
    "add",
    "overview",
    "install",
    "home",
    "backups",
    "diagnostics",
    "debloat",
];

/// Something loaded in the background.
#[derive(Debug, Clone, Default)]
pub enum Load<T> {
    #[default]
    Idle,
    Loading,
    Ready(T),
    Failed(String),
}

impl<T> Load<T> {
    pub fn ready(&self) -> Option<&T> {
        match self {
            Load::Ready(t) => Some(t),
            _ => None,
        }
    }
}

/// The selected TV as last read over adb.
#[derive(Debug, Clone)]
pub struct TvView {
    pub serial: String,
    pub overview: Overview,
    pub metrics: QuickMetrics,
}

#[derive(Debug, Clone, Default)]
pub struct LocalBuilds {
    pub release: Option<ApkInfo>,
    pub debug: Option<ApkInfo>,
}

#[derive(Debug, Clone)]
pub struct CheckRow {
    pub ok: Option<bool>,
    pub title: String,
    pub detail: String,
}

#[derive(Debug, Clone)]
pub struct Check {
    pub rows: Vec<CheckRow>,
    pub time: i64,
}

#[derive(Debug, Clone)]
pub struct Busy {
    pub name: String,
    pub step: String,
}

/// A TV seen on the network or by adb (Add a TV).
#[derive(Debug, Clone)]
pub struct Found {
    pub title: String,
    pub host: String,
    pub mdns_name: String,
    pub connect_port: Option<String>,
    pub pair_port: Option<String>,
    pub connected_serial: Option<String>,
}

#[derive(Default)]
pub struct State {
    pub devices: DeviceFile,
    pub section: String,
    /// Keys of saved TVs adb currently has as `device`.
    pub online: HashSet<String>,
    /// Sidebar foot: adb version and device count, or None while looking.
    pub adb_label: Option<(String, bool)>,
    pub tv: Option<TvView>,
    /// A refresh of the selected TV has finished at least once.
    pub tv_checked: bool,
    pub refreshing: bool,
    pub busy: Option<Busy>,
    /// Output of the running (or last) operation.
    pub op_log: String,
    pub local: LocalBuilds,
    pub snapshots: Load<Vec<(PathBuf, Snapshot)>>,
    pub debloat: Load<Vec<DebloatPackage>>,
    /// Package → wanted enabled state, for rows the user switched.
    pub debloat_pending: BTreeMap<String, bool>,
    pub diag_log: Load<String>,
    pub check: Option<Check>,
    pub found: Vec<Found>,
    pub scanned_once: bool,
    pub activity: activity::Log,
}

impl State {
    pub fn selected_key(&self) -> Option<String> {
        self.devices.selected.clone()
    }

    pub fn selected(&self) -> Option<&SavedTv> {
        self.devices.find(self.devices.selected.as_deref()?)
    }

    pub fn selected_name(&self) -> String {
        self.selected()
            .map(|d| d.name().to_string())
            .unwrap_or_else(|| "No TV".into())
    }

    pub fn is_busy(&self) -> bool {
        self.busy.is_some()
    }

    /// Selected TV answered the last refresh.
    pub fn connected(&self) -> bool {
        self.tv.is_some()
    }

    pub fn overview(&self) -> Option<&Overview> {
        self.tv.as_ref().map(|t| &t.overview)
    }

    /// Short line for page subtitles: "Living Room · Connected".
    pub fn tv_subtitle(&self) -> String {
        let name = self.selected_name();
        if self.selected().is_none() {
            return name;
        }
        let st = if self.connected() {
            "Connected"
        } else if self.refreshing || !self.tv_checked {
            "Connecting…"
        } else {
            "Offline"
        };
        format!("{name} · {st}")
    }

    /// Forget everything read from the previous TV.
    pub fn clear_tv(&mut self) {
        self.tv = None;
        self.tv_checked = false;
        self.snapshots = Load::Idle;
        self.debloat = Load::Idle;
        self.debloat_pending.clear();
        self.diag_log = Load::Idle;
        self.check = None;
    }
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
    static RENDER: RefCell<Option<fn()>> = const { RefCell::new(None) };
    static PENDING: Cell<bool> = const { Cell::new(false) };
}

pub fn init(render: fn()) {
    let mut devices = devices::load();
    if devices.selected.is_none() {
        devices.selected = devices.devices.first().map(|d| d.key().to_string());
    }
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.devices = devices;
        s.activity = activity::load();
    });
    RENDER.with(|r| *r.borrow_mut() = Some(render));
}

pub fn get<R>(f: impl FnOnce(&State) -> R) -> R {
    STATE.with(|s| f(&s.borrow()))
}

/// Change state and redraw on the next idle.
pub fn update(f: impl FnOnce(&mut State)) {
    STATE.with(|s| f(&mut s.borrow_mut()));
    schedule_render();
}

/// Change state without a redraw (the caller updates its own widgets).
pub fn update_quiet(f: impl FnOnce(&mut State)) {
    STATE.with(|s| f(&mut s.borrow_mut()));
}

pub fn schedule_render() {
    if PENDING.with(|p| p.replace(true)) {
        return;
    }
    gtk::glib::idle_add_local_once(|| {
        PENDING.with(|p| p.set(false));
        if let Some(render) = RENDER.with(|r| *r.borrow()) {
            render();
        }
    });
}

pub fn save_devices() {
    get(|s| {
        if let Err(e) = devices::save(&s.devices) {
            eprintln!("tv-manager: saving devices: {e:#}");
        }
    });
}

/// Record a finished action for the selected TV.
pub fn log_activity(text: &str) {
    update(|s| {
        if let Some(key) = s.selected_key() {
            s.activity.push(&key, text);
        }
    });
}
