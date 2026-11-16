//! Every action that changes a TV, defined once so each has one name
//! wherever it appears. Each asks first when it changes system settings.

use crate::devices;
use crate::ops::{self, Ctx};
use crate::page;
use crate::state::{self, CheckRow};
use crate::sync;
use anyhow::{anyhow, Result};
use nyx_tv_device::{
    apk_info, build_release_apk_with, find_local_apk, Tv, NYX_TV_PKG, NYX_TV_PKG_DEBUG,
};
use std::path::PathBuf;

/// What the Install button does, from the TV's version and the local build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallAction {
    BuildAndInstall,
    Install(String),
    Update(String),
    Reinstall(String),
    Downgrade(String),
}

impl InstallAction {
    pub fn label(&self) -> String {
        match self {
            InstallAction::BuildAndInstall => "Build and install".into(),
            InstallAction::Install(v) => format!("Install {v}"),
            InstallAction::Update(v) => format!("Update to {v}"),
            InstallAction::Reinstall(v) => format!("Reinstall {v}"),
            InstallAction::Downgrade(v) => format!("Install older {v}"),
        }
    }
}

pub fn install_action(on_tv: Option<&str>, tv_has_debug: bool, local: Option<&str>) -> InstallAction {
    let Some(local) = local else {
        return InstallAction::BuildAndInstall;
    };
    let local = local.to_string();
    match on_tv {
        None => InstallAction::Install(local),
        Some(_) if tv_has_debug => InstallAction::Install(local),
        Some(tv) => match crate::pages::cmp_versions(&local, tv) {
            std::cmp::Ordering::Greater => InstallAction::Update(local),
            std::cmp::Ordering::Equal => InstallAction::Reinstall(local),
            std::cmp::Ordering::Less => InstallAction::Downgrade(local),
        },
    }
}

/// The install action for the selected TV right now.
pub fn current_install_action() -> InstallAction {
    state::get(|s| {
        let o = s.overview();
        install_action(
            o.and_then(|o| o.nyx_version.as_deref()),
            o.and_then(|o| o.nyx_package.as_deref()) == Some(NYX_TV_PKG_DEBUG),
            s.local.release.as_ref().map(|i| i.version_name.as_str()),
        )
    })
}

fn build(ctx: &Ctx) -> Result<PathBuf> {
    ctx.step("Building Nyx TV from source (Gradle)…");
    let mut forward = |line: &str| ctx.log(line);
    build_release_apk_with(&devices::repo_root(), &mut forward)
}

fn install_release(ctx: &Ctx, tv: &Tv, force_build: bool) -> Result<String> {
    let root = devices::repo_root();
    let apk = match find_local_apk(&root, false) {
        Some(p) if !force_build => p,
        _ => build(ctx)?,
    };
    let ver = apk_info(&apk).version_name;
    ctx.step(&format!("Installing Nyx TV {ver} on the TV…"));
    ctx.log(&format!("adb install -r {}", apk.display()));
    tv.install_apk(&apk, true)?;
    if tv.has_package(NYX_TV_PKG_DEBUG).unwrap_or(false) {
        ctx.step("Removing the debug build…");
        let _ = tv.uninstall_package(NYX_TV_PKG_DEBUG, true);
    }
    Ok(format!("Installed Nyx TV {ver}"))
}

fn set_home_on(ctx: &Ctx, tv: &Tv) -> Result<String> {
    ctx.step("Saving a snapshot of the TV's home settings…");
    let component = tv.home_component_for_installed()?;
    ctx.step("Setting Nyx TV as home and turning off Google TV Home…");
    let snap = tv.replace_launcher(&component, true)?;
    if let Some(p) = &snap.path {
        ctx.log(&format!("Snapshot: {}", p.display()));
    }
    Ok("Nyx TV is now the home app".into())
}

fn after_local(_: bool) {
    sync::refresh_local();
}

/// Install (or update) the release build; builds it first if there is none.
pub fn install() {
    let action = current_install_action();
    let name = action.label();
    ops::run_tv(&name, move |ctx, tv| install_release(ctx, &tv, false), after_local);
}

pub fn install_debug() {
    let Some(path) = state::get(|s| s.local.debug.as_ref().map(|i| i.path.clone())) else {
        return;
    };
    ops::run_tv(
        "Install debug build",
        move |ctx, tv| {
            ctx.step("Installing the debug build on the TV…");
            tv.install_apk(&path, true)?;
            Ok("Installed the Nyx TV debug build".into())
        },
        |_| {},
    );
}

pub fn build_only() {
    ops::run(
        "Build Nyx TV",
        |ctx| {
            let apk = build(ctx)?;
            Ok(format!("Built Nyx TV {}", apk_info(&apk).version_name))
        },
        after_local,
    );
}

