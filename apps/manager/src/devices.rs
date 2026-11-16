//! Saved TVs under XDG config (`nyx-tv-manager/devices.toml`).
//!
//! A TV is known by its host (IP address) rather than `host:port`: wireless
//! debugging picks a new port after every restart. Older files that only have
//! `serial = "host:port"` are read the same way.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct SavedTv {
    /// Last serial adb knew the TV by (`host:port`, an mDNS name or USB serial).
    pub serial: String,
    #[serde(default)]
    pub nickname: String,
    /// Network host, without the port. Empty for USB TVs.
    #[serde(default)]
    pub host: String,
    /// Wireless-debugging mDNS instance name (`adb-XXXX-yyyy`), when known.
    #[serde(default)]
    pub mdns_name: String,
}

impl SavedTv {
    pub fn new(serial: &str, nickname: &str, mdns_name: &str) -> Self {
        let mut tv = SavedTv {
            serial: serial.to_string(),
            nickname: nickname.to_string(),
            host: String::new(),
            mdns_name: mdns_name.to_string(),
        };
        tv.fill_host();
        tv
    }

    /// Stable id used for selection, activity and online state.
    pub fn key(&self) -> &str {
        if self.host.is_empty() {
            &self.serial
        } else {
            &self.host
        }
    }

    pub fn name(&self) -> &str {
        if self.nickname.trim().is_empty() {
            self.key()
        } else {
            &self.nickname
        }
    }

    /// True when an adb serial refers to this TV.
    pub fn matches(&self, serial: &str) -> bool {
        if serial == self.serial {
            return true;
        }
        if !self.host.is_empty() && host_of(serial).as_deref() == Some(self.host.as_str()) {
            return true;
        }
        !self.mdns_name.is_empty() && serial.starts_with(&self.mdns_name)
    }

    fn fill_host(&mut self) {
        if self.host.is_empty()
            && let Some(h) = host_of(&self.serial) {
                self.host = h;
            }
    }
}

/// The host part of a network serial (`192.168.1.4:37215` → `192.168.1.4`).
/// USB serials and mDNS names have no host.
pub fn host_of(serial: &str) -> Option<String> {
    let (host, port) = serial.rsplit_once(':')?;
    if host.is_empty() || port.is_empty() || !port.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    if host.contains("._adb") || host.contains("._tcp") {
        return None;
    }
    Some(host.trim_start_matches('[').trim_end_matches(']').to_string())
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct DeviceFile {
    #[serde(default)]
    pub devices: Vec<SavedTv>,
    /// Key of the selected TV (older files hold its serial).
    #[serde(default)]
    pub selected: Option<String>,
}

impl DeviceFile {
    /// Fill in hosts and turn an old selected serial into a key.
    fn migrate(&mut self) {
        for d in &mut self.devices {
            d.fill_host();
        }
        if let Some(sel) = self.selected.clone() {
            if let Some(d) = self.devices.iter().find(|d| d.key() == sel || d.matches(&sel)) {
                self.selected = Some(d.key().to_string());
            } else {
                self.selected = None;
            }
        }
    }

    pub fn find(&self, key: &str) -> Option<&SavedTv> {
        self.devices.iter().find(|d| d.key() == key)
    }

    pub fn find_mut(&mut self, key: &str) -> Option<&mut SavedTv> {
        self.devices.iter_mut().find(|d| d.key() == key)
    }

    /// Add a TV, or update the one with the same host.
    pub fn upsert(&mut self, tv: SavedTv) -> String {
        let key = tv.key().to_string();
        if let Some(d) = self
            .devices
            .iter_mut()
            .find(|d| d.key() == key || d.matches(&tv.serial))
        {
            d.serial = tv.serial;
            if !tv.mdns_name.is_empty() {
                d.mdns_name = tv.mdns_name;
            }
            if d.nickname.trim().is_empty() {
                d.nickname = tv.nickname;
            }
            if d.host.is_empty() {
                d.host = tv.host;
            }
            return d.key().to_string();
        }
        self.devices.push(tv);
        key
    }
}

fn config_path() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".config"));
    base.join("nyx-tv-manager").join("devices.toml")
}

pub fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn parse(text: &str) -> DeviceFile {
    let mut file: DeviceFile = toml::from_str(text).unwrap_or_default();
    file.migrate();
    file
}

pub fn load() -> DeviceFile {
    match fs::read_to_string(config_path()) {
        Ok(text) => parse(&text),
        Err(_) => DeviceFile::default(),
    }
}

pub fn save(file: &DeviceFile) -> Result<()> {
    let path = config_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let text = toml::to_string_pretty(file).context("encode devices.toml")?;
    let tmp = path.with_extension("toml.tmp");
    fs::write(&tmp, text)?;
    fs::rename(&tmp, &path)?;
    Ok(())
}

pub fn repo_root() -> PathBuf {
    // Prefer the checkout that contains the working directory (dev), else the
    // checkout this binary was built from.
    let mut dir = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    for _ in 0..6 {
        if dir.join("android").is_dir() && dir.join("Cargo.toml").is_file() {
            return dir;
        }
        if !dir.pop() {
            break;
        }
    }
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    if let Some(root) = manifest.parent().and_then(|p| p.parent())
        && root.join("android").is_dir() {
            return root.to_path_buf();
        }
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_from_serials() {
        assert_eq!(host_of("192.168.1.41:37215").as_deref(), Some("192.168.1.41"));
        assert_eq!(host_of("192.168.1.41:5555").as_deref(), Some("192.168.1.41"));
        assert_eq!(host_of("[fe80::1]:5555").as_deref(), Some("fe80::1"));
        assert_eq!(host_of("R58M12ABCDE"), None);
        assert_eq!(host_of("adb-1234-abcd._adb-tls-connect._tcp"), None);
    }

    #[test]
    fn old_file_migrates_to_host_keys() {
        let old = r#"
selected = "192.168.1.41:37215"

[[devices]]
serial = "192.168.1.41:37215"
nickname = "Living Room"

[[devices]]
serial = "192.168.1.50:41111"
"#;
        let f = parse(old);
        assert_eq!(f.devices[0].host, "192.168.1.41");
        assert_eq!(f.devices[0].key(), "192.168.1.41");
        assert_eq!(f.selected.as_deref(), Some("192.168.1.41"));
        assert_eq!(f.devices[1].name(), "192.168.1.50");
    }

    #[test]
    fn new_port_still_matches() {
        let tv = SavedTv::new("192.168.1.41:37215", "Living Room", "adb-XYZ-abc");
        assert!(tv.matches("192.168.1.41:40001"));
        assert!(tv.matches("adb-XYZ-abc._adb-tls-connect._tcp"));
        assert!(!tv.matches("192.168.1.42:37215"));
    }

    #[test]
    fn upsert_updates_port_and_keeps_name() {
        let mut f = DeviceFile::default();
        f.upsert(SavedTv::new("10.0.0.5:1111", "Bedroom", ""));
        let key = f.upsert(SavedTv::new("10.0.0.5:2222", "", "adb-1"));
        assert_eq!(key, "10.0.0.5");
        assert_eq!(f.devices.len(), 1);
        assert_eq!(f.devices[0].serial, "10.0.0.5:2222");
        assert_eq!(f.devices[0].nickname, "Bedroom");
        assert_eq!(f.devices[0].mdns_name, "adb-1");
    }

    #[test]
    fn unknown_selection_is_dropped() {
        let f = parse("selected = \"gone\"\n");
        assert_eq!(f.selected, None);
    }
}
