//! Manager window — direction A (TVs in the sidebar, one page per section).
//! Layout follows `nyx-design/mockups/tv` Manager A and Design 2.0 (`nyx-ui`).

use crate::devices;
use anyhow::Result;
use gtk::prelude::*;
use gtk::{gio, glib};
use nyx_tv_adb::Adb;
use nyx_tv_device::{
    apk_info, find_local_apk, DebloatKind, DebloatPackage, Overview, QuickMetrics, Snapshot, Tv,
    NYX_TV_PKG, NYX_TV_PKG_DEBUG,
};
use nyx_ui::{chrome, widgets as w};
use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;

const SECTIONS: &[(&str, &str, &str)] = &[
    ("overview", "Overview", "computer-symbolic"),
    ("install", "Install and update", "folder-download-symbolic"),
    ("launcher", "Launcher", "user-home-symbolic"),
    ("backups", "Backups", "document-save-symbolic"),
    ("diagnostics", "Diagnostics", "dialog-information-symbolic"),
    ("debloat", "Debloat", "applications-system-symbolic"),
];

struct State {
    devices: devices::DeviceFile,
    selected: Option<String>,
    section: String,
    /// Nested launcher screen: hub | replace | verify | restore | repair
    launcher_view: String,
    last_snapshot: Option<Snapshot>,
    /// True after a Verify run filled the board (don't wipe on reopen).
    verify_has_run: bool,
    overview: Option<Overview>,
    online: bool,
    /// Recent local actions: (time, text, extra)
    activity: Vec<(String, String, String)>,
}

/// Left rail on Install and update (Option A version train).
#[derive(Clone, Copy, PartialEq, Eq)]
enum InstallSource {
    Release,
    OnTv,
    File,
}

struct DebloatRow {
    package: String,
    title: String,
    kind: DebloatKind,
    /// Package is currently enabled on the TV (from last scan).
    enabled: bool,
    check: gtk::CheckButton,
}

struct Ui {
    window: gtk::ApplicationWindow,
    overlay: gtk::Overlay,
    sidebar: Rc<nyx_ui::sidebar::Sidebar>,
    tv_box: gtk::Box,
    section_heading: gtk::Label,
    add_nav: nyx_ui::sidebar::NavItem,
    nav: Vec<(String, nyx_ui::sidebar::NavItem)>,
    stack: gtk::Stack,
    /// Subtitles on the three pages that still use an in-content page bar.
    verify_sub: gtk::Label,
    backups_sub: gtk::Label,
    diag_sub: gtk::Label,
    overview_eyebrow: gtk::Label,
    overview_headline: gtk::Label,
    overview_lede: gtk::Label,
    overview_cta_primary: gtk::Button,
    overview_cta_ghost: gtk::Button,
    overview_meta: gtk::Box,
    overview_feed: gtk::Box,
    diag_values: [gtk::Label; 4],
    diag_subs: [gtk::Label; 4],
    diag_term: gtk::Label,
    launcher_stack: gtk::Stack,
    hub_now_title: gtk::Label,
    hub_now_sub: gtk::Label,
    hub_primary: gtk::Button,
    /// Live "Now" / planned "After" values on Set as home (Home / Stock / Nyx).
    replace_now: [gtk::Label; 3],
    replace_after: [gtk::Label; 3],
    verify_board: gtk::FlowBox,
    restore_strip: gtk::Box,
    restore_detail_title: gtk::Label,
    restore_detail_sub: gtk::Label,
    restore_detail_body: gtk::Label,
    repair_badge: gtk::Label,
    repair_headline: gtk::Label,
    repair_lede: gtk::Label,
    repair_findings: gtk::Box,
    debloat_list: gtk::Box,
    debloat_pkgs: Rc<RefCell<Vec<DebloatRow>>>,
    debloat_will: gtk::Label,
    debloat_action: gtk::Button,
    debloat_enable: gtk::Button,
    /// Centered empty / scanning / error pane (aside hidden).
    debloat_status: gtk::Box,
    /// Scrollable list + sticky footer — shown when packages are listed.
    debloat_content: gtk::Box,
    copy_btn: gtk::Button,
    copy_note: gtk::Label,
    install_ages: [gtk::Box; 3],
    install_rel_title: gtk::Label,
    install_rel_sub: gtk::Label,
    install_on_title: gtk::Label,
    install_on_sub: gtk::Label,
    install_eyebrow: gtk::Label,
    install_title: gtk::Label,
    install_sub: gtk::Label,
    install_changes: gtk::Box,
    install_acts: gtk::Box,
    install_term: gtk::Label,
    install_source: RefCell<InstallSource>,
    install_release: Option<PathBuf>,
    install_debug: Option<PathBuf>,
    adb_foot: gtk::Label,
    state: Rc<RefCell<State>>,
}

thread_local! {
    static UI: RefCell<Option<Rc<RefCell<Ui>>>> = const { RefCell::new(None) };
}

pub fn build(app: &gtk::Application) {
    let window = gtk::ApplicationWindow::builder()
        .application(app)
        .title("TV Manager")
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

    let file = devices::load();
    let selected = file
        .selected
        .clone()
        .or_else(|| file.devices.first().map(|d| d.serial.clone()));
    let start_section = std::env::var("NYX_TV_MANAGER_START")
        .ok()
        .filter(|s| matches!(s.as_str(), "add" | "overview" | "install" | "launcher" | "backups" | "diagnostics" | "debloat"))
        .unwrap_or_else(|| {
            if file.devices.is_empty() {
                "add".into()
            } else {
                "overview".into()
            }
        });
    let start_launcher = std::env::var("NYX_TV_MANAGER_LAUNCHER_VIEW")
        .ok()
        .filter(|s| matches!(s.as_str(), "hub" | "replace" | "verify" | "restore" | "repair"))
        .unwrap_or_else(|| "hub".into());

    let state = Rc::new(RefCell::new(State {
        devices: file,
        selected,
        section: start_section.clone(),
        launcher_view: start_launcher.clone(),
        last_snapshot: None,
        verify_has_run: false,
        overview: None,
        online: false,
        activity: Vec::new(),
    }));

    let header = chrome::app_header(&window, "video-display-symbolic", "TV Manager");
    let refresh = w::bar_button("view-refresh-symbolic", "Refresh (F5)");
    refresh.connect_clicked(|_| refresh_all());
    header.end.prepend(&refresh);
    header.end.append(&chrome::settings_button(crate::settings::open));

    let collapse = w::bar_button("sidebar-show-symbolic", "Collapse the sidebar (Ctrl+B)");
    header.start.append(&collapse);

    let frame = nyx_ui::frame::AppFrame::new(&header.root);

    // ----- Sidebar (Design 2.0: 212px, icons-only under 980) -----
    let sidebar = nyx_ui::sidebar::Sidebar::new();
    sidebar.root.set_hexpand(false);
    sidebar.root.set_size_request(212, -1);
    sidebar.set_collapsed(crate::prefs::get().sidebar_collapsed);
    {
        let side = sidebar.clone();
        collapse.connect_clicked(move |_| {
            side.toggle();
            crate::prefs::update(|p| p.sidebar_collapsed = side.is_collapsed());
            if side.is_compact() {
                side.root.set_size_request(-1, -1);
            } else {
                side.root.set_size_request(212, -1);
            }
        });
    }
    {
        let side = sidebar.clone();
        sidebar.connect_compact(move |compact| {
            if compact {
                side.root.set_size_request(-1, -1);
            } else {
                side.root.set_size_request(212, -1);
            }
        });
    }

    let tvs_heading = nyx_ui::sidebar::group_label("Televisions", true);
    nyx_ui::sidebar::set_section(&tvs_heading, nyx_ui::sidebar::ACCENT_SECTION);
    sidebar.append(&tvs_heading);

    let tv_box = gtk::Box::new(gtk::Orientation::Vertical, 2);
    sidebar.append(&tv_box);

    let add_nav = nyx_ui::sidebar::nav_item("list-add-symbolic", "Add a TV", "Add a TV");
    add_nav.button.connect_clicked(|_| show_section("add"));
    sidebar.append(&add_nav.button);

    // Living-room / per-TV group: teal (mockup). Class is section-swatch-teal.
    let section_heading_row = nyx_ui::sidebar::group_label("TV", false);
    nyx_ui::sidebar::set_section(&section_heading_row, "swatch-teal");
    let section_heading = section_heading_row
        .first_child()
        .and_then(|w| w.downcast::<gtk::Label>().ok())
        .unwrap_or_else(|| gtk::Label::new(Some("TV")));
    sidebar.append(&section_heading_row);

    let section_box = gtk::Box::new(gtk::Orientation::Vertical, 2);
    sidebar.append(&section_box);

    let mut nav = Vec::new();
    for (id, title, icon) in SECTIONS {
        let item = nyx_ui::sidebar::nav_item(icon, title, title);
        if *id == "debloat" {
            item.button.set_tooltip_text(Some("Debloat · Advanced"));
            if let Some(content) = item.button.child().and_downcast::<gtk::Box>() {
                let adv = w::label("Advanced", "nav-advanced");
                adv.add_css_class("compact-hide");
                content.append(&adv);
            }
        }
        let sid = (*id).to_string();
        item.button.connect_clicked(glib::clone!(
            #[strong]
            sid,
            move |_| show_section(&sid)
        ));
        item.label.set_max_width_chars(18);
        section_box.append(&item.button);
        nav.push((sid, item));
    }
    add_nav.label.set_max_width_chars(18);

    let foot_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    foot_row.add_css_class("side-foot-row");
    foot_row.add_css_class("compact-hide");
    let foot_ok = gtk::Image::from_icon_name("emblem-ok-symbolic");
    foot_ok.set_pixel_size(14);
    foot_ok.set_visible(false);
    let adb_foot = gtk::Label::new(Some("adb · looking"));
    adb_foot.add_css_class("side-foot");
    adb_foot.set_xalign(0.0);
    adb_foot.set_hexpand(true);
    adb_foot.set_ellipsize(gtk::pango::EllipsizeMode::End);
    adb_foot.set_margin_top(0);
    adb_foot.set_margin_bottom(0);
    adb_foot.set_margin_start(0);
    adb_foot.set_margin_end(0);
    foot_row.append(&foot_ok);
    foot_row.append(&adb_foot);
    foot_ok.set_widget_name("adb-ok");
    sidebar.append_foot(&foot_row);
    sidebar.follow_window(&window);

    // No shared page-bar above the stack. Verify / Backups / Diagnostics own
    // their title row inside the page (Option A).

    // Each page is its own ScrolledWindow (same pattern as Settings). An outer
    // scroller around the stack forced ~512px min height into the cards.
    let stack = gtk::Stack::new();
    stack.set_hexpand(true);
    stack.set_vexpand(true);
    stack.set_hhomogeneous(false);
    stack.set_vhomogeneous(false);
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);
    stack.set_transition_duration(120);

    let (
        overview_page,
        overview_eyebrow,
        overview_headline,
        overview_lede,
        overview_cta_primary,
        overview_cta_ghost,
        overview_meta,
        overview_feed,
    ) = overview_page();
    let (diag_page, diag_values, diag_subs, diag_term, diag_sub) = diagnostics_page();
    let (
        launcher_page,
        launcher_stack,
        hub_now_title,
        hub_now_sub,
        hub_primary,
        replace_now,
        replace_after,
        verify_board,
        verify_sub,
        restore_strip,
        restore_detail_title,
        restore_detail_sub,
        restore_detail_body,
        repair_badge,
        repair_headline,
        repair_lede,
        repair_findings,
    ) = launcher_page();
    let (backups_page, copy_btn, copy_note, backups_sub) = backups_page();
    let (
        debloat_page,
        debloat_list,
        debloat_pkgs,
        debloat_will,
        debloat_action,
        debloat_enable,
        debloat_status,
        debloat_content,
    ) = debloat_page();
    let (
        install_page_w,
        install_ages,
        install_rel_title,
        install_rel_sub,
        install_on_title,
        install_on_sub,
        install_eyebrow,
        install_title,
        install_sub,
        install_changes,
        install_acts,
        install_term,
        install_release,
        install_debug,
    ) = install_page();
    stack.add_named(&page_scroll(crate::add::build()), Some("add"));
    stack.add_named(&page_scroll(overview_page), Some("overview"));
    stack.add_named(&page_scroll(install_page_w), Some("install"));
    stack.add_named(&page_scroll(launcher_page), Some("launcher"));
    stack.add_named(&page_scroll(backups_page), Some("backups"));
    stack.add_named(&page_scroll(diag_page), Some("diagnostics"));
    // Debloat fills the page: list scrolls inside; footer stays on screen.
    stack.add_named(&page_fill(debloat_page), Some("debloat"));

    let pages = gtk::Box::new(gtk::Orientation::Vertical, 0);
    pages.set_widget_name("pages");
    pages.set_hexpand(true);
    pages.set_vexpand(true);
    pages.append(&stack);

    let page_col = gtk::Box::new(gtk::Orientation::Vertical, 0);
    page_col.set_widget_name("page-col");
    page_col.set_hexpand(true);
    page_col.set_vexpand(true);
    page_col.append(&pages);

    frame.set_sidebar(&sidebar.root);
    frame.set_page(&page_col);
    frame.set_flush(true);
    let overlay = frame.install(&window);

    let ui = Rc::new(RefCell::new(Ui {
        window: window.clone(),
        overlay,
        sidebar: sidebar.clone(),
        tv_box,
        section_heading,
        add_nav,
        nav,
        stack,
        verify_sub,
        backups_sub,
        diag_sub,
        overview_eyebrow,
        overview_headline,
        overview_lede,
        overview_cta_primary,
        overview_cta_ghost,
        overview_meta,
        overview_feed,
        diag_values,
        diag_subs,
        diag_term,
        launcher_stack,
        hub_now_title,
        hub_now_sub,
        hub_primary,
        replace_now,
        replace_after,
        verify_board,
        restore_strip,
        restore_detail_title,
        restore_detail_sub,
        restore_detail_body,
        repair_badge,
        repair_headline,
        repair_lede,
        repair_findings,
        debloat_list,
        debloat_pkgs,
        debloat_will,
        debloat_action,
        debloat_enable,
        debloat_status,
        debloat_content,
        copy_btn,
        copy_note,
        install_ages,
        install_rel_title,
        install_rel_sub,
        install_on_title,
        install_on_sub,
        install_eyebrow,
        install_title,
        install_sub,
        install_changes,
        install_acts,
        install_term,
        install_source: RefCell::new(InstallSource::Release),
        install_release,
        install_debug,
        adb_foot,
        state,
    }));
    UI.with(|u| *u.borrow_mut() = Some(ui));
    sync_debloat_action();
    fill_verify_idle();
    select_install_source(InstallSource::Release);

    let key = gtk::EventControllerKey::new();
    key.connect_key_pressed(|_, key, _, mods| {
        if key == gtk::gdk::Key::F5 {
            refresh_all();
            return glib::Propagation::Stop;
        }
        if mods.contains(gtk::gdk::ModifierType::CONTROL_MASK)
            && (key == gtk::gdk::Key::b || key == gtk::gdk::Key::B)
        {
            let _ = with_ui(|ui| {
                ui.sidebar.toggle();
                crate::prefs::update(|p| p.sidebar_collapsed = ui.sidebar.is_collapsed());
            });
            return glib::Propagation::Stop;
        }
        glib::Propagation::Proceed
    });
    window.add_controller(key);

    refresh_tv_list();
    update_adb_foot();
    show_section(&start_section);
    if start_section == "launcher" && start_launcher != "hub" {
        show_launcher_view(&start_launcher);
    }
    if start_section != "add" {
        refresh_overview();
    }
    window.present();
    if std::env::var_os("NYX_TV_MANAGER_OPEN_SETTINGS").is_some() {
        glib::timeout_add_local_once(std::time::Duration::from_millis(200), || {
            crate::settings::open();
        });
    }
}

