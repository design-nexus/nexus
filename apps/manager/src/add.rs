//! Add a TV — Discovery tiles + pairing bottom sheet (option A).

use crate::devices::{self, SavedTv};
use crate::window;
use gtk::prelude::*;
use gtk::glib;
use nyx_tv_adb::Adb;
use nyx_ui::widgets as w;
use std::cell::RefCell;
use std::rc::Rc;

#[derive(Clone)]
pub struct Found {
    pub title: String,
    pub sub: String,
    pub host: String,
    pub connect_port: Option<String>,
    pub pair_port: Option<String>,
    pub online: bool,
    pub connected: bool,
}

pub struct AddPage {
    tiles: gtk::FlowBox,
    hero_title: gtk::Label,
    hero_lede: gtk::Label,
    pair_revealer: gtk::Revealer,
    pair_title: gtk::Label,
    pair_host: gtk::Label,
    pair_port: gtk::Entry,
    digits: Vec<gtk::Entry>,
    manual_entry: gtk::Entry,
    manual_popover: gtk::Popover,
    selected: RefCell<Option<Found>>,
    found: RefCell<Vec<Found>>,
}

thread_local! {
    static PAGE: RefCell<Option<Rc<AddPage>>> = const { RefCell::new(None) };
}

fn page() -> Option<Rc<AddPage>> {
    PAGE.with(|p| p.borrow().clone())
}

