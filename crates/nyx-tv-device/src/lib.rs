//! Launcher replace, audit, APK install and settings backup for Nyx TV.

use anyhow::{anyhow, bail, Context, Result};
use chrono::Local;
use nyx_tv_adb::Adb;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

pub const STOCK_LAUNCHER: &str = "com.google.android.apps.tv.launcherx";
pub const STOCK_HOME: &str = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
pub const SETUPWRAITH: &str = "com.google.android.tungsten.setupwraith";

pub const NYX_TV_PKG: &str = "io.github.design_nexus.nyx.tv";
pub const NYX_TV_PKG_DEBUG: &str = "io.github.design_nexus.nyx.tv.debug";
pub const NYX_TV_HOME: &str = "io.github.design_nexus.nyx.tv/io.github.design_nexus.nyx.tv.ui.HomeActivity";
pub const NYX_TV_HOME_DEBUG: &str =
    "io.github.design_nexus.nyx.tv.debug/io.github.design_nexus.nyx.tv.ui.HomeActivity";

pub const BACKUP_FILE: &str = "nyx-tv-settings.json";
pub const EXPORT_ACTION: &str = "io.github.design_nexus.nyx.tv.EXPORT";
pub const IMPORT_ACTION: &str = "io.github.design_nexus.nyx.tv.IMPORT";

/// A connected TV with typed ops that match `scripts/launcher.sh` / `audit.sh`.
pub struct Tv {
    adb: Adb,
    /// Repo root (for writing `audit/` snapshots). Defaults to cwd.
    root: PathBuf,
}

/// Snapshot written by [`Tv::snapshot`] — same keys as `launcher.sh snapshot`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub fingerprint: String,
    pub home: String,
    pub role_home: String,
    pub stock_state: String,
    pub setupwraith_state: String,
    pub disabled_packages: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<PathBuf>,
}

/// Read-only overview for the Manager.
#[derive(Debug, Clone)]
pub struct Overview {
    pub model: String,
    pub fingerprint: String,
    pub android_version: String,
    pub sdk: String,
    pub home: String,
    pub role_home: String,
    pub nyx_package: Option<String>,
    pub nyx_version: Option<String>,
    pub stock_state: String,
    pub setupwraith_state: String,
}

impl Overview {
    /// True when the resolved HOME activity or HOME role holder is Nyx TV.
    /// Stock being disabled is not enough — Home can still open something else.
    pub fn nyx_is_home(&self) -> bool {
        let Some(pkg) = self.nyx_package.as_deref() else {
            return false;
        };
        self.home.contains(pkg) || self.role_home.contains(pkg)
    }

    /// Short label for Overview / Replace / Install chips.
    pub fn home_label(&self) -> &'static str {
        if self.nyx_is_home() {
            "Nyx TV"
        } else if self.home.to_lowercase().contains("launcherx")
            || self.role_home.to_lowercase().contains("launcherx")
            || self.home.to_lowercase().contains("google")
            || self.role_home.to_lowercase().contains("setupwraith")
        {
            "Google TV"
        } else if self.home.is_empty() && self.role_home.is_empty() {
            "Unknown"
        } else {
            "Other"
        }
    }

    pub fn stock_disabled(&self) -> bool {
        matches!(
            self.stock_state.as_str(),
            "disabled" | "disabled-user"
        )
    }
}

/// How aggressive Debloat may be with a catalog entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DebloatKind {
    /// Safe to `pm disable-user` for a lean home (promo, analytics, unused TCL chrome).
    Optional,
    /// On some TVs these matter — shown but not auto-selected.
    Dependent,
    /// Never disable (Play, Cast, OTA, stock launcher, Nyx).
    Protected,
}

