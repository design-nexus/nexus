//! Home screen: which app the Home button opens, what setting Nyx TV as home
//! changes, a check that it works, snapshots to go back to, and undo.

use crate::actions;
use crate::activity;
use crate::page::{self, Page};
use crate::state::{Load, State};
use gtk::prelude::*;
use nyx_tv_device::Overview;
use nyx_ui::widgets as w;
use std::cell::RefCell;

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Home screen");
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

fn state_word(s: &str) -> &'static str {
    match s {
        "enabled" | "default" => "On",
        "disabled" | "disabled-user" => "Off",
        "" | "missing" | "not-installed" => "Not on this TV",
        _ => "Unknown",
    }
}

/// What / Now / After table. `after` is None when nothing would change.
fn changes_table(o: &Overview, pending: bool) -> gtk::Grid {
    let g = gtk::Grid::new();
    g.add_css_class("tm-table");
    g.set_column_spacing(18);
    g.set_row_spacing(0);
    let heads: &[&str] = if pending { &["", "Now", "After"] } else { &["", "Now"] };
    for (i, h) in heads.iter().enumerate() {
        let l = w::label(h, "tm-th");
        g.attach(&l, i as i32, 0, 1, 1);
    }
    let rows: Vec<(&str, String, String, bool)> = vec![
        ("Home app", o.home_label().to_string(), "Nyx TV".into(), o.home_label() != "Nyx TV"),
        (
            "Google TV Home",
            state_word(&o.stock_state).to_string(),
            "Off".into(),
            !o.stock_disabled(),
        ),
        ("Play Store, Cast and updates", "On".into(), "Unchanged".into(), false),
    ];
    for (i, (what, now, after, changes)) in rows.into_iter().enumerate() {
        let r = i as i32 + 1;
        let k = w::label(what, "tm-td");
        k.set_hexpand(true);
        g.attach(&k, 0, r, 1, 1);
        g.attach(&w::label(&now, "tm-td"), 1, r, 1, 1);
        if pending {
            let a = w::label(&after, "tm-td");
            if changes {
                a.add_css_class("tm-change");
            } else {
                a.add_css_class("dim");
            }
            g.attach(&a, 2, r, 1, 1);
        }
    }
    g
}