pub fn build() -> gtk::Box {
    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.add_css_class("tm-discover");
    root.set_vexpand(false);
    root.set_valign(gtk::Align::Start);
    root.set_hexpand(true);

    // ----- Hero -----
    let hero = gtk::Box::new(gtk::Orientation::Vertical, 10);
    hero.add_css_class("hero");
    hero.set_halign(gtk::Align::Center);
    let radar = gtk::Image::from_icon_name("network-wireless-symbolic");
    radar.set_pixel_size(28);
    let pulse_wrap = gtk::CenterBox::new();
    pulse_wrap.set_center_widget(Some(&radar));
    pulse_wrap.add_css_class("pulse");
    pulse_wrap.set_halign(gtk::Align::Center);
    pulse_wrap.set_size_request(72, 72);
    hero.append(&pulse_wrap);

    let hero_title = w::label("Looking for TVs…", "hero-title");
    hero_title.set_halign(gtk::Align::Center);
    let hero_lede = gtk::Label::new(Some(
        "Wireless debugging has to be on. Found devices show up as tiles; pair opens a sheet so the list stays visible.",
    ));
    hero_lede.add_css_class("hero-lede");
    hero_lede.set_wrap(true);
    hero_lede.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    hero_lede.set_max_width_chars(48);
    hero_lede.set_justify(gtk::Justification::Center);
    hero_lede.set_halign(gtk::Align::Center);
    hero.append(&hero_title);
    hero.append(&hero_lede);
    root.append(&hero);

    // ----- Tiles -----
    let tiles = gtk::FlowBox::new();
    tiles.add_css_class("tm-tiles");
    tiles.set_selection_mode(gtk::SelectionMode::None);
    tiles.set_homogeneous(true);
    tiles.set_column_spacing(12);
    tiles.set_row_spacing(12);
    tiles.set_max_children_per_line(4);
    tiles.set_min_children_per_line(1);
    tiles.set_halign(gtk::Align::Fill);
    tiles.set_hexpand(true);
    root.append(&tiles);

    // ----- Pair sheet (revealer) -----
    let pair_revealer = gtk::Revealer::new();
    pair_revealer.set_transition_type(gtk::RevealerTransitionType::SlideUp);
    pair_revealer.set_reveal_child(false);

    let pair_sheet = gtk::Box::new(gtk::Orientation::Vertical, 12);
    pair_sheet.add_css_class("tm-pair-sheet");

    let pair_title = w::label("Pair with a TV", "pair-title");
    pair_title.set_xalign(0.0);
    pair_sheet.append(&pair_title);

    let pair_help = gtk::Label::new(Some(
        "On the TV: Developer options → Wireless debugging → Pair device with pairing code.",
    ));
    pair_help.add_css_class("dim2");
    pair_help.set_wrap(true);
    pair_help.set_xalign(0.0);
    pair_sheet.append(&pair_help);

    let pair_row = gtk::Box::new(gtk::Orientation::Horizontal, 16);
    pair_row.set_valign(gtk::Align::Center);

    let digit_box = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    digit_box.add_css_class("pair-digits");
    let mut digits = Vec::new();
    for _ in 0..6 {
        let e = gtk::Entry::builder()
            .max_length(1)
            .width_chars(1)
            .max_width_chars(1)
            .build();
        e.add_css_class("pair-digit");
        gtk::prelude::EditableExt::set_alignment(&e, 0.5);
        digit_box.append(&e);
        digits.push(e);
    }
    wire_digits(&digits);

    let port_field = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    port_field.add_css_class("pair-port-field");
    port_field.set_hexpand(true);
    let pair_host = w::label("", "mono");
    pair_host.add_css_class("pair-host");
    pair_host.set_xalign(0.0);
    let pair_port = gtk::Entry::builder()
        .placeholder_text("port")
        .hexpand(true)
        .build();
    pair_port.add_css_class("mono");
    port_field.append(&pair_host);
    port_field.append(&pair_port);

    pair_row.append(&digit_box);
    pair_row.append(&port_field);

    let pair_btn = gtk::Button::with_label("Pair");
    pair_btn.add_css_class("suggested-action");
    pair_btn.connect_clicked(|_| do_pair());
    let cancel_btn = gtk::Button::with_label("Cancel");
    cancel_btn.add_css_class("flat");
    cancel_btn.connect_clicked(|_| hide_pair_sheet());
    pair_row.append(&pair_btn);
    pair_row.append(&cancel_btn);
    pair_sheet.append(&pair_row);

    pair_revealer.set_child(Some(&pair_sheet));
    root.append(&pair_revealer);

    // Manual address popover (triggered from the "Enter an address" tile).
    let manual_entry = gtk::Entry::builder()
        .placeholder_text("192.168.1.x:5555")
        .hexpand(true)
        .build();
    let manual_box = gtk::Box::new(gtk::Orientation::Vertical, 8);
    manual_box.add_css_class("tm-manual-popover");
    let man_label = w::label("Address and port", "dim2");
    man_label.set_xalign(0.0);
    manual_box.append(&man_label);
    let man_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    man_row.add_css_class("manual-row");
    let connect_btn = gtk::Button::with_label("Connect");
    connect_btn.add_css_class("suggested-action");
    connect_btn.connect_clicked(|_| {
        if let Some(p) = page() {
            let addr = p.manual_entry.text().trim().to_string();
            if !addr.is_empty() {
                do_connect(&addr, None);
            }
        }
    });
    manual_entry.connect_activate(|e| {
        let addr = e.text().trim().to_string();
        if !addr.is_empty() {
            do_connect(&addr, None);
        }
    });
    man_row.append(&manual_entry);
    man_row.append(&connect_btn);
    manual_box.append(&man_row);
    let manual_popover = gtk::Popover::new();
    manual_popover.set_child(Some(&manual_box));

    let page = Rc::new(AddPage {
        tiles,
        hero_title,
        hero_lede,
        pair_revealer,
        pair_title,
        pair_host,
        pair_port,
        digits,
        manual_entry,
        manual_popover,
        selected: RefCell::new(None),
        found: RefCell::new(Vec::new()),
    });
    PAGE.with(|p| *p.borrow_mut() = Some(page));

    refresh();
    root
}