/// A catalog package that is actually installed on the TV.
#[derive(Debug, Clone)]
pub struct DebloatPackage {
    pub package: String,
    pub title: String,
    pub kind: DebloatKind,
    pub enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
struct DebloatCatalogFile {
    #[serde(default)]
    fingerprint: String,
    #[serde(default)]
    #[allow(dead_code)]
    model: String,
    #[serde(default, rename = "package")]
    packages: Vec<DebloatCatalogEntry>,
}

#[derive(Debug, Clone, Deserialize)]
struct DebloatCatalogEntry {
    package: String,
    title: String,
    kind: DebloatKind,
    #[serde(default)]
    #[allow(dead_code)]
    description: String,
    #[serde(default)]
    #[allow(dead_code)]
    depends_on: Vec<String>,
}

/// Built-in fallback when no TOML is found (same set as Bedroom BeyondTV4 catalog).
const DEBLOAT_CATALOG: &[(&str, &str, DebloatKind)] = &[
    ("com.tcl.channelplus", "TCL Channel Plus", DebloatKind::Optional),
    ("com.tcl.tv.tclchannel", "TCL Channel", DebloatKind::Optional),
    ("com.tcl.tv.plus", "TCL TV Plus", DebloatKind::Optional),
    ("com.tcl.waterfall.overseas", "TCL Waterfall", DebloatKind::Optional),
    ("com.tcl.dashboard", "TCL Dashboard", DebloatKind::Optional),
    ("com.tcl.bi", "TCL Analytics (BI)", DebloatKind::Optional),
    ("com.tcl.esticker", "TCL eSticker", DebloatKind::Optional),
    ("com.tcl.messagebox", "TCL Message Box", DebloatKind::Optional),
    ("com.tcl.gallery", "TCL Gallery", DebloatKind::Optional),
    ("com.tcl.usercenter", "TCL User Center", DebloatKind::Optional),
    ("com.tcl.suspension", "TCL Suspension", DebloatKind::Optional),
    ("com.tcl.ui_mediaCenter", "TCL Media Center", DebloatKind::Optional),
    ("com.tcl.partnercustomizer", "TCL Partner customizer", DebloatKind::Optional),
    ("com.google.android.play.games", "Google Play Games", DebloatKind::Optional),
    ("com.google.android.youtube.tvmusic", "YouTube Music (TV)", DebloatKind::Optional),
    ("com.google.android.partnersetup", "Google Partner Setup", DebloatKind::Optional),
    ("com.tcl.assistant", "TCL Assistant", DebloatKind::Dependent),
    ("com.tcl.smartalexa", "TCL Alexa", DebloatKind::Dependent),
    ("com.google.android.tv.assistant", "Google TV Assistant", DebloatKind::Dependent),
    ("com.google.android.youtube.tv", "YouTube", DebloatKind::Dependent),
    ("com.android.vending", "Google Play Store", DebloatKind::Protected),
    ("com.google.android.apps.mediashell", "Chromecast built-in", DebloatKind::Protected),
    ("com.tcl.versionUpdateApp", "TCL System update", DebloatKind::Protected),
    ("com.tcl.tv", "TCL TV app", DebloatKind::Protected),
    ("com.tcl.tvinput", "TCL TV input", DebloatKind::Protected),
    ("com.tcl.settings", "TCL Settings", DebloatKind::Protected),
    (STOCK_LAUNCHER, "Google TV home", DebloatKind::Protected),
    (SETUPWRAITH, "Google TV setup", DebloatKind::Protected),
    (NYX_TV_PKG, "Nyx TV", DebloatKind::Protected),
    (NYX_TV_PKG_DEBUG, "Nyx TV (debug)", DebloatKind::Protected),
];

fn fingerprint_safe(fp: &str) -> String {
    fp.chars()
        .map(|c| match c {
            '/' | ':' => '_',
            c => c,
        })
        .collect()
}

fn builtin_catalog_entries() -> Vec<(String, String, DebloatKind)> {
    DEBLOAT_CATALOG
        .iter()
        .map(|&(p, t, k)| (p.to_string(), t.to_string(), k))
        .collect()
}

#[derive(Debug, Clone)]
pub struct VerifyReport {
    pub ok: bool,
    pub lines: Vec<String>,
    /// Settings activity came up after the Settings intent.
    pub settings_ok: bool,
    /// Play Store activity came up after the Play intent.
    pub play_ok: bool,
}

/// Lightweight live metrics for overview / diagnostics cards.
#[derive(Debug, Clone, Default)]
pub struct QuickMetrics {
    pub memory: String,
    pub memory_sub: String,
    pub cpu: String,
    pub cpu_sub: String,
    pub cold: String,
    pub cold_sub: String,
    pub stock: String,
    pub stock_sub: String,
}

impl Tv {
    pub fn new(serial: impl Into<String>) -> Result<Self> {
        Ok(Self {
            adb: Adb::new(serial)?,
            root: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
        })
    }

    pub fn connect(host_port: &str) -> Result<Self> {
        Ok(Self {
            adb: Adb::connect(host_port)?,
            root: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
        })
    }

    pub fn with_root(mut self, root: impl Into<PathBuf>) -> Self {
        self.root = root.into();
        self
    }

    pub fn serial(&self) -> &str {
        self.adb.serial()
    }

    pub fn adb(&self) -> &Adb {
        &self.adb
    }

    pub fn overview(&self) -> Result<Overview> {
        let model = self.adb.getprop("ro.product.model")?;
        let fingerprint = self.adb.getprop("ro.build.fingerprint")?;
        let android_version = self.adb.getprop("ro.build.version.release")?;
        let sdk = self.adb.getprop("ro.build.version.sdk")?;
        let home = self.home_now()?;
        let role_home = self.role_now()?;
        let stock_state = self.enabled_state(STOCK_LAUNCHER)?;
        let setupwraith_state = self.enabled_state(SETUPWRAITH)?;
        let (nyx_package, nyx_version) = self.nyx_installed()?;
        Ok(Overview {
            model,
            fingerprint,
            android_version,
            sdk,
            home,
            role_home,
            nyx_package,
            nyx_version,
            stock_state,
            setupwraith_state,
        })
    }

    fn nyx_installed(&self) -> Result<(Option<String>, Option<String>)> {
        for pkg in [NYX_TV_PKG_DEBUG, NYX_TV_PKG] {
            let path = self.adb.shell_ok(&["pm", "path", pkg])?;
            if path.contains("package:") {
                let dump = self.adb.shell_ok(&["dumpsys", "package", pkg])?;
                let ver = dump
                    .lines()
                    .find_map(|l| l.trim().strip_prefix("versionName="))
                    .map(|s| s.to_string());
                return Ok((Some(pkg.to_string()), ver));
            }
        }
        Ok((None, None))
    }

    pub fn home_component_for_installed(&self) -> Result<String> {
        let (pkg, _) = self.nyx_installed()?;
        match pkg.as_deref() {
            Some(NYX_TV_PKG_DEBUG) => Ok(NYX_TV_HOME_DEBUG.to_string()),
            Some(NYX_TV_PKG) => Ok(NYX_TV_HOME.to_string()),
            _ => bail!("Nyx TV is not installed on {}", self.serial()),
        }
    }

