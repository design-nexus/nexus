//! Thin wrapper around the system `adb` binary.

use anyhow::{anyhow, bail, Context, Result};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::Duration;

/// A device from `adb devices -l`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Device {
    pub serial: String,
    pub state: String,
    pub model: Option<String>,
    pub product: Option<String>,
    pub transport_id: Option<String>,
}

/// A row from `adb mdns services` (wireless debugging discovery).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MdnsService {
    pub name: String,
    /// e.g. `_adb._tcp`, `_adb-tls-connect._tcp`, `_adb-tls-pairing._tcp`
    pub service_type: String,
    pub host_port: String,
}

impl MdnsService {
    pub fn is_pairing(&self) -> bool {
        self.service_type.contains("pairing")
    }

    pub fn is_connect(&self) -> bool {
        !self.is_pairing()
            && (self.service_type.contains("adb") || self.service_type.contains("connect"))
    }

    pub fn host(&self) -> &str {
        self.host_port
            .rsplit_once(':')
            .map(|(h, _)| h)
            .unwrap_or(self.host_port.as_str())
    }
}

/// Handle that always targets one serial (`-s`).
#[derive(Debug, Clone)]
pub struct Adb {
    bin: PathBuf,
    serial: String,
}

impl Adb {
    pub fn new(serial: impl Into<String>) -> Result<Self> {
        Ok(Self {
            bin: find_adb()?,
            serial: serial.into(),
        })
    }

    pub fn with_bin(bin: PathBuf, serial: impl Into<String>) -> Self {
        Self {
            bin,
            serial: serial.into(),
        }
    }

    pub fn serial(&self) -> &str {
        &self.serial
    }

    pub fn bin(&self) -> &Path {
        &self.bin
    }

    /// `adb connect host:port` then return a handle for that serial.
    pub fn connect(host_port: &str) -> Result<Self> {
        let bin = find_adb()?;
        let out = run_bin(&bin, &["connect", host_port])?;
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        let lower = text.to_lowercase();
        if out.status.success()
            && !lower.contains("disconnected")
            && !lower.contains("not connected")
            && !lower.contains("failed")
            && !lower.contains("cannot connect")
            && (lower.contains("connected") || lower.contains("already connected"))
        {
            return Ok(Self {
                bin,
                serial: host_port.to_string(),
            });
        }
        bail!("adb connect failed: {}", text.trim());
    }

    /// `adb pair host:port CODE` for wireless debugging pairing.
    pub fn pair(host_port: &str, code: &str) -> Result<()> {
        let bin = find_adb()?;
        let out = run_bin(&bin, &["pair", host_port, code])?;
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        if out.status.success() || text.to_lowercase().contains("success") {
            Ok(())
        } else {
            bail!("adb pair failed: {}", text.trim());
        }
    }

    pub fn devices() -> Result<Vec<Device>> {
        let bin = find_adb()?;
        let out = run_bin(&bin, &["devices", "-l"])?;
        Ok(parse_devices(&String::from_utf8_lossy(&out.stdout)))
    }

    /// Wireless debugging services from `adb mdns services`.
    pub fn mdns_services() -> Result<Vec<MdnsService>> {
        let bin = find_adb()?;
        let out = run_bin(&bin, &["mdns", "services"])?;
        Ok(parse_mdns(&String::from_utf8_lossy(&out.stdout)))
    }

    pub fn version_line() -> Result<String> {
        let bin = find_adb()?;
        let out = run_bin(&bin, &["version"])?;
        let text = String::from_utf8_lossy(&out.stdout);
        Ok(text
            .lines()
            .next()
            .unwrap_or("adb")
            .trim()
            .to_string())
    }

    pub fn shell(&self, args: &[&str]) -> Result<String> {
        let mut cmd = vec!["-s", self.serial.as_str(), "shell"];
        cmd.extend(args);
        let out = self.run(&cmd)?;
        Ok(strip_cr(&String::from_utf8_lossy(&out.stdout)))
    }

    pub fn shell_ok(&self, args: &[&str]) -> Result<String> {
        let mut cmd = vec!["-s", self.serial.as_str(), "shell"];
        cmd.extend(args);
        let out = self.run_allow_fail(&cmd)?;
        Ok(strip_cr(&String::from_utf8_lossy(&out.stdout)))
    }

    pub fn getprop(&self, key: &str) -> Result<String> {
        Ok(self.shell(&["getprop", key])?.trim().to_string())
    }

    pub fn install(&self, apk: &Path, replace: bool) -> Result<()> {
        let apk = apk
            .to_str()
            .ok_or_else(|| anyhow!("APK path is not UTF-8"))?;
        let mut args = vec!["-s", self.serial.as_str(), "install"];
        if replace {
            args.push("-r");
        }
        args.push(apk);
        let out = self.run(&args)?;
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        if text.contains("Success") {
            Ok(())
        } else {
            bail!("adb install failed: {}", text.trim());
        }
    }

    pub fn uninstall(&self, package: &str) -> Result<()> {
        let out = self.run(&["-s", self.serial.as_str(), "uninstall", package])?;
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        if text.contains("Success") {
            Ok(())
        } else {
            bail!("adb uninstall failed: {}", text.trim());
        }
    }

