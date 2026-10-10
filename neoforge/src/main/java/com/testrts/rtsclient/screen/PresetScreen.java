package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.Presets;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pick a ready-made building; it goes onto the Blueprint tool for placing like any blueprint. */
public final class PresetScreen extends Screen {
    private static final int CARD_W = 136;
    private static final int CARD_H = 20;
    private static final int GAP = 3;
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
        super(Component.literal("Presets"));
        this.parent = parent;
        for (Presets.Preset p : this.presets) {
            this.built.add(p.build().get());
        }
    }

    @Override
    protected void init() {
        this.columns = Math.max(1, Math.min(3, (this.width - 2 * PAD - 20 + GAP) / (CARD_W + GAP)));
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
            addRenderableWidget(new RtsButton(x, y, CARD_W, CARD_H, p.name(), () -> choose(bp))
                    .icon(new ItemStack(p.icon())).tip(tip).left());
        }
        addRenderableWidget(new RtsButton(this.panelX1 - PAD - 60, this.panelY1 - PAD - 16, 60, 16, "Back", this::onClose));
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
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // A plain dim instead of 1.21's blur, then the panel; widgets are drawn on top by render().
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.font, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "PRESET BUILDINGS");
        ctx.drawString(this.font, "Materials come from your inventory and linked storage.",
                this.panelX0 + PAD, this.panelY1 - PAD - 12, Theme.TEXT_FAINT, false);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