pub fn overlay() -> Option<gtk::Overlay> {
    with_ui(|ui| ui.overlay.clone())
}

pub fn window() -> Option<gtk::ApplicationWindow> {
    with_ui(|ui| ui.window.clone())
}

/// Saved TVs as (serial, display name) for Settings.
pub fn saved_devices() -> Vec<(String, String)> {
    with_ui(|ui| {
        ui.state
            .borrow()
            .devices
            .devices
            .iter()
            .map(|d| {
                let name = if d.nickname.is_empty() {
                    d.serial.clone()
                } else {
                    d.nickname.clone()
                };
                (d.serial.clone(), name)
            })
            .collect()
    })
    .unwrap_or_default()
}

/// Rename a saved TV (sidebar nickname). Empty clears it back to the serial.
pub fn rename_tv(serial: &str) {
    let serial = serial.to_string();
    let current = with_ui(|ui| {
        ui.state
            .borrow()
            .devices
            .devices
            .iter()
            .find(|d| d.serial == serial)
            .map(|d| d.nickname.clone())
            .unwrap_or_default()
    })
    .unwrap_or_default();

    let (dialog, card) = w::dialog("Rename TV", 380);
    let hint = w::label(
        "Name shown in the sidebar. Clear it to use the serial instead.",
        "dim",
    );
    hint.set_wrap(true);
    hint.set_xalign(0.0);
    card.append(&hint);
    let entry = gtk::Entry::new();
    entry.set_text(&current);
    entry.set_placeholder_text(Some(&serial));
    card.append(&entry);
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    buttons.set_halign(gtk::Align::End);
    buttons.set_margin_top(6);
    let cancel = gtk::Button::with_label("Cancel");
    let save = gtk::Button::with_label("Save");
    save.add_css_class("suggested-action");
    buttons.append(&cancel);
    buttons.append(&save);
    card.append(&buttons);

    let d = dialog.clone();
    cancel.connect_clicked(move |_| d.close());
    let apply = Rc::new({
        let d = dialog.clone();
        let e = entry.clone();
        let serial = serial.clone();
        move || {
            let name = e.text().trim().to_string();
            let _ = with_ui_mut(|ui| {
                if let Some(dev) = ui
                    .state
                    .borrow_mut()
                    .devices
                    .devices
                    .iter_mut()
                    .find(|d| d.serial == serial)
                {
                    dev.nickname = name;
                }
                let _ = devices::save(&ui.state.borrow().devices);
            });
            d.close();
            refresh_tv_list();
            refresh_page_bar();
            crate::settings::refresh_if_open();
            toast("TV renamed");
        }
    });
    save.connect_clicked(glib::clone!(
        #[strong]
        apply,
        move |_| apply()
    ));
    entry.connect_activate(glib::clone!(
        #[strong]
        apply,
        move |_| apply()
    ));
    dialog.present();
    entry.grab_focus();
    entry.select_region(0, -1);
}

/// Remove a saved TV from the Manager (not from the device).
pub fn remove_tv(serial: &str) {
    let serial = serial.to_string();
    confirm_then(
        "Remove this TV?",
        "Removes it from the Manager list. The TV itself is unchanged.",
        move || {
            let empty = with_ui_mut(|ui| {
                let mut st = ui.state.borrow_mut();
                st.devices.devices.retain(|d| d.serial != serial);
                if st.selected.as_deref() == Some(serial.as_str()) {
                    st.selected = st.devices.devices.first().map(|d| d.serial.clone());
                    st.devices.selected = st.selected.clone();
                }
                let _ = devices::save(&st.devices);
                st.devices.devices.is_empty()
            })
            .unwrap_or(true);
            refresh_tv_list();
            if empty {
                show_section("add");
            } else {
                refresh_overview();
            }
            toast("TV removed");
        },
    );
}

fn overview_page() -> (
    gtk::Box,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Button,
    gtk::Button,
    gtk::Box,
    gtk::Box,
) {
    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.add_css_class("tm-next");
    root.set_vexpand(false);
    root.set_valign(gtk::Align::Start);
    root.set_hexpand(true);

    let stage = gtk::Box::new(gtk::Orientation::Vertical, 14);
    stage.add_css_class("stage");

    let overview_eyebrow = w::label("No TV · —", "eyebrow");
    overview_eyebrow.set_xalign(0.0);
    stage.append(&overview_eyebrow);

    let overview_headline = w::label("Add a TV to begin", "headline");
    overview_headline.set_xalign(0.0);
    overview_headline.set_wrap(true);
    overview_headline.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    overview_headline.set_max_width_chars(22);
    stage.append(&overview_headline);

    let overview_lede = gtk::Label::new(Some(
        "Pair a TV over wireless debugging, then install Nyx TV and set it as home.",
    ));
    overview_lede.add_css_class("lede");
    overview_lede.set_wrap(true);
    overview_lede.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    overview_lede.set_max_width_chars(52);
    overview_lede.set_xalign(0.0);
    stage.append(&overview_lede);

    let cta_row = gtk::Box::new(gtk::Orientation::Horizontal, 10);
    cta_row.add_css_class("cta-row");
    let overview_cta_primary = gtk::Button::with_label("Add a TV");
    overview_cta_primary.add_css_class("suggested-action");
    overview_cta_primary.connect_clicked(|_| overview_primary_clicked());
    let overview_cta_ghost = gtk::Button::with_label("Verify first");
    overview_cta_ghost.add_css_class("flat");
    overview_cta_ghost.connect_clicked(|_| overview_ghost_clicked());
    cta_row.append(&overview_cta_primary);
    cta_row.append(&overview_cta_ghost);
    stage.append(&cta_row);

    let overview_meta = gtk::Box::new(gtk::Orientation::Horizontal, 18);
    overview_meta.add_css_class("meta-row");
    stage.append(&overview_meta);

    root.append(&stage);

    let feed_wrap = gtk::Box::new(gtk::Orientation::Vertical, 0);
    feed_wrap.add_css_class("feed");
    let feed_h = w::label("On this computer", "feed-h");
    feed_h.set_xalign(0.0);
    feed_wrap.append(&feed_h);
    let overview_feed = gtk::Box::new(gtk::Orientation::Vertical, 0);
    feed_wrap.append(&overview_feed);
    root.append(&feed_wrap);

    (
        root,
        overview_eyebrow,
        overview_headline,
        overview_lede,
        overview_cta_primary,
        overview_cta_ghost,
        overview_meta,
        overview_feed,
    )
}

/// Stack page: sheet scrolls; page content keeps its natural height at the top.
fn page_scroll(child: impl IsA<gtk::Widget>) -> gtk::Widget {
    let child = child.upcast();
    child.set_hexpand(true);
    child.set_vexpand(false);
    child.set_valign(gtk::Align::Start);
    // Explicit non-expanding content host — Viewport expands scroll children that
    // compute_expand(vertical), which walks descendants; keep the host inert.
    let host = gtk::Box::new(gtk::Orientation::Vertical, 0);
    host.set_widget_name("page-host");
    host.set_hexpand(true);
    host.set_vexpand(false);
    host.set_valign(gtk::Align::Start);
    host.append(&child);
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vscrollbar_policy(gtk::PolicyType::Automatic)
        .vexpand(true)
        .hexpand(true)
        .child(&host)
        .build();
    scroll.set_widget_name("page-scroll");
    scroll.upcast()
}

/// Stack page that fills the viewport (internal scroll only — Debloat).
fn page_fill(child: impl IsA<gtk::Widget>) -> gtk::Widget {
    let child = child.upcast();
    child.set_hexpand(true);
    child.set_vexpand(true);
    child.set_valign(gtk::Align::Fill);
    child
}

fn install_page() -> (
    gtk::Box,
    [gtk::Box; 3],
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Box,
    gtk::Box,
    gtk::Label,
    Option<PathBuf>,
    Option<PathBuf>,
) {
    let root = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    root.add_css_class("tm-train");
    root.set_vexpand(false);
    root.set_valign(gtk::Align::Start);
    root.set_hexpand(true);

    let ages = gtk::Box::new(gtk::Orientation::Vertical, 0);
    ages.add_css_class("ages");
    ages.set_hexpand(false);

    let root_path = devices::repo_root();
    let release = find_local_apk(&root_path, false);
    let debug = find_local_apk(&root_path, true);
    let rel_info = release.as_ref().map(|p| apk_info(p));
    let dbg_info = debug.as_ref().map(|p| apk_info(p));
    let (rel_title, rel_sub) = match (&rel_info, &dbg_info) {
        (Some(i), _) => (
            i.version_name.clone(),
            format!("Latest · {} · {}", i.size_label, i.modified_label),
        ),
        (None, Some(i)) => (
            format!("{} (debug)", i.version_name),
            format!("Debug APK · {} · {}", i.size_label, i.modified_label),
        ),
        (None, None) => (
            "No local APK".into(),
            "Build release/debug or pick a file".into(),
        ),
    };

    let (age_rel, rel_title_l, rel_sub_l) = age_row(&rel_title, &rel_sub, true, false);
    ages.append(&age_rel);

    let (age_on, on_title, on_sub) = age_row(
        "On this TV",
        "Refresh to see installed version",
        false,
        true,
    );
    ages.append(&age_on);

    let (age_file, _, _) = age_row(
        "From a file…",
        "APK you built or downloaded",
        false,
        false,
    );
    ages.append(&age_file);
    root.append(&ages);

    let detail = gtk::Box::new(gtk::Orientation::Vertical, 14);
    detail.add_css_class("detail");
    detail.set_hexpand(true);

    let eyebrow = w::label("", "detail-eyebrow");
    eyebrow.set_xalign(0.0);
    detail.append(&eyebrow);

    let title = w::label("", "detail-title");
    title.set_xalign(0.0);
    detail.append(&title);

    let sub = gtk::Label::new(None);
    sub.add_css_class("dim2");
    sub.set_wrap(true);
    sub.set_xalign(0.0);
    detail.append(&sub);

    let changes = gtk::Box::new(gtk::Orientation::Vertical, 6);
    changes.add_css_class("changes");
    detail.append(&changes);

    let acts = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    detail.append(&acts);

    let install_term = gtk::Label::new(None);
    install_term.add_css_class("term");
    install_term.set_xalign(0.0);
    install_term.set_yalign(0.0);
    install_term.set_wrap(true);
    install_term.set_selectable(true);
    install_term.set_visible(false);
    detail.append(&install_term);

    root.append(&detail);

    let install_ages = [age_rel, age_on, age_file];
    for (i, src) in [
        InstallSource::Release,
        InstallSource::OnTv,
        InstallSource::File,
    ]
    .into_iter()
    .enumerate()
    {
        let click = gtk::GestureClick::new();
        click.connect_released(move |_, _, _, _| select_install_source(src));
        install_ages[i].add_controller(click);
        // Select-only — install CTA lives on the detail pane.
        install_ages[i].set_cursor_from_name(Some("default"));
    }

    (
        root,
        install_ages,
        rel_title_l,
        rel_sub_l,
        on_title,
        on_sub,
        eyebrow,
        title,
        sub,
        changes,
        acts,
        install_term,
        release,
        debug,
    )
}

fn age_row(
    title: impl AsRef<str>,
    sub: impl AsRef<str>,
    sel: bool,
    cur: bool,
) -> (gtk::Box, gtk::Label, gtk::Label) {
    let title = title.as_ref();
    let sub = sub.as_ref();
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 10);
    row.add_css_class("age");
    if sel {
        row.add_css_class("sel");
    }
    if cur {
        row.add_css_class("cur");
    }
    let rail = gtk::Box::new(gtk::Orientation::Vertical, 0);
    rail.add_css_class("rail");
    let blob = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    blob.add_css_class("blob");
    blob.set_size_request(14, 14);
    rail.append(&blob);
    row.append(&rail);
    let text = gtk::Box::new(gtk::Orientation::Vertical, 2);
    let t = gtk::Label::new(Some(title));
    t.set_xalign(0.0);
    t.set_markup(&format!("<b>{title}</b>"));
    let s = w::label(sub, "dim2");
    s.set_xalign(0.0);
    s.set_wrap(true);
    text.append(&t);
    text.append(&s);
    row.append(&text);
    (row, t, s)
}

fn select_install_source(src: InstallSource) {
    let _ = with_ui(|ui| {
        *ui.install_source.borrow_mut() = src;
        for (i, row) in ui.install_ages.iter().enumerate() {
            let want = matches!(
                (i, src),
                (0, InstallSource::Release)
                    | (1, InstallSource::OnTv)
                    | (2, InstallSource::File)
            );
            if want {
                row.add_css_class("sel");
            } else {
                row.remove_css_class("sel");
            }
        }
        apply_install_detail(ui);
    });
}

fn refresh_install_on_tv() {
    let _ = with_ui(|ui| {
        let (online, overview) = {
            let st = ui.state.borrow();
            (st.online, st.overview.clone())
        };
        match overview.as_ref() {
            Some(o) if o.nyx_package.is_some() => {
                let ver = o.nyx_version.as_deref().unwrap_or("installed");
                ui.install_on_title
                    .set_markup(&format!("<b>{ver}</b>"));
                ui.install_on_sub.set_text(&format!(
                    "On this TV · {}",
                    o.nyx_package.as_deref().unwrap_or("Nyx TV")
                ));
            }
            Some(_) => {
                ui.install_on_title.set_markup("<b>On this TV</b>");
                ui.install_on_sub.set_text("Nyx TV not installed");
            }
            None if !online => {
                ui.install_on_title.set_markup("<b>On this TV</b>");
                ui.install_on_sub.set_text("TV offline — connect to see version");
            }
            None => {
                ui.install_on_title.set_markup("<b>On this TV</b>");
                ui.install_on_sub.set_text("Refresh to see installed version");
            }
        }
        if *ui.install_source.borrow() == InstallSource::OnTv {
            apply_install_detail(ui);
        }
    });
}

