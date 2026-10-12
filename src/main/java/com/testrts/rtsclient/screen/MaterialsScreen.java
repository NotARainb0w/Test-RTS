package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.FetchTask;
import com.testrts.rtsclient.task.MaterialPlanner;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shopping list: every block the queued build orders still need, against what the player
 * carries and what linked storage holds, with one button to fetch everything in a single trip.
 */
public final class MaterialsScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int PAD = 8;

    private record Line(Item item, int need, int have, int stored) {
        int shortBy() {
            return Math.max(0, this.need - this.have);
        }
    }

    private final Screen parent;
    private final List<Line> lines = new ArrayList<>();
    private int scroll;
    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;
    private int listY0;
    private int listY1;

    public MaterialsScreen(Screen parent) {
        super(Text.literal("Materials"));
        this.parent = parent;
    }

    private void compute() {
        this.lines.clear();
        if (this.client == null || this.client.world == null || this.client.player == null) {
            return;
        }
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing()) {
                MaterialPlanner.required(this.client.world, build.blocks()).forEach((item, n) -> need.merge(item, n, Integer::sum));
            }
        }
        need.forEach((item, n) -> this.lines.add(new Line(item, n, this.client.player.getInventory().count(item),
                StorageManager.get().total(item))));
        // Short items first, then the biggest amounts.
        this.lines.sort((a, b) -> a.shortBy() > 0 != b.shortBy() > 0 ? (a.shortBy() > 0 ? -1 : 1) : Integer.compare(b.need(), a.need()));
    }

    /** What can be fetched from storage to cover the shortfall. */
    private Map<Item, Integer> fetchable() {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Line l : this.lines) {
            int n = Math.min(l.shortBy(), l.stored());
            if (n > 0) {
                out.put(l.item(), n);
            }
        }
        return out;
    }

    @Override
    protected void init() {
        compute();
        int w = Math.min(320, this.width - 24);
        int h = Math.min(this.height - 16, 280);
        this.panelX0 = this.width / 2 - w / 2;
        this.panelX1 = this.panelX0 + w;
        this.panelY0 = this.height / 2 - h / 2;
        this.panelY1 = this.panelY0 + h;
        this.listY0 = this.panelY0 + Theme.HEADER_H + 18;
        this.listY1 = this.panelY1 - PAD - 22;
        int by = this.panelY1 - PAD - 16;
        Map<Item, Integer> fetch = fetchable();
        RtsButton fetchAll = new RtsButton(this.panelX0 + PAD, by, 110, 16, "Fetch all", () -> {
            Map<Item, Integer> f = fetchable();
            if (f.isEmpty()) {
                return;
            }
            TaskQueue.get().pushFront(new FetchTask(f));
            Messages.info("Fetching " + MaterialPlanner.describe(f, 3));
            close();
        }).tip("Walk to storage once and take everything you're short of (as far as storage has it)").selected(() -> true);
        fetchAll.active = !fetch.isEmpty();
        addDrawableChild(fetchAll);
        addDrawableChild(new RtsButton(this.panelX1 - PAD - 60, by, 60, 16, "Back", this::close));
    }

    /** The item whose remove button was clicked once, and when (a second click within 3 s removes it). */
    private Item armed;
    private long armedAt;

    /** Remove every block of {@code item} from the queued build orders; orders left empty come off the queue. */
    private void removeFromQueue(Item item) {
        int removed = 0;
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing()) {
                removed += build.removeItem(item);
                if (build.blocks().isEmpty()) {
                    TaskQueue.get().remove(this.client, build);
                }
            }
        }
        Messages.info("Removed " + removed + "x " + item.getName().getString() + " from the queued orders");
    }

    /** x of the remove button column, and whether {@code (mx, my)} is on a row's button (that row's index, else -1). */
    private int removeRowAt(double mx, double my) {
        int x1 = this.panelX1 - PAD;
        if (mx < x1 - 12 || mx > x1 || my < this.listY0) {
            return -1;
        }
        int r = (int) ((my - this.listY0) / ROW_H);
        int i = this.scroll + r;
        return r < visibleRows() && i < this.lines.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = button == 0 ? removeRowAt(mx, my) : -1;
        if (i >= 0) {
            Item item = this.lines.get(i).item();
            long now = System.currentTimeMillis();
            if (item == this.armed && now - this.armedAt <= 3000) {
                this.armed = null;
                removeFromQueue(item);
                clearAndInit();
            } else {
                this.armed = item;
                this.armedAt = now;
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private int visibleRows() {
        return Math.max(1, (this.listY1 - this.listY0) / ROW_H);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        this.scroll = Math.max(0, Math.min(Math.max(0, this.lines.size() - visibleRows()), this.scroll - (int) Math.signum(amount) * 2));
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.textRenderer, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "MATERIALS FOR QUEUED ORDERS");
        int x0 = this.panelX0 + PAD;
        int x1 = this.panelX1 - PAD;
        int hy = this.panelY0 + Theme.HEADER_H + 5;
        int cNeed = x1 - 124;
        int cHave = x1 - 86;
        int cStore = x1 - 48;
        ctx.drawText(this.textRenderer, "ITEM", x0 + 20, hy, Theme.TEXT_FAINT, false);
        ctx.drawText(this.textRenderer, "NEED", cNeed, hy, Theme.TEXT_FAINT, false);
        ctx.drawText(this.textRenderer, "HAVE", cHave, hy, Theme.TEXT_FAINT, false);
        ctx.drawText(this.textRenderer, "STORED", cStore, hy, Theme.TEXT_FAINT, false);
        if (this.lines.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, "No build orders queued", this.width / 2, this.listY0 + 12, Theme.TEXT_DIM);
        }
        int rows = visibleRows();
        int missingTotal = 0;
        for (Line l : this.lines) {
            missingTotal += Math.max(0, l.shortBy() - l.stored());
        }
        for (int r = 0; r < rows && this.scroll + r < this.lines.size(); r++) {
            Line l = this.lines.get(this.scroll + r);
            int y = this.listY0 + r * ROW_H;
            if (r % 2 == 0) {
                ctx.fill(x0, y, x1, y + ROW_H, 0x20FFFFFF);
            }
            ctx.drawItem(new ItemStack(l.item()), x0 + 1, y + 1);
            int nameRoom = cNeed - (x0 + 20) - 4;
            ctx.drawText(this.textRenderer, this.textRenderer.trimToWidth(l.item().getName().getString(), nameRoom), x0 + 20, y + 5, Theme.TEXT, false);
            boolean shortItem = l.shortBy() > 0;
            boolean coverable = l.shortBy() <= l.stored();
            ctx.drawText(this.textRenderer, Integer.toString(l.need()), cNeed, y + 5, Theme.TEXT, false);
            ctx.drawText(this.textRenderer, Integer.toString(l.have()), cHave, y + 5, shortItem ? Theme.GOLD : Theme.GOOD, false);
            boolean armedRow = l.item() == this.armed && System.currentTimeMillis() - this.armedAt <= 3000;
            if (armedRow) {
                ctx.drawText(this.textRenderer, "Remove?", x1 - 54, y + 5, Theme.BAD, false);
            }
            ctx.drawText(this.textRenderer, "\u2715", x1 - 9, y + 5, armedRow ? Theme.BAD : Theme.TEXT_FAINT, false);
            ctx.drawText(this.textRenderer, Integer.toString(l.stored()), cStore, y + 5,
                    !shortItem ? Theme.TEXT_DIM : coverable ? Theme.GOOD : Theme.BAD, false);
        }
        String summary = this.lines.isEmpty() ? "" : missingTotal == 0
                ? "Inventory + storage cover everything"
                : missingTotal + " block(s) not available anywhere";
        ctx.drawText(this.textRenderer, summary, x0 + 116, this.panelY1 - PAD - 12, missingTotal == 0 ? Theme.GOOD : Theme.BAD, false);
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
