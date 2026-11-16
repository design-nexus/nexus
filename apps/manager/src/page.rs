//! The page scaffold every section uses: a header strip (title, dim subtitle,
//! icon actions, at most one primary button) over a scrolling body of cards.

use gtk::glib;
use gtk::prelude::*;
use gtk::gio;
use nyx_ui::widgets as w;

/// Widest the page content gets; wider windows centre it.
const MAX_WIDTH: i32 = 920;

/// An optional labelled action for empty states and banners.
pub type Action<'a> = Option<(&'a str, Box<dyn Fn()>)>;

#[derive(Clone)]
pub struct Page {
    pub root: gtk::Box,
    title: gtk::Label,
    sub: gtk::Label,
    actions: gtk::Box,
    pub body: gtk::Box,
}

impl Page {
    pub fn new(title: &str) -> Page {
        let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
        root.add_css_class("tm-page");
        root.set_hexpand(true);
        root.set_vexpand(true);

        let head = gtk::Box::new(gtk::Orientation::Horizontal, 8);
        head.add_css_class("tm-head");
        let titles = gtk::Box::new(gtk::Orientation::Horizontal, 10);
        titles.set_hexpand(true);
        titles.set_valign(gtk::Align::Center);
        let t = w::label(title, "tm-title");
        t.set_ellipsize(gtk::pango::EllipsizeMode::End);
        let sub = w::label("", "tm-sub");
        sub.set_ellipsize(gtk::pango::EllipsizeMode::End);
        sub.set_hexpand(true);
        titles.append(&t);
        titles.append(&sub);
        head.append(&titles);
        let actions = gtk::Box::new(gtk::Orientation::Horizontal, 4);
        actions.set_valign(gtk::Align::Center);
        head.append(&actions);
        root.append(&head);

        let body = gtk::Box::new(gtk::Orientation::Vertical, 14);
        body.add_css_class("tm-body");
        body.set_valign(gtk::Align::Start);
        let clamp = nyx_ui::clamp::Clamp::new(&body, MAX_WIDTH);
        clamp.set_shrink(true);
        let scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vscrollbar_policy(gtk::PolicyType::Automatic)
            .vexpand(true)
            .hexpand(true)
            .child(&clamp)
            .build();
        root.append(&scroll);

        Page {
            root,
            title: t,
            sub,
            actions,
            body,
        }
    }

    pub fn set_title(&self, text: &str) {
        self.title.set_text(text);
    }

    pub fn set_sub(&self, text: &str) {
        self.sub.set_text(text);
    }

    pub fn set_actions(&self, widgets: &[gtk::Widget]) {
        clear(&self.actions);
        for wdg in widgets {
            self.actions.append(wdg);
        }
    }

    /// Empty the body before drawing it again.
    pub fn clear(&self) {
        clear(&self.body);
        self.body.set_valign(gtk::Align::Start);
        self.body.set_vexpand(false);
    }

    pub fn add(&self, widget: &impl IsA<gtk::Widget>) {
        self.body.append(widget);
    }

    /// A centred empty, loading or error state filling the body.
    pub fn add_center(&self, widget: &impl IsA<gtk::Widget>) {
        self.body.set_valign(gtk::Align::Fill);
        self.body.set_vexpand(true);
        self.body.append(widget);
    }
}

pub fn clear(b: &gtk::Box) {
    while let Some(c) = b.first_child() {
        b.remove(&c);
    }
}

/// A card with a title row (and icon actions at its right). Returns the card
/// and the box rows go in.
pub fn card(title: &str, actions: &[gtk::Widget]) -> (gtk::Box, gtk::Box) {
    let card = gtk::Box::new(gtk::Orientation::Vertical, 0);
    card.add_css_class("card");
    card.add_css_class("tm-card");
    if !title.is_empty() || !actions.is_empty() {
        let head = gtk::Box::new(gtk::Orientation::Horizontal, 4);
        head.add_css_class("tm-card-head");
        let t = w::label(title, "card-title");
        t.set_hexpand(true);
        t.set_valign(gtk::Align::Center);
        head.append(&t);
        for a in actions {
            head.append(a);
        }
        card.append(&head);
    }
    let rows = gtk::Box::new(gtk::Orientation::Vertical, 0);
    rows.add_css_class("tm-rows");
    card.append(&rows);
    (card, rows)
}

