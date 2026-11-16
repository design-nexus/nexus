//! What the Manager did to each TV, kept across restarts
//! (`$XDG_STATE_HOME/nyx-tv-manager/activity.toml`, newest 50 per TV).

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

const KEEP: usize = 50;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entry {
    pub tv: String,
    /// Unix seconds.
    pub time: i64,
    pub text: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Log {
    #[serde(default, rename = "entry")]
    pub entries: Vec<Entry>,
}

impl Log {
    pub fn push(&mut self, tv: &str, text: &str) {
        self.entries.push(Entry {
            tv: tv.to_string(),
            time: glib_now(),
            text: text.to_string(),
        });
        let mine = self.entries.iter().filter(|e| e.tv == tv).count();
        if mine > KEEP {
            let mut drop = mine - KEEP;
            self.entries.retain(|e| {
                if drop > 0 && e.tv == tv {
                    drop -= 1;
                    false
                } else {
                    true
                }
            });
        }
        save(self);
    }

    /// Newest first.
    pub fn for_tv(&self, tv: &str, n: usize) -> Vec<Entry> {
        self.entries
            .iter()
            .rev()
            .filter(|e| e.tv == tv)
            .take(n)
            .cloned()
            .collect()
    }
}

fn glib_now() -> i64 {
    gtk::glib::DateTime::now_local()
        .map(|d| d.to_unix())
        .unwrap_or(0)
}

/// "14:03" today, "Nov 14" before that.
pub fn when(unix: i64) -> String {
    let Ok(t) = gtk::glib::DateTime::from_unix_local(unix) else {
        return String::new();
    };
    let today = gtk::glib::DateTime::now_local()
        .map(|n| n.ymd() == t.ymd())
        .unwrap_or(false);
    let fmt = if today { "%H:%M" } else { "%b %-d" };
    t.format(fmt).map(|s| s.to_string()).unwrap_or_default()
}

fn path() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::devices::home().join(".local/state"))
        .join("nyx-tv-manager")
        .join("activity.toml")
}

pub fn load() -> Log {
    fs::read_to_string(path())
        .ok()
        .and_then(|t| toml::from_str(&t).ok())
        .unwrap_or_default()
}

fn save(log: &Log) {
    let p = path();
    if let Some(dir) = p.parent() {
        let _ = fs::create_dir_all(dir);
    }
    if let Ok(text) = toml::to_string_pretty(log) {
        let _ = fs::write(p, text);
    }
}