fn apply_install_detail(ui: &Ui) {
    while let Some(c) = ui.install_changes.first_child() {
        ui.install_changes.remove(&c);
    }
    while let Some(c) = ui.install_acts.first_child() {
        ui.install_acts.remove(&c);
    }

    let src = *ui.install_source.borrow();
    let overview = ui.state.borrow().overview.clone();
    let online = ui.state.borrow().online;

    match src {
        InstallSource::Release => {
            let on_ver = overview
                .as_ref()
                .and_then(|o| o.nyx_version.clone());
            if let Some(path) = ui.install_release.clone() {
                let info = apk_info(&path);
                let eyebrow = match on_ver.as_deref() {
                    Some(v) if v == info.version_name => "Same version on TV",
                    Some(_) => "Update available",
                    None => "Ready to install",
                };
                ui.install_eyebrow.set_text(eyebrow);
                ui.install_title
                    .set_text(&format!("Nyx TV {}", info.version_name));
                ui.install_sub.set_text(&format!(
                    "{} · {}. Settings, rows and Plex sign-in stay on the TV. Does not change the home app.",
                    info.size_label, info.modified_label
                ));
                let pkg = info
                    .package_id
                    .clone()
                    .unwrap_or_else(|| NYX_TV_PKG.into());
                let code = info
                    .version_code
                    .map(|c| c.to_string())
                    .unwrap_or_else(|| "—".into());
                let on_line = on_ver
                    .as_deref()
                    .map(|v| format!("On TV now: {v}"))
                    .unwrap_or_else(|| "On TV now: not installed".into());
                let b1 = format!("Package {pkg}");
                let b2 = format!("versionCode {code}");
                let b3 = on_line;
                let b4 = format!("File {}", info.path.display());
                fill_install_bullets(
                    &ui.install_changes,
                    "This APK",
                    &[&b1, &b2, &b3, &b4],
                );
                let label = if on_ver.is_some() {
                    "Update this TV"
                } else {
                    "Install on this TV"
                };
                let update = gtk::Button::with_label(label);
                update.add_css_class("suggested-action");
                update.connect_clicked(move |_| {
                    let p = path.clone();
                    run_op("Install release APK", move || {
                        current_tv()?.install_apk(&p, true)?;
                        let msg = format!("Installed {}", p.display());
                        glib::idle_add_once(|| {
                            push_activity("Installed Nyx TV release", "");
                        });
                        Ok(msg)
                    });
                });
                ui.install_acts.append(&update);
            } else if let Some(path) = ui.install_debug.clone() {
                let info = apk_info(&path);
                ui.install_eyebrow.set_text("Debug build");
                ui.install_title
                    .set_text(&format!("Nyx TV {} (debug)", info.version_name));
                ui.install_sub.set_text(
                    "No release APK in this checkout — this is the debug package id.",
                );
                let pkg = info
                    .package_id
                    .clone()
                    .unwrap_or_else(|| NYX_TV_PKG_DEBUG.into());
                let b1 = format!("Package {pkg}");
                let b2 = format!("{} · {}", info.size_label, info.modified_label);
                let b3 = format!("File {}", info.path.display());
                fill_install_bullets(
                    &ui.install_changes,
                    "This APK",
                    &[&b1, &b2, &b3],
                );
                let install = gtk::Button::with_label("Install debug APK");
                install.add_css_class("suggested-action");
                install.connect_clicked(move |_| {
                    let p = path.clone();
                    run_op("Install debug APK", move || {
                        current_tv()?.install_apk(&p, true)?;
                        let msg = format!("Installed {}", p.display());
                        glib::idle_add_once(|| {
                            push_activity("Installed Nyx TV debug", "");
                        });
                        Ok(msg)
                    });
                });
                ui.install_acts.append(&install);
            } else {
                ui.install_eyebrow.set_text("No local APK");
                ui.install_title.set_text("Build an APK first");
                ui.install_sub.set_text(
                    "No release or debug APK in this checkout. Build one, or pick a file.",
                );
                fill_install_bullets(
                    &ui.install_changes,
                    "Next",
                    &[
                        "cd android && ./gradlew :app:assembleRelease",
                        "Or choose From a file… for an APK you already have",
                    ],
                );
                let pick = gtk::Button::with_label("Choose file…");
                pick.add_css_class("suggested-action");
                pick.connect_clicked(|_| pick_apk_and_install());
                ui.install_acts.append(&pick);
            }
            let uninstall = uninstall_button();
            uninstall.add_css_class("flat");
            ui.install_acts.append(&uninstall);
        }
        InstallSource::OnTv => match overview.as_ref() {
            Some(o) if o.nyx_package.is_some() => {
                let ver = o.nyx_version.as_deref().unwrap_or("installed");
                let pkg = o.nyx_package.as_deref().unwrap_or(NYX_TV_PKG);
                ui.install_eyebrow.set_text("Installed on this TV");
                ui.install_title.set_text(&format!("Nyx TV {ver}"));
                ui.install_sub.set_text(&format!(
                    "{pkg}. Uninstall removes Nyx TV only — the stock launcher is not touched."
                ));
                fill_install_bullets(
                    &ui.install_changes,
                    "On the TV now",
                    &[
                        &format!("Version {ver}"),
                        &format!("Package {pkg}"),
                        if o.nyx_is_home() {
                            "Nyx TV is the home app"
                        } else {
                            "Stock launcher still holds HOME"
                        },
                    ],
                );
                let uninstall = uninstall_button();
                uninstall.add_css_class("suggested-action");
                ui.install_acts.append(&uninstall);
            }
            Some(_) => {
                ui.install_eyebrow.set_text("Not installed");
                ui.install_title.set_text("Nyx TV isn’t on this TV");
                ui.install_sub.set_text(
                    "Pick the release on the left, or a file, then install over wireless debugging.",
                );
                fill_install_bullets(
                    &ui.install_changes,
                    "What to do",
                    &[
                        "Select the latest release in the train",
                        "Or choose From a file… for an APK you built",
                    ],
                );
                let go = gtk::Button::with_label("Select latest release");
                go.add_css_class("suggested-action");
                go.connect_clicked(|_| select_install_source(InstallSource::Release));
                ui.install_acts.append(&go);
            }
            None if !online => {
                ui.install_eyebrow.set_text("Offline");
                ui.install_title.set_text("TV isn’t answering");
                ui.install_sub.set_text(
                    "Connect over wireless debugging, then refresh to see what’s installed.",
                );
                fill_install_bullets(
                    &ui.install_changes,
                    "Needed",
                    &["TV online over adb", "Nyx TV Manager can read package versions"],
                );
            }
            None => {
                ui.install_eyebrow.set_text("Unknown");
                ui.install_title.set_text("No TV selected");
                ui.install_sub
                    .set_text("Add or select a TV to see the installed version.");
                fill_install_bullets(
                    &ui.install_changes,
                    "Needed",
                    &["A paired TV in the sidebar", "Refresh once it’s connected"],
                );
            }
        },
        InstallSource::File => {
            ui.install_eyebrow.set_text("Custom APK");
            ui.install_title.set_text("Install from a file");
            ui.install_sub.set_text(
                "Opens a file picker, then runs adb install -r. Does not change the home app.",
            );
            fill_install_bullets(
                &ui.install_changes,
                "Notes",
                &[
                    "Any APK you built or downloaded",
                    "Must match a package the TV will accept (signature)",
                    "Home app / Play / Cast / OTA are not touched",
                ],
            );
            let pick = gtk::Button::with_label("Choose file…");
            pick.add_css_class("suggested-action");
            pick.connect_clicked(|_| pick_apk_and_install());
            ui.install_acts.append(&pick);
            let uninstall = uninstall_button();
            uninstall.add_css_class("flat");
            ui.install_acts.append(&uninstall);
        }
    }
}

fn fill_install_bullets(box_: &gtk::Box, heading: &str, lines: &[&str]) {
    let ch_h = w::label(heading, "");
    ch_h.set_xalign(0.0);
    box_.append(&ch_h);
    for line in lines {
        let l = gtk::Label::new(Some(&format!("• {line}")));
        l.add_css_class("dim2");
        l.set_xalign(0.0);
        l.set_wrap(true);
        box_.append(&l);
    }
}

fn uninstall_button() -> gtk::Button {
    let uninstall = gtk::Button::with_label("Uninstall");
    uninstall.connect_clicked(|_| {
        confirm_then(
            "Uninstall Nyx TV?",
            &format!("Removes {NYX_TV_PKG} and/or {NYX_TV_PKG_DEBUG}. Stock launcher is not touched."),
            || {
                run_op("Uninstall", || {
                    let mut out = Vec::new();
                    if uninstall_pkg(NYX_TV_PKG_DEBUG).is_ok() {
                        out.push("debug");
                    }
                    if uninstall_pkg(NYX_TV_PKG).is_ok() {
                        out.push("release");
                    }
                    if out.is_empty() {
                        Ok("Nothing to uninstall".into())
                    } else {
                        Ok(format!("Uninstalled {}", out.join(" + ")))
                    }
                })
            },
        )
    });
    uninstall
}

fn launcher_page() -> (
    gtk::Box,
    gtk::Stack,
    gtk::Label,
    gtk::Label,
    gtk::Button,
    [gtk::Label; 3],
    [gtk::Label; 3],
    gtk::FlowBox,
    gtk::Label,
    gtk::Box,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Label,
    gtk::Box,
) {
    let stack = gtk::Stack::new();
    stack.set_vhomogeneous(false);
    stack.set_hhomogeneous(false);
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);
    stack.set_transition_duration(100);

    let (hub, hub_now_title, hub_now_sub, hub_primary) = launcher_hub();
    let (replace_page, replace_now, replace_after) = launcher_replace();
    let verify_board = gtk::FlowBox::new();
    verify_board.add_css_class("tm-lights");
    verify_board.set_selection_mode(gtk::SelectionMode::None);
    verify_board.set_homogeneous(true);
    verify_board.set_column_spacing(12);
    verify_board.set_row_spacing(12);
    verify_board.set_max_children_per_line(3);
    verify_board.set_min_children_per_line(1);

    let restore_strip = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    restore_strip.add_css_class("strip");
    let restore_detail_title = w::label("No snapshot selected", "detail-title");
    restore_detail_title.set_xalign(0.0);
    let restore_detail_sub = w::label("", "dim2");
    restore_detail_sub.set_xalign(0.0);
    let restore_detail_body = gtk::Label::new(Some(
        "Pick a snapshot from the filmstrip. Matching firmware only.",
    ));
    restore_detail_body.set_wrap(true);
    restore_detail_body.set_xalign(0.0);
    restore_detail_body.add_css_class("dim2");

    let repair_badge = w::label("Needs repair", "badge");
    repair_badge.set_halign(gtk::Align::Start);
    let repair_headline = w::label("Checking launcher state…", "incident-title");
    repair_headline.set_xalign(0.0);
    repair_headline.set_wrap(true);
    let repair_lede = gtk::Label::new(Some(""));
    repair_lede.add_css_class("lede");
    repair_lede.set_wrap(true);
    repair_lede.set_xalign(0.0);
    let repair_findings = gtk::Box::new(gtk::Orientation::Vertical, 0);
    repair_findings.add_css_class("findings");

    let (verify_page, verify_sub) = launcher_verify(&verify_board);
    stack.add_named(&hub, Some("hub"));
    stack.add_named(&replace_page, Some("replace"));
    stack.add_named(&verify_page, Some("verify"));
    stack.add_named(
        &launcher_restore(
            &restore_strip,
            &restore_detail_title,
            &restore_detail_sub,
            &restore_detail_body,
        ),
        Some("restore"),
    );
    stack.add_named(
        &launcher_repair(
            &repair_badge,
            &repair_headline,
            &repair_lede,
            &repair_findings,
        ),
        Some("repair"),
    );
    stack.set_visible_child_name("hub");

    let wrap = gtk::Box::new(gtk::Orientation::Vertical, 0);
    wrap.set_vexpand(false);
    wrap.set_valign(gtk::Align::Start);
    wrap.append(&stack);
    (
        wrap,
        stack,
        hub_now_title,
        hub_now_sub,
        hub_primary,
        replace_now,
        replace_after,
        verify_board,
        verify_sub,
        restore_strip,
        restore_detail_title,
        restore_detail_sub,
        restore_detail_body,
        repair_badge,
        repair_headline,
        repair_lede,
        repair_findings,
    )
}

fn show_launcher_view(view: &str) {
    let _ = with_ui_mut(|ui| {
        ui.state.borrow_mut().launcher_view = view.to_string();
        ui.launcher_stack.set_visible_child_name(view);
    });
    if view == "verify" {
        let has_run = with_ui(|ui| ui.state.borrow().verify_has_run).unwrap_or(false);
        if !has_run {
            fill_verify_idle();
            start_verify();
        }
    }
    if view == "replace" {
        refresh_replace_diff();
    }
    if view == "restore" {
        refresh_restore_list();
    }
    if view == "repair" {
        fill_repair_placeholder();
    }
    refresh_page_bar();
}

fn launcher_hub() -> (gtk::Box, gtk::Label, gtk::Label, gtk::Button) {
    let col = gtk::Box::new(gtk::Orientation::Vertical, 22);
    col.add_css_class("tm-state");
    col.set_halign(gtk::Align::Center);
    col.set_hexpand(true);
    col.set_vexpand(false);
    col.set_valign(gtk::Align::Start);

    let map = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    map.add_css_class("map");
    map.set_halign(gtk::Align::Center);

    let now = gtk::Box::new(gtk::Orientation::Vertical, 4);
    now.add_css_class("node");
    now.add_css_class("on");
    let now_l = w::label("Now", "l");
    now_l.set_halign(gtk::Align::Center);
    let hub_now_title = w::label("—", "node-title");
    hub_now_title.set_halign(gtk::Align::Center);
    let hub_now_sub = w::label("", "dim2");
    hub_now_sub.set_halign(gtk::Align::Center);
    now.append(&now_l);
    now.append(&hub_now_title);
    now.append(&hub_now_sub);

    let arrow = w::label("→", "arrow");
    arrow.set_valign(gtk::Align::Center);

    let goal = gtk::Box::new(gtk::Orientation::Vertical, 4);
    goal.add_css_class("node");
    let goal_l = w::label("Goal", "l");
    goal_l.set_halign(gtk::Align::Center);
    let goal_t = w::label("Nyx TV home", "node-title");
    goal_t.set_halign(gtk::Align::Center);
    let goal_s = w::label("Stock restorable", "dim2");
    goal_s.set_halign(gtk::Align::Center);
    goal.append(&goal_l);
    goal.append(&goal_t);
    goal.append(&goal_s);

    map.append(&now);
    map.append(&arrow);
    map.append(&goal);
    col.append(&map);

    let primary = gtk::Button::with_label("Set Nyx TV as home");
    primary.add_css_class("suggested-action");
    primary.set_halign(gtk::Align::Center);
    primary.connect_clicked(|_| hub_primary_clicked());
    col.append(&primary);

    let ops = gtk::Box::new(gtk::Orientation::Horizontal, 10);
    ops.add_css_class("ops");
    ops.set_halign(gtk::Align::Center);
    ops.set_hexpand(true);

    for (title, sub, view, icon) in [
        ("Verify", "Home button, restart, apps", "verify", "emblem-ok-symbolic"),
        ("Restore", "Google TV from a snapshot", "restore", "edit-undo-symbolic"),
        ("Repair", "After a firmware update", "repair", "applications-system-symbolic"),
    ] {
        let card = gtk::Box::new(gtk::Orientation::Vertical, 6);
        card.add_css_class("op-card");
        card.set_hexpand(true);
        let head = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        let img = gtk::Image::from_icon_name(icon);
        img.set_pixel_size(16);
        head.append(&img);
        let t = gtk::Label::new(Some(title));
        t.set_markup(&format!("<b>{title}</b>"));
        t.set_xalign(0.0);
        head.append(&t);
        card.append(&head);
        let s = w::label(sub, "op-sub");
        s.set_xalign(0.0);
        s.set_wrap(true);
        s.set_vexpand(true);
        card.append(&s);
        let open = gtk::Button::with_label("Open");
        let v = view.to_string();
        open.connect_clicked(move |_| show_launcher_view(&v));
        card.append(&open);
        ops.append(&card);
    }
    col.append(&ops);

    (col, hub_now_title, hub_now_sub, primary)
}

fn hub_primary_clicked() {
    let mode = with_ui(|ui| overview_mode(ui)).unwrap_or(OverviewMode::Empty);
    match mode {
        OverviewMode::Empty => show_section("add"),
        OverviewMode::Offline => refresh_overview(),
        OverviewMode::NotInstalled => show_section("install"),
        OverviewMode::SetHome => show_launcher_view("replace"),
        OverviewMode::AllGood => show_launcher_view("verify"),
    }
}

