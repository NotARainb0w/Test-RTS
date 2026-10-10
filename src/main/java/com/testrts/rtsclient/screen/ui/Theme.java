package com.testrts.rtsclient.screen.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Colours and drawing helpers shared by every RTS screen, so they all look like one UI. */
public final class Theme {
    // Surfaces
    public static final int PANEL = 0xD0121820;
    public static final int PANEL_HEADER = 0xE01A2230;
    public static final int BORDER = 0xFF2C3846;
    public static final int HIGHLIGHT = 0x22FFFFFF;
    public static final int SHADOW = 0x50000000;

    // Buttons
    public static final int BTN = 0xFF222B37;
    public static final int BTN_HOVER = 0xFF2E3A49;
    public static final int BTN_SELECTED = 0xFF1C4E6E;
    public static final int BTN_DISABLED = 0xFF1A1F27;

    // Slots
    public static final int SLOT = 0xFF0E1319;
    public static final int SLOT_EDGE = 0xFF27313D;
    public static final int SLOT_HOVER = 0xFF2A3644;

    // Text and accents
    public static final int TEXT = 0xFFE6EDF3;
    public static final int TEXT_DIM = 0xFF8B98A5;
    public static final int TEXT_FAINT = 0xFF5D6873;
    public static final int ACCENT = 0xFF4FC3F7;
    public static final int GOLD = 0xFFFFB74D;
    public static final int GOOD = 0xFF66BB6A;
    public static final int BAD = 0xFFEF5350;

    public static final int HEADER_H = 12;

    private Theme() {
    }

    /** A rectangle with its four corner pixels cut, which reads as slightly rounded. */
    public static void round(DrawContext ctx, int x, int y, int w, int h, int color) {
        if (w <= 2 || h <= 2) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        ctx.fill(x + 1, y, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 1px outline following {@link #round}. */
    public static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 1, y, x + w - 1, y + 1, color);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A panel: soft drop shadow, translucent body, border, and an optional header with a title. */
    public static void panel(DrawContext ctx, TextRenderer font, int x0, int y0, int x1, int y1, String title) {
        int w = x1 - x0;
        int h = y1 - y0;
        round(ctx, x0 + 1, y0 + 2, w, h, SHADOW);
        round(ctx, x0, y0, w, h, PANEL);
        outline(ctx, x0, y0, w, h, BORDER);
        ctx.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, HIGHLIGHT);
        if (title != null && !title.isEmpty()) {
            ctx.fill(x0 + 1, y0 + 1, x1 - 1, y0 + HEADER_H, PANEL_HEADER);
            ctx.fill(x0 + 1, y0 + HEADER_H, x1 - 1, y0 + HEADER_H + 1, BORDER);
            ctx.drawText(font, title, x0 + 5, y0 + 3, TEXT_DIM, false);
        }
    }

    /** A thin labelled divider inside a panel. */
    public static void section(DrawContext ctx, TextRenderer font, int x0, int x1, int y, String title) {
        ctx.drawText(font, title, x0, y, TEXT_FAINT, false);
        int tx = x0 + font.getWidth(title) + 4;
        if (tx < x1) {
            ctx.fill(tx, y + 4, x1, y + 5, BORDER);
        }
    }

    /** Progress bar, {@code progress} in 0..1. */
    public static void bar(DrawContext ctx, int x, int y, int w, int h, float progress, int color) {
        ctx.fill(x, y, x + w, y + h, 0xFF0B0F14);
        int fill = Math.round(Math.max(0F, Math.min(1F, progress)) * w);
        if (fill > 0) {
            ctx.fill(x, y, x + fill, y + h, color);
            ctx.fill(x, y, x + fill, y + 1, HIGHLIGHT);
        }
    }

    /** Small rounded tag, e.g. a key hint. Returns its width. */
    public static int chip(DrawContext ctx, TextRenderer font, String text, int x, int y, int bg, int fg) {
        int w = font.getWidth(text) + 6;
        round(ctx, x, y, w, 11, bg);
        ctx.drawText(font, text, x + 3, y + 2, fg, false);
        return w;
    }

    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 255) * Math.max(0F, Math.min(1F, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
