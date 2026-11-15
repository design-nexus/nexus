//! Design 2.0 theme for TV Manager.
//! Manager A mockups use the revamp kit (Nyx Dark surfaces, violet accent).

pub use nyx_theme::*;

pub fn install() {
    nyx_theme::use_design_2();
    // Mockup: <html data-accent="violet"> → --sw-violet
    nyx_theme::set_app_swatch("tv-manager", Some("violet"));
    nyx_theme::install(Style {
        window_class: "tv-manager-window",
        base_skip: &[],
        css: include_str!("style.css"),
        no_glow_css: "",
        extra_colors: None,
        prefs: || {
            let p = crate::prefs::get();
            ThemePrefs {
                mode: p.mode,
                theme: p.theme,
                glow: p.glow,
                reduce_motion: p.reduce_motion,
            }
        },
        set_theme: Some(|mode, theme| {
            crate::prefs::update(|p| {
                p.mode = mode;
                p.theme = theme.to_string();
            });
        }),
    });
}