fn launcher_replace() -> (gtk::Box, [gtk::Label; 3], [gtk::Label; 3]) {
    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.add_css_class("tm-diff");
    root.set_vexpand(false);
    root.set_valign(gtk::Align::Start);
    root.set_hexpand(true);

    let cols = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    cols.set_hexpand(true);

    let now_col = gtk::Box::new(gtk::Orientation::Vertical, 0);
    now_col.add_css_class("col");
    now_col.set_hexpand(true);
    let now_h = w::label("Now", "hh");
    now_h.set_xalign(0.0);
    now_col.append(&now_h);
    let (home_row, home_v) = diff_row_live("Home app");
    let (stock_row, stock_v) = diff_row_live("Stock launcher");
    let (nyx_row, nyx_v) = diff_row_live("Nyx TV");
    now_col.append(&home_row);
    now_col.append(&stock_row);
    now_col.append(&nyx_row);
    now_col.append(&diff_row("Play Store / Cast / OTA", "Unchanged", false, false));

    let after_col = gtk::Box::new(gtk::Orientation::Vertical, 0);
    after_col.add_css_class("col");
    after_col.add_css_class("after");
    after_col.set_hexpand(true);
    let after_h = w::label("After", "hh");
    after_h.set_xalign(0.0);
    after_col.append(&after_h);
    let (ah, after_home) = diff_row_live("Home app");
    ah.add_css_class("chg");
    let (as_, after_stock) = diff_row_live("Stock launcher");
    as_.add_css_class("chg");
    let (an, after_nyx) = diff_row_live("Nyx TV");
    an.add_css_class("chg");
    after_col.append(&ah);
    after_col.append(&as_);
    after_col.append(&an);
    after_col.append(&diff_row("Play Store / Cast / OTA", "Unchanged", false, false));

    cols.append(&now_col);
    cols.append(&after_col);
    root.append(&cols);

    let bar = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    bar.add_css_class("tm-diff-bar");
    let note = w::label("Snapshot saved first · restorable anytime", "dim2");
    note.set_hexpand(true);
    note.set_xalign(0.0);
    bar.append(&note);
    let cancel = gtk::Button::with_label("Cancel");
    cancel.add_css_class("flat");
    cancel.connect_clicked(|_| show_launcher_view("hub"));
    let go = gtk::Button::with_label("Set Nyx TV as home");
    go.add_css_class("suggested-action");
    go.connect_clicked(|_| {
        confirm_then(
            "Set Nyx TV as the home app?",
            "Takes a snapshot, sets Nyx TV as HOME, then disables Google TV launcherx (and setupwraith).",
            || {
                run_op("Replace launcher", || {
                    let msg = replace_launcher()?;
                    glib::idle_add_once(|| push_activity("Set Nyx TV as home", ""));
                    Ok(msg)
                })
            },
        )
    });
    bar.append(&cancel);
    bar.append(&go);
    root.append(&bar);
    (
        root,
        [home_v, stock_v, nyx_v],
        [after_home, after_stock, after_nyx],
    )
}

fn diff_row_live(k: &str) -> (gtk::Box, gtk::Label) {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    row.add_css_class("row");
    let key = w::label(k, "k");
    key.set_xalign(0.0);
    key.set_hexpand(true);
    let val = w::label("—", "v");
    val.set_xalign(1.0);
    row.append(&key);
    row.append(&val);
    (row, val)
}

fn diff_row(k: &str, v: &str, chg: bool, warn: bool) -> gtk::Box {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    row.add_css_class("row");
    if chg {
        row.add_css_class("chg");
    }
    let key = w::label(k, "k");
    key.set_xalign(0.0);
    key.set_hexpand(true);
    let val = w::label(v, "v");
    val.set_xalign(1.0);
    if warn {
        val.add_css_class("warn-text");
    }
    row.append(&key);
    row.append(&val);
    row
}

fn refresh_replace_diff() {
    let _ = with_ui(|ui| {
        let (online, overview) = {
            let st = ui.state.borrow();
            (st.online, st.overview.clone())
        };
        let (home, stock, nyx) = match overview.as_ref() {
            Some(o) if online => {
                let home = o.home_label();
                let stock = match o.stock_state.as_str() {
                    "disabled" | "disabled-user" => "Disabled for user 0",
                    "enabled" => "Enabled",
                    other if other.is_empty() => "Unknown",
                    other => other,
                };
                let nyx = match (o.nyx_package.as_ref(), o.nyx_version.as_deref(), home) {
                    (Some(_), ver, "Nyx TV") => {
                        format!("Home app · {}", ver.unwrap_or("installed"))
                    }
                    (Some(_), ver, _) => {
                        format!("Installed · not home · {}", ver.unwrap_or("?"))
                    }
                    (None, _, _) => "Not installed".into(),
                };
                (home.to_string(), stock.to_string(), nyx)
            }
            Some(_) => (
                "—".into(),
                "—".into(),
                "TV offline".into(),
            ),
            None => ("—".into(), "—".into(), "No TV".into()),
        };
        ui.replace_now[0].set_text(&home);
        ui.replace_now[1].set_text(&stock);
        ui.replace_now[2].set_text(&nyx);

        let already = home == "Nyx TV" && stock.contains("Disabled");
        if already {
            ui.replace_after[0].set_text("Nyx TV");
            ui.replace_after[1].set_text(&stock);
            ui.replace_after[2].set_text(&nyx);
            ui.replace_after[1].remove_css_class("warn-text");
        } else {
            ui.replace_after[0].set_text("Nyx TV");
            ui.replace_after[1].set_text("Disabled for user 0");
            ui.replace_after[1].add_css_class("warn-text");
            ui.replace_after[2].set_text("Home app");
        }
    });
}

fn launcher_verify(board: &gtk::FlowBox) -> (gtk::Box, gtk::Label) {
    let col = gtk::Box::new(gtk::Orientation::Vertical, 0);
    col.set_vexpand(false);
    col.set_valign(gtk::Align::Start);

    let run_again = gtk::Button::with_label("Run again");
    run_again.set_valign(gtk::Align::Center);
    run_again.connect_clicked(|_| run_verify_ui());
    let (bar, sub) = content_page_bar("Verify", "No TV", &[&run_again]);
    col.append(&bar);
    col.append(board);

    let acts = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    acts.set_margin_start(22);
    acts.set_margin_bottom(18);
    let back = gtk::Button::with_label("Back");
    back.add_css_class("flat");
    back.connect_clicked(|_| show_launcher_view("hub"));
    acts.append(&back);
    col.append(&acts);
    (col, sub)
}

/// In-content title row used only on Verify / Backups / Diagnostics (Option A).
fn content_page_bar(title: &str, sub: &str, actions: &[&gtk::Button]) -> (gtk::Box, gtk::Label) {
    let bar = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    bar.add_css_class("page-bar");
    let titles = gtk::Box::new(gtk::Orientation::Vertical, 2);
    titles.set_hexpand(true);
    let title_l = w::label(title, "page-title");
    title_l.set_xalign(0.0);
    let sub_l = w::label(sub, "page-sub");
    sub_l.set_xalign(0.0);
    titles.append(&title_l);
    titles.append(&sub_l);
    bar.append(&titles);
    let acts = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    acts.add_css_class("page-actions");
    acts.set_valign(gtk::Align::Center);
    for a in actions {
        acts.append(*a);
    }
    bar.append(&acts);
    (bar, sub_l)
}

fn launcher_restore(
    strip: &gtk::Box,
    title: &gtk::Label,
    sub: &gtk::Label,
    body: &gtk::Label,
) -> gtk::Box {
    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.add_css_class("tm-film");
    root.set_vexpand(false);
    root.set_valign(gtk::Align::Start);

    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Automatic)
        .vscrollbar_policy(gtk::PolicyType::Never)
        .child(strip)
        .build();
    scroll.set_hexpand(true);
    root.append(&scroll);

    let detail = gtk::Box::new(gtk::Orientation::Vertical, 14);
    detail.add_css_class("detail");
    detail.append(title);
    detail.append(sub);
    detail.append(body);

    let acts = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let restore = gtk::Button::with_label("Restore Google TV home");
    restore.add_css_class("suggested-action");
    restore.connect_clicked(|_| {
        confirm_then(
            "Restore the original launcher?",
            "Re-enables Google TV home from the selected snapshot. Nyx TV stays installed.",
            || run_op("Restore", restore_launcher),
        )
    });
    let remove = gtk::Button::with_label("Restore and remove Nyx TV");
    remove.add_css_class("flat");
    remove.connect_clicked(|_| {
        confirm_then(
            "Restore and uninstall Nyx TV?",
            "Restores Google TV home, then uninstalls Nyx TV (debug and release if present).",
            || {
                run_op("Restore and remove", || {
                    restore_launcher()?;
                    let _ = uninstall_pkg(NYX_TV_PKG_DEBUG);
                    let _ = uninstall_pkg(NYX_TV_PKG);
                    Ok("Restored and removed Nyx TV".into())
                })
            },
        )
    });
    let back = gtk::Button::with_label("Back");
    back.add_css_class("flat");
    back.connect_clicked(|_| show_launcher_view("hub"));
    acts.append(&restore);
    acts.append(&remove);
    acts.append(&back);
    detail.append(&acts);
    root.append(&detail);
    root
}

fn launcher_repair(
    badge: &gtk::Label,
    headline: &gtk::Label,
    lede: &gtk::Label,
    findings: &gtk::Box,
) -> gtk::Box {
    let col = gtk::Box::new(gtk::Orientation::Vertical, 18);
    col.add_css_class("tm-incident");
    col.set_vexpand(false);
    col.set_valign(gtk::Align::Start);
    col.append(badge);
    col.append(headline);
    col.append(lede);

    let acts = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let repair = gtk::Button::with_label("Repair now");
    repair.add_css_class("suggested-action");
    repair.connect_clicked(|_| {
        confirm_then(
            "Repair Nyx TV as HOME?",
            "Re-snapshots and re-applies set-home + disable-stock for the installed package.",
            || run_op("Repair", repair_launcher),
        )
    });
    let not_now = gtk::Button::with_label("Not now");
    not_now.add_css_class("flat");
    not_now.connect_clicked(|_| show_launcher_view("hub"));
    acts.append(&repair);
    acts.append(&not_now);
    col.append(&acts);

    let disc = gtk::Expander::new(Some("What we found"));
    disc.set_expanded(true);
    disc.set_child(Some(findings));
    col.append(&disc);
    col
}

fn light_tile(state: &str, title: &str, sub: &str) -> gtk::Box {
    let tile = gtk::Box::new(gtk::Orientation::Vertical, 8);
    tile.add_css_class("tm-light");
    tile.add_css_class(state);
    let st = match state {
        "ok" => "Pass",
        "bad" => "Fail",
        "wait" => "Waiting",
        _ => "Not run",
    };
    let st_l = w::label(st, "st");
    st_l.set_xalign(0.0);
    tile.append(&st_l);
    let t = w::label(title, "light-title");
    t.set_xalign(0.0);
    t.set_wrap(true);
    tile.append(&t);
    let s = w::label(sub, "light-sub");
    s.set_xalign(0.0);
    s.set_wrap(true);
    s.set_vexpand(true);
    tile.append(&s);
    tile
}

fn incident_check(state: &str, text: &str) -> gtk::Box {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 10);
    row.add_css_class("check");
    let mark = match state {
        "ok" => "✓",
        "bad" => "✕",
        "warn" => "!",
        _ => "…",
    };
    let m = w::label(mark, state);
    row.append(&m);
    let t = gtk::Label::new(Some(text));
    t.set_xalign(0.0);
    t.set_wrap(true);
    t.set_hexpand(true);
    row.append(&t);
    row
}

fn fill_verify_idle() {
    let _ = with_ui(|ui| {
        while let Some(c) = ui.verify_board.child_at_index(0) {
            ui.verify_board.remove(&c);
        }
        for (st, title, sub) in [
            ("idle", "Nyx TV starts", "Not run yet"),
            ("idle", "Home button", "Not run yet"),
            ("idle", "Stock launcher", "Not run yet"),
            ("idle", "Other apps", "Settings, Play Store"),
            ("idle", "Debugging", "Not checked"),
            ("idle", "Restart test", "Optional"),
        ] {
            ui.verify_board.insert(&light_tile(st, title, sub), -1);
        }
    });
}

fn fill_repair_placeholder() {
    let _ = with_ui(|ui| {
        while let Some(c) = ui.repair_findings.first_child() {
            ui.repair_findings.remove(&c);
        }
        let online = ui.state.borrow().online;
        let o = ui.state.borrow().overview.clone();
        let had_snapshot = ui
            .state
            .borrow()
            .last_snapshot
            .is_some()
            || devices::repo_root().join("audit").is_dir();
        if let Some(o) = o {
            let stock_ok = o.stock_disabled();
            let nyx_home = o.nyx_is_home();
            if nyx_home {
                ui.repair_badge.set_text("All good");
                ui.repair_badge.remove_css_class("badge");
                ui.repair_badge.add_css_class("badge");
                ui.repair_badge.add_css_class("ok");
                ui.repair_headline.set_text("Nyx TV is still the home app");
                ui.repair_lede.set_text(if stock_ok {
                    "No repair needed. Stock home is disabled."
                } else {
                    "Nyx TV holds HOME. Stock is still enabled — optional to disable."
                });
            } else if o.nyx_package.is_some() {
                ui.repair_badge.set_text("Needs repair");
                ui.repair_badge.remove_css_class("ok");
                ui.repair_badge.remove_css_class("wait");
                let (headline, lede) = if had_snapshot && !stock_ok {
                    (
                        "Google TV home is back",
                        "HOME no longer points at Nyx TV (often after a firmware update). Repair saves a new snapshot, then sets Nyx TV as home.",
                    )
                } else {
                    (
                        "Nyx TV isn't the home app yet",
                        "Nyx TV is installed, but HOME still points elsewhere. Repair sets Nyx TV as home the same way Set as home does.",
                    )
                };
                ui.repair_headline.set_text(headline);
                ui.repair_lede.set_text(lede);
            } else {
                ui.repair_badge.set_text("Install first");
                ui.repair_badge.add_css_class("wait");
                ui.repair_headline.set_text("Nyx TV is not installed");
                ui.repair_lede.set_text(
                    "Install Nyx TV from Install and update, then set it as home.",
                );
            }
            ui.repair_findings.append(&incident_check(
                if stock_ok { "ok" } else { "bad" },
                if stock_ok {
                    "Google TV home is disabled"
                } else {
                    "Google TV home is enabled"
                },
            ));
            ui.repair_findings.append(&incident_check(
                if o.nyx_package.is_some() { "ok" } else { "bad" },
                if o.nyx_package.is_some() {
                    "Nyx TV is installed"
                } else {
                    "Nyx TV is not installed"
                },
            ));
            let home_finding = if nyx_home {
                "HOME resolves to Nyx TV".to_string()
            } else {
                format!("HOME · {}", o.home_label())
            };
            ui.repair_findings.append(&incident_check(
                if nyx_home { "ok" } else { "bad" },
                &home_finding,
            ));
            ui.repair_findings.append(&incident_check(
                "idle",
                &format!("Firmware · {}", o.fingerprint),
            ));
        } else if online {
            ui.repair_badge.set_text("Checking");
            ui.repair_badge.add_css_class("wait");
            ui.repair_headline.set_text("Refreshing TV state");
            ui.repair_lede.set_text("");
            ui.repair_findings
                .append(&incident_check("wait", "Refreshing TV state"));
        } else {
            ui.repair_badge.set_text("Offline");
            ui.repair_headline.set_text("This TV isn't answering");
            ui.repair_lede
                .set_text("Connect the TV over wireless debugging to repair.");
            ui.repair_findings
                .append(&incident_check("bad", "TV offline"));
        }
    });
}