    pub fn home_now(&self) -> Result<String> {
        let out = self.adb.shell(&[
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
        ])?;
        Ok(out
            .lines()
            .last()
            .unwrap_or("")
            .trim()
            .to_string())
    }

    pub fn role_now(&self) -> Result<String> {
        let out = self.adb.shell_ok(&["dumpsys", "role"])?;
        let mut take = false;
        for line in out.lines() {
            if line.contains("name=android.app.role.HOME") {
                take = true;
                continue;
            }
            if take {
                if let Some(rest) = line.split("holders=").nth(1) {
                    return Ok(rest.trim().to_string());
                }
                if line.contains("name=") {
                    break;
                }
            }
        }
        Ok(String::new())
    }

    pub fn enabled_state(&self, package: &str) -> Result<String> {
        let disabled = self.adb.shell_ok(&["pm", "list", "packages", "-d"])?;
        let needle = format!("package:{package}");
        if disabled.lines().any(|l| l.trim() == needle) {
            Ok("disabled".into())
        } else {
            Ok("enabled".into())
        }
    }

    pub fn package_present(&self, package: &str) -> Result<bool> {
        let out = self.adb.shell_ok(&["pm", "path", package])?;
        Ok(out.contains("package:"))
    }

    /// Save current HOME state under `audit/snapshot-<model>-<ts>.env`.
    pub fn snapshot(&self) -> Result<Snapshot> {
        let model = fingerprint_safe(&self.adb.getprop("ro.product.model")?);
        let fingerprint = self.adb.getprop("ro.build.fingerprint")?;
        let home = self.home_now()?;
        let role_home = self.role_now()?;
        let stock_state = self.enabled_state(STOCK_LAUNCHER)?;
        let setupwraith_state = self.enabled_state(SETUPWRAITH)?;
        let disabled = self
            .adb
            .shell_ok(&["pm", "list", "packages", "-d"])?
            .lines()
            .filter_map(|l| l.trim().strip_prefix("package:"))
            .collect::<Vec<_>>()
            .join(" ");

        let dir = self.root.join("audit");
        fs::create_dir_all(&dir)?;
        let name = format!(
            "snapshot-{}-{}.env",
            model,
            Local::now().format("%Y%m%d-%H%M%S")
        );
        let path = dir.join(&name);
        let body = format!(
            "fingerprint={fingerprint}\nhome={home}\nrole_home={role_home}\nstock_state={stock_state}\nsetupwraith_state={setupwraith_state}\ndisabled_packages={disabled}\n"
        );
        fs::write(&path, &body)?;
        Ok(Snapshot {
            fingerprint,
            home,
            role_home,
            stock_state,
            setupwraith_state,
            disabled_packages: disabled,
            path: Some(path),
        })
    }

    pub fn load_snapshot(path: &Path) -> Result<Snapshot> {
        let text = fs::read_to_string(path)
            .with_context(|| format!("reading snapshot {}", path.display()))?;
        let mut snap = Snapshot {
            fingerprint: String::new(),
            home: String::new(),
            role_home: String::new(),
            stock_state: "enabled".into(),
            setupwraith_state: "enabled".into(),
            disabled_packages: String::new(),
            path: Some(path.to_path_buf()),
        };
        for line in text.lines() {
            if let Some((k, v)) = line.split_once('=') {
                match k {
                    "fingerprint" => snap.fingerprint = v.to_string(),
                    "home" => snap.home = v.to_string(),
                    "role_home" => snap.role_home = v.to_string(),
                    "stock_state" => snap.stock_state = v.to_string(),
                    "setupwraith_state" => snap.setupwraith_state = v.to_string(),
                    "disabled_packages" => snap.disabled_packages = v.to_string(),
                    _ => {}
                }
            }
        }
        if snap.fingerprint.is_empty() {
            bail!("snapshot missing fingerprint");
        }
        Ok(snap)
    }

    pub fn ensure_fingerprint(&self, expected: &str) -> Result<()> {
        let now = self.adb.getprop("ro.build.fingerprint")?;
        if now != expected {
            bail!(
                "fingerprint mismatch: TV is {now}, snapshot expects {expected}"
            );
        }
        Ok(())
    }

    /// Make `component` the preferred HOME activity.
    pub fn set_home(&self, component: &str) -> Result<()> {
        self.adb.shell(&[
            "cmd",
            "package",
            "set-home-activity",
            component,
        ])?;
        Ok(())
    }

    /// Disable Google TV launcherx and setupwraith (when present). Never uninstalls.
    /// Requires `confirm = true` from the UI.
    pub fn disable_stock(&self, confirm: bool) -> Result<()> {
        if !confirm {
            bail!("disable_stock refused: confirm was false");
        }
        self.adb.shell(&[
            "pm",
            "disable-user",
            "--user",
            "0",
            STOCK_LAUNCHER,
        ])?;
        if self.package_present(SETUPWRAITH)? {
            self.adb.shell(&[
                "pm",
                "disable-user",
                "--user",
                "0",
                SETUPWRAITH,
            ])?;
        }
        Ok(())
    }

