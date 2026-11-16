//! Debloat: turn preinstalled extras off (never uninstall). Each package has
//! a switch showing whether it's on; changes queue up until you apply them.

use crate::ops;
use crate::page::{self, Page};
use crate::state::{self, Load, State};
use crate::sync;
use gtk::prelude::*;
use nyx_tv_device::{DebloatKind, DebloatPackage};
use nyx_ui::widgets as w;
use std::cell::RefCell;

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Debloat");
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

fn page() -> Option<Page> {
    PAGE.with(|c| c.borrow().clone())
}

fn header(st: &State) {
    let Some(p) = page() else { return };
    let mut acts: Vec<gtk::Widget> = Vec::new();
    if st.connected() {
        acts.push(page::icon("view-refresh-symbolic", "Scan again", sync::refresh_debloat));
    }
    let n = st.debloat_pending.len();
    if n > 0 {
        acts.push(page::icon("edit-undo-symbolic", "Discard changes", || {
            state::update(|s| s.debloat_pending.clear())
        }));
        acts.push(page::primary(
            "emblem-ok-symbolic",
            &format!("Apply {n} change{}", if n == 1 { "" } else { "s" }),
            apply,
        ));
    }
    p.set_actions(&acts);
    if st.is_busy() {
        page::set_busy(&p.root, true);
    }
}

fn pkg_row(pkg: &DebloatPackage, pending: Option<bool>) -> gtk::Box {
    let id = w::label(&pkg.package, "tm-pkg-id");
    let mut end: Vec<gtk::Widget> = Vec::new();
    let tag = w::tag("", "warning");
    let set_tag = {
        let tag = tag.clone();
        move |p: Option<bool>| match p {
            Some(true) => {
                tag.set_text("Will turn on");
                tag.set_visible(true);
            }
            Some(false) => {
                tag.set_text("Will turn off");
                tag.set_visible(true);
            }
            None => tag.set_visible(false),
        }
    };
    set_tag(pending);
    end.push(tag.upcast());

    if pkg.kind == DebloatKind::Protected {
        end.push(page::tag("Left alone", ""));
    } else {
        let sw = gtk::Switch::new();
        sw.set_active(pending.unwrap_or(pkg.enabled));
        sw.set_tooltip_text(Some(if pkg.enabled { "On" } else { "Off" }));
        let (name, enabled) = (pkg.package.clone(), pkg.enabled);
        sw.connect_active_notify(move |s| {
            let want = s.is_active();
            let p = if want == enabled { None } else { Some(want) };
            state::update_quiet(|st| {
                match p {
                    Some(v) => st.debloat_pending.insert(name.clone(), v),
                    None => st.debloat_pending.remove(&name),
                };
            });
            set_tag(p);
            state::get(header);
        });
        end.push(sw.upcast());
    }
    let r = page::row(&pkg.title, "", None, &end);
    // Package id under the title, in the row's text column.
    if let Some(text) = r.first_child().and_downcast::<gtk::Box>() {
        text.append(&id);
    }
    if pkg.kind == DebloatKind::Protected {
        r.add_css_class("dim");
    }
    r
}