pub fn install_file() {
    let Some(win) = crate::window::window() else { return };
    let filter = gtk::FileFilter::new();
    filter.set_name(Some("Android apps (APK)"));
    filter.add_pattern("*.apk");
    let dialog = gtk::FileDialog::builder()
        .title("Install an APK")
        .default_filter(&filter)
        .build();
    dialog.open(Some(&win), None::<&gtk::gio::Cancellable>, |res| {
        let Some(path) = res.ok().and_then(|f| gtk::prelude::FileExt::path(&f)) else {
            return;
        };
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        page::confirm(
            &format!("Install {name}?"),
            "It replaces the app with the same package name, keeping its data. The home app doesn't change.",
            "Install",
            move || {
                ops::run_tv(
                    "Install APK",
                    move |ctx, tv| {
                        ctx.step(&format!("Installing {name}…"));
                        tv.install_apk(&path, true)?;
                        Ok(format!("Installed {name}"))
                    },
                    |_| {},
                );
            },
        );
    });
}

/// Install if needed, then set as home. One click from Overview.
pub fn setup() {
    page::confirm(
        "Set up Nyx TV?",
        "Installs Nyx TV, saves a snapshot of the TV's home settings, makes Nyx TV the home app and turns off Google TV Home. Nothing is uninstalled, and you can go back from Home screen.",
        "Set up",
        || {
            ops::run_tv(
                "Set up Nyx TV",
                |ctx, tv| {
                    let installed = tv.has_package(NYX_TV_PKG).unwrap_or(false)
                        || tv.has_package(NYX_TV_PKG_DEBUG).unwrap_or(false);
                    if !installed {
                        install_release(ctx, &tv, false)?;
                    }
                    set_home_on(ctx, &tv)?;
                    Ok("Nyx TV is set up as the home app".into())
                },
                |_| {
                    sync::refresh_local();
                    sync::refresh_snapshots();
                },
            );
        },
    );
}

pub fn set_home() {
    page::confirm(
        "Set Nyx TV as home?",
        "Saves a snapshot of the TV's home settings, makes Nyx TV the home app and turns off Google TV Home so it can't take the Home button back. Nothing is uninstalled.",
        "Set as home",
        || {
            ops::run_tv("Set Nyx TV as home", |ctx, tv| set_home_on(ctx, &tv), |_| {
                sync::refresh_snapshots();
            });
        },
    );
}

pub fn repair() {
    page::confirm(
        "Repair the home app?",
        "Saves a new snapshot, then makes Nyx TV the home app again and turns Google TV Home off.",
        "Repair",
        || {
            ops::run_tv(
                "Repair home app",
                |ctx, tv| {
                    ctx.step("Making Nyx TV the home app again…");
                    let snap = tv.repair(true)?;
                    if let Some(p) = &snap.path {
                        ctx.log(&format!("Snapshot: {}", p.display()));
                    }
                    Ok("Nyx TV is the home app again".into())
                },
                |_| sync::refresh_snapshots(),
            );
        },
    );
}

/// Turn Google TV Home on or off. `on_cancel` puts a switch back.
pub fn set_google_home(enabled: bool, on_cancel: impl FnOnce() + 'static) {
    let run = move || {
        let name = if enabled { "Turn on Google TV Home" } else { "Turn off Google TV Home" };
        ops::run_tv(
            name,
            move |ctx, tv| {
                if enabled {
                    ctx.step("Turning Google TV Home on…");
                    tv.enable_stock(true)?;
                    Ok("Google TV Home is on".into())
                } else {
                    ctx.step("Turning Google TV Home off…");
                    tv.disable_stock(true)?;
                    Ok("Google TV Home is off".into())
                }
            },
            |_| {},
        );
    };
    if enabled {
        run();
    } else {
        page::confirm_or(
            "Turn off Google TV Home?",
            "It stays installed but can't open or take the Home button back. Turn it on again here anytime.",
            "Turn off",
            run,
            on_cancel,
        );
    }
}

/// Go back to Google TV Home (and optionally remove Nyx TV).
pub fn undo(uninstall: bool) {
    let name = if uninstall { "Remove Nyx TV" } else { "Go back to Google TV Home" };
    ops::run_tv(
        name,
        move |ctx, tv| {
            ctx.step("Turning Google TV Home on and making it the home app…");
            tv.restore_stock_home(true)?;
            if uninstall {
                ctx.step("Uninstalling Nyx TV…");
                let _ = tv.uninstall_package(NYX_TV_PKG_DEBUG, true);
                let _ = tv.uninstall_package(NYX_TV_PKG, true);
                Ok("Back on Google TV Home; Nyx TV removed".into())
            } else {
                Ok("Back on Google TV Home".into())
            }
        },
        |_| {},
    );
}

/// Uninstall Nyx TV. If it's the home app, Google TV Home comes back first
/// so the TV is never left without one.
pub fn uninstall() {
    let was_home = state::get(|s| s.overview().is_some_and(|o| o.nyx_is_home()));
    ops::run_tv(
        "Uninstall Nyx TV",
        move |ctx, tv| {
            if was_home {
                ctx.step("Turning Google TV Home back on first…");
                tv.restore_stock_home(true)?;
            }
            ctx.step("Uninstalling Nyx TV…");
            let mut removed = 0;
            for pkg in [NYX_TV_PKG_DEBUG, NYX_TV_PKG] {
                if tv.has_package(pkg).unwrap_or(false) {
                    tv.uninstall_package(pkg, true)?;
                    removed += 1;
                }
            }
            Ok(if removed == 0 {
                "Nyx TV wasn't installed".into()
            } else {
                "Uninstalled Nyx TV".into()
            })
        },
        |_| {},
    );
}