    /// Re-enable stock packages and restore the saved home from a snapshot.
    pub fn restore(&self, snap: &Snapshot, confirm: bool) -> Result<()> {
        if !confirm {
            bail!("restore refused: confirm was false");
        }
        self.ensure_fingerprint(&snap.fingerprint)?;
        if snap.setupwraith_state == "enabled" && self.package_present(SETUPWRAITH)? {
            self.adb.shell(&["pm", "enable", SETUPWRAITH])?;
        }
        if snap.stock_state == "enabled" {
            self.adb.shell(&["pm", "enable", STOCK_LAUNCHER])?;
        }
        let home = if snap.home.is_empty() {
            STOCK_HOME
        } else {
            snap.home.as_str()
        };
        self.set_home(home)?;
        Ok(())
    }

    /// Home button, Settings, Play Store, Home again — same checks as `launcher.sh verify`.
    pub fn verify(&self, component: &str) -> Result<VerifyReport> {
        let pkg = component.split('/').next().unwrap_or(component);
        let mut lines = Vec::new();
        let mut ok = true;

        self.adb.shell(&["input", "keyevent", "KEYCODE_HOME"])?;
        thread::sleep(Duration::from_secs(2));
        let r = self.resumed()?;
        lines.push(format!("Home button -> {r}"));
        if !(r == component || r.starts_with(&format!("{pkg}/"))) {
            lines.push(format!("FAIL: Home didn't open {pkg}"));
            ok = false;
        }

        let sp = self.adb.shell_ok(&["pidof", STOCK_LAUNCHER])?;
        lines.push(format!(
            "stock launcher process: {}",
            if sp.trim().is_empty() {
                "not running"
            } else {
                sp.trim()
            }
        ));

        let _ = self.adb.shell_ok(&[
            "am",
            "start",
            "-W",
            "-a",
            "android.settings.SETTINGS",
        ]);
        thread::sleep(Duration::from_secs(2));
        let settings = self.resumed()?;
        lines.push(format!("Settings -> {settings}"));
        let settings_ok = !settings.is_empty()
            && (settings.to_lowercase().contains("settings")
                || settings.contains("com.android.tv.settings"));
        if !settings_ok {
            lines.push("FAIL: Settings did not open".into());
            ok = false;
        }

        let _ = self.adb.shell_ok(&[
            "am",
            "start",
            "-W",
            "-n",
            "com.android.vending/com.google.android.finsky.tvmainactivity.TvMainActivity",
        ]);
        thread::sleep(Duration::from_secs(3));
        let play = self.resumed()?;
        lines.push(format!("Play Store -> {play}"));
        let play_ok = !play.is_empty()
            && (play.contains("vending") || play.contains("finsky") || play.contains("Play"));
        if !play_ok {
            lines.push("FAIL: Play Store did not open".into());
            ok = false;
        }

        self.adb.shell(&["input", "keyevent", "KEYCODE_HOME"])?;
        thread::sleep(Duration::from_secs(2));
        let r = self.resumed()?;
        lines.push(format!("Home again -> {r}"));
        if !r.starts_with(&format!("{pkg}/")) {
            lines.push(format!("FAIL: Home didn't return to {pkg}"));
            ok = false;
        }

        Ok(VerifyReport {
            ok,
            lines,
            settings_ok,
            play_ok,
        })
    }

    fn resumed(&self) -> Result<String> {
        let out = self.adb.shell_ok(&["dumpsys", "activity", "activities"])?;
        for line in out.lines() {
            for marker in [
                "mResumedActivity: ActivityRecord{",
                "topResumedActivity=ActivityRecord{",
                "ResumedActivity: ActivityRecord{",
            ] {
                if let Some(idx) = line.find(marker) {
                    let rest = &line[idx..];
                    let parts: Vec<_> = rest.split_whitespace().collect();
                    // ActivityRecord{hash user component ...
                    if parts.len() >= 3 {
                        return Ok(parts[2].to_string());
                    }
                }
            }
        }
        Ok(String::new())
    }

    /// Full replace flow: snapshot, set-home, disable-stock. Caller should verify after.
    pub fn replace_launcher(&self, component: &str, confirm: bool) -> Result<Snapshot> {
        if !confirm {
            bail!("replace_launcher refused: confirm was false");
        }
        let snap = self.snapshot()?;
        self.set_home(component)?;
        self.disable_stock(true)?;
        Ok(snap)
    }

    /// Re-snapshot and re-apply Nyx as home (after a firmware update).
    pub fn repair(&self, confirm: bool) -> Result<Snapshot> {
        if !confirm {
            bail!("repair refused: confirm was false");
        }
        let component = self.home_component_for_installed()?;
        self.replace_launcher(&component, true)
    }

    pub fn install_apk(&self, apk: &Path, replace: bool) -> Result<()> {
        if !apk.is_file() {
            bail!("APK not found: {}", apk.display());
        }
        self.adb.install(apk, replace)
    }

    pub fn uninstall_package(&self, package: &str, confirm: bool) -> Result<()> {
        if !confirm {
            bail!("uninstall refused: confirm was false");
        }
        if package == STOCK_LAUNCHER || package == SETUPWRAITH {
            bail!("refusing to uninstall stock package {package}");
        }
        self.adb.uninstall(package)
    }

    fn backup_remote_path(package: &str) -> String {
        format!("/sdcard/Android/data/{package}/files/{BACKUP_FILE}")
    }

    fn backup_component(package: &str) -> String {
        format!("{package}/io.github.design_nexus.nyx.tv.BackupReceiver")
    }

    fn active_nyx_package(&self) -> Result<String> {
        let (pkg, _) = self.nyx_installed()?;
        pkg.ok_or_else(|| anyhow!("Nyx TV is not installed"))
    }

