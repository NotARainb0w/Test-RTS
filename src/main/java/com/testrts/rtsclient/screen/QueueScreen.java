package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.screen.ui.Glyphs;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.task.QueueStore;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Saved order queues for this world: click one to add its orders to the queue, Shift+click to delete it. */
public final class QueueScreen extends Screen {
    private static final int ROW_W = 240;
    private static final int ROW_H = 18;
    private static final int GAP = 3;
    private static final int PAD = 8;
    private static final int MAX_ROWS = 10;

    private final Screen parent;
    private List<QueueStore.Saved> saved = List.of();
    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;

    public QueueScreen(Screen parent) {
        super(Text.literal("Saved queues"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.saved = QueueStore.list();
        int rows = Math.max(1, Math.min(MAX_ROWS, this.saved.size()));
        int w = Math.min(this.width - 20, ROW_W + 2 * PAD);
        int h = Theme.HEADER_H + 8 + rows * (ROW_H + GAP) + 30;
        this.panelX0 = this.width / 2 - w / 2;
        this.panelX1 = this.panelX0 + w;
        this.panelY0 = Math.max(4, this.height / 2 - h / 2);
        this.panelY1 = this.panelY0 + h;
        int y = this.panelY0 + Theme.HEADER_H + 6;
        SimpleDateFormat when = new SimpleDateFormat("MMM d, HH:mm");
        for (int i = 0; i < this.saved.size() && i < MAX_ROWS; i++) {
            QueueStore.Saved s = this.saved.get(i);
            String label = s.name() + "  -  " + s.orders() + (s.orders() == 1 ? " order" : " orders");
            String tip = "Saved " + when.format(new Date(s.savedAt())) + ". Click: add these orders to the end of the queue. "
                    + "Shift+click: delete." + (QueueStore.LAST_SESSION.equals(s.name()) ? " (Kept when you last left this world.)" : "");
            addDrawableChild(new RtsButton(this.panelX0 + PAD, y, w - 2 * PAD, ROW_H, label, () -> pick(s))
                    .glyph(Glyphs.LIST, QueueStore.LAST_SESSION.equals(s.name()) ? Theme.GOLD : Theme.ACCENT).tip(tip).left());
            y += ROW_H + GAP;
        }
        addDrawableChild(new RtsButton(this.panelX1 - PAD - 60, this.panelY1 - PAD - 16, 60, 16, "Back", this::close));
    }

    private void pick(QueueStore.Saved s) {
        if (hasShiftDown()) {
            QueueStore.delete(s);
            Messages.info("Deleted saved queue \"" + s.name() + "\"");
            clearAndInit();
            return;
        }
        try {
            int n = QueueStore.load(s);
            Messages.success("Queued " + n + " order(s) from \"" + s.name() + "\"");
        } catch (IOException e) {
            Messages.error("Couldn't load \"" + s.name() + "\": " + e.getMessage());
        }
        close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.textRenderer, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "SAVED QUEUES");
        String hint = this.saved.isEmpty() ? "No saved queues in this world yet - use Save queue"
                : this.saved.size() > MAX_ROWS ? "Showing the newest " + MAX_ROWS + " of " + this.saved.size()
                : "Click to queue, Shift+click to delete";
        ctx.drawText(this.textRenderer, hint, this.panelX0 + PAD, this.panelY1 - PAD - 12, Theme.TEXT_FAINT, false);
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
