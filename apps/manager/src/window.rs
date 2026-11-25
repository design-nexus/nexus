//! The Manager window: header, sidebar (TVs, then the selected TV's pages),
//! the page stack and the status strip. Pages live in `pages/`.

use crate::pages;
use crate::state::{self, SECTIONS};
use crate::sync;
use gtk::glib;
use gtk::prelude::*;
use nyx_ui::{chrome, widgets as w};
use std::cell::RefCell;
use std::rc::Rc;
use std::time::Duration;

/// Per-TV sections in the sidebar: (id, title, icon).
const NAV: &[(&str, &str, &str)] = &[
    ("overview", "Overview", "computer-symbolic"),
    ("install", "Install and update", "folder-download-symbolic"),
    ("home", "Home screen", "user-home-symbolic"),
    ("backups", "Backups", "document-save-symbolic"),
    ("diagnostics", "Diagnostics", "dialog-information-symbolic"),
    ("debloat", "Debloat", "applications-system-symbolic"),
];

struct Ui {
    window: gtk::ApplicationWindow,
    overlay: gtk::Overlay,
    sidebar: Rc<nyx_ui::sidebar::Sidebar>,
    tv_box: gtk::Box,
    tv_sig: RefCell<String>,
    section_heading: gtk::Label,
    add_nav: nyx_ui::sidebar::NavItem,
    nav: Vec<(String, nyx_ui::sidebar::NavItem)>,
    stack: gtk::Stack,
    adb_foot: gtk::Label,
    adb_ok: gtk::Image,
    strip: gtk::Box,
    strip_bar: gtk::ProgressBar,
    strip_label: gtk::Label,
    log_view: RefCell<Option<gtk::TextBuffer>>,
}

thread_local! {
    static UI: RefCell<Option<Rc<Ui>>> = const { RefCell::new(None) };
}

fn ui() -> Option<Rc<Ui>> {
    UI.with(|u| u.borrow().clone())
}

pub fn window() -> Option<gtk::ApplicationWindow> {
    ui().map(|u| u.window.clone())
}

pub fn overlay() -> Option<gtk::Overlay> {
    ui().map(|u| u.overlay.clone())
}

pub fn toast(msg: &str) {
    if let Some(u) = ui() {
        nyx_ui::toast::show(&u.overlay, msg);
    }
}

pub fn toast_action(msg: &str, label: &str, act: fn()) {
    if let Some(u) = ui() {
        nyx_ui::toast::show_action(&u.overlay, msg, label, act);
    }
}

