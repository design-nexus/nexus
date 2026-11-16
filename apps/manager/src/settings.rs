//! Settings card: Devices, then Appearance / Keyboard / About.

use crate::window;
use gtk::glib;
use gtk::prelude::*;
use nyx_ui::settings_card::{self, About, Spec, Standard};
use nyx_ui::widgets as w;

const GROUPS: &[(&str, &str, &str)] = &[("Devices", "video-display-symbolic", "Saved TVs")];

const SHORTCUTS: &[(&[&str], &str)] = &[
    (&["F5"], "Refresh"),
    (&["Ctrl", ","], "Settings"),
    (&["Ctrl", "B"], "Collapse the sidebar"),
    (&["Ctrl", "Q"], "Close"),
];

fn standard() -> Standard {
    Standard {
        app: "tv-manager",
        about: About {
            name: "TV Manager",
            icon: crate::APP_ID,
            version: env!("CARGO_PKG_VERSION"),
            description: "Install and manage Nyx TV on Android TVs over adb",
            licence: "MIT",
            website: "https://github.com/design-nexus/nyx-tv",
            issues: "https://github.com/design-nexus/nyx-tv/issues",
            credits: &[("Android Debug Bridge", "device connection")],
        },
        shortcuts: SHORTCUTS,
        reduce_motion: || crate::prefs::get().reduce_motion,
        set_reduce_motion: |on| {
            crate::prefs::update(|p| p.reduce_motion = on);
            nyx_theme::apply();
        },
    }
}

fn devices_body() -> gtk::Box {
    let body = w::vbox(12);
    body.add_css_class("settings-page");

    let wrapper = w::vbox(0);
    wrapper.add_css_class("settings-group");
    wrapper.set_widget_name("Devices");
    let heading = gtk::Label::new(Some("DEVICES"));
    heading.add_css_class("group-title");
    heading.set_xalign(0.0);
    wrapper.append(&heading);
    let list = w::vbox(0);
    list.add_css_class("group-list");
    list.set_widget_name("devices-list");
    fill_devices(&list);
    wrapper.append(&list);
    body.append(&wrapper);
    body
}

fn fill_devices(list: &gtk::Box) {
    while let Some(c) = list.first_child() {
        list.remove(&c);
    }
    let devices = window::saved_devices();
    SHOWN.with(|s| *s.borrow_mut() = devices.clone());
    if devices.is_empty() {
        let empty = w::label("No TVs yet. Add one from the sidebar.", "dim");
        empty.set_xalign(0.0);
        empty.set_margin_start(14);
        empty.set_margin_end(14);
        empty.set_margin_top(8);
        empty.set_margin_bottom(8);
        list.append(&empty);
        return;
    }
    for (key, name, online) in devices {
        let end = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        end.set_valign(gtk::Align::Center);
        let rename = w::icon_button("document-edit-symbolic", "Rename");
        let k = key.clone();
        rename.connect_clicked(move |_| window::rename_tv(&k));
        let k = key.clone();
        let remove = w::two_click("Remove", "Remove now?", move || window::forget_tv(&k));
        end.append(&rename);
        end.append(&remove);

        let desc = if online { format!("{key} · online") } else { format!("{key} · offline") };
        let row = w::row(&name, &desc, Some(end.upcast_ref()));
        let lead = crate::page::dot(online);
        row.prepend(&lead);
        list.append(&row);
    }
}

fn refresh_devices_list() {
    if let Some(root) = settings_card::page()
        && let Some(list) = find_named(&root, "devices-list")
            && let Ok(box_) = list.downcast::<gtk::Box>() {
                fill_devices(&box_);
            }
}

thread_local! {
    static SHOWN: std::cell::RefCell<Vec<(String, String, bool)>> = const { std::cell::RefCell::new(Vec::new()) };
}

/// Rebuild the Devices list when Settings is open and the list changed
/// (a rebuild would disarm a half-clicked Remove).
pub fn refresh_if_open() {
    if !settings_card::is_open() {
        return;
    }
    let now = window::saved_devices();
    let changed = SHOWN.with(|s| *s.borrow() != now);
    if changed {
        SHOWN.with(|s| *s.borrow_mut() = now);
        refresh_devices_list();
    }
}

fn find_named(root: &gtk::Widget, name: &str) -> Option<gtk::Widget> {
    if root.widget_name() == name {
        return Some(root.clone());
    }
    let mut child = root.first_child();
    while let Some(c) = child {
        if let Some(found) = find_named(&c, name) {
            return Some(found);
        }
        child = c.next_sibling();
    }
    None
}

pub fn open() {
    let (Some(overlay), Some(win)) = (window::overlay(), window::window()) else {
        return;
    };
    let body = devices_body();
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&body)
        .vexpand(true)
        .build();
    settings_card::open_page(
        &overlay,
        &win,
        Spec {
            groups: GROUPS,
            search_text: None,
            standard: Some(standard()),
        },
        scroll.upcast_ref(),
        &body,
    );
    win.add_tick_callback(|window, _| {
        if !settings_card::is_open() {
            return glib::ControlFlow::Break;
        }
        settings_card::fit(window);
        glib::ControlFlow::Continue
    });
}
