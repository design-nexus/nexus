//! Manager preferences (`~/.config/nyx-tv-manager/settings.toml`).

pub use nyx_theme::ThemeMode;

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Prefs {
    pub mode: ThemeMode,
    pub theme: String,
    pub glow: bool,
    pub reduce_motion: bool,
    pub sidebar_collapsed: bool,
}

impl Default for Prefs {
    fn default() -> Self {
        Self {
            // Follow the desktop; Appearance in Settings can pin Nyx Dark / another theme.
            mode: ThemeMode::Omarchy,
            theme: "nyx-dark".into(),
            glow: true,
            reduce_motion: false,
            sidebar_collapsed: false,
        }
    }
}

fn path() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            std::env::var_os("HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("."))
                .join(".config")
        });
    base.join("nyx-tv-manager").join("settings.toml")
}

pub fn get() -> Prefs {
    let Ok(text) = fs::read_to_string(path()) else {
        return Prefs::default();
    };
    toml::from_str(&text).unwrap_or_default()
}

pub fn update(f: impl FnOnce(&mut Prefs)) {
    let mut p = get();
    f(&mut p);
    if let Some(parent) = path().parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(text) = toml::to_string_pretty(&p) {
        let _ = fs::write(path(), text);
    }
}