pub fn build(app: &gtk::Application) {
    state::init(render);

    let window = gtk::ApplicationWindow::builder()
        .application(app)
        .title("Nexus Manager")
        .default_width(1100)
        .default_height(720)
        .build();
    window.add_css_class("tv-manager-window");
    // Hyprland owns the chrome; don't draw a GTK titlebar.
    window.set_titlebar(Some(&gtk::Box::new(gtk::Orientation::Horizontal, 0)));
    window.set_icon_name(Some(crate::APP_ID));
    nyx_ui::keys::install(
        &window,
        nyx_ui::keys::Common {
            close: true,
            settings: Some(crate::settings::open),
            ..Default::default()
        },
    );

    let header = chrome::app_header(&window, "video-display-symbolic", "Nexus Manager");
    let refresh = w::bar_button("view-refresh-symbolic", "Refresh (F5)");
    refresh.connect_clicked(|_| refresh_all());
    header.end.prepend(&refresh);
    header.end.append(&chrome::settings_button(crate::settings::open));
    let collapse = w::bar_button("sidebar-show-symbolic", "Collapse the sidebar (Ctrl+B)");
    collapse.connect_clicked(|_| toggle_sidebar());
    header.start.append(&collapse);

    let frame = nyx_ui::frame::AppFrame::new(&header.root);

    // ----- Sidebar -----
    let sidebar = nyx_ui::sidebar::Sidebar::new();
    sidebar.root.set_hexpand(false);
    sidebar.set_collapsed(crate::prefs::get().sidebar_collapsed);
    size_sidebar(&sidebar);
    {
        let side = sidebar.clone();
        sidebar.connect_compact(move |_| size_sidebar(&side));
    }

    let tvs_heading = nyx_ui::sidebar::group_label("Televisions", true);
    nyx_ui::sidebar::set_section(&tvs_heading, nyx_ui::sidebar::ACCENT_SECTION);
    sidebar.append(&tvs_heading);
    let tv_box = gtk::Box::new(gtk::Orientation::Vertical, 2);
    sidebar.append(&tv_box);
    let add_nav = nyx_ui::sidebar::nav_item("list-add-symbolic", "Add a TV", "Add a TV");
    add_nav.label.set_max_width_chars(18);
    add_nav.button.connect_clicked(|_| show_section("add"));
    sidebar.append(&add_nav.button);

    let section_row = nyx_ui::sidebar::group_label("TV", false);
    nyx_ui::sidebar::set_section(&section_row, "swatch-teal");
    let section_heading = section_row
        .first_child()
        .and_then(|w| w.downcast::<gtk::Label>().ok())
        .unwrap_or_else(|| gtk::Label::new(Some("TV")));
    sidebar.append(&section_row);

    let mut nav = Vec::new();
    for (id, title, icon) in NAV {
        let item = nyx_ui::sidebar::nav_item(icon, title, title);
        if *id == "debloat" {
            item.button.set_tooltip_text(Some("Debloat (advanced)"));
            if let Some(content) = item.button.child().and_downcast::<gtk::Box>() {
                let adv = w::label("Advanced", "nav-advanced");
                adv.add_css_class("compact-hide");
                content.append(&adv);
            }
        }
        item.label.set_max_width_chars(18);
        let sid = id.to_string();
        item.button.connect_clicked(move |_| show_section(&sid));
        sidebar.append(&item.button);
        nav.push((id.to_string(), item));
    }

    let foot_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    foot_row.add_css_class("side-foot-row");
    foot_row.add_css_class("compact-hide");
    let adb_ok = gtk::Image::from_icon_name("emblem-ok-symbolic");
    adb_ok.set_pixel_size(14);
    adb_ok.set_visible(false);
    let adb_foot = w::label("Looking for adb…", "side-foot");
    adb_foot.set_hexpand(true);
    adb_foot.set_ellipsize(gtk::pango::EllipsizeMode::End);
    foot_row.append(&adb_ok);
    foot_row.append(&adb_foot);
    sidebar.append_foot(&foot_row);
    sidebar.follow_window(&window);

    // ----- Pages -----
    let stack = gtk::Stack::new();
    stack.set_hexpand(true);
    stack.set_vexpand(true);
    stack.set_hhomogeneous(false);
    stack.set_vhomogeneous(false);
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);
    stack.set_transition_duration(if crate::prefs::get().reduce_motion { 0 } else { 120 });
    stack.add_named(&pages::add::build(), Some("add"));
    stack.add_named(&pages::overview::build(), Some("overview"));
    stack.add_named(&pages::install::build(), Some("install"));
    stack.add_named(&pages::home::build(), Some("home"));
    stack.add_named(&pages::backups::build(), Some("backups"));
    stack.add_named(&pages::diagnostics::build(), Some("diagnostics"));
    stack.add_named(&pages::debloat::build(), Some("debloat"));

    // ----- Status strip: the running operation -----
    let strip = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    strip.add_css_class("tm-strip");
    strip.set_visible(false);
    let strip_bar = gtk::ProgressBar::new();
    strip_bar.set_valign(gtk::Align::Center);
    strip_bar.set_size_request(120, -1);
    strip_bar.set_pulse_step(0.08);
    let strip_label = w::label("", "tm-strip-text");
    strip_label.set_hexpand(true);
    strip_label.set_ellipsize(gtk::pango::EllipsizeMode::End);
    let show_log = w::icon_button("utilities-terminal-symbolic", "Show output");
    show_log.add_css_class("tm-keep");
    show_log.connect_clicked(|_| show_op_log());
    strip.append(&strip_bar);
    strip.append(&strip_label);
    strip.append(&show_log);

    let page_col = gtk::Box::new(gtk::Orientation::Vertical, 0);
    page_col.set_hexpand(true);
    page_col.set_vexpand(true);
    page_col.append(&stack);
    page_col.append(&strip);

    frame.set_sidebar(&sidebar.root);
    frame.set_page(&page_col);
    frame.set_flush(true);
    let overlay = frame.install(&window);

    UI.with(|u| {
        *u.borrow_mut() = Some(Rc::new(Ui {
            window: window.clone(),
            overlay,
            sidebar,
            tv_box,
            tv_sig: RefCell::new(String::new()),
            section_heading,
            add_nav,
            nav,
            stack,
            adb_foot,
            adb_ok,
            strip,
            strip_bar,
            strip_label,
            log_view: RefCell::new(None),
        }))
    });

    let key = gtk::EventControllerKey::new();
    key.connect_key_pressed(|_, key, _, mods| {
        if key == gtk::gdk::Key::F5 {
            refresh_all();
            return glib::Propagation::Stop;
        }
        if mods.contains(gtk::gdk::ModifierType::CONTROL_MASK)
            && (key == gtk::gdk::Key::b || key == gtk::gdk::Key::B)
        {
            toggle_sidebar();
            return glib::Propagation::Stop;
        }
        glib::Propagation::Proceed
    });
    window.add_controller(key);

    let has_tvs = state::get(|s| !s.devices.devices.is_empty());
    let start = std::env::var("NEXUS_MANAGER_START")
        .ok()
        .map(|s| if s == "launcher" { "home".to_string() } else { s })
        .filter(|s| SECTIONS.contains(&s.as_str()))
        .unwrap_or_else(|| if has_tvs { "overview".into() } else { "add".into() });
    show_section(&start);
    sync::refresh_online();
    sync::refresh_tv();
    sync::refresh_local();
    start_timers();
    window.present();

    if std::env::var_os("NEXUS_MANAGER_OPEN_SETTINGS").is_some() {
        glib::timeout_add_local_once(Duration::from_millis(200), crate::settings::open);
    }
}

