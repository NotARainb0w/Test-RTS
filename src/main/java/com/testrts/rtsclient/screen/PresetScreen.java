package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.Presets;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pick a ready-made building; it goes onto the Blueprint tool for placing like any blueprint. */
public final class PresetScreen extends Screen {
    private static final int CARD_W = 150;
    private static final int CARD_H = 24;
    private static final int GAP = 4;
    private static final int PAD = 8;

    private final Screen parent;
    private final List<Presets.Preset> presets = Presets.all();
    private final List<Blueprint> built = new ArrayList<>();
    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;
    private int columns;

    public PresetScreen(Screen parent) {
        super(Text.literal("Presets"));
        this.parent = parent;
        for (Presets.Preset p : this.presets) {
            this.built.add(p.build().get());
        }
    }

    @Override
    protected void init() {
        this.columns = this.width >= 2 * CARD_W + GAP + 2 * PAD + 20 ? 2 : 1;
        int rows = (this.presets.size() + this.columns - 1) / this.columns;
        int w = this.columns * CARD_W + (this.columns - 1) * GAP + 2 * PAD;
        int h = Theme.HEADER_H + 8 + rows * (CARD_H + GAP) + 26;
        this.panelX0 = this.width / 2 - w / 2;
        this.panelX1 = this.panelX0 + w;
        this.panelY0 = Math.max(4, this.height / 2 - h / 2);
        this.panelY1 = this.panelY0 + h;
        int top = this.panelY0 + Theme.HEADER_H + 6;
        for (int i = 0; i < this.presets.size(); i++) {
            Presets.Preset p = this.presets.get(i);
            Blueprint bp = this.built.get(i);
            int x = this.panelX0 + PAD + (i % this.columns) * (CARD_W + GAP);
            int y = top + (i / this.columns) * (CARD_H + GAP);
            String tip = p.description() + ". " + bp.size() + ", "
                    + String.format(Locale.ROOT, "%,d", bp.blockCount()) + " blocks";
            addDrawableChild(new RtsButton(x, y, CARD_W, CARD_H, p.name(), () -> choose(bp))
                    .icon(new ItemStack(p.icon())).tip(tip).left());
        }
        addDrawableChild(new RtsButton(this.panelX1 - PAD - 60, this.panelY1 - PAD - 16, 60, 16, "Back", this::close));
    }

    private void choose(Blueprint bp) {
        BuildSession s = RtsController.get().session();
        s.tool = ToolMode.BLUEPRINT;
        s.clipboard = bp;
        s.pasteTurns = 0;
        s.pasteMirror = false;
        s.pasteAnchor = null;
        s.clearSelection();
        Messages.success(bp.name() + " (" + bp.size() + ") - click to place it, R rotates, Enter builds");
        close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.textRenderer, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "PRESET BUILDINGS");
        ctx.drawText(this.textRenderer, "Materials come from your inventory and linked storage.",
                this.panelX0 + PAD, this.panelY1 - PAD - 12, Theme.TEXT_FAINT, false);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