    pub fn push(&self, local: &Path, remote: &str) -> Result<()> {
        let local = local
            .to_str()
            .ok_or_else(|| anyhow!("local path is not UTF-8"))?;
        let out = self.run(&["-s", self.serial.as_str(), "push", local, remote])?;
        if out.status.success() {
            Ok(())
        } else {
            bail!(
                "adb push failed: {}",
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
    }

    pub fn pull(&self, remote: &str, local: &Path) -> Result<()> {
        let local = local
            .to_str()
            .ok_or_else(|| anyhow!("local path is not UTF-8"))?;
        let out = self.run(&["-s", self.serial.as_str(), "pull", remote, local])?;
        if out.status.success() {
            Ok(())
        } else {
            bail!(
                "adb pull failed: {}",
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
    }

    pub fn wait_for_device(&self, timeout: Duration) -> Result<()> {
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let devices = Self::devices()?;
            if devices.iter().any(|d| d.serial == self.serial && d.state == "device") {
                return Ok(());
            }
            if std::time::Instant::now() >= deadline {
                bail!("device {} not ready within {:?}", self.serial, timeout);
            }
            std::thread::sleep(Duration::from_millis(400));
        }
    }

    fn run(&self, args: &[&str]) -> Result<Output> {
        let out = run_bin(&self.bin, args)?;
        if out.status.success() {
            Ok(out)
        } else {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let stderr = String::from_utf8_lossy(&out.stderr);
            let detail = match (stdout.trim(), stderr.trim()) {
                ("", s) | (s, "") => s.to_string(),
                (a, b) => format!("{a}; {b}"),
            };
            bail!("adb {} failed ({}): {detail}", args.join(" "), out.status);
        }
    }

    fn run_allow_fail(&self, args: &[&str]) -> Result<Output> {
        run_bin(&self.bin, args)
    }
}

fn find_adb() -> Result<PathBuf> {
    if let Ok(p) = which("adb") {
        return Ok(p);
    }
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
    for candidate in [
        home.join("Android/Sdk/platform-tools/adb"),
        PathBuf::from("/usr/bin/adb"),
        PathBuf::from("/usr/local/bin/adb"),
    ] {
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    bail!("adb not found on PATH or in ~/Android/Sdk/platform-tools")
}

fn which(name: &str) -> Result<PathBuf> {
    let path = std::env::var_os("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path) {
        let p = dir.join(name);
        if p.is_file() {
            return Ok(p);
        }
    }
    bail!("{name} not on PATH")
}

fn run_bin(bin: &Path, args: &[&str]) -> Result<Output> {
    Command::new(bin)
        .args(args)
        .output()
        .with_context(|| format!("running {} {}", bin.display(), args.join(" ")))
}

fn strip_cr(s: &str) -> String {
    s.replace('\r', "")
}

fn parse_devices(text: &str) -> Vec<Device> {
    let mut out = Vec::new();
    for line in text.lines().skip(1) {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else { continue };
        let Some(state) = parts.next() else { continue };
        let mut model = None;
        let mut product = None;
        let mut transport_id = None;
        for p in parts {
            if let Some(v) = p.strip_prefix("model:") {
                model = Some(v.replace('_', " "));
            } else if let Some(v) = p.strip_prefix("product:") {
                product = Some(v.to_string());
            } else if let Some(v) = p.strip_prefix("transport_id:") {
                transport_id = Some(v.to_string());
            }
        }
        out.push(Device {
            serial: serial.to_string(),
            state: state.to_string(),
            model,
            product,
            transport_id,
        });
    }
    out
}

fn parse_mdns(text: &str) -> Vec<MdnsService> {
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with("List of") {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 3 {
            continue;
        }
        // name \t type \t host:port  (tabs or spaces)
        let name = parts[0].to_string();
        let service_type = parts[1].to_string();
        let host_port = parts[2].to_string();
        if !host_port.contains(':') {
            continue;
        }
        out.push(MdnsService {
            name,
            service_type,
            host_port,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_devices_list() {
        let text = "List of devices attached\n\
192.168.1.237:5555 device product:Smart_TV model:Smart_TV device:BeyondTV transport_id:1\n\
emulator-5554 offline\n";
        let d = parse_devices(text);
        assert_eq!(d.len(), 2);
        assert_eq!(d[0].serial, "192.168.1.237:5555");
        assert_eq!(d[0].state, "device");
        assert_eq!(d[0].model.as_deref(), Some("Smart TV"));
        assert_eq!(d[1].state, "offline");
    }

    #[test]
    fn parses_mdns_services() {
        let text = "List of discovered mdns services\n\
adb-AAA\t_adb._tcp\t192.168.1.41:5555\n\
adb-BBB\t_adb-tls-pairing._tcp\t192.168.1.41:37123\n";
        let s = parse_mdns(text);
        assert_eq!(s.len(), 2);
        assert!(s[0].is_connect());
        assert!(s[1].is_pairing());
        assert_eq!(s[1].host(), "192.168.1.41");
    }
}