fn wire_digits(digits: &[gtk::Entry]) {
    let digs = Rc::new(digits.to_vec());
    for (idx, e) in digits.iter().enumerate() {
        let digs2 = digs.clone();
        e.connect_changed(move |entry| {
            let t = entry.text();
            if t.is_empty() {
                return;
            }
            if let Some(c) = t.chars().last() {
                if c.is_ascii_digit() {
                    if entry.text().len() != 1 {
                        entry.set_text(&c.to_string());
                    }
                    if idx + 1 < digs2.len() {
                        digs2[idx + 1].grab_focus();
                    }
                } else {
                    entry.set_text("");
                }
            }
        });
        let digs2 = digs.clone();
        let key = gtk::EventControllerKey::new();
        key.connect_key_pressed(move |_, key, _, _| {
            if key == gtk::gdk::Key::BackSpace {
                let cur = &digs2[idx];
                if cur.text().is_empty() && idx > 0 {
                    digs2[idx - 1].set_text("");
                    digs2[idx - 1].grab_focus();
                    return glib::Propagation::Stop;
                }
            }
            glib::Propagation::Proceed
        });
        e.add_controller(key);
    }
}

pub fn refresh() {
    let Some(page) = page() else { return };
    let found = discover();
    *page.found.borrow_mut() = found.clone();

    // Option A keeps the searching hero copy even when tiles appear.
    page.hero_title.set_text("Looking for TVs…");
    page.hero_lede.set_text(
        "Wireless debugging has to be on. Found devices show up as tiles; pair opens a sheet so the list stays visible.",
    );

    let sel = page.selected.borrow().clone();
    if let Some(sel) = sel {
        if let Some(updated) = found.iter().find(|f| f.host == sel.host) {
            *page.selected.borrow_mut() = Some(updated.clone());
            if page.pair_revealer.reveals_child() {
                apply_pair_target(&page, updated);
            }
        }
    }
    rebuild_tiles(&page);
}

fn discover() -> Vec<Found> {
    let devices = Adb::devices().unwrap_or_default();
    let mdns = Adb::mdns_services().unwrap_or_default();

    let mut by_host: Vec<(String, Option<String>, Option<String>, String)> = Vec::new();
    for s in &mdns {
        let host = s.host().to_string();
        if let Some(e) = by_host.iter_mut().find(|(h, _, _, _)| h == &host) {
            if s.is_pairing() {
                e.2 = Some(port_of(&s.host_port));
            } else if s.is_connect() {
                e.1 = Some(port_of(&s.host_port));
                e.3 = s.name.clone();
            }
        } else {
            let (mut connect, mut pair) = (None, None);
            if s.is_pairing() {
                pair = Some(port_of(&s.host_port));
            } else {
                connect = Some(port_of(&s.host_port));
            }
            by_host.push((host, connect, pair, s.name.clone()));
        }
    }

    for d in &devices {
        if d.state != "device" && d.state != "offline" {
            continue;
        }
        let host = d
            .serial
            .rsplit_once(':')
            .map(|(h, _)| h.to_string())
            .unwrap_or_else(|| d.serial.clone());
        if !by_host.iter().any(|(h, _, _, _)| h == &host) {
            let port = d.serial.rsplit_once(':').map(|(_, p)| p.to_string());
            by_host.push((host, port, None, d.serial.clone()));
        }
    }

    let mut out = Vec::new();
    for (host, connect_port, pair_port, name) in by_host {
        let serial_guess = connect_port
            .as_ref()
            .map(|p| format!("{host}:{p}"))
            .unwrap_or_else(|| host.clone());
        let dev = devices.iter().find(|d| {
            d.serial == serial_guess
                || d.serial.starts_with(&format!("{host}:"))
                || d.serial == host
        });
        let connected = dev.is_some_and(|d| d.state == "device");
        let online = connected || connect_port.is_some() || pair_port.is_some();
        let model = dev
            .and_then(|d| d.model.clone())
            .unwrap_or_else(|| nice_name(&name, &host));
        let mut parts = Vec::new();
        if !model.starts_with("TV at ") {
            parts.push(model.clone());
        }
        parts.push(host.clone());
        if pair_port.is_some() && !connected {
            parts.push("wireless debugging, pairing available".into());
        } else if connected {
            parts.push("already connected".into());
        } else if let Some(p) = &connect_port {
            parts.push(format!("Network debugging on port {p}"));
        } else {
            parts.push("debugging is off on this device".into());
        }
        let sub = parts.join(" · ");
        out.push(Found {
            title: model,
            sub,
            host,
            connect_port,
            pair_port,
            online,
            connected,
        });
    }
    out.sort_by(|a, b| b.connected.cmp(&a.connected).then(a.title.cmp(&b.title)));
    out
}