pub fn render(st: &State) {
    let Some(p) = page() else { return };
    p.clear();
    p.set_actions(&[]);
    p.set_sub(&format!("{} · Advanced", st.tv_subtitle()));
    if super::gate(&p, st, true) {
        return;
    }
    header(st);
    let pkgs = match &st.debloat {
        Load::Idle | Load::Loading => {
            p.add_center(&page::empty(
                "system-search-symbolic",
                "Scanning this TV…",
                "Looking for known extras that are installed.",
                None,
            ));
            return;
        }
        Load::Failed(e) => {
            p.add_center(&page::empty(
                "dialog-error-symbolic",
                "Couldn't scan this TV",
                e,
                Some(("Try again", Box::new(sync::refresh_debloat))),
            ));
            return;
        }
        Load::Ready(v) => v,
    };
    if pkgs.is_empty() {
        p.add_center(&page::empty(
            "emblem-ok-symbolic",
            "Nothing to turn off",
            "None of the extras TV Manager knows about are installed on this TV.",
            None,
        ));
        return;
    }

    p.add(&page::banner(
        "Packages are turned off, never removed. A snapshot is saved first, and each one can be turned back on here.",
        true,
        None,
    ));

    for (kind, title, note) in [
        (DebloatKind::Optional, "Safe to turn off", "Not needed with Nyx TV."),
        (
            DebloatKind::Dependent,
            "Depends on the TV",
            "Voice assistants and YouTube matter on some TVs. Only turn these off if you don't use them.",
        ),
    ] {
        let group: Vec<_> = pkgs.iter().filter(|p| p.kind == kind).collect();
        if group.is_empty() {
            continue;
        }
        let (card, rows) = page::card(&format!("{title} ({})", group.len()), &[]);
        rows.append(&page::dim(note));
        for pkg in group {
            rows.append(&pkg_row(pkg, st.debloat_pending.get(&pkg.package).copied()));
        }
        p.add(&card);
    }

    let protected: Vec<_> = pkgs.iter().filter(|p| p.kind == DebloatKind::Protected).collect();
    if !protected.is_empty() {
        let (card, rows) = page::card("", &[]);
        let inner = gtk::Box::new(gtk::Orientation::Vertical, 0);
        inner.append(&page::dim("Play Store, Cast, system updates, the TV's own apps and Nyx TV are never changed."));
        for pkg in protected {
            inner.append(&pkg_row(pkg, None));
        }
        let exp = gtk::Expander::new(Some(&format!("Protected ({})", protected_len(pkgs))));
        exp.add_css_class("tm-expander");
        exp.set_child(Some(&inner));
        rows.append(&exp);
        p.add(&card);
    }
}

fn protected_len(pkgs: &[DebloatPackage]) -> usize {
    pkgs.iter().filter(|p| p.kind == DebloatKind::Protected).count()
}

fn apply() {
    let (off, on, titles) = state::get(|s| {
        let pkgs = s.debloat.ready().cloned().unwrap_or_default();
        let title = |id: &str| {
            pkgs.iter()
                .find(|p| p.package == id)
                .map(|p| p.title.clone())
                .unwrap_or_else(|| id.to_string())
        };
        let off: Vec<String> = s.debloat_pending.iter().filter(|(_, v)| !**v).map(|(k, _)| k.clone()).collect();
        let on: Vec<String> = s.debloat_pending.iter().filter(|(_, v)| **v).map(|(k, _)| k.clone()).collect();
        let mut lines = Vec::new();
        if !off.is_empty() {
            lines.push(format!("Turn off: {}", off.iter().map(|k| title(k)).collect::<Vec<_>>().join(", ")));
        }
        if !on.is_empty() {
            lines.push(format!("Turn on: {}", on.iter().map(|k| title(k)).collect::<Vec<_>>().join(", ")));
        }
        (off, on, lines.join("\n"))
    });
    if off.is_empty() && on.is_empty() {
        return;
    }
    let n = off.len() + on.len();
    page::confirm(
        &format!("Apply {n} change{}?", if n == 1 { "" } else { "s" }),
        &format!("{titles}\n\nA snapshot is saved first. Nothing is uninstalled."),
        "Apply",
        move || {
            ops::run_tv(
                "Debloat",
                move |ctx, tv| {
                    ctx.step("Saving a snapshot…");
                    let _ = tv.snapshot();
                    let mut done = 0;
                    if !off.is_empty() {
                        ctx.step("Turning packages off…");
                        let refs: Vec<&str> = off.iter().map(String::as_str).collect();
                        done += tv.disable_packages(&refs, true)?.len();
                    }
                    if !on.is_empty() {
                        ctx.step("Turning packages on…");
                        let refs: Vec<&str> = on.iter().map(String::as_str).collect();
                        done += tv.enable_packages(&refs)?.len();
                    }
                    Ok(format!("Changed {done} package{}", if done == 1 { "" } else { "s" }))
                },
                |_| sync::refresh_debloat(),
            );
        },
    );
}