fn refresh_restore_list() {
    let _ = with_ui(|ui| {
        while let Some(c) = ui.restore_strip.first_child() {
            ui.restore_strip.remove(&c);
        }
        ui.restore_detail_title.set_text("Loading snapshots…");
        ui.restore_detail_sub.set_text("");
        ui.restore_detail_body.set_text("");
        let serial = ui.state.borrow().selected.clone();
        std::thread::spawn(move || {
            let rows: Vec<(String, String, String, PathBuf, bool)> = (|| -> Result<_> {
                let serial = serial.ok_or_else(|| anyhow::anyhow!("no TV"))?;
                let tv = Tv::new(&serial)?.with_root(devices::repo_root());
                let fp = tv.overview()?.fingerprint;
                let mut out = Vec::new();
                for (path, snap) in tv.list_snapshots()? {
                    let name = path
                        .file_stem()
                        .map(|s| s.to_string_lossy().into_owned())
                        .unwrap_or_else(|| "snapshot".into());
                    let match_fp = snap.fingerprint == fp;
                    let preview = format!(
                        "home {}\n{}",
                        snap.home,
                        if match_fp {
                            "this TV"
                        } else {
                            "other firmware"
                        }
                    );
                    let when = path
                        .file_name()
                        .map(|s| s.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    out.push((name, preview, when, path, match_fp));
                }
                Ok(out)
            })()
            .unwrap_or_default();
            glib::idle_add_once(move || {
                let _ = with_ui(|ui| {
                    if rows.is_empty() {
                        ui.restore_detail_title.set_text("No snapshots yet");
                        ui.restore_detail_sub.set_text("");
                        ui.restore_detail_body.set_text(
                            "Set as home saves one automatically. You can also take a snapshot now — only matching firmware can be restored.",
                        );
                        let take = gtk::Button::with_label("Take a snapshot");
                        take.add_css_class("suggested-action");
                        take.set_halign(gtk::Align::Start);
                        take.connect_clicked(|_| {
                            run_op("Snapshot", || {
                                let msg = snapshot_op()?;
                                glib::idle_add_once(|| {
                                    push_activity("Took a launcher snapshot", "");
                                    refresh_restore_list();
                                });
                                Ok(msg)
                            })
                        });
                        ui.restore_strip.append(&take);
                        return;
                    }
                    let first_ok = rows.iter().position(|r| r.4);
                    for (i, (title, preview, when, path, ok)) in rows.into_iter().enumerate() {
                        let shot = gtk::Box::new(gtk::Orientation::Vertical, 8);
                        shot.add_css_class("shot");
                        if !ok {
                            shot.add_css_class("dim");
                        }
                        if Some(i) == first_ok {
                            shot.add_css_class("sel");
                        }
                        let prev = gtk::Label::new(Some(&preview));
                        prev.add_css_class("preview");
                        prev.set_justify(gtk::Justification::Center);
                        prev.set_halign(gtk::Align::Fill);
                        prev.set_valign(gtk::Align::Center);
                        shot.append(&prev);
                        let t = gtk::Label::new(Some(&title));
                        t.set_markup(&format!("<b>{title}</b>"));
                        t.set_xalign(0.0);
                        shot.append(&t);
                        let s = w::label(&when, "dim2");
                        s.set_xalign(0.0);
                        s.set_ellipsize(gtk::pango::EllipsizeMode::End);
                        shot.append(&s);

                        if ok {
                            let p = path.clone();
                            let title_c = title.clone();
                            let when_c = when.clone();
                            let shot_w = shot.clone();
                            let click = gtk::GestureClick::new();
                            click.connect_released(move |_, n, _, _| {
                                if n != 1 {
                                    return;
                                }
                                match Tv::load_snapshot(&p) {
                                    Ok(snap) => {
                                        let _ = with_ui(|ui| {
                                            ui.state.borrow_mut().last_snapshot = Some(snap);
                                            ui.restore_detail_title.set_text(&title_c);
                                            ui.restore_detail_sub
                                                .set_text(&format!("Firmware match · {when_c}"));
                                            ui.restore_detail_body.set_text(
                                                "Turns Google TV home back on and makes it the home app. Nyx TV stays installed unless you remove it.",
                                            );
                                            let mut child = ui.restore_strip.first_child();
                                            while let Some(c) = child {
                                                c.remove_css_class("sel");
                                                child = c.next_sibling();
                                            }
                                            shot_w.add_css_class("sel");
                                        });
                                    }
                                    Err(e) => toast(&format!("Couldn’t load snapshot: {e:#}")),
                                }
                            });
                            shot.add_controller(click);
                            shot.set_cursor_from_name(Some("pointer"));
                        }

                        if Some(i) == first_ok {
                            let p = path.clone();
                            let title_c = title.clone();
                            let when_c = when.clone();
                            glib::idle_add_once(move || {
                                let _ = with_ui(|ui| {
                                    if let Ok(snap) = Tv::load_snapshot(&p) {
                                        ui.state.borrow_mut().last_snapshot = Some(snap);
                                    }
                                    ui.restore_detail_title.set_text(&title_c);
                                    ui.restore_detail_sub
                                        .set_text(&format!("Firmware match · {when_c}"));
                                    ui.restore_detail_body.set_text(
                                        "Turns Google TV home back on and makes it the home app. Nyx TV stays installed unless you remove it.",
                                    );
                                });
                            });
                        }

                        ui.restore_strip.append(&shot);
                    }
                });
            });
        });
    });
}

fn backups_page() -> (gtk::Box, gtk::Button, gtk::Label, gtk::Label) {
    let col = gtk::Box::new(gtk::Orientation::Vertical, 0);
    col.set_vexpand(false);
    col.set_valign(gtk::Align::Start);

    let import = gtk::Button::with_label("Import…");
    import.set_valign(gtk::Align::Center);
    import.connect_clicked(|_| import_settings_dialog());
    let (bar, backups_sub) = content_page_bar("Backups", "No TV", &[&import]);
    col.append(&bar);

    let lib = gtk::FlowBox::new();
    lib.add_css_class("tm-lib");
    lib.set_selection_mode(gtk::SelectionMode::None);
    lib.set_homogeneous(true);
    lib.set_column_spacing(12);
    lib.set_row_spacing(12);
    lib.set_max_children_per_line(4);
    lib.set_min_children_per_line(1);

    let layout = file_tile(
        "view-list-symbolic",
        "Layout",
        "Rows, favorites, theme",
        false,
    );
    let export = gtk::Button::with_label("Export…");
    export.add_css_class("suggested-action");
    export.connect_clicked(|_| export_settings_dialog());
    layout.append(&export);
    lib.insert(&layout, -1);

    let snaps = file_tile(
        "document-open-recent-symbolic",
        "Snapshots",
        "System snapshots for this TV",
        false,
    );
    let export_snaps = gtk::Button::with_label("Export…");
    export_snaps.connect_clicked(|_| export_snapshots_dialog());
    snaps.append(&export_snaps);
    lib.insert(&snaps, -1);

    let copy = file_tile(
        "edit-copy-symbolic",
        "Copy to another TV",
        "Exports layout from this TV and imports it on another now",
        false,
    );
    let copy_btn = gtk::Button::with_label("Copy now");
    copy_btn.connect_clicked(|_| copy_layout_to_other());
    copy.append(&copy_btn);
    let copy_note = w::label("Add another TV to copy a layout between them.", "file-sub");
    copy_note.set_xalign(0.0);
    copy.append(&copy_note);
    lib.insert(&copy, -1);

    let plex = file_tile(
        "security-high-symbolic",
        "Plex stays out",
        "Sign-in is never in a backup.",
        true,
    );
    lib.insert(&plex, -1);

    col.append(&lib);
    (col, copy_btn, copy_note, backups_sub)
}

fn file_tile(icon: &str, title: &str, sub: &str, note: bool) -> gtk::Box {
    let tile = gtk::Box::new(gtk::Orientation::Vertical, 8);
    tile.add_css_class("tm-file");
    if note {
        tile.add_css_class("note");
    }
    let ic = gtk::CenterBox::new();
    ic.add_css_class("ic");
    ic.set_halign(gtk::Align::Start);
    ic.set_size_request(36, 36);
    let img = gtk::Image::from_icon_name(icon);
    img.set_pixel_size(18);
    ic.set_center_widget(Some(&img));
    tile.append(&ic);
    let t = gtk::Label::new(Some(title));
    t.set_markup(&format!("<b>{title}</b>"));
    t.set_xalign(0.0);
    tile.append(&t);
    let s = w::label(sub, "file-sub");
    s.set_xalign(0.0);
    s.set_wrap(true);
    s.set_vexpand(true);
    tile.append(&s);
    tile
}

fn diagnostics_page() -> (gtk::Box, [gtk::Label; 4], [gtk::Label; 4], gtk::Label, gtk::Label) {
    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.add_css_class("tm-obs");
    root.set_vexpand(false);
    root.set_valign(gtk::Align::Start);
    root.set_hexpand(true);

    let export = gtk::Button::with_label("Export…");
    export.set_valign(gtk::Align::Center);
    export.connect_clicked(|_| export_diagnostics_dialog());
    let audit = gtk::Button::with_label("Run audit");
    audit.add_css_class("suggested-action");
    audit.set_valign(gtk::Align::Center);
    audit.connect_clicked(|_| {
        run_op("Audit", || {
            let dir = current_tv()?.audit()?;
            Ok(format!("Wrote {}", dir.display()))
        });
        refresh_diagnostics();
    });
    let (bar, diag_sub) = content_page_bar("Diagnostics", "No TV", &[&export, &audit]);
    root.append(&bar);

    let charts = gtk::FlowBox::new();
    charts.add_css_class("charts");
    charts.set_selection_mode(gtk::SelectionMode::None);
    charts.set_homogeneous(true);
    charts.set_column_spacing(12);
    charts.set_row_spacing(12);
    charts.set_max_children_per_line(2);
    charts.set_min_children_per_line(1);

    let mut values = Vec::new();
    let mut subs = Vec::new();
    for title in ["Memory (PSS)", "CPU when idle", "Cold start", "Google TV home"] {
        let chart = gtk::Box::new(gtk::Orientation::Vertical, 8);
        chart.add_css_class("chart");
        let k = w::label(title, "k");
        k.set_xalign(0.0);
        chart.append(&k);
        let v = w::label("—", "v");
        v.set_xalign(0.0);
        chart.append(&v);
        let s = w::label("Connect a TV", "dim2");
        s.set_xalign(0.0);
        s.set_wrap(true);
        chart.append(&s);
        charts.insert(&chart, -1);
        values.push(v);
        subs.push(s);
    }
    root.append(&charts);

    let term = gtk::Label::new(Some(
        "Connect a TV and refresh to pull NyxTV log lines.\nTokens stay out of summaries.",
    ));
    term.add_css_class("drawer");
    term.set_xalign(0.0);
    term.set_yalign(0.0);
    term.set_wrap(true);
    term.set_selectable(true);
    term.set_vexpand(false);
    root.append(&term);

    (
        root,
        values.try_into().unwrap(),
        subs.try_into().unwrap(),
        term,
        diag_sub,
    )
}

fn debloat_page() -> (
    gtk::Box,
    gtk::Box,
    Rc<RefCell<Vec<DebloatRow>>>,
    gtk::Label,
    gtk::Button,
    gtk::Button,
    gtk::Box,
    gtk::Box,
) {
    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.add_css_class("tm-deb");
    root.set_hexpand(true);
    root.set_vexpand(true);

    // Calm full-pane state (scan / empty / error) — no side card.
    let status = gtk::Box::new(gtk::Orientation::Vertical, 10);
    status.add_css_class("tm-deb-status");
    status.set_hexpand(true);
    status.set_vexpand(true);
    status.set_halign(gtk::Align::Fill);
    status.set_valign(gtk::Align::Center);
    let status_title = w::label("Connect a TV", "status-title");
    status_title.set_halign(gtk::Align::Center);
    let status_sub = w::label(
        "Pick a television in the sidebar to scan for packages you can disable.",
        "status-sub",
    );
    status_sub.set_halign(gtk::Align::Center);
    status_sub.set_wrap(true);
    status_sub.set_max_width_chars(42);
    status_sub.set_justify(gtk::Justification::Center);
    status.append(&status_title);
    status.append(&status_sub);

    let list = gtk::Box::new(gtk::Orientation::Vertical, 0);
    list.add_css_class("list");
    list.set_hexpand(true);

    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vscrollbar_policy(gtk::PolicyType::Automatic)
        .vexpand(true)
        .hexpand(true)
        .child(&list)
        .build();
    scroll.add_css_class("tm-deb-scroll");

    let pkgs = Rc::new(RefCell::new(Vec::new()));

    let footer = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    footer.add_css_class("tm-deb-bar");
    footer.set_hexpand(true);

    let will_col = gtk::Box::new(gtk::Orientation::Vertical, 2);
    will_col.set_hexpand(true);
    let will_k = w::label("Selection", "bar-k");
    will_k.set_xalign(0.0);
    let debloat_will = w::label(
        "Tick packages to disable or re-enable. Snapshot first; never uninstalled.",
        "will",
    );
    debloat_will.set_xalign(0.0);
    debloat_will.set_ellipsize(gtk::pango::EllipsizeMode::End);
    debloat_will.set_hexpand(true);
    will_col.append(&will_k);
    will_col.append(&debloat_will);
    footer.append(&will_col);

    let debloat_enable = gtk::Button::with_label("Enable selected");
    debloat_enable.add_css_class("flat");
    debloat_enable.set_valign(gtk::Align::Center);
    debloat_enable.set_visible(false);
    debloat_enable.connect_clicked(|_| confirm_debloat_enable());
    footer.append(&debloat_enable);

    let debloat_action = gtk::Button::with_label("Disable packages");
    debloat_action.add_css_class("suggested-action");
    debloat_action.set_valign(gtk::Align::Center);
    debloat_action.set_visible(false);
    debloat_action.connect_clicked(|_| confirm_debloat());
    footer.append(&debloat_action);

    let content = gtk::Box::new(gtk::Orientation::Vertical, 0);
    content.add_css_class("tm-deb-content");
    content.set_hexpand(true);
    content.set_vexpand(true);
    content.set_visible(false);
    content.append(&scroll);
    content.append(&footer);

    root.append(&status);
    root.append(&content);
    (
        root,
        list,
        pkgs,
        debloat_will,
        debloat_action,
        debloat_enable,
        status,
        content,
    )
}

fn show_debloat_status(title: &str, sub: &str) {
    let _ = with_ui(|ui| {
        while let Some(c) = ui.debloat_status.first_child() {
            ui.debloat_status.remove(&c);
        }
        let t = w::label(title, "status-title");
        t.set_halign(gtk::Align::Center);
        let s = w::label(sub, "status-sub");
        s.set_halign(gtk::Align::Center);
        s.set_wrap(true);
        s.set_max_width_chars(44);
        s.set_justify(gtk::Justification::Center);
        ui.debloat_status.append(&t);
        ui.debloat_status.append(&s);
        ui.debloat_status.set_visible(true);
        ui.debloat_content.set_visible(false);
        ui.debloat_pkgs.borrow_mut().clear();
        while let Some(c) = ui.debloat_list.first_child() {
            ui.debloat_list.remove(&c);
        }
        sync_debloat_action();
    });
}

fn show_debloat_content() {
    let _ = with_ui(|ui| {
        ui.debloat_status.set_visible(false);
        ui.debloat_content.set_visible(true);
    });
}

fn with_ui<R>(f: impl FnOnce(&Ui) -> R) -> Option<R> {
    UI.with(|slot| slot.borrow().as_ref().map(|ui| f(&ui.borrow())))
}

fn with_ui_mut<R>(f: impl FnOnce(&mut Ui) -> R) -> Option<R> {
    UI.with(|slot| slot.borrow().as_ref().map(|ui| f(&mut ui.borrow_mut())))
}

fn set_status(_s: &str) {
    // Status lives in toasts / the sidebar adb foot — no page footer bar.
}

pub fn set_status_pub(s: &str) {
    set_status(s);
}

/// After a TV is paired/connected and saved — reload sidebar and go to Overview.
pub fn after_tv_added(serial: &str) {
    let _ = with_ui_mut(|ui| {
        ui.state.borrow_mut().devices = devices::load();
        ui.state.borrow_mut().selected = Some(serial.to_string());
    });
    refresh_tv_list();
    show_section("overview");
    refresh_overview();
    crate::add::refresh();
}

fn toast(msg: &str) {
    let _ = with_ui(|ui| {
        nyx_ui::toast::show(&ui.overlay, msg);
    });
}

fn current_serial() -> Result<String> {
    with_ui(|ui| ui.state.borrow().selected.clone())
        .flatten()
        .ok_or_else(|| anyhow::anyhow!("No TV selected — add one from the sidebar"))
}

fn current_tv() -> Result<Tv> {
    let serial = current_serial()?;
    Ok(Tv::new(serial)?.with_root(devices::repo_root()))
}

fn run_op(name: &str, f: impl FnOnce() -> Result<String> + Send + 'static) {
    let name = name.to_string();
    set_status(&format!("{name}"));
    let is_install =
        name.contains("Install") || name.contains("Update") || name.contains("APK");
    if is_install {
        let _ = with_ui(|ui| {
            ui.install_term.set_visible(true);
            ui.install_term
                .set_text(&format!("{name} in progress…"));
        });
    }
    let (tx, rx) = async_channel::unbounded::<Result<String>>();
    let name_t = name.clone();
    std::thread::spawn(move || {
        let r = f().map(|detail| {
            if detail.is_empty() {
                format!("{name_t} done")
            } else {
                format!("{name_t} done · {detail}")
            }
        });
        let _ = tx.send_blocking(r);
    });
    glib::spawn_future_local(async move {
        if let Ok(r) = rx.recv().await {
            match r {
                Ok(msg) => {
                    let short = msg.lines().next().unwrap_or("Done");
                    set_status(short);
                    toast(short);
                    if is_install {
                        let _ = with_ui(|ui| {
                            ui.install_term.set_visible(true);
                            ui.install_term.set_text(short);
                        });
                    }
                }
                Err(e) => {
                    let msg = format!("{name} failed: {e}");
                    set_status(&msg);
                    toast(&msg);
                    if is_install {
                        let _ = with_ui(|ui| {
                            ui.install_term.set_visible(true);
                            ui.install_term.set_text(&msg);
                        });
                    }
                }
            }
            refresh_overview();
            if is_install {
                refresh_install_apks();
            }
        }
    });
}

fn confirm_then(title: &str, body: &str, on_yes: impl FnOnce() + 'static) {
    let _ = with_ui(|ui| {
        let dialog = gtk::AlertDialog::builder()
            .modal(true)
            .message(title)
            .detail(body)
            .buttons(["Cancel", "Continue"])
            .cancel_button(0)
            .default_button(1)
            .build();
        let window = ui.window.clone();
        let on_yes = RefCell::new(Some(on_yes));
        dialog.choose(Some(&window), None::<&gio::Cancellable>, move |res| {
            if res.ok() == Some(1) {
                if let Some(f) = on_yes.borrow_mut().take() {
                    f();
                }
            }
        });
    });
}

fn show_section(id: &str) {
    let _ = with_ui_mut(|ui| {
        ui.state.borrow_mut().section = id.to_string();
        // Sidebar Launcher always opens the hub; subflows use show_launcher_view.
        if id == "launcher" {
            ui.state.borrow_mut().launcher_view = "hub".into();
            ui.launcher_stack.set_visible_child_name("hub");
        }
        ui.stack.set_visible_child_name(id);
        ui.add_nav.set_active(id == "add");
        for (sid, item) in &ui.nav {
            item.set_active(sid == id);
        }
    });
    refresh_tv_list();
    if id == "add" {
        crate::add::refresh();
    }
    if id == "diagnostics" {
        refresh_diagnostics();
    }
    if id == "backups" {
        refresh_copy_target();
    }
    if id == "debloat" {
        refresh_debloat_presence();
    }
    if id == "install" {
        refresh_install_apks();
        refresh_install_on_tv();
    }
    refresh_page_bar();
}

/// Rescan local release/debug APKs (F5 / Install show). Paths are not frozen at window build.
fn refresh_install_apks() {
    let src = with_ui_mut(|ui| {
        let root = devices::repo_root();
        let release = find_local_apk(&root, false);
        let debug = find_local_apk(&root, true);
        let rel_info = release.as_ref().map(|p| apk_info(p));
        let dbg_info = debug.as_ref().map(|p| apk_info(p));
        let (rel_title, rel_sub) = match (&rel_info, &dbg_info) {
            (Some(i), _) => (
                i.version_name.clone(),
                format!("Latest · {} · {}", i.size_label, i.modified_label),
            ),
            (None, Some(i)) => (
                format!("{} (debug)", i.version_name),
                format!("Debug APK · {} · {}", i.size_label, i.modified_label),
            ),
            (None, None) => (
                "No local APK".into(),
                "Build release/debug or pick a file".into(),
            ),
        };
        ui.install_rel_title
            .set_markup(&format!("<b>{rel_title}</b>"));
        ui.install_rel_sub.set_text(&rel_sub);
        ui.install_release = release;
        ui.install_debug = debug;
        *ui.install_source.borrow()
    })
    .unwrap_or(InstallSource::Release);
    select_install_source(src);
}

fn refresh_page_bar() {
    let _ = with_ui(|ui| {
        let st = ui.state.borrow();
        let nick = st
            .selected
            .as_ref()
            .and_then(|s| {
                st.devices.devices.iter().find(|d| &d.serial == s).map(|d| {
                    if d.nickname.is_empty() {
                        d.serial.clone()
                    } else {
                        d.nickname.clone()
                    }
                })
            })
            .unwrap_or_else(|| "No TV".into());
        let online = st.online;
        drop(st);

        ui.verify_sub.set_text(&nick);
        ui.backups_sub.set_text(&nick);
        ui.diag_sub.set_text(&format!(
            "{nick} · {}",
            if online { "live" } else { "offline" }
        ));
        ui.section_heading.set_text(&nick.to_uppercase());
    });
}

fn refresh_tv_list() {
    let adb_online: std::collections::HashSet<String> = Adb::devices()
        .unwrap_or_default()
        .into_iter()
        .filter(|d| d.state == "device")
        .map(|d| d.serial)
        .collect();

    let _ = with_ui_mut(|ui| {
        while let Some(c) = ui.tv_box.first_child() {
            ui.tv_box.remove(&c);
        }
        let devices = ui.state.borrow().devices.devices.clone();
        let selected = ui.state.borrow().selected.clone();
        // Mockup: TV row is .active only when a TV section is open — not on Add.
        let section = ui.state.borrow().section.clone();
        let mark_tv_active = section != "add";
        for d in devices {
            let row = gtk::Button::new();
            row.add_css_class("nav-item");
            row.add_css_class("tv-row");
            let is_sel = selected.as_deref() == Some(d.serial.as_str());
            if is_sel && mark_tv_active {
                row.add_css_class("active");
            }
            let is_on = adb_online.contains(&d.serial);
            let content = gtk::Box::new(gtk::Orientation::Horizontal, 10);
            let dot = gtk::Box::new(gtk::Orientation::Horizontal, 0);
            dot.add_css_class("cdot");
            if is_on {
                dot.add_css_class("on");
            }
            dot.set_valign(gtk::Align::Center);
            content.append(&dot);
            let name = if d.nickname.is_empty() {
                d.serial.clone()
            } else {
                d.nickname.clone()
            };
            let label = gtk::Label::new(Some(&name));
            label.set_xalign(0.0);
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
            row.set_tooltip_text(Some(&name));
            let serial = d.serial.clone();
            row.connect_clicked(glib::clone!(
                #[strong]
                serial,
                move |_| select_tv(&serial)
            ));

            let menu_serial = d.serial.clone();
            let menu_name = name.clone();
            nyx_ui::menu::on_right_click(&row, move |w, x, y| {
                let serial = menu_serial.clone();
                let serial_rm = menu_serial.clone();
                nyx_ui::menu::popup_at(
                    w,
                    x,
                    y,
                    vec![
                        nyx_ui::menu::heading(&menu_name),
                        nyx_ui::menu::item("Rename", move || rename_tv(&serial))
                            .icon("document-edit-symbolic"),
                        nyx_ui::menu::separator(),
                        nyx_ui::menu::item("Remove from Manager", move || remove_tv(&serial_rm))
                            .icon("user-trash-symbolic")
                            .danger(),
                    ],
                );
            });

            ui.tv_box.append(&row);
        }
        ui.sidebar.refresh();
    });
}

fn select_tv(serial: &str) {
    let _ = with_ui_mut(|ui| {
        ui.state.borrow_mut().selected = Some(serial.to_string());
        ui.state.borrow_mut().devices.selected = Some(serial.to_string());
        ui.state.borrow_mut().verify_has_run = false;
        ui.state.borrow_mut().last_snapshot = None;
        let _ = devices::save(&ui.state.borrow().devices);
    });
    // Selecting a saved TV opens its Overview (mockup: TV row is the section context).
    show_section("overview");
    refresh_overview();
}

fn refresh_all() {
    update_adb_foot();
    crate::add::refresh();
    let (section, launcher_view) = with_ui(|ui| {
        let st = ui.state.borrow();
        (st.section.clone(), st.launcher_view.clone())
    })
    .unwrap_or_default();
    if section != "add" {
        refresh_overview();
    }
    match section.as_str() {
        "install" => {
            refresh_install_apks();
            refresh_install_on_tv();
        }
        "diagnostics" => refresh_diagnostics(),
        "debloat" => refresh_debloat_presence(),
        "backups" => refresh_copy_target(),
        "launcher" => match launcher_view.as_str() {
            "replace" => refresh_replace_diff(),
            "restore" => refresh_restore_list(),
            "repair" => fill_repair_placeholder(),
            _ => {}
        },
        _ => {}
    }
}

fn update_adb_foot() {
    std::thread::spawn(|| {
        let (msg, ok) = match Adb::devices() {
            Ok(list) => {
                let n = list.iter().filter(|d| d.state == "device").count();
                let ver = adb_version_short();
                let msg = if ver.is_empty() {
                    format!("adb · {n} device{}", if n == 1 { "" } else { "s" })
                } else {
                    format!("adb {ver} · {n} device{}", if n == 1 { "" } else { "s" })
                };
                (msg, true)
            }
            Err(_) => ("adb · not found".into(), false),
        };
        glib::idle_add_once(move || {
            let _ = with_ui(|ui| {
                ui.adb_foot.set_text(&msg);
                if let Some(parent) = ui.adb_foot.parent().and_downcast::<gtk::Box>() {
                    if let Some(img) = parent.first_child().and_downcast::<gtk::Image>() {
                        img.set_visible(ok);
                    }
                }
            });
        });
    });
}

fn adb_version_short() -> String {
    let Ok(line) = Adb::version_line() else {
        return String::new();
    };
    // "Android Debug Bridge version 1.0.41" → "1.0.41"
    line.split_whitespace().last().unwrap_or("").to_string()
}

fn set_metrics(online: bool, values: &[&str; 4], subs: &[&str; 4]) {
    let _ = with_ui(|ui| {
        for i in 0..4 {
            if online {
                ui.diag_values[i].set_text(values[i]);
                let sub = if subs[i].is_empty() { " " } else { subs[i] };
                ui.diag_subs[i].set_text(sub);
                ui.diag_subs[i].set_visible(true);
            } else {
                ui.diag_values[i].set_text("—");
                ui.diag_subs[i].set_text("Offline");
            }
        }
    });
}

fn apply_quick_metrics(m: &QuickMetrics) {
    set_metrics(
        true,
        &[&m.memory, &m.cpu, &m.cold, &m.stock],
        &[&m.memory_sub, &m.cpu_sub, &m.cold_sub, &m.stock_sub],
    );
}

fn feed_row(time: &str, text: &str, extra: &str) -> gtk::Box {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    row.add_css_class("tm-feed-row");
    let t = w::label(time, "t");
    t.set_xalign(0.0);
    row.append(&t);
    let body = gtk::Label::new(Some(text));
    body.set_xalign(0.0);
    body.set_hexpand(true);
    body.set_ellipsize(gtk::pango::EllipsizeMode::End);
    row.append(&body);
    let x = w::label(extra, "x");
    x.set_xalign(1.0);
    row.append(&x);
    row
}

fn rebuild_overview_feed(ui: &Ui) {
    while let Some(c) = ui.overview_feed.first_child() {
        ui.overview_feed.remove(&c);
    }
    let activity = ui.state.borrow().activity.clone();
    if activity.is_empty() {
        let empty = w::label("No actions yet on this computer.", "dim2");
        empty.set_xalign(0.0);
        empty.set_wrap(true);
        empty.set_margin_top(4);
        ui.overview_feed.append(&empty);
        return;
    }
    for (t, msg, x) in activity.iter().rev().take(8) {
        ui.overview_feed.append(&feed_row(t, msg, x));
    }
}

pub fn push_activity(text: &str, extra: &str) {
    let now = chrono_ish_now();
    let _ = with_ui(|ui| {
        ui.state
            .borrow_mut()
            .activity
            .push((now, text.to_string(), extra.to_string()));
        rebuild_overview_feed(ui);
    });
}

fn chrono_ish_now() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // Local-ish HH:MM from UTC offset approximation via localtime via libc-free: use formatted epoch mod day.
    // Prefer `date` isn't needed — use glib DateTime.
    glib::DateTime::now_local()
        .ok()
        .and_then(|d| d.format("%H:%M").ok())
        .map(|g| g.to_string())
        .unwrap_or_else(|| format!("{:02}:{:02}", (secs / 3600) % 24, (secs / 60) % 60))
}