/// An option row: title and description, an optional leading widget and
/// trailing controls.
pub fn row(
    title: &str,
    desc: &str,
    lead: Option<&gtk::Widget>,
    end: &[gtk::Widget],
) -> gtk::Box {
    let r = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    r.add_css_class("tm-row");
    if let Some(l) = lead {
        l.set_valign(gtk::Align::Center);
        r.append(l);
    }
    let text = gtk::Box::new(gtk::Orientation::Vertical, 2);
    text.set_hexpand(true);
    text.set_valign(gtk::Align::Center);
    let t = w::label(title, "tm-row-title");
    t.set_wrap(true);
    t.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    text.append(&t);
    if !desc.is_empty() {
        let d = w::label(desc, "tm-row-desc");
        d.set_wrap(true);
        d.set_wrap_mode(gtk::pango::WrapMode::WordChar);
        text.append(&d);
    }
    r.append(&text);
    for e in end {
        e.set_valign(gtk::Align::Center);
        r.append(e);
    }
    r
}

/// Two-column facts: dim key, value. `mono` values (ids, addresses) use the
/// monospace face and ellipsize.
pub fn facts(rows: &[(&str, String, bool)]) -> gtk::Grid {
    let g = gtk::Grid::new();
    g.add_css_class("tm-facts");
    g.set_column_spacing(18);
    g.set_row_spacing(8);
    for (i, (k, v, mono)) in rows.iter().enumerate() {
        let key = w::label(k, "tm-fact-k");
        key.set_valign(gtk::Align::Start);
        let val = w::label(v, "tm-fact-v");
        val.set_hexpand(true);
        val.set_selectable(true);
        val.set_can_focus(false);
        if *mono {
            val.add_css_class("mono");
            val.set_ellipsize(gtk::pango::EllipsizeMode::Middle);
            val.set_tooltip_text(Some(v));
        } else {
            val.set_wrap(true);
        }
        g.attach(&key, 0, i as i32, 1, 1);
        g.attach(&val, 1, i as i32, 1, 1);
    }
    g
}

/// The page's single primary button: icon and label, accent fill.
pub fn primary(icon: &str, label: &str, on_click: impl Fn() + 'static) -> gtk::Widget {
    let b = w::labeled_button(icon, label);
    b.add_css_class("suggested-action");
    b.set_valign(gtk::Align::Center);
    b.connect_clicked(move |_| on_click());
    b.upcast()
}

/// A flat icon button with a tooltip.
pub fn icon(name: &str, tooltip: &str, on_click: impl Fn() + 'static) -> gtk::Widget {
    let b = w::icon_button(name, tooltip);
    b.connect_clicked(move |_| on_click());
    b.upcast()
}

/// A plain text button (row actions where an icon can't say it).
pub fn button(label: &str, on_click: impl Fn() + 'static) -> gtk::Widget {
    let b = gtk::Button::with_label(label);
    b.connect_clicked(move |_| on_click());
    b.upcast()
}

/// A destructive action that arms on the first click and acts on the second.
pub fn danger(label: &str, armed: &str, act: impl Fn() + 'static) -> gtk::Widget {
    w::two_click(label, armed, act).upcast()
}

/// A dim tag ("Installed", "Newer"). `kind`: "", "accent" or "warning".
pub fn tag(text: &str, kind: &str) -> gtk::Widget {
    w::tag(text, kind).upcast()
}