pub fn restart_tv() {
    ops::run_tv(
        "Restart the TV",
        |ctx, tv| {
            ctx.step("Restarting the TV…");
            tv.adb().shell(&["reboot"])?;
            Ok("The TV is restarting. Refresh in a minute.".into())
        },
        |_| {},
    );
}

pub fn take_snapshot() {
    ops::run_tv(
        "Save a snapshot",
        |ctx, tv| {
            ctx.step("Reading the TV's home settings…");
            let snap = tv.snapshot()?;
            if let Some(p) = &snap.path {
                ctx.log(&format!("Saved {}", p.display()));
            }
            Ok("Snapshot saved".into())
        },
        |_| sync::refresh_snapshots(),
    );
}

pub fn restore_snapshot(path: PathBuf) {
    ops::run_tv(
        "Restore snapshot",
        move |ctx, tv| {
            let snap = Tv::load_snapshot(&path)?;
            ctx.step("Restoring the home settings from the snapshot…");
            tv.restore(&snap, true)?;
            Ok("Snapshot restored".into())
        },
        |_| {},
    );
}

/// Press Home on the TV, open Settings and Play, and check each landed.
pub fn run_check() {
    ops::run_tv(
        "Check the home app",
        |ctx, tv| {
            ctx.step("Pressing Home and opening apps on the TV…");
            let serial = tv.serial().to_string();
            let component = tv.home_component_for_installed()?;
            let report = tv.verify(&component)?;
            for l in &report.lines {
                ctx.log(l);
            }
            let still = nyx_tv_adb::Adb::devices()
                .map(|l| l.iter().any(|d| d.serial == serial && d.state == "device"))
                .unwrap_or(false);
            let rows = check_rows(&report.lines, report.settings_ok, report.play_ok, still);
            let ok = rows.iter().all(|r| r.ok != Some(false));
            gtk::glib::idle_add_once(move || {
                state::update(|s| {
                    s.check = Some(state::Check {
                        rows,
                        time: gtk::glib::DateTime::now_local().map(|d| d.to_unix()).unwrap_or(0),
                    })
                });
            });
            if ok {
                Ok("Check passed".into())
            } else {
                Err(anyhow!("some checks failed — see Home screen"))
            }
        },
        |_| {},
    );
}

fn find<'a>(lines: &'a [String], prefix: &str) -> Option<&'a str> {
    lines.iter().find(|l| l.starts_with(prefix)).map(|s| {
        s.split_once("->")
            .map(|(_, v)| v.trim())
            .unwrap_or(s.as_str())
    })
}

fn check_rows(lines: &[String], settings_ok: bool, play_ok: bool, still: bool) -> Vec<CheckRow> {
    let fail = |needle: &str| lines.iter().any(|l| l.contains(needle));
    let stock_line = lines
        .iter()
        .find(|l| l.starts_with("stock launcher"))
        .cloned()
        .unwrap_or_default();
    let stock_ok = stock_line.contains("not running");
    vec![
        CheckRow {
            ok: Some(!fail("FAIL: Home didn't open")),
            title: "Home button opens Nyx TV".into(),
            detail: find(lines, "Home button").unwrap_or("").to_string(),
        },
        CheckRow {
            ok: Some(stock_ok),
            title: "Google TV Home isn't running".into(),
            detail: if stock_ok { String::new() } else { "It's still running in the background".into() },
        },
        CheckRow {
            ok: Some(settings_ok),
            title: "Settings opens".into(),
            detail: find(lines, "Settings").unwrap_or("").to_string(),
        },
        CheckRow {
            ok: Some(play_ok),
            title: "Play Store opens".into(),
            detail: find(lines, "Play Store").unwrap_or("").to_string(),
        },
        CheckRow {
            ok: Some(!fail("FAIL: Home didn't return")),
            title: "Home brings Nyx TV back".into(),
            detail: find(lines, "Home again").unwrap_or("").to_string(),
        },
        CheckRow {
            ok: Some(still),
            title: "Still connected".into(),
            detail: if still { String::new() } else { "The TV dropped off wireless debugging".into() },
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::{install_action, InstallAction::*};

    #[test]
    fn install_labels() {
        assert_eq!(install_action(None, false, None), BuildAndInstall);
        assert_eq!(install_action(None, false, Some("0.1.1")), Install("0.1.1".into()));
        assert_eq!(install_action(Some("0.1.0"), false, Some("0.1.1")), Update("0.1.1".into()));
        assert_eq!(install_action(Some("0.1.1"), false, Some("0.1.1")), Reinstall("0.1.1".into()));
        assert_eq!(install_action(Some("0.2.0"), false, Some("0.1.1")), Downgrade("0.1.1".into()));
        // A debug build on the TV is a different app: installing release is a fresh install.
        assert_eq!(install_action(Some("0.1.1"), true, Some("0.1.1")), Install("0.1.1".into()));
    }
}
