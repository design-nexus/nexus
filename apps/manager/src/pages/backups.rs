//! Backups: Nexus's layout and settings to and from a file, or straight to
//! another TV. Plex sign-in never leaves the TV.

use crate::devices;
use crate::ops;
use crate::page::{self, Page};
use crate::state::{self, State};
use crate::sync;
use anyhow::bail;
use gtk::gio;
use gtk::prelude::*;
use nexus_device::Tv;
use std::cell::RefCell;
use std::path::PathBuf;

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Backups");
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

pub fn render(st: &State) {
    let Some(p) = PAGE.with(|c| c.borrow().clone()) else { return };
    p.clear();
    p.set_actions(&[]);
    p.set_sub(&st.tv_subtitle());
    if super::gate(&p, st, true) {
        return;
    }
    let installed = st.overview().is_some_and(|o| o.nexus_package.is_some());
    if !installed {
        p.add_center(&page::empty(
            "document-save-symbolic",
            "Nexus isn't installed",
            "Backups hold Nexus's layout and settings. Install Nexus first.",
            Some(("Install", Box::new(|| crate::window::show_section("install")))),
        ));
        return;
    }
    p.set_actions(&[
        page::icon("document-open-symbolic", "Import from a file…", import_settings),
        page::primary("document-save-symbolic", "Export…", export_settings),
    ]);

    let (card, rows) = page::card("Layout and settings", &[]);
    rows.append(&page::row(
        "Save to a file",
        "Rows, favourites, theme and preferences.",
        None,
        &[page::icon("document-save-symbolic", "Export…", export_settings)],
    ));
    rows.append(&page::row(
        "Load from a file",
        "Replaces the layout and preferences on this TV.",
        None,
        &[page::icon("document-open-symbolic", "Import…", import_settings)],
    ));
    rows.append(&page::dim("Plex sign-in is never included in a backup."));
    p.add(&card);

    let key = st.selected_key().unwrap_or_default();
    let (card, rows) = page::card("Copy to another TV", &[]);
    let others: Vec<_> = st.devices.devices.iter().filter(|d| d.key() != key).collect();
    if others.is_empty() {
        rows.append(&page::dim("Add another TV to copy this TV's layout to it."));
    }
    for d in others {
        let on = st.online.contains(d.key());
        let (tkey, tname) = (d.key().to_string(), d.name().to_string());
        let copy = page::icon("edit-copy-symbolic", &format!("Copy layout to {tname}"), move || {
            copy_to(&tkey, &tname)
        });
        rows.append(&page::row(
            d.name(),
            if on { "Online" } else { "Offline — TV Manager tries to reach it when you copy" },
            Some(&page::dot(on)),
            &[copy],
        ));
    }
    p.add(&card);
}

fn export_settings() {
    let Some(win) = crate::window::window() else { return };
    let dialog = gtk::FileDialog::builder()
        .title("Export Nexus layout")
        .initial_name("nexus-settings.json")
        .build();
    dialog.save(Some(&win), None::<&gio::Cancellable>, |res| {
        let Some(path) = res.ok().and_then(|f| f.path()) else { return };
        ops::run_tv(
            "Export layout",
            move |ctx, tv| {
                ctx.step("Exporting the layout from the TV…");
                tv.export_settings(&path)?;
                Ok(format!("Layout saved to {}", path.display()))
            },
            |_| {},
        );
    });
}

fn import_settings() {
    let Some(win) = crate::window::window() else { return };
    let filter = gtk::FileFilter::new();
    filter.set_name(Some("Nexus backup (JSON)"));
    filter.add_pattern("*.json");
    let dialog = gtk::FileDialog::builder()
        .title("Import Nexus layout")
        .default_filter(&filter)
        .build();
    dialog.open(Some(&win), None::<&gio::Cancellable>, |res| {
        let Some(path) = res.ok().and_then(|f| f.path()) else { return };
        page::confirm(
            "Import this layout?",
            "Replaces the layout and preferences on the TV. Plex sign-in isn't affected.",
            "Import",
            move || {
                ops::run_tv(
                    "Import layout",
                    move |ctx, tv| {
                        ctx.step("Importing the layout on the TV…");
                        tv.import_settings(&path)?;
                        Ok("Layout imported".into())
                    },
                    |_| {},
                );
            },
        );
    });
}

fn copy_to(target_key: &str, target_name: &str) {
    let Some(target) = state::get(|s| s.devices.find(target_key).cloned()) else { return };
    let from = state::get(|s| s.selected_name());
    let tname = target_name.to_string();
    page::confirm(
        &format!("Copy the layout to {tname}?"),
        &format!("Replaces the layout and preferences on {tname} with {from}'s. Plex sign-in isn't copied."),
        "Copy",
        move || {
            ops::run_tv(
                "Copy layout",
                move |ctx, tv| {
                    let dir = std::env::var_os("XDG_RUNTIME_DIR")
                        .map(PathBuf::from)
                        .unwrap_or_else(std::env::temp_dir);
                    let tmp = dir.join(format!("nexus-layout-{}.json", std::process::id()));
                    let result = (|| {
                        ctx.step("Exporting the layout…");
                        tv.export_settings(&tmp)?;
                        ctx.step(&format!("Connecting to {}…", target.name()));
                        let serial = sync::resolve_serial(&target)?;
                        ctx.step(&format!("Importing on {}…", target.name()));
                        let other = Tv::new(&serial)?.with_root(devices::repo_root());
                        if !other.has_package(nexus_device::NEXUS_PKG).unwrap_or(false)
                            && !other.has_package(nexus_device::NEXUS_PKG_DEBUG).unwrap_or(false)
                        {
                            bail!("Nexus isn't installed on {}", target.name());
                        }
                        other.import_settings(&tmp)?;
                        Ok(format!("Copied the layout to {}", target.name()))
                    })();
                    let _ = std::fs::remove_file(&tmp);
                    result
                },
                |_| {},
            );
        },
    );
}

/// Copy this TV's snapshots to a folder (Home screen's export button).
pub fn export_snapshots() {
    let Some(win) = crate::window::window() else { return };
    let dialog = gtk::FileDialog::builder()
        .title("Export snapshots to a folder")
        .build();
    dialog.select_folder(Some(&win), None::<&gio::Cancellable>, |res| {
        let Some(dir) = res.ok().and_then(|f| f.path()) else { return };
        ops::run_tv(
            "Export snapshots",
            move |ctx, tv| {
                ctx.step("Copying snapshots…");
                let fp = tv.overview()?.fingerprint;
                let matching: Vec<_> = tv
                    .list_snapshots()?
                    .into_iter()
                    .filter(|(_, s)| s.fingerprint == fp)
                    .collect();
                if matching.is_empty() {
                    bail!("No snapshots for this TV's firmware yet");
                }
                let dest = dir.join("nexus-snapshots");
                std::fs::create_dir_all(&dest)?;
                for (path, _) in &matching {
                    if let Some(name) = path.file_name() {
                        std::fs::copy(path, dest.join(name))?;
                    }
                }
                Ok(format!("Exported {} snapshot(s) to {}", matching.len(), dest.display()))
            },
            |_| {},
        );
    });
}