fn overview_primary_clicked() {
    let mode = with_ui(|ui| overview_mode(ui)).unwrap_or(OverviewMode::Empty);
    match mode {
        OverviewMode::Empty => show_section("add"),
        OverviewMode::Offline => refresh_overview(),
        OverviewMode::NotInstalled => {
            show_section("install");
            if find_local_apk(&devices::repo_root(), false).is_some() {
                run_op("Install release APK", install_release_apk);
            } else {
                run_op("Install debug APK", install_debug_apk);
            }
        }
        OverviewMode::SetHome => show_launcher_view("replace"),
        OverviewMode::AllGood => show_launcher_view("verify"),
    }
}

fn overview_ghost_clicked() {
    let mode = with_ui(|ui| overview_mode(ui)).unwrap_or(OverviewMode::Empty);
    match mode {
        OverviewMode::Empty | OverviewMode::Offline => show_section("add"),
        OverviewMode::NotInstalled => show_section("install"),
        OverviewMode::SetHome => {
            show_section("launcher");
            show_launcher_view("verify");
        }
        OverviewMode::AllGood => show_section("diagnostics"),
    }
}

#[derive(Clone, Copy)]
enum OverviewMode {
    Empty,
    Offline,
    NotInstalled,
    SetHome,
    AllGood,
}

fn overview_mode(ui: &Ui) -> OverviewMode {
    let st = ui.state.borrow();
    if st.devices.devices.is_empty() {
        return OverviewMode::Empty;
    }
    if !st.online {
        return OverviewMode::Offline;
    }
    match &st.overview {
        None => OverviewMode::Offline,
        Some(o) if o.nyx_package.is_none() => OverviewMode::NotInstalled,
        Some(o) if o.nyx_is_home() => OverviewMode::AllGood,
        Some(_) => OverviewMode::SetHome,
    }
}

