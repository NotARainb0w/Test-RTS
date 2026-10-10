package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.baritone.BaritoneBridge;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.build.BuildMode;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.OrderPlanner;
import com.testrts.rtsclient.build.BuildShape;
import com.testrts.rtsclient.build.ShapeGenerator;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.build.UndoHistory;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.DepositTask;
import com.testrts.rtsclient.task.IndexStorageTask;
import com.testrts.rtsclient.task.InteractTask;
import com.testrts.rtsclient.task.MoveTask;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import com.testrts.rtsclient.util.MouseRay;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The RTS command screen. It sits over the world (no background, no pause), gives the player a
 * cursor, and turns clicks into orders for the task queue.
 */
public final class RtsScreen extends Screen {
    // Layout
    private static final int TOP_H = 22;
    private static final int LEFT_W = 104;
    private static final int RIGHT_W = 156;
    private static final int SLOT = 18;
    private static final int GRID_ROWS = 3;
    private static final int PAD = 4;

    // Colours (ARGB)
    private static final int PANEL = 0xB0101418;
    private static final int PANEL_EDGE = 0xFF2A323C;
    private static final int SLOT_BG = 0x60FFFFFF;
    private static final int SLOT_HOVER = 0x90FFFFFF;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int GOLD = 0xFFFFC107;
    private static final int TEXT = 0xFFE0E6EE;
    private static final int TEXT_DIM = 0xFF8A96A3;

    private static final int DRAG_THRESHOLD = 3;

    private enum Tab { ALL, INVENTORY, STORAGE }

    private record PaletteEntry(Item item, ItemStack display, int inventory, int stored) {
        int total() {
            return this.inventory + this.stored;
        }
    }

    private final RtsController controller;
    private final BuildSession session;

    private TextFieldWidget search;
    private String searchText = "";
    private Tab tab = Tab.ALL;
    private List<PaletteEntry> palette = new ArrayList<>();
    private int paletteRefresh;
    private int gridScroll;
    private int taskScroll;

    private record Highlight(int x, int y, int w, int h, java.util.function.BooleanSupplier active) {
    }

    private final List<Highlight> highlights = new ArrayList<>();
    private int leftPanelBottom = TOP_H;
    private int heightLabelY = -1;
    private int clipboardLabelY = -1;
    private int savedLabelY = -1;
    private List<Blueprint> blueprints = new ArrayList<>();
    private boolean blueprintsLoaded;

    private long lastFrameNanos;
    private int pressedButton = -1;
    private double pressX;
    private double pressY;
    private boolean dragging;

    public RtsScreen(RtsController controller) {
        super(Text.literal("RTS"));
        this.controller = controller;
        this.session = controller.session();
    }

    // ------------------------------------------------------------------ layout helpers

    private int bottomH() {
        // pinned row + header row + grid
        return PAD + SLOT + PAD + 14 + PAD + GRID_ROWS * SLOT + PAD;
    }

    private int bottomY() {
        return this.height - bottomH();
    }

    private int gridCols() {
        return Math.max(1, (this.width - 2 * PAD) / SLOT);
    }

    private int pinnedY() {
        return bottomY() + PAD;
    }

    private int headerY() {
        return pinnedY() + SLOT + PAD;
    }

    private int gridY() {
        return headerY() + 14 + PAD;
    }

    private int rightX() {
        return this.width - RIGHT_W - PAD;
    }

    private boolean overUi(double mx, double my) {
        if (my < TOP_H || my >= bottomY()) {
            return true;
        }
        if (hasLeftPanel() && mx < LEFT_W + PAD && my < this.leftPanelBottom) {
            return true;
        }
        return mx >= rightX() && my < TOP_H + rightPanelH();
    }

    private boolean hasLeftPanel() {
        return this.session.tool.usesShapes() || this.session.tool == ToolMode.BLUEPRINT;
    }