    /// Export settings JSON from the TV to a local file (never includes Plex tokens).
    pub fn export_settings(&self, local: &Path) -> Result<()> {
        let pkg = self.active_nyx_package()?;
        let remote = Self::backup_remote_path(&pkg);
        let comp = Self::backup_component(&pkg);
        let out = self.adb.shell(&[
            "am",
            "broadcast",
            "-n",
            &comp,
            "-a",
            EXPORT_ACTION,
        ])?;
        if !out.contains("result=1") && !out.contains("data=") {
            // Some adb builds only print Broadcast completed; still try pull.
        }
        if let Some(parent) = local.parent() {
            fs::create_dir_all(parent)?;
        }
        self.adb.pull(&remote, local)?;
        Ok(())
    }

    /// Push a settings JSON and import it on the TV.
    pub fn import_settings(&self, local: &Path) -> Result<()> {
        if !local.is_file() {
            bail!("settings file not found: {}", local.display());
        }
        let pkg = self.active_nyx_package()?;
        let remote = Self::backup_remote_path(&pkg);
        let comp = Self::backup_component(&pkg);
        self.adb.push(local, &remote)?;
        let out = self.adb.shell(&[
            "am",
            "broadcast",
            "-n",
            &comp,
            "-a",
            IMPORT_ACTION,
        ])?;
        if out.contains("result=0") {
            bail!("import failed: {out}");
        }
        Ok(())
    }

    /// Read-only diagnostics dump into `audit/<model>-<ts>/`.
    pub fn audit(&self) -> Result<PathBuf> {
        let model = fingerprint_safe(&self.adb.getprop("ro.product.model")?);
        let dir = self.root.join("audit").join(format!(
            "{}-{}",
            model,
            Local::now().format("%Y%m%d-%H%M%S")
        ));
        fs::create_dir_all(&dir)?;

        let save = |name: &str, args: &[&str]| -> Result<()> {
            let text = self.adb.shell_ok(args)?;
            fs::write(dir.join(format!("{name}.txt")), text)?;
            Ok(())
        };

        save("getprop", &["getprop"])?;
        save("packages-system", &["pm", "list", "packages", "-s", "-U"])?;
        save("packages-third-party", &["pm", "list", "packages", "-3", "-U"])?;
        save("packages-disabled", &["pm", "list", "packages", "-d"])?;
        save(
            "home-resolve",
            &[
                "cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.HOME",
            ],
        )?;
        save(
            "home-candidates",
            &[
                "cmd",
                "package",
                "query-activities",
                "--brief",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.HOME",
            ],
        )?;
        save("home-role", &["dumpsys", "role"])?;
        save("display", &["wm", "size"])?;
        save("meminfo-system", &["cat", "/proc/meminfo"])?;
        save(
            "accessibility",
            &["settings", "get", "secure", "enabled_accessibility_services"],
        )?;

        for p in [
            STOCK_LAUNCHER,
            SETUPWRAITH,
            "com.plexapp.android",
            NYX_TV_PKG,
            NYX_TV_PKG_DEBUG,
        ] {
            if self.package_present(p)? {
                let safe = p.replace('.', "_");
                let _ = save(&format!("pkg-{safe}"), &["dumpsys", "package", p]);
            }
        }

        // Redacted summary for the UI / export
        let overview = self.overview()?;
        let summary = format!(
            "model={}\nfingerprint={}\nandroid={}\nsdk={}\nhome={}\nrole_home={}\nnyx={:?}\nnyx_version={:?}\nstock={}\nsetupwraith={}\n",
            overview.model,
            overview.fingerprint,
            overview.android_version,
            overview.sdk,
            overview.home,
            overview.role_home,
            overview.nyx_package,
            overview.nyx_version,
            overview.stock_state,
            overview.setupwraith_state,
        );
        fs::write(dir.join("summary.txt"), summary)?;
        Ok(dir)
    }

