//! Saved TVs under XDG config (`nyx-tv-manager/devices.toml`).

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SavedTv {
    pub serial: String,
    #[serde(default)]
    pub nickname: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct DeviceFile {
    #[serde(default)]
    pub devices: Vec<SavedTv>,
    #[serde(default)]
    pub selected: Option<String>,
}

fn config_path() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            dirs_fallback_home().join(".config")
        });
    base.join("nyx-tv-manager").join("devices.toml")
}

fn dirs_fallback_home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn load() -> DeviceFile {
    let path = config_path();
    let Ok(text) = fs::read_to_string(&path) else {
        return DeviceFile::default();
    };
    toml::from_str(&text).unwrap_or_default()
}

pub fn save(file: &DeviceFile) -> Result<()> {
    let path = config_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let text = toml::to_string_pretty(file).context("encode devices.toml")?;
    fs::write(&path, text)?;
    Ok(())
}

pub fn repo_root() -> PathBuf {
    // Prefer the checkout that contains this binary's source (dev), else cwd.
    let mut dir = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for _ in 0..6 {
        if dir.join("android").is_dir() && dir.join("Cargo.toml").is_file() {
            return dir;
        }
        if !dir.pop() {
            break;
        }
    }
    // Walk up from CARGO_MANIFEST_DIR at compile time for installed builds from a checkout.
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    if let Some(root) = manifest.parent().and_then(|p| p.parent()) {
        if root.join("android").is_dir() {
            return root.to_path_buf();
        }
    }
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}