    private int rightPanelH() {
        return Math.min(bottomY() - TOP_H - PAD, 54 + 5 * 22 + 70);
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        this.lastFrameNanos = System.nanoTime();

        int x = PAD;
        for (ToolMode tool : ToolMode.values()) {
            int w = this.textRenderer.getWidth(tool.label()) + 12;
            addDrawableChild(ButtonWidget.builder(Text.literal(tool.label()), b -> selectTool(tool))
                    .dimensions(x, 2, w, 18)
                    .tooltip(Tooltip.of(Text.literal(tool.description() + " [" + (tool.ordinal() + 1) + "]")))
                    .build());
            x += w + 2;
        }

        button("Settings", "Engine, layers, preview size, camera...", this.width - PAD - 52, 2, 52, 18,
                () -> this.client.setScreen(new RtsSettingsScreen(this)));

        int rx = rightX();
        // Command buttons sit at the top of the right panel so the top bar never overflows.
        int bx = rx + PAD;
        int bw = RIGHT_W - 2 * PAD;
        int half0 = (bw - 2) / 2;
        addDrawableChild(ButtonWidget.builder(engineLabel(), b -> {
                    Engine.cycle();
                    b.setMessage(engineLabel());
                    Messages.info("Engine: " + Engine.configured().label() + " (using " + Engine.activeName() + ")");
                })
                .dimensions(bx, TOP_H + 4, bw - 40, 16)
                .tooltip(Tooltip.of(Text.literal("Who walks, builds and mines. Auto = Baritone when installed, otherwise the built-in engine. "
                        + "Switch to Built-in if Baritone misbehaves.")))
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Undo"), b -> {
                    if (this.client.world != null) {
                        UndoHistory.undoLast(this.client.world);
                    }
                })
                .dimensions(bx + bw - 38, TOP_H + 4, 38, 16)
                .tooltip(Tooltip.of(Text.literal("Put back what your last order changed [Ctrl+Z]")))
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Store inv"), b -> queue(new DepositTask()))
                .dimensions(bx, TOP_H + 22, half0, 16)
                .tooltip(Tooltip.of(Text.literal("Walk to linked storage and put away everything outside your hotbar (pinned items stay)")))
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop all"), b -> {
                    TaskQueue.get().clear(this.client);
                    Messages.info("All orders cancelled");
                })
                .dimensions(bx + half0 + 2, TOP_H + 22, half0, 16)
                .tooltip(Tooltip.of(Text.literal("Cancel every order and stop walking/building")))
                .build());

        initLeftPanel();

        // Storage actions live at the bottom of the right panel.
        int sy = TOP_H + rightPanelH() - 4 - 2 * 18;
        int half = (RIGHT_W - 2 * PAD - 2) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Scan nearby"), b -> scanNearby())
                .dimensions(rx + PAD, sy, half, 16)
                .tooltip(Tooltip.of(Text.literal("Link every chest, barrel and shulker box within " + RtsConfig.get().scanRadius + " blocks, then open the new ones to read what they hold")))
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Index new"), b -> indexStorage(true))
                .dimensions(rx + PAD + half + 2, sy, half, 16)
                .tooltip(Tooltip.of(Text.literal("Open linked containers we haven't seen inside yet, to learn what they hold")))
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Re-index all"), b -> indexStorage(false))
                .dimensions(rx + PAD, sy + 18, half, 16)
                .tooltip(Tooltip.of(Text.literal("Visit every linked container again to refresh counts")))
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Unlink all"), b -> {
                    StorageManager.get().unlinkAll();
                    Messages.info("Storage links cleared");
                })
                .dimensions(rx + PAD + half + 2, sy + 18, half, 16)
                .build());

        int searchW = Math.min(160, this.width / 3);
        this.search = new TextFieldWidget(this.textRenderer, PAD + 1, headerY() + 1, searchW, 12, Text.literal("Search"));
        this.search.setMaxLength(50);
        // init() runs again every time the screen comes back (after a chest, chat...), keep the query.
        this.search.setText(this.searchText);
        this.search.setPlaceholder(Text.literal("Search items...").formatted(Formatting.DARK_GRAY));
        this.search.setChangedListener(s -> {
            this.searchText = s;
            this.gridScroll = 0;
            refreshPalette();
        });
        addDrawableChild(this.search);

        int tx = PAD + searchW + 6;
        for (Tab t : Tab.values()) {
            String label = switch (t) {
                case ALL -> "All";
                case INVENTORY -> "Inventory";
                case STORAGE -> "Storage";
            };
            int w = this.textRenderer.getWidth(label) + 10;
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                        this.tab = t;
                        this.gridScroll = 0;
                        refreshPalette();
                    })
                    .dimensions(tx, headerY(), w, 14)
                    .build());
            tx += w + 2;
        }
        refreshPalette();
    }

    private ButtonWidget button(String label, String tip, int x, int y, int w, int h, Runnable action) {
        ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), btn -> action.run()).dimensions(x, y, w, h);
        if (tip != null) {
            b.tooltip(Tooltip.of(Text.literal(tip)));
        }
        return addDrawableChild(b.build());
    }

    private void initLeftPanel() {
        this.highlights.clear();
        this.heightLabelY = -1;
        this.clipboardLabelY = -1;
        this.savedLabelY = -1;
        int y = TOP_H + PAD;
        int full = LEFT_W - 4;
        int col = (full - 2) / 2;
        BuildSession s = this.session;
        if (s.tool.usesShapes()) {
            BuildShape[] shapes = BuildShape.values();
            for (int i = 0; i < shapes.length; i++) {
                BuildShape shape = shapes[i];
                int bx = PAD + (i % 2) * (col + 2);
                int by = y + (i / 2) * 16;
                button(shape.label(), shape.description() + " [ and ] cycle", bx, by, col, 15, () -> {
                    s.shape = shape;
                    s.clearSelection();
                });
                this.highlights.add(new Highlight(bx, by, col, 15, () -> s.shape == shape));
            }
            y += ((shapes.length + 1) / 2) * 16 + 2;
            if (s.tool == ToolMode.BUILD) {
                button("Mode: " + s.mode.label(), s.mode.description() + " [M]", PAD, y, full, 16, () -> {
                    s.mode = s.mode.next();
                    s.clearSelection();
                    clearAndInit();
                });
                y += 18;
            }
            button("Hollow", "Only the outside of floors, boxes, circles and spheres [H]", PAD, y, full, 16,
                    () -> s.hollow = !s.hollow);
            this.highlights.add(new Highlight(PAD, y, full, 16, () -> s.hollow));
            y += 18;
            button("-", "Lower height", PAD, y, 16, 16, () -> s.adjustHeight(-1));
            button("+", "Height for walls, boxes and cylinders. Negative digs down. [Ctrl+scroll]",
                    PAD + full - 16, y, 16, 16, () -> s.adjustHeight(1));
            this.heightLabelY = y;
            y += 18;
            if (s.tool == ToolMode.BUILD) {
                button("Rotate", "Turn directional blocks (stairs, logs, furnaces) [R]", PAD, y, full, 16, s.orientation::cycle);
                y += 18;
                y = initStatePicker(y, full);
            }
        } else if (s.tool == ToolMode.BLUEPRINT) {
            if (!this.blueprintsLoaded) {
                this.blueprints = Blueprint.loadAll();
                this.blueprintsLoaded = true;
            }
            button("New copy", "Select a region with two clicks to copy it", PAD, y, full, 16, () -> {
                s.clipboard = null;
                s.pasteAnchor = null;
                s.clearSelection();
                clearAndInit();
            });
            y += 18;
            button("Rotate", "Turn the blueprint 90 degrees [R]", PAD, y, full, 16, () -> s.pasteTurns++);
            y += 18;
            button("Load schematic...", "Litematica .litematic, WorldEdit .schem or structure .nbt from .minecraft/schematics",
                    PAD, y, full, 16, () -> this.client.setScreen(new SchematicBrowserScreen(this)));
            y += 18;
            button("Air: " + (s.pasteClearsAir ? "clear" : "keep"),
                    "clear: also mine whatever is in the blueprint's empty spaces, for an exact copy. keep: only add blocks.",
                    PAD, y, full, 16, () -> {
                        s.pasteClearsAir = !s.pasteClearsAir;
                        clearAndInit();
                    });
            y += 18;
            this.clipboardLabelY = y + 2;
            y += 24;
            this.savedLabelY = y;
            y += 11;
            int shown = 0;
            for (Blueprint bp : this.blueprints) {
                if (shown++ >= 10) {
                    break;
                }
                button(bp.name(), bp.size() + ", " + bp.blockCount() + " blocks. Click to paste, Shift+click to delete.",
                        PAD, y, full, 14, () -> {
                            if (hasShiftDown()) {
                                bp.delete();
                                if (s.clipboard == bp) {
                                    s.clipboard = null;
                                }
                                this.blueprints = Blueprint.loadAll();
                                Messages.info("Deleted " + bp.name());
                            } else {
                                s.clipboard = bp;
                                s.pasteTurns = 0;
                                s.pasteAnchor = null;
                                s.clearSelection();
                            }
                            clearAndInit();
                        });
                this.highlights.add(new Highlight(PAD, y, full, 14, () -> s.clipboard == bp));
                y += 15;
            }
            if (this.blueprints.isEmpty()) {
                y += 10;
            }
        }
        this.leftPanelBottom = y + PAD;
    }

    /** One button per block-state property of the brush (slab half, stair shape...), cycling through values. */
    private int initStatePicker(int y, int width) {
        BlockState state = this.session.brushState(this.controller.camera().yaw());
        if (state == null) {
            return y;
        }
        int shown = 0;
        for (net.minecraft.state.property.Property<?> property : state.getProperties()) {
            String name = property.getName();
            if (name.equals("waterlogged") || name.equals("powered") || name.equals("distance") || name.equals("persistent")) {
                continue;
            }
            if (shown++ >= 6) {
                break;
            }
            String value = this.session.stateOverrides.getOrDefault(name, "auto");
            button(name + ": " + value, "Click to change this block property (auto = decided by where you place it)",
                    PAD, y, width, 14, () -> {
                        cycleProperty(property);
                        clearAndInit();
                    });
            y += 15;
        }
        if (!this.session.stateOverrides.isEmpty()) {
            button("Reset state", "Back to automatic block states", PAD, y, width, 14, () -> {
                this.session.stateOverrides.clear();
                clearAndInit();
            });
            y += 15;
        }
        return y;
    }

    private <T extends Comparable<T>> void cycleProperty(net.minecraft.state.property.Property<T> property) {
        List<String> values = new ArrayList<>();
        values.add("auto");
        for (T v : property.getValues()) {
            values.add(property.name(v));
        }
        String current = this.session.stateOverrides.getOrDefault(property.getName(), "auto");
        String next = values.get((values.indexOf(current) + 1) % values.size());
        if (next.equals("auto")) {
            this.session.stateOverrides.remove(property.getName());
        } else {
            this.session.stateOverrides.put(property.getName(), next);
        }
    }

    private static Text engineLabel() {
        return Text.literal("Engine: " + Engine.configured().label());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.controller.disable(this.client);
    }

    // ------------------------------------------------------------------ actions

    private void selectTool(ToolMode tool) {
        if (this.session.tool != tool) {
            this.session.tool = tool;
            this.session.clearSelection();
            // Shape panel buttons only exist for shape tools.
            clearAndInit();
        }
    }

    private void queue(RtsTask task) {
        TaskQueue.get().add(task);
        if (TaskQueue.get().size() > 1) {
            Messages.info(task.label() + " queued (" + (TaskQueue.get().size() - 1) + " ahead)");
        }
    }

    /** Link nearby containers, then queue a walk to open each unread one so the item panel fills up. */
    private void scanNearby() {
        if (this.client.world == null || this.client.player == null) {
            return;
        }
        StorageManager.get().pruneMissing(this.client.world);
        int added = StorageManager.get().scanNearby(this.client.world, this.client.player.getBlockPos(), RtsConfig.get().scanRadius);
        int unread = StorageManager.get().unindexed().size();
        if (unread == 0) {
            Messages.info(added == 0 ? "No new containers nearby" : "Linked " + added + " container(s)");
            return;
        }
        Messages.info("Linked " + added + " container(s), reading " + unread + "...");
        boolean alreadyQueued = TaskQueue.get().snapshot().stream().anyMatch(t -> t instanceof IndexStorageTask);
        if (!alreadyQueued) {
            queue(new IndexStorageTask(this.client.player.getBlockPos(), true));
        }
    }

    private void indexStorage(boolean onlyNew) {
        if (this.client.player == null) {
            return;
        }
        if (StorageManager.get().linked().isEmpty()) {
            Messages.warn("No linked storage. Use Link storage mode or Scan nearby first");
            return;
        }
        IndexStorageTask task = new IndexStorageTask(this.client.player.getBlockPos(), onlyNew);
        if (onlyNew && StorageManager.get().unindexed().isEmpty()) {
            Messages.info("Every linked container is already indexed");
            return;
        }
        queue(task);
    }

    private void worldPrimary(double mx, double my) {
        BlockHitResult hit = pick(mx, my);
        if (hit == null || this.client.world == null) {
            return;
        }
        switch (this.session.tool) {
            case BUILD -> {
                if (!(this.session.brush.getItem() instanceof BlockItem)) {
                    Messages.warn("Pick a block from the bar below first (or middle-click a block in the world)");
                    return;
                }
                boolean onExisting = this.session.shape == BuildShape.VEIN || this.session.mode.targetsExisting();
                if (this.session.mode == BuildMode.REPLACE_TYPE && this.session.anchorA == null) {
                    this.session.replaceTarget = this.client.world.getBlockState(hit.getBlockPos()).getBlock();
                    Messages.info("Replacing " + this.session.replaceTarget.getName().getString());
                }
                shapeClick(onExisting ? hit.getBlockPos() : WorldOverlay.targetFor(this.session, hit));
            }
            case DESTROY -> shapeClick(hit.getBlockPos());
            case INTERACT -> {
                BlockState state = this.client.world.getBlockState(hit.getBlockPos());
                queue(new InteractTask(hit, state.getBlock().getName().getString()));
            }
            case LINK -> {
                if (!StorageManager.isContainer(this.client.world, hit.getBlockPos())) {
                    Messages.warn("That's not a container");
                    return;
                }
                boolean linked = StorageManager.get().toggle(this.client.world, hit.getBlockPos());
                Messages.info((linked ? "Linked " : "Unlinked ") + hit.getBlockPos().toShortString());
            }
            case BLUEPRINT -> blueprintClick(hit);
        }
    }

    private void blueprintClick(BlockHitResult hit) {
        BuildSession s = this.session;
        if (s.clipboard != null) {
            if (s.pasteAnchor == null) {
                // First click pins it; arrows nudge it; click again or Enter builds it.
                s.pasteAnchor = s.clipboard.originFor(WorldOverlay.targetFor(s, hit), s.pasteTurns);
                Messages.info("Placed - arrows to nudge, R to rotate, click or Enter to build, X to move it again");
            } else {
                confirmPaste();
            }
            return;
        }
        // Copy: two corners.
        BlockPos p = hit.getBlockPos();
        if (s.anchorA == null) {
            s.anchorA = p;
            Messages.info("Copy: first corner set - click the opposite corner");
            return;
        }
        BlockPos a = s.anchorA;
        s.clearSelection();
        String name = "Blueprint " + (this.blueprints.size() + 1);
        Blueprint bp = Blueprint.capture(this.client.world, a, p, name, RtsConfig.get().maxShapeBlocks);
        if (bp.blockCount() == 0) {
            Messages.warn("Nothing to copy there");
            return;
        }
        bp.save();
        s.clipboard = bp;
        s.pasteTurns = 0;
        this.blueprints = Blueprint.loadAll();
        Messages.success("Copied " + bp.blockCount() + " blocks (" + bp.size() + ") as \"" + name + "\" - click to paste, R to rotate");
        clearAndInit();
    }

    private void confirmPaste() {
        BuildSession s = this.session;
        if (s.clipboard == null || s.pasteAnchor == null || this.client.world == null) {
            return;
        }
        Blueprint bp = s.clipboard;
        Map<BlockPos, BlockState> build = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : bp.placed(s.pasteAnchor, s.pasteTurns).entrySet()) {
            if (this.client.world.getBlockState(e.getKey()).getBlock() != e.getValue().getBlock()) {
                build.put(e.getKey(), e.getValue());
            }
        }
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        if (s.pasteClearsAir) {
            if (bp.volume() > RtsConfig.get().maxShapeBlocks) {
                Messages.warn("Too big to clear the air inside; pasting blocks only");
            } else {
                for (BlockPos p : bp.placedAir(s.pasteAnchor, s.pasteTurns)) {
                    BlockState now = this.client.world.getBlockState(p);
                    if (!now.isAir() && !(now.getBlock() instanceof net.minecraft.block.FluidBlock)
                            && now.getHardness(this.client.world, p) >= 0) {
                        clear.put(p, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
        s.pasteAnchor = null;
        if (build.isEmpty() && clear.isEmpty()) {
            Messages.info("Already built there");
            return;
        }
        Map<BlockPos, BlockState> all = new LinkedHashMap<>(clear);
        all.putAll(build);
        pushUndo("Paste " + bp.name(), all);
        if (!clear.isEmpty()) {
            queue(new BuildTask("Clear for " + bp.name() + " (" + clear.size() + ")", clear, true));
        }
        if (!build.isEmpty()) {
            queue(new BuildTask("Build " + bp.name() + " (" + build.size() + ")", build, false));
        }
    }

    private void shapeClick(BlockPos target) {
        if (this.session.shape.singleClick()) {
            this.session.anchorA = target;
            this.session.anchorB = target;
            confirmOrder();
            return;
        }
        if (this.session.anchorA == null) {
            this.session.anchorA = target;
            Messages.info("First point set - click the second point");
        } else if (this.session.anchorB == null) {
            this.session.anchorB = target;
            Messages.info("Click again or press Enter to confirm, X to cancel, arrows to nudge");
        } else {
            confirmOrder();
        }
    }

    private void confirmOrder() {
        if (!this.session.hasPendingOrder() || this.client.world == null) {
            return;
        }
        BuildSession s = this.session;
        OrderPlanner.Request request = OrderPlanner.request(s, s.anchorA, s.anchorB, this.controller.camera().yaw(),
                RtsConfig.get().maxShapeBlocks);
        OrderPlanner.Plan plan = OrderPlanner.plan(this.client.world, request);
        if (plan.truncated()) {
            Messages.error("Shape is larger than " + RtsConfig.get().maxShapeBlocks + " blocks (raise it in Settings)");
            return;
        }
        if (s.shape == BuildShape.HOLE && plan.shapeSize() == 0) {
            Messages.warn("That hole isn't enclosed (or is too big) - nothing to fill");
            s.clearSelection();
            return;
        }
        boolean clearing = s.tool == ToolMode.DESTROY;
        String label = OrderPlanner.label(request, plan.changes().size());
        s.clearSelection();
        if (plan.changes().isEmpty()) {
            Messages.info("Nothing to do there");
            return;
        }
        pushUndo(label, plan.changes());
        queue(new BuildTask(label, plan.changes(), clearing));
    }

    private void pushUndo(String label, Map<BlockPos, BlockState> order) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos p : order.keySet()) {
            before.put(p, this.client.world.getBlockState(p));
        }
        UndoHistory.push(label, before);
    }

    private void moveTo(double mx, double my) {
        BlockHitResult hit = pick(mx, my);
        if (hit == null) {
            return;
        }
        BlockPos feet = hit.getSide() == Direction.UP || hit.getSide() == Direction.DOWN
                ? hit.getBlockPos().up()
                : hit.getBlockPos().offset(hit.getSide());
        queue(new MoveTask(feet));
    }

    private void pickBlock(double mx, double my) {
        BlockHitResult hit = pick(mx, my);
        if (hit == null || this.client.world == null) {
            return;
        }
        Item item = this.client.world.getBlockState(hit.getBlockPos()).getBlock().asItem();
        if (item instanceof BlockItem) {
            selectBrush(item);
            selectTool(ToolMode.BUILD);
            Messages.info("Selected " + item.getName().getString());
        }
    }

    @Nullable
    private BlockHitResult pick(double mx, double my) {
        return MouseRay.pick(this.controller.camera(), mx, my, this.width, this.height, false);
    }

    // ------------------------------------------------------------------ palette

    private void refreshPalette() {
        this.paletteRefresh = 0;
        if (this.client == null || this.client.player == null) {
            this.palette = new ArrayList<>();
            return;
        }
        Map<Item, Integer> inv = new HashMap<>();
        PlayerInventory pi = this.client.player.getInventory();
        for (int i = 0; i < pi.main.size(); i++) {
            ItemStack st = pi.main.get(i);
            if (!st.isEmpty()) {
                inv.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }
        for (ItemStack st : pi.offHand) {
            if (!st.isEmpty()) {
                inv.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }
        Map<Item, Integer> stored = StorageManager.get().totals();

        Map<Item, PaletteEntry> entries = new LinkedHashMap<>();
        if (this.tab != Tab.STORAGE) {
            inv.forEach((item, n) -> entries.put(item, new PaletteEntry(item, new ItemStack(item), n, stored.getOrDefault(item, 0))));
        }
        if (this.tab != Tab.INVENTORY) {
            stored.forEach((item, n) -> entries.putIfAbsent(item, new PaletteEntry(item, new ItemStack(item), inv.getOrDefault(item, 0), n)));
        }
        String query = this.search == null ? "" : this.search.getText().trim().toLowerCase(Locale.ROOT);
        List<PaletteEntry> list = new ArrayList<>();
        for (PaletteEntry e : entries.values()) {
            if (query.isEmpty() || e.display().getName().getString().toLowerCase(Locale.ROOT).contains(query)
                    || Registries.ITEM.getId(e.item()).getPath().contains(query)) {
                list.add(e);
            }
        }
        // Blocks first (they're what you build with), then by name.
        list.sort(Comparator
                .comparing((PaletteEntry e) -> !(e.item() instanceof BlockItem))
                .thenComparing(e -> e.display().getName().getString()));
        this.palette = list;
        int maxScroll = Math.max(0, (list.size() + gridCols() - 1) / gridCols() - GRID_ROWS);
        this.gridScroll = MathHelper.clamp(this.gridScroll, 0, maxScroll);
    }

    private int countOf(Item item) {
        int inv = this.client.player == null ? 0 : this.client.player.getInventory().count(item);
        return inv + StorageManager.get().total(item);
    }

    private void togglePin(Item item) {
        String id = Registries.ITEM.getId(item).toString();
        List<String> pinned = RtsConfig.get().pinned;
        if (!pinned.remove(id)) {
            if (pinned.size() >= gridCols()) {
                Messages.warn("Quick bar is full - right-click a pinned item to unpin it");
                return;
            }
            pinned.add(id);
        }
        RtsConfig.save();
    }

    private void selectBrush(Item item) {
        if (this.session.brush.getItem() != item) {
            // Picked properties (slab half...) belong to the old block.
            this.session.stateOverrides.clear();
        }
        this.session.brush = new ItemStack(item);
        if (item instanceof BlockItem && !this.session.tool.usesShapes()) {
            selectTool(ToolMode.BUILD);
        } else {
            // The block-state buttons depend on the brush.
            clearAndInit();
        }
    }

    @Nullable
    private Item pinnedAt(double mx, double my) {
        int y = pinnedY();
        if (my < y || my >= y + SLOT) {
            return null;
        }
        int idx = (int) ((mx - PAD) / SLOT);
        List<String> pinned = RtsConfig.get().pinned;
        if (mx < PAD || idx < 0 || idx >= pinned.size()) {
            return null;
        }
        Identifier id = Identifier.tryParse(pinned.get(idx));
        return id != null && Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
    }

    @Nullable
    private PaletteEntry gridAt(double mx, double my) {
        int y0 = gridY();
        if (my < y0 || my >= y0 + GRID_ROWS * SLOT || mx < PAD || mx >= PAD + gridCols() * SLOT) {
            return null;
        }
        int col = (int) ((mx - PAD) / SLOT);
        int row = (int) ((my - y0) / SLOT);
        int idx = (row + this.gridScroll) * gridCols() + col;
        return idx >= 0 && idx < this.palette.size() ? this.palette.get(idx) : null;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (this.search != null && this.search.isFocused() && !this.search.isMouseOver(mx, my)) {
            this.search.setFocused(false);
            setFocused(null);
        }
        Item pinned = pinnedAt(mx, my);
        if (pinned != null) {
            if (button == 0) {
                selectBrush(pinned);
            } else if (button == 1) {
                togglePin(pinned);
            }
            return true;
        }
        PaletteEntry entry = gridAt(mx, my);
        if (entry != null) {
            if (button == 0) {
                selectBrush(entry.item());
            } else if (button == 1 || button == 2) {
                togglePin(entry.item());
            }
            return true;
        }
        if (clickTaskList(mx, my)) {
            return true;
        }
        if (overUi(mx, my)) {
            return true;
        }
        this.pressedButton = button;
        this.pressX = mx;
        this.pressY = my;
        this.dragging = false;
        if (button == 0) {
            worldPrimary(mx, my);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == this.pressedButton && (button == 1 || button == 2)) {
            if (!this.dragging && Math.abs(mx - this.pressX) + Math.abs(my - this.pressY) > DRAG_THRESHOLD) {
                this.dragging = true;
            }
            if (this.dragging) {
                if (button == 1) {
                    this.controller.camera().rotate(dx, dy);
                } else {
                    this.controller.camera().dragPan(this.client, dx, dy, this.height);
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == this.pressedButton) {
            this.pressedButton = -1;
            if (!this.dragging) {
                if (button == 1) {
                    moveTo(mx, my);
                } else if (button == 2) {
                    pickBlock(mx, my);
                }
            }
            this.dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (my >= bottomY()) {
            int rows = (this.palette.size() + gridCols() - 1) / gridCols();
            this.gridScroll = MathHelper.clamp(this.gridScroll - (int) Math.signum(amount), 0, Math.max(0, rows - GRID_ROWS));
            return true;
        }
        if (mx >= rightX() && my < TOP_H + rightPanelH()) {
            this.taskScroll = Math.max(0, this.taskScroll - (int) Math.signum(amount));
            return true;
        }
        if (hasControlDown()) {
            this.session.adjustHeight(amount > 0 ? 1 : -1);
            return true;
        }
        this.controller.camera().zoom(this.client, amount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.search.isFocused()) {
                this.search.setFocused(false);
                setFocused(null);
            } else if (this.session.anchorA != null || this.session.pasteAnchor != null) {
                this.session.clearSelection();
                this.session.pasteAnchor = null;
            } else {
                close();
            }
            return true;
        }
        if (this.search.isFocused()) {
            this.search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (RtsClientMod.toggleKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (RtsClientMod.stopKey.matchesKey(keyCode, scanCode)) {
            TaskQueue.get().clear(this.client);
            return true;
        }
        if (this.client.options.chatKey.matchesKey(keyCode, scanCode)) {
            this.client.setScreen(new ChatScreen(""));
            return true;
        }
        if (this.client.options.commandKey.matchesKey(keyCode, scanCode)) {
            this.client.setScreen(new ChatScreen("/"));
            return true;
        }
        if (this.client.options.inventoryKey.matchesKey(keyCode, scanCode) && this.client.player != null) {
            this.client.setScreen(new InventoryScreen(this.client.player));
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5 -> {
                selectTool(ToolMode.values()[keyCode - GLFW.GLFW_KEY_1]);
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                if (this.session.tool == ToolMode.BLUEPRINT) {
                    this.session.pasteTurns++;
                } else {
                    this.session.orientation.cycle();
                }
                return true;
            }
            case GLFW.GLFW_KEY_M -> {
                if (this.session.tool == ToolMode.BUILD) {
                    this.session.mode = this.session.mode.next();
                    this.session.clearSelection();
                    Messages.info("Build mode: " + this.session.mode.label() + " - " + this.session.mode.description());
                    clearAndInit();
                }
                return true;
            }
            case GLFW.GLFW_KEY_V -> {
                toggleCut();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> {
                Integer cut = this.controller.cutY();
                int base = cut != null ? cut : (this.client.player != null ? this.client.player.getBlockY() + 2 : 64);
                this.controller.setCut(this.client, base + (keyCode == GLFW.GLFW_KEY_PAGE_UP ? 1 : -1));
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                nudge(keyCode);
                return true;
            }
            case GLFW.GLFW_KEY_H -> {
                this.session.hollow = !this.session.hollow;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (this.session.tool == ToolMode.BLUEPRINT) {
                    confirmPaste();
                } else {
                    confirmOrder();
                }
                return true;
            }
            case GLFW.GLFW_KEY_Z -> {
                if (hasControlDown() && this.client.world != null) {
                    UndoHistory.undoLast(this.client.world);
                }
                return true;
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_BACKSPACE -> {
                this.session.clearSelection();
                this.session.pasteAnchor = null;
                return true;
            }
            case GLFW.GLFW_KEY_LEFT_BRACKET -> {
                this.session.shape = this.session.shape.previous();
                this.session.clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> {
                this.session.shape = this.session.shape.next();
                this.session.clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_Q -> {
                this.controller.camera().rotateYaw(-45.0F);
                return true;
            }
            case GLFW.GLFW_KEY_E -> {
                this.controller.camera().rotateYaw(45.0F);
                return true;
            }
            case GLFW.GLFW_KEY_F -> {
                this.search.setFocused(true);
                setFocused(this.search);
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** V: hide everything above the block under the cursor (or above the player), or turn it off. */
    private void toggleCut() {
        if (this.controller.cutY() != null) {
            this.controller.setCut(this.client, null);
            Messages.info("Cut view off");
            return;
        }
        BlockHitResult hover = this.controller.hover();
        int y = hover != null ? hover.getBlockPos().getY() : (this.client.player != null ? this.client.player.getBlockY() + 2 : 64);
        this.controller.setCut(this.client, y);
        Messages.info("Cut view at Y=" + y + " - PageUp/PageDown to move, V to turn off");
    }

    /** Arrow keys move the shape's points one block (relative to the camera); Shift+Up/Down moves them vertically. */
    private void nudge(int keyCode) {
        BuildSession s = this.session;
        if (s.anchorA == null && s.pasteAnchor == null) {
            return;
        }
        Direction forward = Direction.fromRotation(this.controller.camera().yaw());
        Direction d;
        if (hasShiftDown() && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            d = keyCode == GLFW.GLFW_KEY_UP ? Direction.UP : Direction.DOWN;
        } else {
            d = switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> forward;
                case GLFW.GLFW_KEY_DOWN -> forward.getOpposite();
                case GLFW.GLFW_KEY_LEFT -> forward.rotateYCounterclockwise();
                default -> forward.rotateYClockwise();
            };
        }
        if (s.pasteAnchor != null) {
            s.pasteAnchor = s.pasteAnchor.offset(d);
        }
        if (s.anchorA != null) {
            s.anchorA = s.anchorA.offset(d);
        }
        if (s.anchorB != null) {
            s.anchorB = s.anchorB.offset(d);
        }
    }

    private boolean held(KeyBinding binding) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
        if (key.getCategory() != InputUtil.Type.KEYSYM || key.getCode() < 0) {
            return false;
        }
        return InputUtil.isKeyPressed(this.client.getWindow().getHandle(), key.getCode());
    }

    private void moveCamera() {
        long now = System.nanoTime();
        double dt = (now - this.lastFrameNanos) / 1.0e9;
        this.lastFrameNanos = now;
        if (this.search != null && this.search.isFocused()) {
            this.controller.camera().update(this.client, dt, 0, 0, 0, false);
            return;
        }
        var o = this.client.options;
        double forward = (held(o.forwardKey) ? 1 : 0) - (held(o.backKey) ? 1 : 0);
        double strafe = (held(o.rightKey) ? 1 : 0) - (held(o.leftKey) ? 1 : 0);
        double vertical = (held(o.jumpKey) ? 1 : 0) - (held(o.sneakKey) ? 1 : 0);
        this.controller.camera().update(this.client, dt, forward, strafe, vertical, held(o.sprintKey));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void tick() {
        if (++this.paletteRefresh >= 10) {
            refreshPalette();
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        moveCamera();
        this.controller.setHover(overUi(mouseX, mouseY) ? null : pick(mouseX, mouseY));

        // Panels
        panel(ctx, 0, 0, this.width, TOP_H);
        panel(ctx, 0, bottomY(), this.width, this.height);
        if (hasLeftPanel()) {
            panel(ctx, 0, TOP_H, LEFT_W + PAD, this.leftPanelBottom);
        }
        panel(ctx, rightX(), TOP_H, this.width, TOP_H + rightPanelH());

        super.render(ctx, mouseX, mouseY, delta);

        drawToolHighlight(ctx);
        drawStatus(ctx);
        if (hasLeftPanel()) {
            drawLeftPanel(ctx);
        }
        drawRightPanel(ctx, mouseX, mouseY);
        drawPinned(ctx, mouseX, mouseY);
        drawGrid(ctx, mouseX, mouseY);
        drawHints(ctx);
        drawMessages(ctx);
        drawTooltips(ctx, mouseX, mouseY);
        if (!overUi(mouseX, mouseY)) {
            drawOrderInfo(ctx, mouseX, mouseY);
        }
    }

    private static void panel(DrawContext ctx, int x0, int y0, int x1, int y1) {
        ctx.fill(x0, y0, x1, y1, PANEL);
        ctx.drawBorder(x0, y0, x1 - x0, y1 - y0, PANEL_EDGE);
    }

    private void drawToolHighlight(DrawContext ctx) {
        int x = PAD;
        for (ToolMode tool : ToolMode.values()) {
            int w = this.textRenderer.getWidth(tool.label()) + 12;
            if (tool == this.session.tool) {
                ctx.fill(x + 1, 19, x + w - 1, 21, ACCENT);
            }
            x += w + 2;
        }
    }

    private void drawStatus(DrawContext ctx) {
        if (this.client.player == null) {
            return;
        }
        int x = PAD;
        for (ToolMode tool : ToolMode.values()) {
            x += this.textRenderer.getWidth(tool.label()) + 14;
        }
        x += 6;
        float hp = this.client.player.getHealth();
        int food = this.client.player.getHungerManager().getFoodLevel();
        String vitals = String.format(Locale.ROOT, "HP %.0f  Food %d", hp, food);
        ctx.drawTextWithShadow(this.textRenderer, vitals, x, 7, hp <= 6 ? 0xFFFF5555 : TEXT);
        x += this.textRenderer.getWidth(vitals) + 10;

        Integer cut = this.controller.cutY();
        String bar = (cut != null ? "Cut Y" + cut + "  " : "") + Engine.activeName() + ": " + Engine.status();
        int limit = this.width - PAD - 58;
        if (x + this.textRenderer.getWidth(bar) < limit) {
            ctx.drawTextWithShadow(this.textRenderer, bar, x, 7, TEXT_DIM);
        }
    }

    private void drawLeftPanel(DrawContext ctx) {
        for (Highlight h : this.highlights) {
            if (h.active().getAsBoolean()) {
                ctx.fill(h.x() - 2, h.y(), h.x(), h.y() + h.h(), ACCENT);
            }
        }
        if (this.heightLabelY >= 0) {
            String h = "Height " + this.session.height;
            ctx.drawCenteredTextWithShadow(this.textRenderer, h, PAD + (LEFT_W - 4) / 2, this.heightLabelY + 4,
                    this.session.shape.usesHeight() ? TEXT : TEXT_DIM);
        }
        if (this.clipboardLabelY >= 0) {
            Blueprint bp = this.session.clipboard;
            String line1 = bp == null ? "Click two corners" : trim(bp.name(), LEFT_W - 6);
            String line2 = bp == null ? "to copy a region" : bp.size() + ", " + bp.blockCount() + " blocks";
            ctx.drawTextWithShadow(this.textRenderer, line1, PAD, this.clipboardLabelY, bp == null ? TEXT_DIM : GOLD);
            ctx.drawTextWithShadow(this.textRenderer, line2, PAD, this.clipboardLabelY + 10, TEXT_DIM);
        }
        if (this.savedLabelY >= 0) {
            ctx.drawTextWithShadow(this.textRenderer, this.blueprints.isEmpty() ? "No saved blueprints" : "Saved blueprints:",
                    PAD, this.savedLabelY + 1, TEXT_DIM);
        }
    }

    private int taskRowsTop() {
        return TOP_H + 54;
    }

    private int taskRowsVisible() {
        int bottom = TOP_H + rightPanelH() - 4 - 2 * 18 - 26;
        return Math.max(1, (bottom - taskRowsTop()) / 22);
    }

    private void drawRightPanel(DrawContext ctx, int mx, int my) {
        int x = rightX() + PAD;
        List<RtsTask> tasks = TaskQueue.get().snapshot();
        ctx.drawTextWithShadow(this.textRenderer, "Orders (" + tasks.size() + ")", x, TOP_H + 43, ACCENT);
        int visible = taskRowsVisible();
        this.taskScroll = MathHelper.clamp(this.taskScroll, 0, Math.max(0, tasks.size() - visible));
        int y = taskRowsTop();
        if (tasks.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, "Nothing queued", x, y + 2, TEXT_DIM);
        }
        for (int i = this.taskScroll; i < tasks.size() && i < this.taskScroll + visible; i++) {
            RtsTask task = tasks.get(i);
            boolean running = i == 0;
            ctx.fill(x - 1, y, rightX() + RIGHT_W - PAD, y + 20, running ? 0x402E7D32 : 0x30FFFFFF);
            ctx.drawTextWithShadow(this.textRenderer, trim(task.label(), RIGHT_W - 24), x + 2, y + 2, running ? 0xFFA5D6A7 : TEXT);
            ctx.drawTextWithShadow(this.textRenderer, trim(task.detail(), RIGHT_W - 24), x + 2, y + 11, TEXT_DIM);
            int bx = rightX() + RIGHT_W - PAD - 11;
            boolean hot = mx >= bx && mx < bx + 10 && my >= y + 1 && my < y + 11;
            ctx.drawTextWithShadow(this.textRenderer, "x", bx + 2, y + 2, hot ? 0xFFFF5555 : TEXT_DIM);
            y += 22;
        }

        int sy = TOP_H + rightPanelH() - 4 - 2 * 18 - 22;
        int linked = StorageManager.get().linked().size();
        int indexed = linked - StorageManager.get().unindexed().size();
        ctx.drawTextWithShadow(this.textRenderer, "Storage", x, sy, GOLD);
        ctx.drawTextWithShadow(this.textRenderer, linked + " linked, " + indexed + " indexed", x, sy + 10, TEXT_DIM);
    }

    private boolean clickTaskList(double mx, double my) {
        if (mx < rightX() || my < taskRowsTop() || my >= TOP_H + rightPanelH()) {
            return false;
        }
        List<RtsTask> tasks = TaskQueue.get().snapshot();
        int row = (int) ((my - taskRowsTop()) / 22);
        if (row >= taskRowsVisible()) {
            return false;
        }
        int idx = this.taskScroll + row;
        if (idx < 0 || idx >= tasks.size()) {
            return false;
        }
        int bx = rightX() + RIGHT_W - PAD - 11;
        double rowY = taskRowsTop() + row * 22;
        if (mx >= bx && mx < bx + 10 && my >= rowY + 1 && my < rowY + 11) {
            TaskQueue.get().remove(this.client, tasks.get(idx));
            return true;
        }
        return false;
    }

    private void drawPinned(DrawContext ctx, int mx, int my) {
        int y = pinnedY();
        int cols = gridCols();
        List<String> pinned = RtsConfig.get().pinned;
        for (int i = 0; i < cols; i++) {
            int x = PAD + i * SLOT;
            ctx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x40000000);
            if (i >= pinned.size()) {
                continue;
            }
            Identifier id = Identifier.tryParse(pinned.get(i));
            if (id == null || !Registries.ITEM.containsId(id)) {
                continue;
            }
            Item item = Registries.ITEM.get(id);
            ItemStack stack = new ItemStack(item);
            boolean hot = mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
            if (hot) {
                ctx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, SLOT_HOVER);
            }
            if (this.session.brush.getItem() == item) {
                ctx.drawBorder(x, y, SLOT, SLOT, ACCENT);
            }
            ctx.drawItem(stack, x + 1, y + 1);
            int count = countOf(item);
            ctx.drawItemInSlot(this.textRenderer, stack, x + 1, y + 1, count == 0 ? "§c0" : shortCount(count));
        }
        if (pinned.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, "Quick bar - right-click items below to pin them here", PAD + 4, y + 5, TEXT_DIM);
        }
    }

    private void drawGrid(DrawContext ctx, int mx, int my) {
        int cols = gridCols();
        int y0 = gridY();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                int x = PAD + col * SLOT;
                int y = y0 + row * SLOT;
                ctx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, SLOT_BG & 0x30FFFFFF);
                int idx = (row + this.gridScroll) * cols + col;
                if (idx >= this.palette.size()) {
                    continue;
                }
                PaletteEntry e = this.palette.get(idx);
                if (mx >= x && mx < x + SLOT && my >= y && my < y + SLOT) {
                    ctx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, SLOT_HOVER);
                }
                if (this.session.brush.getItem() == e.item()) {
                    ctx.drawBorder(x, y, SLOT, SLOT, ACCENT);
                }
                ctx.drawItem(e.display(), x + 1, y + 1);
                String count = shortCount(e.total());
                // Gold count: only in storage, the build will fetch it.
                ctx.drawItemInSlot(this.textRenderer, e.display(), x + 1, y + 1, e.inventory() == 0 ? "§6" + count : count);
            }
        }
        if (this.palette.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    this.tab == Tab.STORAGE ? "No indexed storage yet - link containers and press Index new" : "No items",
                    PAD + 4, y0 + 5, TEXT_DIM);
        }
        int rows = (this.palette.size() + cols - 1) / cols;
        if (rows > GRID_ROWS) {
            String s = (this.gridScroll + 1) + "/" + (rows - GRID_ROWS + 1);
            ctx.drawTextWithShadow(this.textRenderer, s, this.width - PAD - this.textRenderer.getWidth(s), headerY() + 3, TEXT_DIM);
        }
    }

    private void drawHints(DrawContext ctx) {
        int y = bottomY() - 12;
        String brush = this.session.brush.isEmpty() ? "none" : this.session.brush.getName().getString();
        String line;
        switch (this.session.tool) {
            case BUILD -> {
                String orient = "";
                if (this.session.brush.getItem() instanceof BlockItem bi) {
                    String d = this.session.orientation.describe(bi.getBlock(), this.controller.camera().yaw());
                    orient = d.isEmpty() ? "" : " (" + d + ")";
                }
                line = "Build: " + brush + orient + " | " + this.session.mode.label() + " | " + this.session.shape.label()
                        + (this.session.hollow && this.session.shape.supportsHollow() ? " hollow" : "")
                        + (this.session.shape.usesHeight() ? " h" + this.session.height : "");
            }
            case DESTROY -> line = "Destroy | " + this.session.shape.label()
                    + (this.session.hollow && this.session.shape.supportsHollow() ? " hollow" : "")
                    + (this.session.shape.usesHeight() ? " h" + this.session.height : "");
            case INTERACT -> line = "Interact: click a block to walk over and use it";
            case LINK -> line = "Link storage: click containers to link/unlink";
            case BLUEPRINT -> line = this.session.clipboard == null
                    ? "Blueprint: click two corners to copy a region"
                    : this.session.pasteAnchor == null
                    ? "Blueprint: click to place " + this.session.clipboard.name() + " | R rotate"
                    : "Blueprint: arrows nudge, R rotate, click/Enter to build, X to move";
            default -> line = "";
        }
        ctx.drawTextWithShadow(this.textRenderer, line, PAD, y, TEXT);
        String help = "LMB act | RMB move / drag rotate | MMB pick / drag pan | WASD fly | Scroll zoom | V cut view | Esc exit";
        int hw = this.textRenderer.getWidth(help);
        if (PAD + this.textRenderer.getWidth(line) + 12 + hw < this.width) {
            ctx.drawTextWithShadow(this.textRenderer, help, this.width - PAD - hw, y, TEXT_DIM);
        }
    }

    /**
     * Next to the cursor while drawing a shape or pasting: its size, block count and the materials
     * it needs against what you carry and what's in linked storage (red when short).
     */
    private void drawOrderInfo(DrawContext ctx, int mx, int my) {
        BuildSession s = this.session;
        List<Text> lines = new ArrayList<>();
        Map<Item, Integer> materials;
        if (s.tool == ToolMode.BLUEPRINT && s.clipboard != null) {
            lines.add(Text.literal(s.clipboard.name() + "  " + s.clipboard.size(s.pasteTurns) + "  |  "
                    + s.clipboard.blockCount() + " blocks").formatted(Formatting.GOLD));
            materials = s.clipboard.materials();
        } else if (s.tool.usesShapes() && s.anchorA != null) {
            OrderPlanner.Plan plan = WorldOverlay.lastPlan();
            if (plan == null) {
                return;
            }
            if (plan.truncated()) {
                lines.add(Text.literal("Too big: over " + RtsConfig.get().maxShapeBlocks + " blocks").formatted(Formatting.RED));
                ctx.drawTooltip(this.textRenderer, lines, mx + 8, my + 18);
                return;
            }
            String verb = s.tool == ToolMode.DESTROY ? " to mine" : " to place";
            lines.add(Text.literal(plan.dimensions() + "  |  " + plan.changes().size() + verb)
                    .formatted(s.tool == ToolMode.DESTROY ? Formatting.RED : Formatting.AQUA));
            materials = plan.materials();
        } else {
            return;
        }
        int shown = 0;
        for (Map.Entry<Item, Integer> e : materials.entrySet()) {
            if (shown++ >= 4) {
                lines.add(Text.literal("+" + (materials.size() - 4) + " more types").formatted(Formatting.DARK_GRAY));
                break;
            }
            int need = e.getValue();
            int have = this.client.player == null ? 0 : this.client.player.getInventory().count(e.getKey());
            int stored = StorageManager.get().total(e.getKey());
            boolean enough = have + stored >= need;
            lines.add(Text.literal(e.getKey().getName().getString() + ": need " + need + ", have " + have
                    + (stored > 0 ? " (+" + stored + " stored)" : "")).formatted(enough ? Formatting.GRAY : Formatting.RED));
        }
        ctx.drawTooltip(this.textRenderer, lines, mx + 8, my + 18);
    }

    private void drawMessages(DrawContext ctx) {
        List<Messages.Entry> lines = Messages.visible();
        int left = hasLeftPanel() ? LEFT_W + PAD * 3 : PAD * 2;
        int y = bottomY() - 26 - lines.size() * 10;
        for (Messages.Entry e : lines) {
            int alpha = (int) (Messages.alpha(e) * 255) << 24;
            if ((alpha >>> 24) > 8) {
                ctx.drawTextWithShadow(this.textRenderer, e.text(), left, y, 0x00FFFFFF | alpha);
            }
            y += 10;
        }
    }

    private void drawTooltips(DrawContext ctx, int mx, int my) {
        PaletteEntry e = gridAt(mx, my);
        Item item = e != null ? e.item() : pinnedAt(mx, my);
        if (item == null) {
            return;
        }
        int inv = this.client.player == null ? 0 : this.client.player.getInventory().count(item);
        int stored = StorageManager.get().total(item);
        List<Text> lines = new ArrayList<>();
        lines.add(new ItemStack(item).getName());
        lines.add(Text.literal("Inventory: " + inv).formatted(Formatting.GRAY));
        lines.add(Text.literal("Storage: " + stored).formatted(Formatting.GOLD));
        lines.add(Text.literal(e != null ? "Left: select  Right: pin" : "Left: select  Right: unpin").formatted(Formatting.DARK_GRAY));
        ctx.drawTooltip(this.textRenderer, lines, mx, my);
    }

    private String trim(String s, int width) {
        if (this.textRenderer.getWidth(s) <= width) {
            return s;
        }
        return this.textRenderer.trimToWidth(s, width - this.textRenderer.getWidth("...")) + "...";
    }

    private static String shortCount(int n) {
        if (n < 1000) {
            return Integer.toString(n);
        }
        if (n < 10000) {
            return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
        }
        return (n / 1000) + "k";
    }
}
