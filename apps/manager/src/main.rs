//! TV Manager — install and manage Nyx TV on Android TVs over adb.

mod add;
mod devices;
mod prefs;
mod settings;
mod theme;
mod window;

use gtk::prelude::*;
use gtk::glib;

pub const APP_ID: &str = "io.github.design_nexus.nyx.TvManager";

fn main() -> glib::ExitCode {
    if std::env::var_os("GSK_RENDERER").is_none() {
        // SAFETY: still single-threaded before GTK starts.
        unsafe { std::env::set_var("GSK_RENDERER", "ngl") };
    }

    let id = std::env::var("NYX_TV_MANAGER_APP_ID").unwrap_or_else(|_| APP_ID.to_string());
    let app = gtk::Application::builder()
        .application_id(&id)
        .build();

    app.connect_activate(|app| {
        if let Some(win) = app.active_window() {
            win.present();
            return;
        }
        theme::install();
        window::build(app);
    });

    app.run()
}
