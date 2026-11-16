//! Overview: where the TV is in setup, the one thing to do next, its facts
//! and what TV Manager did to it lately.

use crate::actions::{self, InstallAction};
use crate::activity;
use crate::page::{self, Page};
use crate::state::State;
use crate::window;
use gtk::prelude::*;
use nyx_tv_device::NYX_TV_PKG_DEBUG;
use nyx_ui::widgets as w;
use std::cell::RefCell;

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Overview");
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

fn step(done: bool, title: &str, value: &str) -> gtk::Box {
    let b = gtk::Box::new(gtk::Orientation::Vertical, 4);
    b.add_css_class("tm-step");
    b.add_css_class(if done { "done" } else { "todo" });
    let head = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let mark = gtk::Image::from_icon_name(if done {
        "emblem-ok-symbolic"
    } else {
        "radio-symbolic"
    });
    mark.set_pixel_size(16);
    mark.add_css_class("tm-step-mark");
    head.append(&mark);
    let t = w::label(title, "tm-step-title");
    t.set_wrap(true);
    t.set_max_width_chars(14);
    head.append(&t);
    b.append(&head);
    let v = w::label(value, "tm-step-value");
    v.set_ellipsize(gtk::pango::EllipsizeMode::End);
    v.set_max_width_chars(14);
    v.set_tooltip_text(Some(value));
    b.append(&v);
    b
}

pub fn render(st: &State) {
    let Some(p) = PAGE.with(|c| c.borrow().clone()) else { return };
    p.clear();
    p.set_actions(&[]);
    if st.selected().is_none() {
        p.set_title("Overview");
        p.set_sub("");
        super::gate(&p, st, false);
        return;
    }
    let key = st.selected_key().unwrap_or_default();
    p.set_title(&st.selected_name());
    let rename = {
        let k = key.clone();
        page::icon("document-edit-symbolic", "Rename this TV", move || window::rename_tv(&k))
    };

    let Some(tv) = st.tv.as_ref() else {
        p.set_sub(if st.tv_checked { "Offline" } else { "Connecting…" });
        p.set_actions(&[rename]);
        super::gate(&p, st, true);
        return;
    };
    let o = &tv.overview;
    let model = if o.model.is_empty() { "Android TV".to_string() } else { o.model.clone() };
    p.set_sub(&format!("{model} · Connected"));

    let installed = o.nyx_package.is_some();
    let is_debug = o.nyx_package.as_deref() == Some(NYX_TV_PKG_DEBUG);
    let home = o.nyx_is_home();
    let stock_off = o.stock_disabled();
    let ver = o.nyx_version.clone().unwrap_or_else(|| "installed".into());

    // The single next step.
    let install_action = actions::current_install_action();
    let (primary, next): (Option<gtk::Widget>, String) = if !installed {
        (
            Some(page::primary("folder-download-symbolic", "Set up Nyx TV", actions::setup)),
            "Next: install Nyx TV and make it the home app. A snapshot of the TV's home settings is saved first, so you can go back.".into(),
        )
    } else if !home {
        (
            Some(page::primary("user-home-symbolic", "Set as home", actions::set_home)),
            "Next: make Nyx TV the home app. Google TV Home is turned off so it can't take the Home button back.".into(),
        )
    } else if !stock_off {
        (
            Some(page::primary("system-shutdown-symbolic", "Turn off Google TV Home", || {
                actions::set_google_home(false, || {})
            })),
            "Nyx TV is the home app, but Google TV Home is still on and can take the Home button back.".into(),
        )
    } else if let InstallAction::Update(v) = &install_action {
        (
            Some(page::primary("folder-download-symbolic", &format!("Update to {v}"), actions::install)),
            format!("All set. Nyx TV {v} is ready to install over {ver}."),
        )
    } else {
        (None, "All set. Pressing Home on the remote opens Nyx TV.".into())
    };
    let mut head_actions = vec![rename];
    if let Some(b) = primary {
        head_actions.push(b);
    }
    p.set_actions(&head_actions);

    // Setup card
    let (card, rows) = page::card("Setup", &[]);
    let steps = gtk::FlowBox::new();
    steps.add_css_class("tm-steps");
    steps.set_selection_mode(gtk::SelectionMode::None);
    steps.set_homogeneous(true);
    steps.set_min_children_per_line(2);
    steps.set_max_children_per_line(4);
    steps.set_column_spacing(10);
    steps.set_row_spacing(10);
    let conn = devices_conn_label(&tv.serial);
    for s in [
        step(true, "Connected", &conn),
        step(
            installed,
            "Nyx TV installed",
            &if !installed {
                "Not installed".to_string()
            } else if is_debug {
                format!("{ver} (debug build)")
            } else {
                ver.clone()
            },
        ),
        step(home, "Home app", if home { "Nyx TV" } else { o.home_label() }),
        step(stock_off, "Google TV Home off", if stock_off { "Off" } else { "On" }),
    ] {
        steps.insert(&s, -1);
    }
    let mut c = steps.first_child();
    while let Some(ch) = c {
        ch.set_focusable(false);
        c = ch.next_sibling();
    }
    rows.append(&steps);
    let next_l = page::dim(&next);
    next_l.add_css_class("tm-next");
    rows.append(&next_l);
    p.add(&card);

    // Facts
    let (card, rows) = page::card("This TV", &[]);
    let android = if o.sdk.is_empty() {
        o.android_version.clone()
    } else {
        format!("{} (API {})", o.android_version, o.sdk)
    };
    rows.append(&page::facts(&[
        ("Model", model.clone(), false),
        ("Android TV", android, false),
        ("Firmware", o.fingerprint.clone(), true),
        ("Connection", tv.serial.clone(), true),
        (
            "Nyx TV",
            match (&o.nyx_package, &o.nyx_version) {
                (Some(_), Some(v)) if is_debug => format!("{v} · debug build"),
                (Some(_), Some(v)) => v.clone(),
                (Some(_), None) => "Installed".into(),
                _ => "Not installed".into(),
            },
            false,
        ),
        ("Home app", o.home_label().to_string(), false),
    ]));
    p.add(&card);

    // Activity
    let (card, rows) = page::card("Recent activity", &[]);
    let entries = st.activity.for_tv(&key, 8);
    if entries.is_empty() {
        rows.append(&page::dim("Nothing yet. Installs, home changes and backups for this TV show up here."));
    }
    for e in entries {
        let r = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        r.add_css_class("tm-feed-row");
        let t = w::label(&activity::when(e.time), "tm-feed-time");
        t.set_width_chars(7);
        r.append(&t);
        let body = w::label(&e.text, "");
        body.set_hexpand(true);
        body.set_ellipsize(gtk::pango::EllipsizeMode::End);
        body.set_tooltip_text(Some(&e.text));
        r.append(&body);
        rows.append(&r);
    }
    p.add(&card);
}

fn devices_conn_label(serial: &str) -> String {
    if crate::devices::host_of(serial).is_some() || serial.contains("._adb") {
        "Wireless debugging".into()
    } else {
        "USB".into()
    }
}