/// A status dot: green when on.
pub fn dot(on: bool) -> gtk::Widget {
    let d = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    d.add_css_class("cdot");
    if on {
        d.add_css_class("on");
    }
    d.set_valign(gtk::Align::Center);
    d.upcast()
}

/// A centred icon, line and optional action.
pub fn empty(icon: &str, title: &str, desc: &str, action: Action<'_>) -> gtk::Widget {
    w::empty_state(icon, title, desc, action).upcast()
}

pub fn dim(text: &str) -> gtk::Label {
    let l = w::label(text, "tm-note");
    l.set_wrap(true);
    l.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    l
}

pub fn banner(text: &str, warning: bool, action: Action<'_>) -> gtk::Widget {
    let b = w::banner(&glib::markup_escape_text(text), warning);
    b.add_css_class("tm-banner");
    if let Some((label, act)) = action {
        let btn = gtk::Button::with_label(label);
        btn.set_valign(gtk::Align::Center);
        btn.connect_clicked(move |_| act());
        b.append(&btn);
    }
    b.upcast()
}

/// A dialog that names its action: Cancel first, then e.g. "Set as home".
pub fn confirm(title: &str, body: &str, action: &str, on_yes: impl FnOnce() + 'static) {
    confirm_or(title, body, action, on_yes, || {});
}

/// [`confirm`] with a callback when the user backs out.
pub fn confirm_or(
    title: &str,
    body: &str,
    action: &str,
    on_yes: impl FnOnce() + 'static,
    on_no: impl FnOnce() + 'static,
) {
    let Some(win) = crate::window::window() else {
        return;
    };
    let dialog = gtk::AlertDialog::builder()
        .modal(true)
        .message(title)
        .detail(body)
        .buttons(["Cancel", action])
        .cancel_button(0)
        .default_button(1)
        .build();
    let cell = std::cell::RefCell::new(Some((on_yes, on_no)));
    dialog.choose(Some(&win), None::<&gio::Cancellable>, move |res| {
        if let Some((yes, no)) = cell.borrow_mut().take() {
            if res.ok() == Some(1) {
                yes();
            } else {
                no();
            }
        }
    });
}

/// Disable every button in `root` while an operation runs.
pub fn set_busy(root: &impl IsA<gtk::Widget>, busy: bool) {
    fn walk(w: &gtk::Widget, busy: bool) {
        if w.is::<gtk::Button>() || w.is::<gtk::Switch>() {
            if !w.has_css_class("tm-keep") {
                w.set_sensitive(!busy);
            }
            return;
        }
        let mut c = w.first_child();
        while let Some(ch) = c {
            walk(&ch, busy);
            c = ch.next_sibling();
        }
    }
    walk(root.upcast_ref(), busy);
}

/// "Nov 15, 17:52" from a snapshot file name (`snapshot-MODEL-YYYYMMDD-HHMMSS.env`).
pub fn snapshot_when(name: &str) -> String {
    let stem = name.trim_end_matches(".env");
    let mut parts = stem.rsplitn(3, '-');
    let (Some(time), Some(date)) = (parts.next(), parts.next()) else {
        return stem.to_string();
    };
    if date.len() != 8 || time.len() != 6 {
        return stem.to_string();
    }
    let num = |s: &str| s.parse::<i32>().ok();
    let (Some(y), Some(mo), Some(d), Some(h), Some(mi)) = (
        num(&date[0..4]),
        num(&date[4..6]),
        num(&date[6..8]),
        num(&time[0..2]),
        num(&time[2..4]),
    ) else {
        return stem.to_string();
    };
    glib::DateTime::from_local(y, mo, d, h, mi, 0.0)
        .ok()
        .and_then(|t| t.format("%b %-d %Y, %H:%M").ok())
        .map(|s| s.to_string())
        .unwrap_or_else(|| stem.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn snapshot_names() {
        // Bad names fall back to the stem.
        assert_eq!(super::snapshot_when("weird.env"), "weird");
    }
}
