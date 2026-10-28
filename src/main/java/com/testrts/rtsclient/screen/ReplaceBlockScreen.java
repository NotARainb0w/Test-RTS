package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Materials list's replace button: pick a block to put in place of one material in every queued build
 * order. Blocks the player has (inventory or linked storage) come first; search reaches every block in the game.
 */
public final class ReplaceBlockScreen extends Screen {
    private static final int SLOT = 18;
    private static final int PAD = 8;

    private final Screen parent;
    private final Item from;
    private final Map<Item, Integer> owned = new HashMap<>();
    private final List<Item> items = new ArrayList<>();
    private TextFieldWidget search;
    private String query = "";
    private int scroll;
    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;
    private int gridX0;
    private int gridY0;
    private int cols;
    private int rows;

    public ReplaceBlockScreen(Screen parent, Item from) {
        super(Text.literal("Replace block"));
        this.parent = parent;
        this.from = from;
    }

    private void countOwned() {
        this.owned.clear();
        if (this.client != null && this.client.player != null) {
            PlayerInventory inv = this.client.player.getInventory();
            for (ItemStack st : inv.main) {
                if (!st.isEmpty()) {
                    this.owned.merge(st.getItem(), st.getCount(), Integer::sum);
                }
            }
        }
        StorageManager.get().totals().forEach((item, n) -> this.owned.merge(item, n, Integer::sum));
    }

    private void refresh() {
        this.items.clear();
        String q = this.query.trim().toLowerCase(Locale.ROOT);
        List<Item> have = new ArrayList<>();
        List<Item> rest = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            if (!(item instanceof BlockItem bi) || item == this.from || bi.getBlock().getDefaultState().isAir()) {
                continue;
            }
            if (!q.isEmpty() && !item.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    && !Registries.ITEM.getId(item).toString().contains(q)) {
                continue;
            }
            (this.owned.containsKey(item) ? have : rest).add(item);
        }
        have.sort(Comparator.comparingInt((Item i) -> -this.owned.get(i)));
        this.items.addAll(have);
        this.items.addAll(rest);
        this.scroll = MathHelper.clamp(this.scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, (this.items.size() + this.cols - 1) / this.cols - this.rows);
    }

    @Override
    protected void init() {
        int w = Math.min(340, this.width - 24);
        int h = Math.min(this.height - 16, 260);
        this.panelX0 = this.width / 2 - w / 2;
        this.panelX1 = this.panelX0 + w;
        this.panelY0 = this.height / 2 - h / 2;
        this.panelY1 = this.panelY0 + h;
        this.gridX0 = this.panelX0 + PAD;
        this.gridY0 = this.panelY0 + Theme.HEADER_H + 34;
        this.cols = Math.max(1, (w - 2 * PAD) / SLOT);
        this.rows = Math.max(1, (this.panelY1 - PAD - 22 - this.gridY0) / SLOT);
        this.search = new TextFieldWidget(this.textRenderer, this.panelX0 + PAD + 4, this.panelY0 + Theme.HEADER_H + 19,
                w - 2 * PAD - 8, 10, Text.literal("Search"));
        this.search.setMaxLength(50);
        this.search.setDrawsBackground(false);
        this.search.setText(this.query);
        this.search.setPlaceholder(Text.literal("Search all blocks...").formatted(Formatting.DARK_GRAY));
        this.search.setChangedListener(text -> {
            this.query = text;
            this.scroll = 0;
            refresh();
        });
        addDrawableChild(this.search);
        setInitialFocus(this.search);
        addDrawableChild(new RtsButton(this.panelX1 - PAD - 60, this.panelY1 - PAD - 16, 60, 16, "Back", this::close));
        countOwned();
        refresh();
    }

    /** Index into {@link #items} of the slot under the mouse, else -1. */
    private int slotAt(double mx, double my) {
        if (mx < this.gridX0 || my < this.gridY0) {
            return -1;
        }
        int c = (int) ((mx - this.gridX0) / SLOT);
        int r = (int) ((my - this.gridY0) / SLOT);
        int i = (this.scroll + r) * this.cols + c;
        return c < this.cols && r < this.rows && i < this.items.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = button == 0 ? slotAt(mx, my) : -1;
        if (i >= 0) {
            replaceWith((BlockItem) this.items.get(i));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void replaceWith(BlockItem to) {
        int changed = 0;
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing()) {
                changed += build.replaceItem(this.from, to.getBlock());
            }
        }
        Messages.info("Replaced " + changed + "x " + this.from.getName().getString() + " with "
                + to.getName().getString() + " in the queued orders");
        close();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        this.scroll = MathHelper.clamp(this.scroll - (int) Math.signum(amount), 0, maxScroll());
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.textRenderer, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "REPLACE BLOCK");
        int x0 = this.panelX0 + PAD;
        int hy = this.panelY0 + Theme.HEADER_H + 5;
        ctx.drawItem(new ItemStack(this.from), x0, hy - 4);
        ctx.drawText(this.textRenderer, this.textRenderer.trimToWidth("Replace " + this.from.getName().getString() + " with...",
                this.panelX1 - PAD - x0 - 20), x0 + 20, hy, Theme.TEXT, false);
        ctx.fill(x0, this.panelY0 + Theme.HEADER_H + 16, this.panelX1 - PAD, this.panelY0 + Theme.HEADER_H + 30, Theme.SLOT);
        int hovered = slotAt(mouseX, mouseY);
        for (int r = 0; r < this.rows; r++) {
            for (int c = 0; c < this.cols; c++) {
                int i = (this.scroll + r) * this.cols + c;
                if (i >= this.items.size()) {
                    break;
                }
                int sx = this.gridX0 + c * SLOT;
                int sy = this.gridY0 + r * SLOT;
                ctx.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, i == hovered ? Theme.SLOT_HOVER : Theme.SLOT);
                ctx.drawItem(new ItemStack(this.items.get(i)), sx + 1, sy + 1);
                if (this.owned.containsKey(this.items.get(i))) {
                    ctx.fill(sx + SLOT - 4, sy + 1, sx + SLOT - 2, sy + 3, Theme.GOOD);
                }
            }
        }
        if (this.items.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, "No matching blocks", this.width / 2, this.gridY0 + 12, Theme.TEXT_DIM);
        }
        ctx.fill(x0, this.panelY1 - PAD - 10, x0 + 3, this.panelY1 - PAD - 7, Theme.GOOD);
        ctx.drawText(this.textRenderer, "In inventory or storage", x0 + 6, this.panelY1 - PAD - 12, Theme.TEXT_FAINT, false);
        super.render(ctx, mouseX, mouseY, delta);
        if (hovered >= 0) {
            Item item = this.items.get(hovered);
            ItemStack stack = new ItemStack(item);
            List<Text> tip = new ArrayList<>(getTooltipFromItem(this.client, stack));
            int n = this.owned.getOrDefault(item, 0);
            tip.add(Text.literal(n > 0 ? n + " available" : "None in inventory or storage")
                    .formatted(n > 0 ? Formatting.GREEN : Formatting.GRAY));
            ctx.drawTooltip(this.textRenderer, tip, mouseX, mouseY);
        }
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
