//! Diagnostics: live figures, the Nyx TV log, a full audit and an export.

use crate::ops;
use crate::page::{self, Page};
use crate::state::{Load, State};
use crate::sync;
use gtk::gio;
use gtk::prelude::*;
use nyx_ui::widgets as w;
use std::cell::RefCell;
use std::path::PathBuf;

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
    static LAST_AUDIT: RefCell<Option<PathBuf>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Diagnostics");
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

fn metric(title: &str, value: &str, sub: &str) -> gtk::Box {
    let b = gtk::Box::new(gtk::Orientation::Vertical, 4);
    b.add_css_class("card");
    b.add_css_class("tm-metric");
    b.append(&w::label(title, "tm-metric-k"));
    let v = w::label(if value.is_empty() { "—" } else { value }, "tm-metric-v");
    v.add_css_class("num");
    b.append(&v);
    let s = w::label(sub, "tm-metric-s");
    s.set_wrap(true);
    b.append(&s);
    b
}

pub fn render(st: &State) {
    let Some(p) = PAGE.with(|c| c.borrow().clone()) else { return };
    p.clear();
    p.set_actions(&[]);
    p.set_sub(&st.tv_subtitle());
    if super::gate(&p, st, true) {
        return;
    }
    let Some(tv) = st.tv.as_ref() else { return };
    p.set_actions(&[
        page::icon("view-refresh-symbolic", "Refresh figures and log", sync::refresh_diag),
        page::icon("document-send-symbolic", "Export diagnostics…", export),
        page::primary("system-search-symbolic", "Run audit", run_audit),
    ]);

    let m = &tv.metrics;
    let grid = gtk::FlowBox::new();
    grid.set_selection_mode(gtk::SelectionMode::None);
    grid.set_homogeneous(true);
    grid.set_min_children_per_line(2);
    grid.set_max_children_per_line(4);
    grid.set_column_spacing(12);
    grid.set_row_spacing(12);
    for (t, v, s) in [
        ("Memory", &m.memory, &m.memory_sub),
        ("CPU when idle", &m.cpu, &m.cpu_sub),
        ("Cold start", &m.cold, &m.cold_sub),
        ("Google TV Home", &m.stock, &m.stock_sub),
    ] {
        grid.insert(&metric(t, v, s), -1);
    }
    let mut c = grid.first_child();
    while let Some(ch) = c {
        ch.set_focusable(false);
        c = ch.next_sibling();
    }
    p.add(&grid);

    let log_text = match &st.diag_log {
        Load::Ready(t) => t.clone(),
        Load::Failed(e) => format!("Couldn't read the log: {e}"),
        _ => "Reading the log…".into(),
    };
    let copy_text = log_text.clone();
    let (card, rows) = page::card(
        "Nyx TV log",
        &[page::icon("edit-copy-symbolic", "Copy the log", move || {
            if let Some(win) = crate::window::window() {
                win.clipboard().set_text(&copy_text);
                crate::window::toast("Log copied");
            }
        })],
    );
    let l = w::label(&log_text, "tm-log");
    l.add_css_class("mono");
    l.set_selectable(true);
    l.set_wrap(true);
    l.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    l.set_yalign(0.0);
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .min_content_height(260)
        .max_content_height(420)
        .propagate_natural_height(true)
        .child(&l)
        .build();
    scroll.add_css_class("tm-log-box");
    rows.append(&scroll);
    rows.append(&page::dim("Recent lines from Nyx TV. Sign-in tokens are left out."));
    p.add(&card);
}

fn run_audit() {
    ops::run_tv(
        "Run audit",
        |ctx, tv| {
            ctx.step("Collecting packages, settings and logs from the TV…");
            let dir = tv.audit()?;
            ctx.log(&format!("Wrote {}", dir.display()));
            let d = dir.clone();
            gtk::glib::idle_add_once(move || LAST_AUDIT.with(|l| *l.borrow_mut() = Some(d)));
            Ok("Audit saved".into())
        },
        |ok| {
            sync::refresh_diag();
            if ok {
                crate::window::toast_action("Audit saved", "Open folder", open_last_audit);
            }
        },
    );
}

fn open_last_audit() {
    if let Some(dir) = LAST_AUDIT.with(|l| l.borrow().clone()) {
        let uri = gio::File::for_path(dir).uri();
        let _ = gio::AppInfo::launch_default_for_uri(&uri, None::<&gio::AppLaunchContext>);
    }
}

fn export() {
    let Some(win) = crate::window::window() else { return };
    let dialog = gtk::FileDialog::builder()
        .title("Export diagnostics to a folder")
        .build();
    dialog.select_folder(Some(&win), None::<&gio::Cancellable>, |res| {
        let Some(dir) = res.ok().and_then(|f| f.path()) else { return };
        let stamp = gtk::glib::DateTime::now_local()
            .ok()
            .and_then(|d| d.format("%Y%m%d-%H%M%S").ok())
            .map(|s| s.to_string())
            .unwrap_or_else(|| "export".into());
        let dest = dir.join(format!("nyx-tv-diagnostics-{stamp}"));
        ops::run_tv(
            "Export diagnostics",
            move |ctx, tv| {
                ctx.step("Collecting diagnostics from the TV…");
                let out = tv.export_diagnostics(&dest)?;
                Ok(format!("Diagnostics saved to {}", out.display()))
            },
            |_| {},
        );
    });
}
