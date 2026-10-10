package com.testrts.rtsclient.screen.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A flat themed button with an optional item icon or glyph and a "selected" state. */
public class RtsButton extends AbstractButton {
    private final Runnable action;
    private ItemStack icon = ItemStack.EMPTY;
    @Nullable
    private Glyphs glyph;
    private int glyphColor = Theme.TEXT;
    private BooleanSupplier selected = () -> false;
    @Nullable
    private Supplier<String> label;
    private boolean iconOnly;
    private boolean centered = true;
    private float hoverAnim;
    private float selectAnim;

    public RtsButton(int x, int y, int width, int height, String text, Runnable action) {
        super(x, y, width, height, Component.literal(text));
        this.action = action;
    }

    public RtsButton icon(ItemStack stack) {
        this.icon = stack;
        return this;
    }

    public RtsButton glyph(Glyphs glyph, int color) {
        this.glyph = glyph;
        this.glyphColor = color;
        return this;
    }

    public RtsButton selected(BooleanSupplier selected) {
        this.selected = selected;
        return this;
    }

    /** Label recomputed every frame (for toggles like "Air: keep"). */
    public RtsButton dynamic(Supplier<String> label) {
        this.label = label;
        return this;
    }

    public RtsButton iconOnly() {
        this.iconOnly = true;
        return this;
    }

    public RtsButton left() {
        this.centered = false;
        return this;
    }

    public RtsButton tip(@Nullable String tip) {
        if (tip != null && !tip.isEmpty()) {
            setTooltip(Tooltip.create(Component.literal(tip)));
        }
        return this;
    }

    @Override
    public void onPress() {
        this.action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean sel = this.selected.getAsBoolean();
        boolean hover = this.active && isHovered();
        // Ease hover and selection in and out instead of snapping.
        this.hoverAnim += ((hover ? 1F : 0F) - this.hoverAnim) * 0.3F;
        this.selectAnim += ((sel ? 1F : 0F) - this.selectAnim) * 0.25F;
        float hv = this.hoverAnim;
        float sv = this.selectAnim;
        int base = !this.active ? Theme.BTN_DISABLED : Theme.mix(Theme.mix(Theme.BTN, Theme.BTN_HOVER, hv), Theme.BTN_SELECTED, sv);
        Theme.round(ctx, x, y, w, h, base);
        if (this.active && h > 4) {
            ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h / 2, Theme.mix(base, 0xFFFFFFFF, 0.07F + 0.05F * hv), base);
        }
        int edge = Theme.mix(Theme.mix(0xFF2A3441, 0xFF4A5C70, hv), Theme.withAlpha(Theme.ACCENT, 0.85F), sv);
        Theme.outline(ctx, x, y, w, h, edge);
        if (sv > 0.02F) {
            int lw = Math.round((w - 4) * sv);
            int lx = x + (w - lw) / 2;
            ctx.fill(lx, y + h - 2, lx + lw, y + h - 1, Theme.ACCENT);
            ctx.fill(lx, y + h - 3, lx + lw, y + h - 2, Theme.withAlpha(Theme.ACCENT, 0.3F));
        }
        if (hv > 0.02F && !sel) {
            ctx.fill(x + 1, y + 2, x + 2, y + h - 2, Theme.withAlpha(Theme.ACCENT, 0.8F * hv));
        }

        int contentX = x + 4;
        int iconW = 0;
        if (!this.icon.isEmpty() && h >= 16) {
            ctx.renderItem(this.icon, x + (this.iconOnly ? (w - 16) / 2 : 2), y + (h - 16) / 2);
            iconW = 18;
            contentX = x + 2 + iconW;
        } else if (this.glyph != null) {
            int gx = this.iconOnly ? x + (w - Glyphs.SIZE) / 2 : x + 4;
            this.glyph.draw(ctx, gx, y + (h - Glyphs.SIZE) / 2, this.active ? (sel ? Theme.ACCENT : this.glyphColor) : Theme.TEXT_FAINT);
            iconW = Glyphs.SIZE + 4;
            contentX = x + 4 + iconW;
        }
        if (this.iconOnly) {
            return;
        }
        String text = this.label != null ? this.label.get() : getMessage().getString();
        int color = !this.active ? Theme.TEXT_FAINT : sel ? 0xFFFFFFFF : hover ? Theme.TEXT : 0xFFC9D3DD;
        int room = x + w - 3 - contentX;
        if (font.width(text) > room) {
            text = font.plainSubstrByWidth(text, Math.max(0, room - font.width("..."))) + "...";
        }
        int tw = font.width(text);
        int tx = this.centered && iconW == 0 ? x + (w - tw) / 2 : contentX;
        ctx.drawString(font, text, tx, y + (h - 8) / 2, color, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        defaultButtonNarrationText(builder);
    }
}