    /// Copy an audit folder to a destination (for “export diagnostics”).
    pub fn export_diagnostics(&self, dest: &Path) -> Result<PathBuf> {
        let src = self.audit()?;
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent)?;
        }
        copy_dir(&src, dest)?;
        Ok(dest.to_path_buf())
    }

    /// Fast metrics for the Manager cards (no full audit).
    pub fn quick_metrics(&self) -> Result<QuickMetrics> {
        let (pkg, _) = self.nyx_installed()?;
        let mut m = QuickMetrics {
            memory: "—".into(),
            memory_sub: String::new(),
            cpu: "—".into(),
            cpu_sub: "Idle sample".into(),
            cold: "—".into(),
            cold_sub: "Warm start".into(),
            stock: "—".into(),
            stock_sub: "Process check".into(),
        };

        if let Some(p) = pkg.as_deref() {
            if let Ok(dump) = self.adb.shell_ok(&["dumpsys", "meminfo", p]) {
                if let Some(mb) = parse_total_pss_mb(&dump) {
                    m.memory = format!("{mb} MB");
                    m.memory_sub = p.to_string();
                }
            }
            m.cold_sub = "Use Verify to measure".into();
        } else {
            m.memory_sub = "Not installed".into();
        }

        let stock_pid = self.adb.shell_ok(&["pidof", STOCK_LAUNCHER]).unwrap_or_default();
        if stock_pid.trim().is_empty() {
            m.stock = "Not running".into();
            m.stock_sub = "Process check".into();
        } else {
            m.stock = "Running".into();
            if let Ok(dump) = self.adb.shell_ok(&["dumpsys", "meminfo", STOCK_LAUNCHER]) {
                if let Some(mb) = parse_total_pss_mb(&dump) {
                    m.stock_sub = format!("{mb} MB PSS");
                } else {
                    m.stock_sub = "Process check".into();
                }
            }
        }

        // Best-effort idle CPU from /proc/stat delta (~200ms).
        if let (Ok(a), Ok(b)) = (
            self.adb.shell_ok(&["cat", "/proc/stat"]),
            {
                thread::sleep(Duration::from_millis(200));
                self.adb.shell_ok(&["cat", "/proc/stat"])
            },
        ) {
            if let Some(pct) = cpu_idle_pct(&a, &b) {
                m.cpu = format!("{pct:.1}%");
                m.cpu_sub = "Idle sample".into();
            }
        }

        Ok(m)
    }

    /// Snapshots under `audit/snapshot-*.env`, newest first.
    pub fn list_snapshots(&self) -> Result<Vec<(PathBuf, Snapshot)>> {
        let dir = self.root.join("audit");
        if !dir.is_dir() {
            return Ok(Vec::new());
        }
        let mut out = Vec::new();
        for entry in fs::read_dir(&dir)? {
            let entry = entry?;
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with("snapshot-") && name.ends_with(".env") {
                if let Ok(snap) = Self::load_snapshot(&path) {
                    out.push((path, snap));
                }
            }
        }
        out.sort_by(|a, b| b.0.cmp(&a.0));
        Ok(out)
    }

    /// Recent NyxTV logcat lines (redact-ish: truncate long tokens).
    pub fn recent_log(&self, lines: usize) -> Result<String> {
        let raw = self.adb.shell_ok(&[
            "logcat",
            "-d",
            "-t",
            &lines.to_string(),
            "-s",
            "NyxTV:*",
            "nyx.tv:*",
        ])?;
        let mut out = String::new();
        for line in raw.lines().rev().take(lines).collect::<Vec<_>>().into_iter().rev() {
            out.push_str(&redact_log_line(line));
            out.push('\n');
        }
        if out.trim().is_empty() {
            out = "No NyxTV log lines yet. Open the app on the TV, then refresh.\n".into();
        }
        Ok(out)
    }

    /// Load catalog entries for this TV: TOML under `data/debloat/` (or XDG),
    /// else the built-in fallback. Only packages present on the TV are returned.
    pub fn debloat_catalog_entries(&self) -> Result<Vec<(String, String, DebloatKind)>> {
        let fp = self.adb.getprop("ro.build.fingerprint").unwrap_or_default();
        let model = self.adb.getprop("ro.product.device").unwrap_or_else(|_| {
            self.adb
                .getprop("ro.product.model")
                .unwrap_or_else(|_| "unknown".into())
        });
        if let Some(entries) = load_debloat_toml(&self.root, &model, &fp) {
            return Ok(entries);
        }
        Ok(builtin_catalog_entries())
    }

    /// Catalog ∩ installed packages, with enabled state. Empty if none of the
    /// known IDs are on this TV.
    pub fn debloat_packages(&self) -> Result<Vec<DebloatPackage>> {
        let mut out = Vec::new();
        for (package, title, kind) in self.debloat_catalog_entries()? {
            if !self.package_present(&package)? {
                continue;
            }
            let enabled = self.enabled_state(&package)? == "enabled";
            out.push(DebloatPackage {
                package,
                title,
                kind,
                enabled,
            });
        }
        Ok(out)
    }

    /// Disable packages for the current user (`pm disable-user`). Refuses
    /// protected catalog entries and stock/Play/Chromecast/Nyx.
    pub fn disable_packages(&self, packages: &[&str], confirm: bool) -> Result<Vec<String>> {
        if !confirm {
            bail!("disable_packages refused: confirm was false");
        }
        let catalog = self.debloat_catalog_entries().unwrap_or_default();
        let mut done = Vec::new();
        for pkg in packages {
            if Self::debloat_is_protected(pkg)
                || catalog
                    .iter()
                    .any(|(p, _, k)| p == pkg && *k == DebloatKind::Protected)
            {
                bail!("refusing to disable protected package {pkg}");
            }
            if !self.package_present(pkg)? {
                continue;
            }
            self.adb.shell(&["pm", "disable-user", "--user", "0", pkg])?;
            done.push((*pkg).to_string());
        }
        Ok(done)
    }

    /// Re-enable a previously disabled package.
    pub fn enable_package(&self, package: &str) -> Result<()> {
        if Self::debloat_is_protected(package) && package != STOCK_LAUNCHER && package != SETUPWRAITH
        {
            // Allow enabling Play/Cast if someone disabled them outside Manager;
            // still refuse nothing here — enable is safe. Protected check is for disable.
        }
        self.adb.shell(&["pm", "enable", package])?;
        Ok(())
    }

    pub fn enable_packages(&self, packages: &[&str]) -> Result<Vec<String>> {
        let mut done = Vec::new();
        for pkg in packages {
            if !self.package_present(pkg)? {
                continue;
            }
            self.enable_package(pkg)?;
            done.push((*pkg).to_string());
        }
        Ok(done)
    }

    fn debloat_is_protected(package: &str) -> bool {
        matches!(
            package,
            STOCK_LAUNCHER
                | SETUPWRAITH
                | "com.android.vending"
                | "com.google.android.apps.mediashell"
                | "com.tcl.versionUpdateApp"
                | "com.tcl.tv"
                | "com.tcl.tvinput"
                | "com.tcl.settings"
                | NYX_TV_PKG
                | NYX_TV_PKG_DEBUG
        ) || DEBLOAT_CATALOG
            .iter()
            .any(|&(p, _, k)| p == package && k == DebloatKind::Protected)
    }
}