fn port_of(host_port: &str) -> String {
    host_port
        .rsplit_once(':')
        .map(|(_, p)| p.to_string())
        .unwrap_or_default()
}

fn nice_name(mdns_name: &str, host: &str) -> String {
    if mdns_name.starts_with("adb-") {
        format!("TV at {host}")
    } else {
        mdns_name.to_string()
    }
}

fn make_tile(f: &Found, selected: bool) -> gtk::Box {
    let tile = gtk::Box::new(gtk::Orientation::Vertical, 10);
    tile.add_css_class("tm-tile");
    if selected {
        tile.add_css_class("sel");
    }
    tile.set_hexpand(true);

    let lead = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    lead.add_css_class("dot");
    if f.online {
        lead.add_css_class("on");
    }
    tile.append(&lead);

    let nm = w::label(&f.title, "nm");
    nm.set_xalign(0.0);
    nm.set_ellipsize(gtk::pango::EllipsizeMode::End);
    tile.append(&nm);

    let sub = gtk::Label::new(Some(&f.sub));
    sub.add_css_class("sub");
    sub.set_wrap(true);
    sub.set_xalign(0.0);
    sub.set_vexpand(true);
    tile.append(&sub);

    let foot = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    foot.add_css_class("foot");
    if f.connected {
        let l = w::label("This computer", "dim2");
        foot.append(&l);
    } else if f.pair_port.is_some() {
        let b = gtk::Button::with_label("Pair");
        b.add_css_class("suggested-action");
        let f2 = f.clone();
        b.connect_clicked(move |_| open_pair_sheet(f2.clone()));
        foot.append(&b);
    } else if f.connect_port.is_some() {
        let b = gtk::Button::with_label("Connect");
        b.add_css_class("suggested-action");
        let addr = format!(
            "{}:{}",
            f.host,
            f.connect_port.as_deref().unwrap_or("5555")
        );
        let title = f.title.clone();
        b.connect_clicked(move |_| do_connect(&addr, Some(&title)));
        foot.append(&b);
    } else {
        let l = w::label("Turn on debugging", "dim2");
        foot.append(&l);
    }
    tile.append(&foot);
    tile
}

fn make_manual_tile(page: &AddPage) -> gtk::Box {
    let tile = gtk::Box::new(gtk::Orientation::Vertical, 10);
    tile.add_css_class("tm-tile");
    tile.set_hexpand(true);

    let lead = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    lead.add_css_class("dot");
    tile.append(&lead);

    let nm = w::label("Enter an address", "nm");
    nm.set_xalign(0.0);
    tile.append(&nm);

    let sub = gtk::Label::new(Some("Other network, or discovery blocked"));
    sub.add_css_class("sub");
    sub.set_wrap(true);
    sub.set_xalign(0.0);
    sub.set_vexpand(true);
    tile.append(&sub);

    let foot = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let b = gtk::Button::with_label("Type address");
    let pop = page.manual_popover.clone();
    b.connect_clicked(move |btn| {
        if pop.parent().is_some() {
            pop.unparent();
        }
        pop.set_parent(btn);
        pop.popup();
    });
    foot.append(&b);
    tile.append(&foot);
    tile
}

fn rebuild_tiles(page: &AddPage) {
    while let Some(c) = page.tiles.child_at_index(0) {
        page.tiles.remove(&c);
    }
    let found = page.found.borrow().clone();
    let selected_host = page.selected.borrow().as_ref().map(|f| f.host.clone());

    for f in &found {
        let sel = selected_host.as_deref() == Some(f.host.as_str());
        let tile = make_tile(f, sel);
        let click = gtk::GestureClick::new();
        let f2 = f.clone();
        click.connect_released(move |_, n_press, _, _| {
            if n_press == 1 && !f2.connected {
                if f2.pair_port.is_some() {
                    open_pair_sheet(f2.clone());
                } else if let Some(p) = &f2.connect_port {
                    let addr = format!("{}:{p}", f2.host);
                    do_connect(&addr, Some(&f2.title));
                }
            }
        });
        tile.add_controller(click);
        page.tiles.insert(&tile, -1);
    }
    page.tiles.insert(&make_manual_tile(page), -1);
}