pub fn render(st: &State) {
    let Some(p) = PAGE.with(|c| c.borrow().clone()) else { return };
    p.clear();
    p.set_actions(&[]);
    p.set_sub(&st.tv_subtitle());
    if super::gate(&p, st, true) {
        return;
    }
    let Some(o) = st.overview() else { return };
    let installed = o.nyx_package.is_some();
    let home = o.nyx_is_home();
    let fp = o.fingerprint.clone();
    let snaps: Vec<_> = st
        .snapshots
        .ready()
        .map(|v| v.iter().filter(|(_, s)| s.fingerprint == fp).cloned().collect())
        .unwrap_or_default();
    // Was set up here before, and the Home button moved away since:
    // usually a firmware update.
    let lost_home = installed && !home && !snaps.is_empty();

    if !installed {
        p.add(&page::banner(
            "Nyx TV isn't on this TV yet. Install it first; then it can become the home app.",
            false,
            Some(("Install", Box::new(|| crate::window::show_section("install")))),
        ));
    } else if lost_home {
        p.set_actions(&[page::primary("view-refresh-symbolic", "Repair", actions::repair)]);
        p.add(&page::banner(
            "Google TV has taken the Home button back. This often happens after a firmware update.",
            true,
            None,
        ));
    } else if !home {
        p.set_actions(&[page::primary("user-home-symbolic", "Set as home", actions::set_home)]);
    }

    // Home app
    let pending = installed && (!home || !o.stock_disabled());
    let (card, rows) = page::card("Home app", &[]);
    rows.append(&changes_table(o, pending));
    if pending {
        rows.append(&page::dim(
            "Setting Nyx TV as home saves a snapshot first and never uninstalls anything. Wireless debugging stays on so you can come back here.",
        ));
    }
    let stock_on = !o.stock_disabled();
    let sw = gtk::Switch::new();
    sw.set_active(stock_on);
    sw.set_valign(gtk::Align::Center);
    // On cancel, redraw from state; setting the switch back here would fire
    // this handler again and run the opposite action.
    sw.connect_active_notify(|s| actions::set_google_home(s.is_active(), crate::state::schedule_render));
    rows.append(&page::row(
        "Google TV Home",
        if stock_on {
            "On. It can take the Home button back from Nyx TV."
        } else {
            "Off. Still installed; turn it on again here."
        },
        None,
        &[sw.upcast()],
    ));
    p.add(&card);

    // Check
    if installed && home {
        let (card, rows) = page::card(
            "Check",
            &[page::icon(
                "media-playback-start-symbolic",
                "Run the check (presses Home and opens apps on the TV)",
                actions::run_check,
            )],
        );
        match &st.check {
            None => rows.append(&page::dim(
                "Presses Home on the TV, opens Settings and the Play Store, and checks Nyx TV comes back each time. Takes about ten seconds.",
            )),
            Some(c) => {
                for r in &c.rows {
                    let mark = gtk::Image::from_icon_name(match r.ok {
                        Some(true) => "emblem-ok-symbolic",
                        Some(false) => "dialog-error-symbolic",
                        None => "radio-symbolic",
                    });
                    mark.add_css_class(match r.ok {
                        Some(true) => "tm-ok",
                        Some(false) => "tm-bad",
                        None => "dim",
                    });
                    rows.append(&page::row(&r.title, &r.detail, Some(mark.upcast_ref()), &[]));
                }
                rows.append(&page::dim(&format!("Checked {}", activity::when(c.time))));
            }
        }
        rows.append(&page::row(
            "Restart the TV",
            "Confirms Nyx TV is still the home app after a restart.",
            None,
            &[page::danger("Restart", "Restart now?", actions::restart_tv)],
        ));
        p.add(&card);
    }

    // Snapshots
    let (card, rows) = page::card(
        "Snapshots",
        &[
            page::icon("document-send-symbolic", "Export snapshots…", crate::pages::backups::export_snapshots),
            page::icon("camera-photo-symbolic", "Save a snapshot now", actions::take_snapshot),
        ],
    );
    match &st.snapshots {
        Load::Idle | Load::Loading => rows.append(&page::dim("Loading snapshots…")),
        Load::Failed(e) => rows.append(&page::dim(&format!("Couldn't read snapshots: {e}"))),
        Load::Ready(all) => {
            let mine: Vec<_> = all.iter().filter(|(_, s)| s.fingerprint == fp).collect();
            if mine.is_empty() {
                rows.append(&page::dim(
                    "No snapshots for this TV yet. One is saved automatically before Nyx TV becomes the home app.",
                ));
            }
            for (path, snap) in mine.iter().take(8) {
                let name = path
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default();
                let home_was = if snap.home.contains("nyx") { "Nyx TV" } else { "Google TV" };
                let desc = format!(
                    "Home app: {home_was} · Google TV Home {}",
                    state_word(&snap.stock_state).to_lowercase()
                );
                let pth = path.clone();
                let restore = page::danger("Restore", "Restore now?", move || {
                    actions::restore_snapshot(pth.clone())
                });
                rows.append(&page::row(&page::snapshot_when(&name), &desc, None, &[restore]));
            }
            if mine.len() > 8 {
                rows.append(&page::dim(&format!("{} older snapshots not shown.", mine.len() - 8)));
            }
        }
    }
    p.add(&card);

    // Undo
    if installed {
        let (card, rows) = page::card("Go back", &[]);
        rows.append(&page::row(
            "Go back to Google TV Home",
            "Turns Google TV Home on and makes it the home app. Nyx TV stays installed.",
            None,
            &[page::danger("Go back", "Go back now?", || actions::undo(false))],
        ));
        rows.append(&page::row(
            "Go back and remove Nyx TV",
            "The same, then uninstalls Nyx TV and its settings.",
            None,
            &[page::danger("Remove", "Remove now?", || actions::undo(true))],
        ));
        p.add(&card);
    }
}