/// Search order: `NYX_TV_DEBLOAT_DIR`, `<root>/data/debloat`, XDG data `nyx-tv-manager/debloat`.
fn load_debloat_toml(
    root: &Path,
    model: &str,
    fingerprint: &str,
) -> Option<Vec<(String, String, DebloatKind)>> {
    let safe = fingerprint_safe(fingerprint);
    let model_dir = if model.is_empty() {
        "unknown".to_string()
    } else {
        model.replace(['/', ' '], "_")
    };
    let mut dirs = Vec::new();
    if let Ok(extra) = std::env::var("NYX_TV_DEBLOAT_DIR") {
        dirs.push(PathBuf::from(extra));
    }
    dirs.push(root.join("data/debloat"));
    if let Ok(xdg) = std::env::var("XDG_DATA_HOME") {
        dirs.push(PathBuf::from(xdg).join("nyx-tv-manager/debloat"));
    } else if let Ok(home) = std::env::var("HOME") {
        dirs.push(PathBuf::from(home).join(".local/share/nyx-tv-manager/debloat"));
    }

    let mut candidates = Vec::new();
    for dir in &dirs {
        candidates.push(dir.join(&model_dir).join(format!("{safe}.toml")));
        candidates.push(dir.join(format!("{safe}.toml")));
        // Any TOML under model dir whose fingerprint field matches.
        let model_path = dir.join(&model_dir);
        if model_path.is_dir() {
            if let Ok(rd) = fs::read_dir(&model_path) {
                for entry in rd.flatten() {
                    let p = entry.path();
                    if p.extension().and_then(|e| e.to_str()) == Some("toml") {
                        candidates.push(p);
                    }
                }
            }
        }
    }

    let mut seen = std::collections::HashSet::new();
    for path in candidates {
        if !seen.insert(path.clone()) {
            continue;
        }
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        let Ok(file) = toml::from_str::<DebloatCatalogFile>(&text) else {
            continue;
        };
        if !file.fingerprint.is_empty()
            && !fingerprint.is_empty()
            && file.fingerprint != fingerprint
        {
            continue;
        }
        if file.packages.is_empty() {
            continue;
        }
        return Some(
            file.packages
                .into_iter()
                .map(|e| (e.package, e.title, e.kind))
                .collect(),
        );
    }
    None
}

fn parse_total_pss_mb(dump: &str) -> Option<u64> {
    for line in dump.lines() {
        let t = line.trim();
        // "TOTAL PSS: 54832" or "TOTAL:    54832"
        if t.starts_with("TOTAL PSS:") || (t.starts_with("TOTAL:") && !t.contains("SWAP")) {
            let num: String = t
                .chars()
                .skip_while(|c| !c.is_ascii_digit())
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if let Ok(kb) = num.parse::<u64>() {
                return Some((kb + 512) / 1024);
            }
        }
    }
    None
}

fn cpu_idle_pct(a: &str, b: &str) -> Option<f64> {
    let pa = parse_proc_stat_cpu(a)?;
    let pb = parse_proc_stat_cpu(b)?;
    let d_total = pb.0.saturating_sub(pa.0) as f64;
    let d_idle = pb.1.saturating_sub(pa.1) as f64;
    if d_total < 1.0 {
        return None;
    }
    // Show busy % like the mockup's "0.3%" idle CPU label means utilization when idle.
    Some(((d_total - d_idle) / d_total) * 100.0)
}

fn parse_proc_stat_cpu(s: &str) -> Option<(u64, u64)> {
    let line = s.lines().next()?;
    let mut parts = line.split_whitespace();
    if parts.next()? != "cpu" {
        return None;
    }
    let nums: Vec<u64> = parts.filter_map(|x| x.parse().ok()).collect();
    if nums.len() < 4 {
        return None;
    }
    let total: u64 = nums.iter().sum();
    let idle = nums[3];
    Some((total, idle))
}

fn redact_log_line(line: &str) -> String {
    let mut s = line.to_string();
    for key in ["token", "Token", "auth", "Auth"] {
        if let Some(i) = s.find(key)
            && let Some(rest) = s.get(i..)
            && rest.len() > key.len() + 8
        {
            let mut start = (i + key.len() + 4).min(s.len());
            while start < s.len() && !s.is_char_boundary(start) {
                start += 1;
            }
            let mut end = (start + 24).min(s.len());
            while end > start && !s.is_char_boundary(end) {
                end -= 1;
            }
            if start < end {
                s.replace_range(start..end, "••••••••");
            }
        }
    }
    s
}

fn copy_dir(src: &Path, dest: &Path) -> Result<()> {
    fs::create_dir_all(dest)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let to = dest.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir(&entry.path(), &to)?;
        } else {
            fs::copy(entry.path(), to)?;
        }
    }
    Ok(())
}