fn open_pair_sheet(f: Found) {
    let Some(page) = page() else { return };
    *page.selected.borrow_mut() = Some(f.clone());
    apply_pair_target(&page, &f);
    page.pair_revealer.set_reveal_child(true);
    rebuild_tiles(&page);
    if let Some(d) = page.digits.first() {
        d.grab_focus();
    }
}

fn hide_pair_sheet() {
    let Some(page) = page() else { return };
    page.pair_revealer.set_reveal_child(false);
    for d in &page.digits {
        d.set_text("");
    }
}

fn apply_pair_target(page: &AddPage, f: &Found) {
    page.pair_title
        .set_text(&format!("Pair with {}", f.title));
    page.pair_host.set_text(&format!("{}:", f.host));
    if let Some(p) = &f.pair_port {
        page.pair_port.set_text(p);
    } else {
        page.pair_port.set_text("");
        page.pair_port.set_placeholder_text(Some("port"));
    }
}

fn do_pair() {
    let Some(page) = page() else { return };
    let sel = page.selected.borrow().clone();
    let Some(sel) = sel else {
        window::set_status_pub("Select a TV to pair");
        return;
    };
    let port = page.pair_port.text().trim().to_string();
    if port.is_empty() {
        window::set_status_pub("Enter the pairing port from the TV");
        return;
    }
    let code: String = page.digits.iter().map(|e| e.text().to_string()).collect();
    if code.len() != 6 || !code.chars().all(|c| c.is_ascii_digit()) {
        window::set_status_pub("Enter the 6-digit pairing code");
        return;
    }
    let host_port = format!("{}:{port}", sel.host);
    let connect_port = sel.connect_port.clone().unwrap_or_else(|| "5555".into());
    let nick = sel.title.clone();
    let host = sel.host.clone();
    window::set_status_pub("Pairing");
    let (tx, rx) = async_channel::unbounded::<anyhow::Result<String>>();
    std::thread::spawn(move || {
        let r = (|| {
            Adb::pair(&host_port, &code)?;
            let addr = format!("{host}:{connect_port}");
            Adb::connect(&addr)?;
            Ok(addr)
        })();
        let _ = tx.send_blocking(r);
    });
    glib::spawn_future_local(async move {
        if let Ok(r) = rx.recv().await {
            match r {
                Ok(addr) => {
                    save_tv(&addr, &nick);
                    window::set_status_pub("Paired and connected");
                    window::push_activity("Paired over wireless debugging", &addr);
                    hide_pair_sheet();
                    window::after_tv_added(&addr);
                }
                Err(e) => window::set_status_pub(&format!("Pair failed: {e}")),
            }
        }
    });
}

fn do_connect(addr: &str, nick: Option<&str>) {
    let addr = addr.trim().to_string();
    let nick = nick.unwrap_or("").to_string();
    window::set_status_pub("Connecting");
    let (tx, rx) = async_channel::unbounded::<anyhow::Result<String>>();
    std::thread::spawn(move || {
        let r = Adb::connect(&addr).map(|a| a.serial().to_string());
        let _ = tx.send_blocking(r);
    });
    glib::spawn_future_local(async move {
        if let Ok(r) = rx.recv().await {
            match r {
                Ok(addr) => {
                    save_tv(&addr, &nick);
                    window::set_status_pub("Connected");
                    window::push_activity(
                        &format!("Added {}", if nick.is_empty() { &addr } else { &nick }),
                        &addr,
                    );
                    window::after_tv_added(&addr);
                }
                Err(e) => window::set_status_pub(&format!("Connect failed: {e}")),
            }
        }
    });
}

fn save_tv(serial: &str, nickname: &str) {
    let mut file = devices::load();
    if !file.devices.iter().any(|d| d.serial == serial) {
        file.devices.push(SavedTv {
            serial: serial.to_string(),
            nickname: nickname.to_string(),
        });
    }
    file.selected = Some(serial.to_string());
    let _ = devices::save(&file);
}
