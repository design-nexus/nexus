//! One module per sidebar section. Each has `build()` (the page widget, built
//! once) and `render(&State)` (redraws its body from state).

pub mod add;
pub mod backups;
pub mod debloat;
pub mod diagnostics;
pub mod home;
pub mod install;
pub mod overview;

use crate::page::{self, Page};
use crate::state::State;
use crate::{sync, window};

/// Draw the "no TV" / "connecting" / "offline" state when the page can't
/// show anything else. Returns true when it did.
pub fn gate(p: &Page, st: &State, needs_connection: bool) -> bool {
    if st.selected().is_none() {
        p.add_center(&page::empty(
            "video-display-symbolic",
            "No TV yet",
            "Add a TV on your network to manage it here.",
            Some(("Add a TV", Box::new(|| window::show_section("add")))),
        ));
        return true;
    }
    if needs_connection && !st.connected() {
        let name = st.selected_name();
        if !st.tv_checked {
            p.add_center(&page::empty(
                "network-wireless-symbolic",
                &format!("Connecting to {name}…"),
                "",
                None,
            ));
        } else {
            p.add_center(&offline(&name));
        }
        return true;
    }
    false
}

pub fn offline(name: &str) -> gtk::Widget {
    page::empty(
        "network-offline-symbolic",
        &format!("{name} isn't answering"),
        "Check that the TV is on and that Wireless debugging is still on in Developer options. TV Manager finds the TV again if its port changed.",
        Some(("Try again", Box::new(sync::refresh_tv))),
    )
}

/// Compare dotted version names ("0.1.10" > "0.1.9"). Non-numeric parts
/// compare as text.
pub fn cmp_versions(a: &str, b: &str) -> std::cmp::Ordering {
    let parts = |s: &str| -> Vec<String> {
        s.trim_start_matches('v')
            .split(['.', '-', '+'])
            .map(str::to_string)
            .collect()
    };
    let (pa, pb) = (parts(a), parts(b));
    for i in 0..pa.len().max(pb.len()) {
        let x = pa.get(i).map(String::as_str).unwrap_or("0");
        let y = pb.get(i).map(String::as_str).unwrap_or("0");
        let o = match (x.parse::<u64>(), y.parse::<u64>()) {
            (Ok(m), Ok(n)) => m.cmp(&n),
            _ => x.cmp(y),
        };
        if o != std::cmp::Ordering::Equal {
            return o;
        }
    }
    std::cmp::Ordering::Equal
}

#[cfg(test)]
mod tests {
    use super::cmp_versions;
    use std::cmp::Ordering::*;

    #[test]
    fn versions() {
        assert_eq!(cmp_versions("0.1.10", "0.1.9"), Greater);
        assert_eq!(cmp_versions("0.1.0", "0.1"), Equal);
        assert_eq!(cmp_versions("v1.2.0", "1.2.0"), Equal);
        assert_eq!(cmp_versions("0.2.0", "0.10.0"), Less);
    }
}
