//! Add a TV: TVs found over wireless debugging (rescanned every few seconds
//! while this page is open), pairing with the TV's code, or an address.

use crate::devices::{self, SavedTv};
use crate::page::{self, Page};
use crate::state::{self, Found, State};
use crate::sync;
use crate::window;
use anyhow::{bail, Result};
use gtk::glib;
use gtk::prelude::*;
use nyx_tv_adb::Adb;
use nyx_ui::widgets as w;
use std::cell::RefCell;
use std::rc::Rc;
use std::time::{Duration, Instant};

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Add a TV");
    p.set_sub("Over wireless debugging");
    p.set_actions(&[
        page::icon("view-refresh-symbolic", "Look again", sync::discover),
        page::icon("network-server-symbolic", "Enter an address…", address_dialog),
    ]);
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

const HOW_TO: &str = "On the TV, open Settings → System → About and select Android TV OS build seven times. Then go to Settings → System → Developer options and turn on Wireless debugging. The TV shows up here on its own.";

pub fn render(st: &State) {
    let Some(p) = PAGE.with(|c| c.borrow().clone()) else { return };
    p.clear();
    if st.found.is_empty() {
        let title = if st.scanned_once {
            "No TVs found yet"
        } else {
            "Looking for TVs…"
        };
        p.add_center(&page::empty("network-wireless-symbolic", title, HOW_TO, None));
        return;
    }

    let (card, rows) = page::card("On your network", &[]);
    for f in &st.found {
        let saved = st
            .devices
            .devices
            .iter()
            .find(|d| (!f.host.is_empty() && d.host == f.host) || f.connected_serial.as_deref().is_some_and(|s| d.matches(s)))
            .cloned();
        let connected = f.connected_serial.is_some();
        let mut desc = vec![f.host.clone()];
        let mut end: Vec<gtk::Widget> = Vec::new();
        if let Some(d) = &saved {
            desc.push(if connected { "Connected".into() } else { "Saved".into() });
            let key = d.key().to_string();
            end.push(page::tag("Saved", "accent"));
            end.push(page::icon("go-next-symbolic", "Open this TV", move || window::select_tv(&key)));
        } else if connected {
            desc.push("Connected".into());
            let f2 = f.clone();
            end.push(page::button("Add", move || add_connected(&f2)));
        } else if f.pair_port.is_some() {
            desc.push("Ready to pair".into());
            let f2 = f.clone();
            end.push(page::button("Pair", move || pair_dialog(f2.clone())));
        } else if let Some(port) = &f.connect_port {
            desc.push("Paired before".into());
            let (addr, f2) = (format!("{}:{port}", f.host), f.clone());
            end.push(page::button("Connect", move || connect(&addr, &f2.title, &f2.mdns_name)));
        } else {
            desc.push("Wireless debugging is off".into());
        }
        let title = saved.as_ref().map(|d| d.name().to_string()).unwrap_or_else(|| f.title.clone());
        rows.append(&page::row(&title, &desc.join(" · "), Some(&page::dot(connected)), &end));
    }
    p.add(&card);
    let hint = page::dim(
        "Pairing a new TV: on the TV, open Developer options → Wireless debugging → Pair device with pairing code, then choose Pair here.",
    );
    p.add(&hint);
}

fn save_and_open(serial: &str, title: &str, mdns_name: &str) {
    let tv = SavedTv::new(serial, title, mdns_name);
    let mut key = String::new();
    state::update(|s| {
        key = s.devices.upsert(tv);
        s.devices.selected = Some(key.clone());
        s.clear_tv();
    });
    state::save_devices();
    state::log_activity("Added to TV Manager");
    sync::refresh_online();
    sync::refresh_tv();
    window::show_section("overview");
}

fn add_connected(f: &Found) {
    if let Some(serial) = &f.connected_serial {
        save_and_open(serial, &f.title, &f.mdns_name);
    }
}

fn connect(addr: &str, title: &str, mdns_name: &str) {
    let (addr, title, mdns) = (addr.to_string(), title.to_string(), mdns_name.to_string());
    window::toast(&format!("Connecting to {addr}…"));
    sync::bg(
        move || Adb::connect(&addr).map(|a| a.serial().to_string()),
        move |r| match r {
            Ok(serial) => {
                window::toast("Connected");
                save_and_open(&serial, &title, &mdns);
            }
            Err(e) => window::toast(&format!("Couldn't connect: {e}")),
        },
    );
}