fn apply_overview_console(ui: &Ui) {
    let (nick, online, o) = {
        let st = ui.state.borrow();
        let nick = st
            .selected
            .as_ref()
            .and_then(|s| {
                st.devices.devices.iter().find(|d| &d.serial == s).map(|d| {
                    if d.nickname.is_empty() {
                        d.serial.clone()
                    } else {
                        d.nickname.clone()
                    }
                })
            })
            .unwrap_or_else(|| "No TV".into());
        (nick, st.online, st.overview.clone())
    };
    let mode = overview_mode(ui);

    let (eyebrow, headline, lede, primary, ghost, ghost_vis): (
        String,
        String,
        String,
        String,
        String,
        bool,
    ) = match mode {
        OverviewMode::Empty => (
            "No TV · —".to_string(),
            "Add a TV to begin".to_string(),
            "Pair a TV over wireless debugging, then install Nyx TV and set it as home.".to_string(),
            "Add a TV".to_string(),
            "Learn more".to_string(),
            false,
        ),
        OverviewMode::Offline => (
            format!("{nick} · unreachable"),
            "This TV isn't answering".to_string(),
            "Last known over wireless debugging. Reconnect, or pick another TV from the sidebar."
                .to_string(),
            "Try again".to_string(),
            "Add a different TV".to_string(),
            true,
        ),
        OverviewMode::NotInstalled => {
            let model = o.as_ref().map(|x| x.model.clone()).unwrap_or_default();
            (
                format!("{nick} · connected"),
                "Nyx TV isn't installed yet".to_string(),
                format!(
                    "{}Install Nyx TV, then set it as the home app.",
                    if model.is_empty() {
                        String::new()
                    } else {
                        format!("{model}. ")
                    }
                ),
                "Install Nyx TV".to_string(),
                "Open Install".to_string(),
                true,
            )
        }
        OverviewMode::SetHome => {
            let ver = o
                .as_ref()
                .and_then(|x| x.nyx_version.clone())
                .unwrap_or_else(|| "installed".to_string());
            (
                format!("{nick} · connected"),
                "Nyx TV isn't the home app yet".to_string(),
                format!(
                    "Nyx TV {ver} is installed. The HOME role still points at Google TV. One step finishes setup."
                ),
                "Set Nyx TV as home".to_string(),
                "Verify first".to_string(),
                true,
            )
        }
        OverviewMode::AllGood => {
            let ver = o
                .as_ref()
                .and_then(|x| x.nyx_version.clone())
                .unwrap_or_else(|| "installed".to_string());
            let stock_note = if o.as_ref().map(|x| x.stock_disabled()).unwrap_or(false) {
                "Google TV home is disabled and can be restored anytime."
            } else {
                "Google TV home is still enabled — consider disabling stock from Set as home if Home ever opens the wrong app."
            };
            (
                format!("{nick} · connected"),
                "Nyx TV is the home app".to_string(),
                format!("Nyx TV {ver} is home. {stock_note}"),
                "Run verify".to_string(),
                "Diagnostics".to_string(),
                true,
            )
        }
    };

    ui.overview_eyebrow.set_text(&eyebrow);
    ui.overview_headline.set_text(&headline);
    ui.overview_lede.set_text(&lede);
    ui.overview_cta_primary.set_label(&primary);
    ui.overview_cta_ghost.set_label(&ghost);
    ui.overview_cta_ghost.set_visible(ghost_vis);

    while let Some(c) = ui.overview_meta.first_child() {
        ui.overview_meta.remove(&c);
    }
    if let Some(o) = &o {
        if online {
            ui.overview_meta.append(&meta_chip(
                "Model",
                &format!("{} · Android TV {}", o.model, o.android_version),
            ));
            ui.overview_meta.append(&meta_chip(
                "Nyx TV",
                o.nyx_version.as_deref().unwrap_or("—"),
            ));
            ui.overview_meta.append(&meta_chip(
                "Home",
                o.home_label(),
            ));
        }
    }

    // Hub Now node + primary CTA from live state (not a fixed mockup label).
    match mode {
        OverviewMode::AllGood => {
            ui.hub_now_title.set_text("Nyx TV home");
            let stock = o
                .as_ref()
                .map(|x| {
                    if x.stock_disabled() {
                        "Stock disabled"
                    } else {
                        "Stock still enabled"
                    }
                })
                .unwrap_or("Connected");
            ui.hub_now_sub.set_text(stock);
            ui.hub_primary.set_label("Run verify");
            ui.hub_primary.set_sensitive(true);
            ui.hub_primary.set_visible(true);
        }
        OverviewMode::SetHome => {
            ui.hub_now_title.set_text("Google TV home");
            ui.hub_now_sub.set_text("Nyx TV installed");
            ui.hub_primary.set_label("Set Nyx TV as home");
            ui.hub_primary.set_sensitive(true);
            ui.hub_primary.set_visible(true);
        }
        OverviewMode::NotInstalled => {
            ui.hub_now_title.set_text("Google TV home");
            ui.hub_now_sub.set_text("Nyx TV missing");
            ui.hub_primary.set_label("Install Nyx TV");
            ui.hub_primary.set_sensitive(true);
            ui.hub_primary.set_visible(true);
        }
        OverviewMode::Offline => {
            ui.hub_now_title.set_text("Unreachable");
            ui.hub_now_sub.set_text("Reconnect");
            ui.hub_primary.set_label("Try reconnect");
            ui.hub_primary.set_sensitive(true);
            ui.hub_primary.set_visible(true);
        }
        OverviewMode::Empty => {
            ui.hub_now_title.set_text("—");
            ui.hub_now_sub.set_text("");
            ui.hub_primary.set_label("Add a TV");
            ui.hub_primary.set_sensitive(true);
            ui.hub_primary.set_visible(true);
        }
    }

    rebuild_overview_feed(ui);
}

fn meta_chip(k: &str, v: &str) -> gtk::Box {
    let chip = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    chip.add_css_class("meta-chip");
    let key = w::label(k, "meta-k");
    key.set_xalign(0.0);
    let val = gtk::Label::new(Some(v));
    val.add_css_class("meta-v");
    val.set_xalign(0.0);
    chip.append(&key);
    chip.append(&val);
    chip
}

fn refresh_overview() {
    let serial = match current_serial() {
        Ok(s) => s,
        Err(_) => {
            let _ = with_ui(|ui| {
                ui.state.borrow_mut().overview = None;
                ui.state.borrow_mut().online = false;
                apply_overview_console(ui);
            });
            set_metrics(false, &["—", "—", "—", "—"], &["", "", "", ""]);
            refresh_tv_list();
            refresh_page_bar();
            refresh_install_on_tv();
            refresh_replace_diff();
            set_status("Ready");
            return;
        }
    };
    set_status("Refreshing");
    // Show Offline console immediately so we don't flash the empty "Add a TV" stage
    // while adb is still checking the selected TV.
    let _ = with_ui(|ui| {
        ui.state.borrow_mut().online = false;
        apply_overview_console(ui);
    });
    let (tx, rx) = async_channel::unbounded::<Result<(Overview, QuickMetrics)>>();
    std::thread::spawn(move || {
        let r = (|| {
            let listed = Adb::devices().unwrap_or_default();
            let ready = listed
                .iter()
                .any(|d| d.serial == serial && d.state == "device");
            if !ready {
                anyhow::bail!("TV is offline or not connected over adb");
            }
            let tv = Tv::new(&serial)?.with_root(devices::repo_root());
            let o = tv.overview()?;
            let m = tv.quick_metrics().unwrap_or_default();
            Ok((o, m))
        })();
        let _ = tx.send_blocking(r);
    });
    glib::spawn_future_local(async move {
        if let Ok(r) = rx.recv().await {
            match r {
                Ok((o, metrics)) => {
                    let _ = with_ui(|ui| {
                        ui.state.borrow_mut().overview = Some(o);
                        ui.state.borrow_mut().online = true;
                        apply_overview_console(ui);
                    });
                    apply_quick_metrics(&metrics);
                    set_status("Ready");
                }
                Err(_) => {
                    let _ = with_ui(|ui| {
                        ui.state.borrow_mut().online = false;
                        ui.state.borrow_mut().overview = None;
                        apply_overview_console(ui);
                    });
                    set_metrics(false, &["—", "—", "—", "—"], &["", "", "", ""]);
                    set_status("Offline");
                }
            }
            refresh_tv_list();
            refresh_page_bar();
            refresh_install_on_tv();
            let on_replace = with_ui(|ui| ui.state.borrow().launcher_view == "replace")
                .unwrap_or(false);
            if on_replace {
                refresh_replace_diff();
            }
        }
    });
}

fn install_debug_apk() -> Result<String> {
    let root = devices::repo_root();
    let apk = find_local_apk(&root, true).ok_or_else(|| {
        anyhow::anyhow!("No debug APK. Run: cd android && ./gradlew :app:assembleDebug")
    })?;
    current_tv()?.install_apk(&apk, true)?;
    Ok(format!("Installed {}", apk.display()))
}

fn install_release_apk() -> Result<String> {
    let root = devices::repo_root();
    let apk = find_local_apk(&root, false).ok_or_else(|| {
        anyhow::anyhow!("No release APK. Run: cd android && ./gradlew :app:assembleRelease")
    })?;
    current_tv()?.install_apk(&apk, true)?;
    Ok(format!("Installed {}", apk.display()))
}

fn pick_apk_and_install() {
    let _ = with_ui(|ui| {
        let filters = gtk::FileFilter::new();
        filters.set_name(Some("APK"));
        filters.add_pattern("*.apk");
        let dialog = gtk::FileDialog::builder()
            .title("Install APK")
            .default_filter(&filters)
            .build();
        dialog.open(Some(&ui.window), None::<&gio::Cancellable>, move |res| {
            if let Ok(file) = res {
                if let Some(path) = file.path() {
                    run_op("Install APK", move || {
                        current_tv()?.install_apk(&path, true)?;
                        Ok(path.display().to_string())
                    });
                }
            }
        });
    });
}

fn uninstall_pkg(package: &str) -> Result<String> {
    current_tv()?.uninstall_package(package, true)?;
    Ok(format!("Uninstalled {package}"))
}

fn snapshot_op() -> Result<String> {
    let tv = current_tv()?;
    let snap = tv.snapshot()?;
    let path = snap
        .path
        .as_ref()
        .map(|p| p.display().to_string())
        .unwrap_or_default();
    let snap_clone = snap.clone();
    glib::idle_add_once(move || {
        let _ = with_ui(|ui| {
            ui.state.borrow_mut().last_snapshot = Some(snap_clone);
        });
    });
    Ok(format!("Saved {path}"))
}

fn replace_launcher() -> Result<String> {
    let tv = current_tv()?;
    let component = tv.home_component_for_installed()?;
    let snap = tv.replace_launcher(&component, true)?;
    let path = snap
        .path
        .as_ref()
        .map(|p| p.display().to_string())
        .unwrap_or_default();
    let snap_clone = snap.clone();
    glib::idle_add_once(move || {
        let _ = with_ui(|ui| {
            ui.state.borrow_mut().last_snapshot = Some(snap_clone);
        });
    });
    let report = tv.verify(&component)?;
    let mut out = format!("Snapshot: {path}\n");
    out.push_str(&report.lines.join("\n"));
    if !report.ok {
        anyhow::bail!("{out}\nverify reported failures");
    }
    Ok(out)
}

fn verify_launcher() -> Result<String> {
    let tv = current_tv()?;
    let serial = tv.serial().to_string();
    let component = tv.home_component_for_installed()?;
    let report = tv.verify(&component)?;
    let lines = report.lines.clone();
    let settings_ok = report.settings_ok;
    let play_ok = report.play_ok;
    let still_connected = Adb::devices()
        .ok()
        .map(|list| {
            list.iter()
                .any(|d| d.serial == serial && d.state == "device")
        })
        .unwrap_or(false);
    glib::idle_add_once(move || {
        let _ = with_ui(|ui| {
            ui.state.borrow_mut().verify_has_run = true;
            while let Some(c) = ui.verify_board.child_at_index(0) {
                ui.verify_board.remove(&c);
            }
            let home = lines
                .iter()
                .find(|l| l.starts_with("Home button"))
                .cloned()
                .unwrap_or_else(|| "Home button".into());
            let home_fail = lines.iter().any(|l| l.contains("FAIL: Home didn't open"));
            let stock = lines
                .iter()
                .find(|l| l.starts_with("stock launcher"))
                .cloned()
                .unwrap_or_else(|| "stock launcher".into());
            let stock_ok = stock.contains("not running");
            let home_again = lines
                .iter()
                .find(|l| l.starts_with("Home again"))
                .cloned()
                .unwrap_or_default();
            let home_again_fail = lines
                .iter()
                .any(|l| l.contains("FAIL: Home didn't return"));

            ui.verify_board.insert(
                &light_tile(
                    if home_again_fail || home_fail {
                        "bad"
                    } else {
                        "ok"
                    },
                    "Nyx TV starts",
                    if home_again.is_empty() {
                        &home
                    } else {
                        &home_again
                    },
                ),
                -1,
            );
            ui.verify_board.insert(
                &light_tile(if home_fail { "bad" } else { "ok" }, "Home button", &home),
                -1,
            );
            ui.verify_board.insert(
                &light_tile(
                    if stock_ok { "ok" } else { "bad" },
                    "Stock launcher",
                    if stock_ok { "Not running" } else { &stock },
                ),
                -1,
            );
            let other_ok = settings_ok && play_ok;
            let other_sub = format!(
                "Settings · {}; Play · {}",
                if settings_ok { "ok" } else { "didn't open" },
                if play_ok { "ok" } else { "didn't open" },
            );
            ui.verify_board.insert(
                &light_tile(
                    if other_ok { "ok" } else { "bad" },
                    "Other apps",
                    &other_sub,
                ),
                -1,
            );
            ui.verify_board.insert(
                &light_tile(
                    if still_connected { "ok" } else { "bad" },
                    "Debugging",
                    if still_connected {
                        "Connected over adb"
                    } else {
                        "TV dropped off adb"
                    },
                ),
                -1,
            );
            let restart = light_tile("idle", "Restart test", "Optional · not checked");
            let btn = gtk::Button::with_label("Restart TV");
            btn.connect_clicked(|_| {
                run_op("Restart TV", || {
                    current_tv()?.adb().shell(&["reboot"])?;
                    Ok("Reboot sent".into())
                })
            });
            restart.append(&btn);
            ui.verify_board.insert(&restart, -1);
        });
    });
    let out = report.lines.join("\n");
    if !report.ok {
        anyhow::bail!("{out}\nverify reported failures");
    }
    Ok(out)
}

fn start_verify() {
    run_op("Verify", verify_launcher);
}

fn run_verify_ui() {
    let _ = with_ui_mut(|ui| {
        ui.state.borrow_mut().launcher_view = "verify".into();
        ui.launcher_stack.set_visible_child_name("verify");
        ui.state.borrow_mut().verify_has_run = false;
    });
    fill_verify_idle();
    start_verify();
    refresh_page_bar();
}

fn restore_launcher() -> Result<String> {
    let tv = current_tv()?;
    let snap = with_ui(|ui| ui.state.borrow().last_snapshot.clone())
        .flatten()
        .ok_or_else(|| anyhow::anyhow!("Select a matching snapshot first"))?;
    let fp = tv.overview()?.fingerprint;
    if snap.fingerprint != fp {
        anyhow::bail!("Selected snapshot is for a different firmware");
    }
    tv.restore(&snap, true)?;
    Ok("Stock launcher restored".into())
}

fn repair_launcher() -> Result<String> {
    let tv = current_tv()?;
    let snap = tv.repair(true)?;
    glib::idle_add_once(move || {
        let _ = with_ui(|ui| {
            ui.state.borrow_mut().last_snapshot = Some(snap);
        });
    });
    let component = tv.home_component_for_installed()?;
    let report = tv.verify(&component)?;
    Ok(report.lines.join("\n"))
}