/// Sidebar width: 212 px, or the icon rail.
fn size_sidebar(side: &nyx_ui::sidebar::Sidebar) {
    if side.is_compact() {
        side.root.set_size_request(-1, -1);
    } else {
        side.root.set_size_request(212, -1);
    }
}

fn toggle_sidebar() {
    if let Some(u) = ui() {
        u.sidebar.toggle();
        crate::prefs::update(|p| p.sidebar_collapsed = u.sidebar.is_collapsed());
        size_sidebar(&u.sidebar);
    }
}

/// Polling: Add a TV rescans every 3 s while it's open; online dots every
/// 15 s; an offline selected TV is retried every 30 s.
fn start_timers() {
    let tick = Rc::new(std::cell::Cell::new(0u32));
    glib::timeout_add_local(Duration::from_secs(3), move || {
        let n = tick.get() + 1;
        tick.set(n);
        let visible = window().is_some_and(|w| w.is_visible());
        if !visible {
            return glib::ControlFlow::Continue;
        }
        let (section, offline, busy, refreshing) = state::get(|s| {
            (
                s.section.clone(),
                s.selected().is_some() && s.tv_checked && !s.connected(),
                s.is_busy(),
                s.refreshing,
            )
        });
        if section == "add" {
            sync::discover();
        }
        if n.is_multiple_of(5) {
            sync::refresh_online();
        }
        if n.is_multiple_of(10) && offline && !busy && !refreshing {
            sync::refresh_tv();
        }
        glib::ControlFlow::Continue
    });
}

pub fn show_section(id: &str) {
    state::update(|s| s.section = id.to_string());
    if let Some(u) = ui() {
        u.stack.set_visible_child_name(id);
        u.add_nav.set_active(id == "add");
        for (sid, item) in &u.nav {
            item.set_active(sid == id);
        }
    }
    sync::refresh_for_section();
}

/// Select a saved TV and open its Overview.
pub fn select_tv(key: &str) {
    let same = state::get(|s| s.selected_key().as_deref() == Some(key));
    if !same {
        state::update(|s| {
            s.devices.selected = Some(key.to_string());
            s.clear_tv();
        });
        state::save_devices();
        sync::refresh_tv();
    }
    show_section("overview");
}

/// F5: reread adb, the TV and the visible page's data.
pub fn refresh_all() {
    sync::refresh_online();
    sync::refresh_tv();
    sync::refresh_local();
    let section = state::get(|s| s.section.clone());
    match section.as_str() {
        "diagnostics" => sync::refresh_diag(),
        "debloat" => sync::refresh_debloat(),
        _ => sync::refresh_for_section(),
    }
}

/// Redraw the sidebar, the visible page and the strip from [`state`].
fn render() {
    let Some(u) = ui() else { return };
    render_sidebar(&u);
    let section = state::get(|s| s.section.clone());
    state::get(|st| match section.as_str() {
        "add" => pages::add::render(st),
        "overview" => pages::overview::render(st),
        "install" => pages::install::render(st),
        "home" => pages::home::render(st),
        "backups" => pages::backups::render(st),
        "diagnostics" => pages::diagnostics::render(st),
        "debloat" => pages::debloat::render(st),
        _ => {}
    });
    let busy = state::get(|s| s.busy.clone());
    if let Some(page) = u.stack.visible_child() {
        crate::page::set_busy(&page, busy.is_some());
    }
    render_strip(&u, busy);
    crate::settings::refresh_if_open();
}