/// Manual `host:port` (other network, or mDNS blocked).
fn address_dialog() {
    let (dialog, card) = w::dialog("Enter an address", 400);
    card.append(&page::dim(
        "The address and port shown under Wireless debugging on the TV, like 192.168.1.20:37215.",
    ));
    let entry = gtk::Entry::builder().placeholder_text("192.168.1.20:37215").build();
    entry.add_css_class("mono");
    card.append(&entry);
    let err = w::label("", "tm-error");
    err.set_wrap(true);
    err.set_visible(false);
    card.append(&err);
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    buttons.set_halign(gtk::Align::End);
    let cancel = gtk::Button::with_label("Cancel");
    let go = gtk::Button::with_label("Connect");
    go.add_css_class("suggested-action");
    buttons.append(&cancel);
    buttons.append(&go);
    card.append(&buttons);
    let d = dialog.clone();
    cancel.connect_clicked(move |_| d.close());
    let submit = Rc::new({
        let (d, e, err, go) = (dialog.clone(), entry.clone(), err.clone(), go.clone());
        move || {
            let addr = e.text().trim().to_string();
            if devices::host_of(&addr).is_none() {
                err.set_text("Enter the address with its port, like 192.168.1.20:37215.");
                err.set_visible(true);
                return;
            }
            go.set_sensitive(false);
            err.set_visible(false);
            let (d, err, go) = (d.clone(), err.clone(), go.clone());
            let a2 = addr.clone();
            sync::bg(
                move || Adb::connect(&a2).map(|a| a.serial().to_string()),
                move |r| {
                    go.set_sensitive(true);
                    match r {
                        Ok(serial) => {
                            d.close();
                            save_and_open(&serial, "", "");
                        }
                        Err(e) => {
                            err.set_text(&format!("{e}"));
                            err.set_visible(true);
                        }
                    }
                },
            );
        }
    });
    let s = submit.clone();
    go.connect_clicked(move |_| s());
    entry.connect_activate(move |_| submit());
    dialog.present();
    entry.grab_focus();
}

/// Six digit boxes that move along as you type and accept a pasted code.
fn digit_boxes() -> (gtk::Box, Rc<Vec<gtk::Entry>>) {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    row.set_halign(gtk::Align::Center);
    let mut v = Vec::new();
    for _ in 0..6 {
        let e = gtk::Entry::builder().width_chars(1).max_width_chars(1).build();
        e.add_css_class("pair-digit");
        gtk::prelude::EditableExt::set_alignment(&e, 0.5);
        e.set_input_purpose(gtk::InputPurpose::Digits);
        row.append(&e);
        v.push(e);
    }
    let digits = Rc::new(v);
    for (i, e) in digits.iter().enumerate() {
        let ds = digits.clone();
        e.connect_changed(move |entry| {
            let text: String = entry.text().chars().filter(|c| c.is_ascii_digit()).collect();
            if text.len() > 1 {
                // Pasted or typed over: spread the digits from this box on.
                let chars: Vec<char> = text.chars().collect();
                let start = if chars.len() >= 6 { 0 } else { i };
                let ds2 = ds.clone();
                glib::idle_add_local_once(move || {
                    for (k, c) in chars.iter().enumerate() {
                        if let Some(d) = ds2.get(start + k) {
                            d.set_text(&c.to_string());
                        }
                    }
                    let last = (start + chars.len()).min(6) - 1;
                    ds2[last].grab_focus();
                    ds2[last].set_position(-1);
                });
                return;
            }
            if entry.text().as_str() != text {
                entry.set_text(&text);
                return;
            }
            if text.len() == 1 && i + 1 < ds.len() {
                ds[i + 1].grab_focus();
            }
        });
        let ds = digits.clone();
        let key = gtk::EventControllerKey::new();
        key.connect_key_pressed(move |_, k, _, _| {
            if k == gtk::gdk::Key::BackSpace && ds[i].text().is_empty() && i > 0 {
                ds[i - 1].set_text("");
                ds[i - 1].grab_focus();
                return glib::Propagation::Stop;
            }
            glib::Propagation::Proceed
        });
        e.add_controller(key);
    }
    (row, digits)
}

