package io.github.design_nexus.nyx.tv.ui

import android.graphics.Color

/** A theme: Nyx Design 2.0 tokens for the 10-foot UI. */
data class Palette(
    val id: String,
    val name: String,
    val light: Boolean,
    val crust: Int, val mantle: Int, val base: Int,
    val s0: Int, val s1: Int, val s2: Int,
    val text: Int, val sub1: Int, val sub0: Int, val o0: Int,
    val red: Int, val yellow: Int, val green: Int,
    val accent: Int,
) {
    val onAccent: Int get() = if (light) Color.WHITE else base
    fun withAccent(c: Int) = copy(accent = c)

    companion object {
        private fun c(hex: String) = Color.parseColor(hex)

        // Secondary text (sub1/sub0/o0) is brighter than stock Catppuccin so captions
        // stay readable on the TV at 10 feet and on translucent panels.
        val MOCHA = Palette("mocha", "Catppuccin Mocha", false, c("#11111b"), c("#181825"), c("#1e1e2e"), c("#313244"), c("#45475a"), c("#585b70"),
            c("#cdd6f4"), c("#c6d0e8"), c("#b4bcd8"), c("#8b90a8"), c("#f38ba8"), c("#f9e2af"), c("#a6e3a1"), c("#cba6f7"))
        val MACCHIATO = Palette("macchiato", "Catppuccin Macchiato", false, c("#181926"), c("#1e2030"), c("#24273a"), c("#363a4f"), c("#494d64"), c("#5b6078"),
            c("#cad3f5"), c("#c4cce8"), c("#b2bad6"), c("#8a90a8"), c("#ed8796"), c("#eed49f"), c("#a6da95"), c("#c6a0f6"))
        val FRAPPE = Palette("frappe", "Catppuccin Frappé", false, c("#232634"), c("#292c3c"), c("#303446"), c("#414559"), c("#51576d"), c("#626880"),
            c("#c6d0f5"), c("#c0c8e8"), c("#b0b8d6"), c("#8c92aa"), c("#e78284"), c("#e5c890"), c("#a6d189"), c("#ca9ee6"))
        val LATTE = Palette("latte", "Catppuccin Latte", true, c("#dce0e8"), c("#e6e9ef"), c("#eff1f5"), c("#ccd0da"), c("#bcc0cc"), c("#acb0be"),
            c("#2e3148"), c("#4c4f69"), c("#5c5f77"), c("#7c8098"), c("#d20f39"), c("#df8e1d"), c("#40a02b"), c("#8839ef"))
        val OLED = MOCHA.copy(id = "oled", name = "OLED Black", crust = Color.BLACK, mantle = c("#0b0b0f"), base = Color.BLACK, s0 = c("#1a1a1f"), s1 = c("#2a2a31"), s2 = c("#3a3a42"))
        val NYX = Palette("nyx", "Nyx Dark", false, c("#060609"), c("#121318"), c("#0d0e12"), c("#1f2028"), c("#2b2d37"), c("#3a3c48"),
            c("#e7e8ee"), c("#d0d2e0"), c("#b8bbcc"), c("#8488a0"), c("#f2655f"), c("#eeb43f"), c("#59d45b"), c("#3d8ae8"))

        val ALL = listOf(MOCHA, MACCHIATO, FRAPPE, LATTE, OLED, NYX)

        /** Accent name to (dark-theme colour, light-theme colour). */
        val ACCENTS = linkedMapOf(
            "mauve" to (c("#cba6f7") to c("#8839ef")),
            "blue" to (c("#89b4fa") to c("#1e66f5")),
            "lavender" to (c("#b4befe") to c("#7287fd")),
            "pink" to (c("#f5c2e7") to c("#ea76cb")),
            "peach" to (c("#fab387") to c("#fe640b")),
            "yellow" to (c("#f9e2af") to c("#df8e1d")),
            "green" to (c("#a6e3a1") to c("#40a02b")),
            "teal" to (c("#94e2d5") to c("#179299")),
            "red" to (c("#f38ba8") to c("#d20f39")),
        )

        fun of(theme: String, accent: String): Palette {
            val p = ALL.firstOrNull { it.id == theme } ?: MOCHA
            val a = ACCENTS[accent] ?: return p
            return p.withAccent(if (p.light) a.second else a.first)
        }
    }
}