fn render_sidebar(u: &Ui) {
    let (devices, selected, online, section, label, heading) = state::get(|s| {
        (
            s.devices.devices.clone(),
            s.selected_key(),
            s.online.clone(),
            s.section.clone(),
            s.adb_label.clone(),
            s.selected_name(),
        )
    });
    u.section_heading.set_text(&heading.to_uppercase());
    match label {
        Some((text, ok)) => {
            u.adb_foot.set_text(&text);
            u.adb_ok.set_visible(ok);
        }
        None => u.adb_foot.set_text("Looking for adb…"),
    }

    let mark_active = section != "add";
    let sig = format!(
        "{}|{:?}|{mark_active}|{}",
        devices
            .iter()
            .map(|d| format!("{}={}", d.key(), d.name()))
            .collect::<Vec<_>>()
            .join(","),
        selected,
        {
            let mut v: Vec<_> = online.iter().cloned().collect();
            v.sort();
            v.join(",")
        }
    );
    if *u.tv_sig.borrow() == sig {
        return;
    }
    *u.tv_sig.borrow_mut() = sig;

    crate::page::clear(&u.tv_box);
    for d in devices {
        let key = d.key().to_string();
        let name = d.name().to_string();
        let row = gtk::Button::new();
        row.add_css_class("nav-item");
        row.add_css_class("tv-row");
        if selected.as_deref() == Some(key.as_str()) && mark_active {
            row.add_css_class("active");
        }
        let is_on = online.contains(&key);
        let content = gtk::Box::new(gtk::Orientation::Horizontal, 10);
        content.append(&crate::page::dot(is_on));
        let label = w::label(&name, "");
        label.set_hexpand(true);
        label.set_ellipsize(gtk::pango::EllipsizeMode::End);
        label.add_css_class("compact-hide");
        content.append(&label);
        if !is_on {
            let off = w::label("Offline", "dim");
            off.add_css_class("compact-hide");
            content.append(&off);
        }
        row.set_child(Some(&content));
        row.set_tooltip_text(Some(&if is_on {
            name.clone()
        } else {
            format!("{name} (offline)")
        }));
        let k = key.clone();
        row.connect_clicked(move |_| select_tv(&k));

        let (k_rename, k_remove, heading) = (key.clone(), key.clone(), name.clone());
        nyx_ui::menu::on_right_click(&row, move |wd, x, y| {
            let (a, b) = (k_rename.clone(), k_remove.clone());
            nyx_ui::menu::popup_at(
                wd,
                x,
                y,
                vec![
                    nyx_ui::menu::heading(&heading),
                    nyx_ui::menu::item("Rename…", move || rename_tv(&a))
                        .icon("document-edit-symbolic"),
                    nyx_ui::menu::separator(),
                    nyx_ui::menu::item("Remove from Nexus Manager", move || remove_tv(&b))
                        .icon("user-trash-symbolic")
                        .danger(),
                ],
            );
        });
        u.tv_box.append(&row);
    }
    u.sidebar.refresh();
}

fn render_strip(u: &Ui, busy: Option<state::Busy>) {
    thread_local! {
        static PULSE: RefCell<Option<glib::SourceId>> = const { RefCell::new(None) };
    }
    match busy {
        Some(b) => {
            u.strip_label.set_text(&b.step);
            u.strip.set_visible(true);
            PULSE.with(|p| {
                if p.borrow().is_none() {
                    let bar = u.strip_bar.clone();
                    let still = crate::prefs::get().reduce_motion;
                    if still {
                        bar.set_fraction(0.35);
                    }
                    let id = glib::timeout_add_local(Duration::from_millis(120), move || {
                        if !still {
                            bar.pulse();
                        }
                        glib::ControlFlow::Continue
                    });
                    *p.borrow_mut() = Some(id);
                }
            });
        }
        None => {
            u.strip.set_visible(false);
            PULSE.with(|p| {
                if let Some(id) = p.borrow_mut().take() {
                    id.remove();
                }
            });
        }
    }
}

/// The running operation wrote output: update an open log window.
pub fn op_log_changed() {
    if let Some(u) = ui()
        && let Some(buf) = u.log_view.borrow().as_ref() {
            buf.set_text(&state::get(|s| s.op_log.clone()));
        }
}