fn pair_dialog(f: Found) {
    let (dialog, card) = w::dialog(&format!("Pair with {}", f.title), 420);
    card.append(&page::dim(
        "On the TV: Developer options → Wireless debugging → Pair device with pairing code. Enter the six-digit code it shows.",
    ));
    let (digits_row, digits) = digit_boxes();
    card.append(&digits_row);

    let port_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    port_row.append(&w::label(&format!("Pairing port on {}", f.host), "dim"));
    let port = gtk::Entry::new();
    port.add_css_class("mono");
    port.set_hexpand(true);
    port.set_text(f.pair_port.as_deref().unwrap_or(""));
    port.set_placeholder_text(Some("port"));
    port_row.append(&port);
    card.append(&port_row);

    let err = w::label("", "tm-error");
    err.set_wrap(true);
    err.set_visible(false);
    card.append(&err);

    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    buttons.set_halign(gtk::Align::End);
    let cancel = gtk::Button::with_label("Cancel");
    let pair = gtk::Button::with_label("Pair");
    pair.add_css_class("suggested-action");
    buttons.append(&cancel);
    buttons.append(&pair);
    card.append(&buttons);

    let d = dialog.clone();
    cancel.connect_clicked(move |_| d.close());
    let (d, ds, p, e, btn) = (dialog.clone(), digits.clone(), port.clone(), err.clone(), pair.clone());
    pair.connect_clicked(move |_| {
        let code: String = ds.iter().map(|x| x.text().to_string()).collect();
        let port = p.text().trim().to_string();
        if code.len() != 6 || !code.chars().all(|c| c.is_ascii_digit()) {
            e.set_text("Enter all six digits of the code shown on the TV.");
            e.set_visible(true);
            return;
        }
        if port.is_empty() || !port.chars().all(|c| c.is_ascii_digit()) {
            e.set_text("Enter the pairing port shown next to the code on the TV.");
            e.set_visible(true);
            return;
        }
        e.set_visible(false);
        btn.set_sensitive(false);
        btn.set_label("Pairing…");
        let f2 = f.clone();
        let (d, e, btn) = (d.clone(), e.clone(), btn.clone());
        sync::bg(
            move || pair_and_connect(&f2, &port, &code),
            move |r| {
                btn.set_sensitive(true);
                btn.set_label("Pair");
                match r {
                    Ok((serial, title, mdns)) => {
                        d.close();
                        window::toast("Paired and connected");
                        save_and_open(&serial, &title, &mdns);
                    }
                    Err(err) => {
                        e.set_text(&format!("{err:#}"));
                        e.set_visible(true);
                    }
                }
            },
        );
    });
    dialog.present();
    digits[0].grab_focus();
}

/// Pair, then connect on the port the TV advertises for connections (it
/// isn't the pairing port, and isn't 5555).
fn pair_and_connect(f: &Found, port: &str, code: &str) -> Result<(String, String, String)> {
    Adb::pair(&format!("{}:{port}", f.host), code)?;
    let deadline = Instant::now() + Duration::from_secs(6);
    loop {
        // adb often connects on its own right after pairing.
        if let Ok(list) = Adb::devices()
            && let Some(d) = list.iter().find(|d| {
                d.state == "device"
                    && (devices::host_of(&d.serial).as_deref() == Some(f.host.as_str())
                        || (!f.mdns_name.is_empty() && d.serial.starts_with(&f.mdns_name)))
            }) {
                return Ok((d.serial.clone(), f.title.clone(), f.mdns_name.clone()));
            }
        let svc = Adb::mdns_services()
            .unwrap_or_default()
            .into_iter()
            .find(|s| s.is_connect() && s.host() == f.host);
        if let Some(s) = svc {
            if let Ok(a) = Adb::connect(&s.host_port) {
                return Ok((a.serial().to_string(), f.title.clone(), s.name));
            }
        } else if let Some(p) = &f.connect_port
            && let Ok(a) = Adb::connect(&format!("{}:{p}", f.host)) {
                return Ok((a.serial().to_string(), f.title.clone(), f.mdns_name.clone()));
            }
        if Instant::now() > deadline {
            bail!(
                "Paired, but the TV didn't offer a connection. Keep Wireless debugging open on the TV and choose Connect when it shows up."
            );
        }
        std::thread::sleep(Duration::from_millis(700));
    }
}