fn export_settings_dialog() {
    let _ = with_ui(|ui| {
        let dialog = gtk::FileDialog::builder()
            .title("Export Nyx TV settings")
            .initial_name("nyx-tv-settings.json")
            .build();
        dialog.save(Some(&ui.window), None::<&gio::Cancellable>, move |res| {
            if let Ok(file) = res {
                if let Some(path) = file.path() {
                    run_op("Export settings", move || {
                        current_tv()?.export_settings(&path)?;
                        Ok(path.display().to_string())
                    });
                }
            }
        });
    });
}

fn import_settings_dialog() {
    let _ = with_ui(|ui| {
        let filters = gtk::FileFilter::new();
        filters.set_name(Some("JSON"));
        filters.add_pattern("*.json");
        let dialog = gtk::FileDialog::builder()
            .title("Import Nyx TV settings")
            .default_filter(&filters)
            .build();
        dialog.open(Some(&ui.window), None::<&gio::Cancellable>, move |res| {
            if let Ok(file) = res {
                if let Some(path) = file.path() {
                    confirm_then(
                        "Import settings?",
                        "Replaces layout and preferences on the TV. Plex sign-in is not in this file.",
                        move || {
                            run_op("Import settings", move || {
                                current_tv()?.import_settings(&path)?;
                                Ok(path.display().to_string())
                            })
                        },
                    );
                }
            }
        });
    });
}

fn export_diagnostics_dialog() {
    let _ = with_ui(|ui| {
        let dialog = gtk::FileDialog::builder()
            .title("Export diagnostics")
            .build();
        dialog.select_folder(Some(&ui.window), None::<&gio::Cancellable>, move |res| {
            if let Ok(file) = res {
                if let Some(dir) = file.path() {
                    let dest = dir.join(format!(
                        "nyx-tv-diag-{}",
                        std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_secs())
                            .unwrap_or(0)
                    ));
                    run_op("Export diagnostics", move || {
                        let out = current_tv()?.export_diagnostics(&dest)?;
                        Ok(format!("Wrote {}", out.display()))
                    });
                }
            }
        });
    });
}

fn export_snapshots_dialog() {
    let _ = with_ui(|ui| {
        let dialog = gtk::FileDialog::builder()
            .title("Export system snapshots")
            .build();
        dialog.select_folder(Some(&ui.window), None::<&gio::Cancellable>, move |res| {
            if let Ok(file) = res {
                if let Some(dir) = file.path() {
                    run_op("Export snapshots", move || {
                        let tv = current_tv()?;
                        let fp = tv.overview()?.fingerprint;
                        let snaps = tv.list_snapshots()?;
                        let matching: Vec<_> = snaps
                            .into_iter()
                            .filter(|(_, s)| s.fingerprint == fp)
                            .collect();
                        if matching.is_empty() {
                            anyhow::bail!("No snapshots for this TV's firmware under audit/");
                        }
                        let dest = dir.join("nyx-tv-snapshots");
                        std::fs::create_dir_all(&dest)?;
                        for (path, _) in &matching {
                            if let Some(name) = path.file_name() {
                                std::fs::copy(path, dest.join(name))?;
                            }
                        }
                        Ok(format!(
                            "Copied {} snapshot(s) for this TV to {}",
                            matching.len(),
                            dest.display()
                        ))
                    });
                }
            }
        });
    });
}

fn refresh_copy_target() {
    let _ = with_ui(|ui| {
        let selected = ui.state.borrow().selected.clone();
        let others: Vec<_> = ui
            .state
            .borrow()
            .devices
            .devices
            .iter()
            .filter(|d| Some(&d.serial) != selected.as_ref())
            .cloned()
            .collect();
        if others.is_empty() {
            ui.copy_btn.set_visible(false);
            ui.copy_btn.set_sensitive(false);
            ui.copy_btn.set_label("Copy now");
            ui.copy_note
                .set_text("Add another TV to copy a layout between them.");
        } else {
            let target = &others[0];
            let name = if target.nickname.is_empty() {
                target.serial.clone()
            } else {
                target.nickname.clone()
            };
            ui.copy_btn.set_visible(true);
            ui.copy_btn.set_sensitive(true);
            ui.copy_btn.set_label(&format!("Copy to {name} now"));
            let note = if others.len() == 1 {
                format!(
                    "{} · runs immediately · fails if that TV is offline · Plex sign-in is never copied",
                    target.serial
                )
            } else {
                format!(
                    "Copies to {name} (first other TV of {}). Plex sign-in is never copied.",
                    others.len()
                )
            };
            ui.copy_note.set_text(&note);
        }
    });
}

fn copy_layout_to_other() {
    let target = with_ui(|ui| {
        let selected = ui.state.borrow().selected.clone();
        ui.state
            .borrow()
            .devices
            .devices
            .iter()
            .find(|d| Some(&d.serial) != selected.as_ref())
            .map(|d| d.serial.clone())
    })
    .flatten();
    let Some(serial) = target else {
        toast("Add another TV first");
        return;
    };
    run_op("Copy layout", move || copy_layout_to(&serial));
}

fn copy_layout_to(target_serial: &str) -> Result<String> {
    let tmp = devices::repo_root().join("audit").join("layout-copy-tmp.json");
    if let Some(parent) = tmp.parent() {
        std::fs::create_dir_all(parent)?;
    }
    current_tv()?.export_settings(&tmp)?;
    Tv::new(target_serial)?
        .with_root(devices::repo_root())
        .import_settings(&tmp)?;
    let _ = std::fs::remove_file(&tmp);
    Ok(format!("Copied layout to {target_serial}"))
}

fn refresh_diagnostics() {
    let serial = match current_serial() {
        Ok(s) => s,
        Err(_) => {
            let _ = with_ui(|ui| {
                ui.diag_term.set_text(
                    "Connect a TV and refresh to pull NyxTV log lines.\nTokens stay out of summaries.",
                );
            });
            return;
        }
    };
    std::thread::spawn(move || {
        let r = (|| {
            let tv = Tv::new(&serial)?.with_root(devices::repo_root());
            let m = tv.quick_metrics()?;
            let log = tv.recent_log(40).unwrap_or_else(|_| "No log lines.".into());
            Ok::<_, anyhow::Error>((m, log))
        })();
        glib::idle_add_once(move || {
            match r {
                Ok((m, log)) => {
                    apply_quick_metrics(&m);
                    let _ = with_ui(|ui| ui.diag_term.set_text(&log));
                }
                Err(e) => {
                    let _ = with_ui(|ui| {
                        ui.diag_term.set_text(&format!("Could not refresh: {e:#}"));
                    });
                }
            }
        });
    });
}

fn sync_debloat_action() {
    let _ = with_ui(|ui| {
        let rows = ui.debloat_pkgs.borrow();
        let to_disable: Vec<&DebloatRow> = rows
            .iter()
            .filter(|r| {
                matches!(r.kind, DebloatKind::Optional | DebloatKind::Dependent)
                    && r.enabled
                    && r.check.is_active()
            })
            .collect();
        let to_enable: Vec<&DebloatRow> = rows
            .iter()
            .filter(|r| {
                matches!(r.kind, DebloatKind::Optional | DebloatKind::Dependent)
                    && !r.enabled
                    && r.check.is_active()
            })
            .collect();
        let summary = match (to_disable.is_empty(), to_enable.is_empty()) {
            (true, true) => {
                "Tick packages to disable or re-enable. Snapshot first; never uninstalled."
                    .to_string()
            }
            (false, true) => {
                let names = to_disable
                    .iter()
                    .map(|r| r.title.as_str())
                    .collect::<Vec<_>>()
                    .join(", ");
                format!("Disable · {names}")
            }
            (true, false) => {
                let names = to_enable
                    .iter()
                    .map(|r| r.title.as_str())
                    .collect::<Vec<_>>()
                    .join(", ");
                format!("Enable · {names}")
            }
            (false, false) => {
                let d = to_disable
                    .iter()
                    .map(|r| r.title.as_str())
                    .collect::<Vec<_>>()
                    .join(", ");
                let e = to_enable
                    .iter()
                    .map(|r| r.title.as_str())
                    .collect::<Vec<_>>()
                    .join(", ");
                format!("Disable · {d}  ·  Enable · {e}")
            }
        };
        ui.debloat_will.set_text(&summary);
        let n_dis = to_disable.len();
        let n_en = to_enable.len();
        ui.debloat_action.set_visible(n_dis > 0);
        ui.debloat_action.set_sensitive(n_dis > 0);
        ui.debloat_action.set_label(&format!(
            "Disable {n_dis} package{}",
            if n_dis == 1 { "" } else { "s" }
        ));
        ui.debloat_enable.set_visible(n_en > 0);
        ui.debloat_enable.set_sensitive(n_en > 0);
        ui.debloat_enable.set_label(&format!(
            "Enable {n_en} package{}",
            if n_en == 1 { "" } else { "s" }
        ));
    });
}

fn rebuild_debloat_list(packages: &[DebloatPackage]) {
    if packages.is_empty() {
        show_debloat_status(
            "Nothing to list",
            "No known packages on this TV. The catalog covers common TCL / Google TV extras.",
        );
        return;
    }
    let _ = with_ui(|ui| {
        while let Some(c) = ui.debloat_list.first_child() {
            ui.debloat_list.remove(&c);
        }
        ui.debloat_pkgs.borrow_mut().clear();

        let mut last_kind: Option<DebloatKind> = None;
        for pkg in packages {
            if last_kind != Some(pkg.kind) {
                let label = match pkg.kind {
                    DebloatKind::Optional => "Verified optional",
                    DebloatKind::Dependent => "Device dependent",
                    DebloatKind::Protected => "Protected",
                };
                let grp = w::label(label, "grp");
                grp.set_xalign(0.0);
                ui.debloat_list.append(&grp);
                last_kind = Some(pkg.kind);
            }

            let selectable = matches!(pkg.kind, DebloatKind::Optional | DebloatKind::Dependent);
            let row = gtk::Box::new(gtk::Orientation::Horizontal, 10);
            row.add_css_class("pkg");
            if pkg.kind == DebloatKind::Protected {
                row.add_css_class("dim");
            }
            if selectable {
                let cb = gtk::CheckButton::new();
                cb.set_valign(gtk::Align::Center);
                // Optional enabled → selected by default. Dependent → opt-in.
                // Disabled → unchecked (tick to re-enable).
                cb.set_active(pkg.enabled && pkg.kind == DebloatKind::Optional);
                cb.connect_toggled(|_| sync_debloat_action());
                row.append(&cb);
                ui.debloat_pkgs.borrow_mut().push(DebloatRow {
                    package: pkg.package.clone(),
                    title: pkg.title.clone(),
                    kind: pkg.kind,
                    enabled: pkg.enabled,
                    check: cb,
                });
            }
            let tx = gtk::Box::new(gtk::Orientation::Vertical, 1);
            tx.set_hexpand(true);
            let t = w::label(&pkg.title, "pkg-title");
            t.set_xalign(0.0);
            let status_text = match (pkg.kind, pkg.enabled) {
                (DebloatKind::Optional, true) => {
                    format!("{} · can disable", pkg.package)
                }
                (DebloatKind::Optional, false) => {
                    format!("{} · disabled · tick to re-enable", pkg.package)
                }
                (DebloatKind::Dependent, true) => {
                    format!("{} · caution · opt-in", pkg.package)
                }
                (DebloatKind::Dependent, false) => {
                    format!("{} · disabled · tick to re-enable", pkg.package)
                }
                (DebloatKind::Protected, _) => format!("{} · left alone", pkg.package),
            };
            let status = w::label(&status_text, "pkg-id");
            status.set_xalign(0.0);
            status.set_ellipsize(gtk::pango::EllipsizeMode::End);
            tx.append(&t);
            tx.append(&status);
            row.append(&tx);
            ui.debloat_list.append(&row);
        }
    });
    show_debloat_content();
    sync_debloat_action();
}

fn refresh_debloat_presence() {
    let serial = match current_serial() {
        Ok(s) => s,
        Err(_) => {
            show_debloat_status(
                "Connect a TV",
                "Pick a television in the sidebar to scan for packages you can disable.",
            );
            return;
        }
    };
    show_debloat_status(
        "Scanning this TV",
        "Looking for packages from the catalog that are installed here…",
    );
    std::thread::spawn(move || {
        let result = (|| -> anyhow::Result<Vec<DebloatPackage>> {
            let tv = Tv::new(&serial)?.with_root(devices::repo_root());
            tv.debloat_packages()
        })();
        glib::idle_add_once(move || match result {
            Ok(pkgs) => rebuild_debloat_list(&pkgs),
            Err(e) => {
                show_debloat_status("Couldn’t scan", &format!("{e:#}"));
            }
        });
    });
}

fn confirm_debloat() {
    let selected: Vec<(String, String, DebloatKind)> = with_ui(|ui| {
        ui.debloat_pkgs
            .borrow()
            .iter()
            .filter(|r| {
                matches!(r.kind, DebloatKind::Optional | DebloatKind::Dependent)
                    && r.enabled
                    && r.check.is_active()
            })
            .map(|r| (r.package.clone(), r.title.clone(), r.kind))
            .collect()
    })
    .unwrap_or_default();
    if selected.is_empty() {
        toast("Select packages to disable first");
        return;
    }
    let has_dep = selected.iter().any(|(_, _, k)| *k == DebloatKind::Dependent);
    let names = selected
        .iter()
        .map(|(_, t, _)| t.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let n = selected.len();
    let pkgs: Vec<String> = selected.into_iter().map(|(p, _, _)| p).collect();
    let caution = if has_dep {
        " Includes device-dependent packages (assistant / YouTube) — only if you mean it."
    } else {
        ""
    };
    confirm_then(
        &format!("Disable {n} package{}?", if n == 1 { "" } else { "s" }),
        &format!(
            "{names}.{caution} A snapshot is saved first; turn each back on from this page."
        ),
        move || {
            let pkgs = pkgs.clone();
            run_op("Debloat", move || {
                let tv = current_tv()?;
                let _ = tv.snapshot();
                let refs: Vec<&str> = pkgs.iter().map(|s| s.as_str()).collect();
                let done = tv.disable_packages(&refs, true)?;
                glib::idle_add_once(|| {
                    push_activity("Disabled packages", "");
                    refresh_debloat_presence();
                });
                if done.is_empty() {
                    Ok("None of those packages were installed on the TV".into())
                } else {
                    Ok(format!("Disabled {}", done.join(", ")))
                }
            })
        },
    );
}

fn confirm_debloat_enable() {
    let selected: Vec<(String, String)> = with_ui(|ui| {
        ui.debloat_pkgs
            .borrow()
            .iter()
            .filter(|r| {
                matches!(r.kind, DebloatKind::Optional | DebloatKind::Dependent)
                    && !r.enabled
                    && r.check.is_active()
            })
            .map(|r| (r.package.clone(), r.title.clone()))
            .collect()
    })
    .unwrap_or_default();
    if selected.is_empty() {
        toast("Tick a disabled package to re-enable");
        return;
    }
    let names = selected
        .iter()
        .map(|(_, t)| t.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let n = selected.len();
    let pkgs: Vec<String> = selected.into_iter().map(|(p, _)| p).collect();
    confirm_then(
        &format!("Enable {n} package{}?", if n == 1 { "" } else { "s" }),
        &format!("{names}."),
        move || {
            let pkgs = pkgs.clone();
            run_op("Enable packages", move || {
                let tv = current_tv()?;
                let refs: Vec<&str> = pkgs.iter().map(|s| s.as_str()).collect();
                let done = tv.enable_packages(&refs)?;
                glib::idle_add_once(|| {
                    push_activity("Re-enabled packages", "");
                    refresh_debloat_presence();
                });
                if done.is_empty() {
                    Ok("Nothing to enable".into())
                } else {
                    Ok(format!("Enabled {}", done.join(", ")))
                }
            })
        },
    );
}


