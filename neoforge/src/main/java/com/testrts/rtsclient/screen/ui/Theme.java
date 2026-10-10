package com.testrts.rtsclient.screen.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Colours and drawing helpers shared by every RTS screen, so they all look like one UI. */
public final class Theme {
    // Surfaces
    public static final int PANEL = 0xD0121820;
    public static final int PANEL_TOP = 0xD81A2330;
    public static final int PANEL_HEADER = 0xE0182230;
    public static final int PANEL_HEADER_TOP = 0xE0243246;
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
    public static void round(GuiGraphics ctx, int x, int y, int w, int h, int color) {
        if (w <= 2 || h <= 2) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        ctx.fill(x + 1, y, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 1px outline following {@link #round}. */
    public static void outline(GuiGraphics ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 1, y, x + w - 1, y + 1, color);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A panel: soft drop shadow, gradient glass body, border, HUD corner brackets and an optional header. */
    public static void panel(GuiGraphics ctx, Font font, int x0, int y0, int x1, int y1, String title) {
        int w = x1 - x0;
        int h = y1 - y0;
        round(ctx, x0 + 1, y0 + 2, w, h, SHADOW);
        ctx.fillGradient(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL_TOP, PANEL);
        ctx.fill(x0, y0 + 1, x0 + 1, y1 - 1, PANEL);
        ctx.fill(x1 - 1, y0 + 1, x1, y1 - 1, PANEL);
        outline(ctx, x0, y0, w, h, BORDER);
        ctx.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, HIGHLIGHT);
        if (title != null && !title.isEmpty()) {
            ctx.fillGradient(x0 + 1, y0 + 1, x1 - 1, y0 + HEADER_H, PANEL_HEADER_TOP, PANEL_HEADER);
            glowLine(ctx, x0 + 1, x1 - 1, y0 + HEADER_H, ACCENT);
            ctx.fill(x0 + 3, y0 + 4, x0 + 5, y0 + HEADER_H - 3, ACCENT);
            ctx.drawString(font, title, x0 + 8, y0 + 3, TEXT, false);
        }
        brackets(ctx, x0, y0, x1, y1, withAlpha(ACCENT, 0.55F));
    }

    /** HUD-style corner marks just inside a rectangle. */
    public static void brackets(GuiGraphics ctx, int x0, int y0, int x1, int y1, int color) {
        int l = Math.min(6, Math.min(x1 - x0, y1 - y0) / 3);
        ctx.fill(x0, y0, x0 + l, y0 + 1, color);
        ctx.fill(x0, y0, x0 + 1, y0 + l, color);
        ctx.fill(x1 - l, y0, x1, y0 + 1, color);
        ctx.fill(x1 - 1, y0, x1, y0 + l, color);
        ctx.fill(x0, y1 - 1, x0 + l, y1, color);
        ctx.fill(x0, y1 - l, x0 + 1, y1, color);
        ctx.fill(x1 - l, y1 - 1, x1, y1, color);
        ctx.fill(x1 - 1, y1 - l, x1, y1, color);
    }

    /** A 1px line that is brightest in the middle and fades out to both ends. */
    public static void glowLine(GuiGraphics ctx, int x0, int x1, int y, int color) {
        int w = x1 - x0;
        if (w <= 0) {
            return;
        }
        int steps = Math.min(24, w);
        for (int i = 0; i < steps; i++) {
            int a = x0 + w * i / steps;
            int b = x0 + w * (i + 1) / steps;
            float t = (i + 0.5F) / steps;
            float fade = 1.0F - Math.abs(t - 0.5F) * 2.0F;
            ctx.fill(a, y, b, y + 1, withAlpha(color, 0.15F + 0.85F * fade));
        }
    }

    /** 0..1 pulse, {@code periodMs} long. */
    public static float pulse(long periodMs) {
        double t = (System.currentTimeMillis() % periodMs) / (double) periodMs;
        return (float) (0.5 - 0.5 * Math.cos(t * Math.PI * 2.0));
    }

    /** Mix two ARGB colours, {@code t} in 0..1. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0F, Math.min(1F, t));
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 255;
            int cb = (b >>> shift) & 255;
            r |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return r;
    }

    /** A small status light with a soft halo. */
    public static void light(GuiGraphics ctx, int cx, int cy, int color, boolean pulsing) {
        float glow = pulsing ? 0.25F + 0.45F * pulse(1400) : 0.2F;
        ctx.fill(cx - 3, cy - 2, cx + 3, cy + 2, withAlpha(color, glow * 0.6F));
        ctx.fill(cx - 2, cy - 3, cx + 2, cy + 3, withAlpha(color, glow * 0.6F));
        ctx.fill(cx - 1, cy - 2, cx + 1, cy + 2, color);
        ctx.fill(cx - 2, cy - 1, cx + 2, cy + 1, color);
    }

    /** A thin labelled divider inside a panel. */
    public static void section(GuiGraphics ctx, Font font, int x0, int x1, int y, String title) {
        ctx.drawString(font, title, x0, y, TEXT_FAINT, false);
        int tx = x0 + font.width(title) + 4;
        if (tx < x1) {
            ctx.fill(tx, y + 4, x1, y + 5, BORDER);
        }
    }

    /** Progress bar, {@code progress} in 0..1. When {@code animated}, a highlight sweeps along the filled part. */
    public static void bar(GuiGraphics ctx, int x, int y, int w, int h, float progress, int color, boolean animated) {
        ctx.fill(x, y, x + w, y + h, 0xFF0B0F14);
        int fill = Math.round(Math.max(0F, Math.min(1F, progress)) * w);
        if (fill > 0) {
            ctx.fillGradient(x, y, x + fill, y + h, mix(color, 0xFFFFFFFF, 0.25F), color);
            if (animated && fill > 6) {
                int sweep = (int) ((System.currentTimeMillis() / 12) % (fill + 16)) - 8;
                int a = Math.max(x, x + sweep - 4);
                int b = Math.min(x + fill, x + sweep + 4);
                if (b > a) {
                    ctx.fill(a, y, b, y + h, 0x55FFFFFF);
                }
            }
        }
    }

    public static void bar(GuiGraphics ctx, int x, int y, int w, int h, float progress, int color) {
        bar(ctx, x, y, w, h, progress, color, false);
    }

    /** Small rounded tag, e.g. a key hint. Returns its width. */
    public static int chip(GuiGraphics ctx, Font font, String text, int x, int y, int bg, int fg) {
        int w = font.width(text) + 6;
        round(ctx, x, y, w, 11, bg);
        ctx.drawString(font, text, x + 3, y + 2, fg, false);
        return w;
    }

    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 255) * Math.max(0F, Math.min(1F, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