/// Find a debug/release APK under the android checkout.
pub fn find_local_apk(repo: &Path, debug: bool) -> Option<PathBuf> {
    let name = if debug {
        "app-debug.apk"
    } else {
        "app-release.apk"
    };
    let candidates = [
        repo.join("android/app/build/outputs/apk/debug").join(name),
        repo.join("android/app/build/outputs/apk/release").join(name),
        repo.join("android/app/build/outputs/apk")
            .join(if debug { "debug" } else { "release" })
            .join(name),
    ];
    candidates.into_iter().find(|p| p.is_file())
}

/// Facts about a local APK for Install and update (version from aapt or Gradle metadata).
#[derive(Debug, Clone)]
pub struct ApkInfo {
    pub path: PathBuf,
    pub version_name: String,
    pub version_code: Option<u32>,
    pub package_id: Option<String>,
    pub size_label: String,
    pub modified_label: String,
}

pub fn apk_info(path: &Path) -> ApkInfo {
    let size_label = fs::metadata(path)
        .map(|m| {
            let b = m.len() as f64;
            if b >= 1_048_576.0 {
                format!("{:.1} MB", b / 1_048_576.0)
            } else {
                format!("{:.0} KB", b / 1024.0)
            }
        })
        .unwrap_or_else(|_| "?".into());
    let modified_label = fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .map(|t| {
            let dt: chrono::DateTime<Local> = t.into();
            dt.format("%b %-d").to_string()
        })
        .unwrap_or_else(|| "—".into());

    let mut version_name = String::new();
    let mut version_code = None;
    let mut package_id = None;

    // Prefer Gradle output-metadata.json next to the APK (no aapt required).
    if let Some(meta) = path.parent().map(|d| d.join("output-metadata.json")) {
        if let Ok(text) = fs::read_to_string(&meta) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                if let Some(el) = v.get("elements").and_then(|e| e.get(0)) {
                    if let Some(s) = el.get("versionName").and_then(|x| x.as_str()) {
                        version_name = s.to_string();
                    }
                    version_code = el
                        .get("versionCode")
                        .and_then(|x| x.as_u64())
                        .map(|n| n as u32);
                }
                if let Some(s) = v.get("applicationId").and_then(|x| x.as_str()) {
                    package_id = Some(s.to_string());
                }
            }
        }
    }

    // Fall back to aapt badging when metadata is missing.
    if version_name.is_empty() {
        if let Some((ver, code, pkg)) = aapt_badging(path) {
            version_name = ver;
            version_code = code.or(version_code);
            if package_id.is_none() {
                package_id = pkg;
            }
        }
    }

    if version_name.is_empty() {
        version_name = path
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| "APK".into());
    }

    ApkInfo {
        path: path.to_path_buf(),
        version_name,
        version_code,
        package_id,
        size_label,
        modified_label,
    }
}

fn aapt_badging(path: &Path) -> Option<(String, Option<u32>, Option<String>)> {
    let mut bins = Vec::new();
    if let Ok(home) = std::env::var("ANDROID_HOME").or_else(|_| std::env::var("ANDROID_SDK_ROOT"))
    {
        if let Ok(rd) = fs::read_dir(Path::new(&home).join("build-tools")) {
            let mut vers: Vec<PathBuf> = rd.filter_map(|e| e.ok().map(|e| e.path())).collect();
            vers.sort();
            for v in vers.into_iter().rev() {
                let a = v.join("aapt");
                if a.is_file() {
                    bins.push(a);
                    break;
                }
            }
        }
    }
    bins.push(PathBuf::from("aapt"));
    for bin in bins {
        let Ok(out) = std::process::Command::new(&bin)
            .args(["dump", "badging"])
            .arg(path)
            .output()
        else {
            continue;
        };
        if !out.status.success() {
            continue;
        }
        let text = String::from_utf8_lossy(&out.stdout);
        let line = text.lines().next()?;
        // package: name='…' versionCode='1' versionName='0.1.0'
        let mut ver = None;
        let mut code = None;
        let mut pkg = None;
        for part in line.split_whitespace() {
            if let Some(v) = part.strip_prefix("name='").and_then(|s| s.strip_suffix('\'')) {
                pkg = Some(v.to_string());
            }
            if let Some(v) = part
                .strip_prefix("versionName='")
                .and_then(|s| s.strip_suffix('\''))
            {
                ver = Some(v.to_string());
            }
            if let Some(v) = part
                .strip_prefix("versionCode='")
                .and_then(|s| s.strip_suffix('\''))
            {
                code = v.parse().ok();
            }
        }
        if let Some(ver) = ver {
            return Some((ver, code, pkg));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fingerprint_safe_replaces_slash_and_colon() {
        let s = fingerprint_safe(
            "TCL/G03_4K_US_NF_DB/BeyondTV4:11/RP1A.200622.001/AR26:user/release-keys",
        );
        assert!(!s.contains('/'));
        assert!(!s.contains(':'));
        assert!(s.contains("BeyondTV4"));
    }

    #[test]
    fn loads_bedroom_debloat_toml_from_checkout() {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
        let fp = "TCL/G03_4K_US_NF_DB/BeyondTV4:11/RP1A.200622.001/AR26:user/release-keys";
        let entries = load_debloat_toml(&root, "BeyondTV4", fp).expect("toml present in checkout");
        assert!(entries.iter().any(|(p, _, k)| {
            p == "com.tcl.bi" && *k == DebloatKind::Optional
        }));
        assert!(entries.iter().any(|(p, _, k)| {
            p == STOCK_LAUNCHER && *k == DebloatKind::Protected
        }));
    }
}
