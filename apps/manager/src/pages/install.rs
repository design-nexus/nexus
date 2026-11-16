//! Install and update: what's on the TV, what's built here, and the one
//! button that brings them together.

use crate::actions::{self, InstallAction};
use crate::page::{self, Page};
use crate::state::State;
use gtk::prelude::*;
use nyx_tv_device::NYX_TV_PKG_DEBUG;
use std::cell::RefCell;

thread_local! {
    static PAGE: RefCell<Option<Page>> = const { RefCell::new(None) };
}

pub fn build() -> gtk::Widget {
    let p = Page::new("Install and update");
    let root = p.root.clone();
    PAGE.with(|c| *c.borrow_mut() = Some(p));
    root.upcast()
}

fn open_folder(path: &std::path::Path) {
    if let Some(dir) = path.parent() {
        let uri = gtk::gio::File::for_path(dir).uri();
        let _ = gtk::gio::AppInfo::launch_default_for_uri(&uri, None::<&gtk::gio::AppLaunchContext>);
    }
}

pub fn render(st: &State) {
    let Some(p) = PAGE.with(|c| c.borrow().clone()) else { return };
    p.clear();
    p.set_actions(&[]);
    p.set_sub(&st.tv_subtitle());
    if super::gate(&p, st, false) {
        return;
    }

    let o = st.overview();
    let on_tv_ver = o.and_then(|o| o.nyx_version.clone());
    let tv_debug = o.and_then(|o| o.nyx_package.as_deref()) == Some(NYX_TV_PKG_DEBUG);
    let action = actions::current_install_action();

    if st.connected() {
        let icon = match action {
            InstallAction::BuildAndInstall => "applications-engineering-symbolic",
            _ => "folder-download-symbolic",
        };
        p.set_actions(&[page::primary(icon, &action.label(), actions::install)]);
    }

    if tv_debug {
        p.add(&page::banner(
            "The TV has the debug build. Installing the release build replaces it; the debug build's settings don't carry over.",
            true,
            None,
        ));
    }

    // Versions
    let (card, rows) = page::card("Versions", &[]);
    let (tv_desc, tv_tag) = match (st.connected(), &on_tv_ver, tv_debug) {
        (false, _, _) => ("TV offline".to_string(), None),
        (true, None, _) => ("Not installed".to_string(), None),
        (true, Some(v), true) => (format!("Nyx TV {v}"), Some(page::tag("Debug build", "warning"))),
        (true, Some(v), false) => (format!("Nyx TV {v}"), Some(page::tag("Installed", "accent"))),
    };
    let lead = gtk::Image::from_icon_name("video-display-symbolic");
    rows.append(&page::row(
        "On this TV",
        &tv_desc,
        Some(lead.upcast_ref()),
        &tv_tag.into_iter().collect::<Vec<_>>(),
    ));

    let lead = gtk::Image::from_icon_name("package-x-generic-symbolic");
    match &st.local.release {
        Some(info) => {
            let mut end = Vec::new();
            if let Some(v) = &on_tv_ver
                && !tv_debug {
                    let t = match super::cmp_versions(&info.version_name, v) {
                        std::cmp::Ordering::Greater => page::tag("Newer", "accent"),
                        std::cmp::Ordering::Equal => page::tag("Same as TV", ""),
                        std::cmp::Ordering::Less => page::tag("Older", "warning"),
                    };
                    end.push(t);
                }
            let path = info.path.clone();
            end.push(page::icon("folder-open-symbolic", "Show in folder", move || open_folder(&path)));
            rows.append(&page::row(
                "Release build",
                &format!("Nyx TV {} · {} · {}", info.version_name, info.size_label, info.modified_label),
                Some(lead.upcast_ref()),
                &end,
            ));
        }
        None => rows.append(&page::row(
            "Release build",
            "None in this checkout yet. The install button builds one first.",
            Some(lead.upcast_ref()),
            &[],
        )),
    }

    if let Some(info) = &st.local.debug {
        let lead = gtk::Image::from_icon_name("applications-engineering-symbolic");
        let path = info.path.clone();
        rows.append(&page::row(
            "Debug build",
            &format!("Nyx TV {} · {} · {}", info.version_name, info.size_label, info.modified_label),
            Some(lead.upcast_ref()),
            &[
                page::icon("folder-open-symbolic", "Show in folder", move || open_folder(&path)),
                page::icon("folder-download-symbolic", "Install the debug build", actions::install_debug),
            ],
        ));
    }

    let lead = gtk::Image::from_icon_name("document-open-symbolic");
    rows.append(&page::row(
        "From a file",
        "An APK you built or downloaded",
        Some(lead.upcast_ref()),
        &[page::icon("document-open-symbolic", "Install an APK file…", actions::install_file)],
    ));
    p.add(&card);

    // Build
    let (card, rows) = page::card("Build", &[]);
    let last = st
        .local
        .release
        .as_ref()
        .map(|i| format!("Last build: {}.", i.modified_label))
        .unwrap_or_else(|| "Not built yet.".into());
    rows.append(&page::row(
        "Build the release from source",
        &format!("Runs the Gradle release build in this checkout. {last}"),
        None,
        &[page::icon("applications-engineering-symbolic", "Build now", actions::build_only)],
    ));
    rows.append(&page::dim(
        "Installing keeps the TV's settings, rows and Plex sign-in. It doesn't change the home app.",
    ));
    p.add(&card);

    // Remove
    if st.connected() && on_tv_ver.is_some() {
        let home = o.is_some_and(|o| o.nyx_is_home());
        let (card, rows) = page::card("Remove", &[]);
        rows.append(&page::row(
            "Uninstall Nyx TV",
            if home {
                "Nyx TV is the home app, so Google TV Home is turned back on first."
            } else {
                "Removes Nyx TV and its settings from this TV."
            },
            None,
            &[page::danger("Uninstall", "Uninstall now?", actions::uninstall)],
        ));
        p.add(&card);
    }
}