/// Output of the running or last operation.
pub fn show_op_log() {
    let Some(u) = ui() else { return };
    let (dialog, card) = w::dialog("Output", 640);
    let text = state::get(|s| s.op_log.clone());
    let buf = gtk::TextBuffer::new(None);
    buf.set_text(if text.is_empty() { "Nothing has run yet." } else { &text });
    let view = gtk::TextView::with_buffer(&buf);
    view.set_editable(false);
    view.set_cursor_visible(false);
    view.set_monospace(true);
    view.set_wrap_mode(gtk::WrapMode::WordChar);
    view.add_css_class("tm-log");
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .min_content_height(320)
        .child(&view)
        .build();
    card.append(&scroll);
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    buttons.set_halign(gtk::Align::End);
    let copy = gtk::Button::with_label("Copy");
    let b2 = buf.clone();
    copy.connect_clicked(move |btn| {
        let (s, e) = b2.bounds();
        btn.clipboard().set_text(&b2.text(&s, &e, false));
        toast("Copied");
    });
    let close = gtk::Button::with_label("Close");
    close.add_css_class("suggested-action");
    let d = dialog.clone();
    close.connect_clicked(move |_| d.close());
    buttons.append(&copy);
    buttons.append(&close);
    card.append(&buttons);
    *u.log_view.borrow_mut() = Some(buf);
    dialog.connect_close_request(|_| {
        if let Some(u) = ui() {
            *u.log_view.borrow_mut() = None;
        }
        glib::Propagation::Proceed
    });
    dialog.present();
}

/// Saved TVs as (key, display name) for Settings.
pub fn saved_devices() -> Vec<(String, String, bool)> {
    state::get(|s| {
        s.devices
            .devices
            .iter()
            .map(|d| {
                (
                    d.key().to_string(),
                    d.name().to_string(),
                    s.online.contains(d.key()),
                )
            })
            .collect()
    })
}

/// Rename a saved TV. Empty clears the name back to its address.
pub fn rename_tv(key: &str) {
    let key = key.to_string();
    let Some(tv) = state::get(|s| s.devices.find(&key).cloned()) else {
        return;
    };
    let (dialog, card) = w::dialog("Rename TV", 380);
    let hint = w::label("Shown in the sidebar. Leave it empty to use the address.", "dim");
    hint.set_wrap(true);
    card.append(&hint);
    let entry = gtk::Entry::new();
    entry.set_text(&tv.nickname);
    entry.set_placeholder_text(Some(tv.key()));
    card.append(&entry);
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    buttons.set_halign(gtk::Align::End);
    buttons.set_margin_top(6);
    let cancel = gtk::Button::with_label("Cancel");
    let save = gtk::Button::with_label("Rename");
    save.add_css_class("suggested-action");
    buttons.append(&cancel);
    buttons.append(&save);
    card.append(&buttons);

    let d = dialog.clone();
    cancel.connect_clicked(move |_| d.close());
    let apply = Rc::new({
        let (d, e) = (dialog.clone(), entry.clone());
        move || {
            let name = e.text().trim().to_string();
            state::update(|s| {
                if let Some(dev) = s.devices.find_mut(&key) {
                    dev.nickname = name;
                }
            });
            state::save_devices();
            d.close();
            toast("TV renamed");
        }
    });
    let a = apply.clone();
    save.connect_clicked(move |_| a());
    entry.connect_activate(move |_| apply());
    dialog.present();
    entry.grab_focus();
    entry.select_region(0, -1);
}

/// Forget a saved TV (the TV itself is not touched).
pub fn remove_tv(key: &str) {
    let key = key.to_string();
    let name = state::get(|s| s.devices.find(&key).map(|d| d.name().to_string()))
        .unwrap_or_default();
    crate::page::confirm(
        &format!("Remove {name} from Nexus Manager?"),
        "Only this list changes. Nexus and everything on the TV stay as they are.",
        "Remove",
        move || forget_tv(&key),
    );
}

/// Remove without asking (Settings uses a two-click button instead).
pub fn forget_tv(key: &str) {
    let mut was_selected = false;
    state::update(|s| {
        s.devices.devices.retain(|d| d.key() != key);
        if s.devices.selected.as_deref() == Some(key) {
            was_selected = true;
            s.devices.selected = s.devices.devices.first().map(|d| d.key().to_string());
            s.clear_tv();
        }
    });
    state::save_devices();
    toast("TV removed");
    if state::get(|s| s.devices.devices.is_empty()) {
        show_section("add");
    } else if was_selected {
        sync::refresh_tv();
    }
}
