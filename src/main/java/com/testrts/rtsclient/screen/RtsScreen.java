package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.BuildShape;
import com.testrts.rtsclient.build.OrderPlanner;
import com.testrts.rtsclient.build.TerrainMode;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.build.UndoHistory;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.screen.ui.Glyphs;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.DepositTask;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.CompletedBuilds;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import com.testrts.rtsclient.util.MouseRay;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
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
 * The RTS command screen: sits over the world (no background, no pause), gives the player a
 * cursor, and turns clicks into orders. Layout and input live here; what clicks do lives in
 * {@link WorldActions}.
 */
public final class RtsScreen extends Screen {
    // Layout
    private static final int TOP_H = 24;
    /** Smallest screen (in GUI pixels) the RTS UI fits on; below that it is drawn scaled down. */
    private static final int MIN_W = 560;
    private static final int MIN_H = 380;
    /** How much the UI is scaled down (1 = not at all); mouse positions are divided by it. */
    private float uiScale = 1.0F;
    private static final int LEFT_W = 134;
    private static final int RIGHT_W = 164;
    private static final int SLOT = 18;
    private static final int GRID_ROWS = 3;
    private static final int PAD = 4;
    private static final int ROW = 15;
    private static final int CARD_H = 26;
    /** One-line order cards, used when fewer than {@link #MIN_FULL_CARDS} full ones fit. */
    private static final int COMPACT_H = 14;
    private static final int MIN_FULL_CARDS = 3;
    /** Command buttons above the order list, with the ORDERS title and activity line (keep in step with initRightPanel). */
    private static final int RIGHT_BUTTONS_H = 12 + 3 + 4 * 18 + 20 + 22;
    /** Order cards the right panel makes room for when the screen is tall enough. */
    private static final int ORDER_CARDS = 6;
    /** The storage section under the orders: gap, title and AE2 line, three button rows, padding. */
    private static final int STORAGE_H = 4 + 34 + 3 * 18 + 4;
    private static final int DRAG_THRESHOLD = 3;
    /** Order card being dragged to a new place in the queue. */
    @Nullable
    private RtsTask dragTask;
    private double dragFromY;
    private boolean dragMoved;

    private enum Tab { ALL, INVENTORY, STORAGE }

    /** What kind of item the bar shows (on top of where it is); null = every kind. */
    private enum Category { BLOCKS, TOOLS, MISC }

    private record PaletteEntry(Item item, ItemStack display, int inventory, int stored) {
        int total() {
            return this.inventory + this.stored;
        }
    }

    private final RtsController controller;
    private final BuildSession session;
    private WorldActions actions;

    private TextFieldWidget search;
    private String searchText = "";
    private Tab tab = Tab.ALL;
    private Category category;
    private List<PaletteEntry> palette = new ArrayList<>();
    private int paletteRefresh;
    private int gridScroll;
    private int taskScroll;

    private int leftPanelBottom = TOP_H;
    private final List<int[]> leftSections = new ArrayList<>();
    private final List<String> leftSectionNames = new ArrayList<>();
    private int heightRowY = -1;
    /** Blend strength / Level edge row (its value is drawn between the - and + buttons). */
    private int strengthRowY = -1;
    /** Level brush height row. */
    private int levelRowY = -1;
    private int clipboardInfoY = -1;
    /** Where the chosen shape's name is written under the shape icons. */
    private int shapeNameY = -1;
    private int ordersTop;
    private int ordersBottom;
    /** When Forget all was clicked once; a second click within 3 seconds confirms. */
    private long forgetArmedAt;
    private int storageTop;
    private List<Blueprint> blueprints = new ArrayList<>();
    private boolean blueprintsLoaded;

    private long lastFrameNanos;
    private int pressedButton = -1;
    private double pressX;
    private double pressY;
    private boolean dragging;
    /** Left button held with the Blend brush: dragging keeps painting. */
    private boolean blendPainting;
    /** Interact: the mob under the cursor. */
    @Nullable
    private net.minecraft.entity.Entity hoverEntity;
    /** The cursor is on the air layer (not on a block). */
    private boolean hoverOnLayer;
    /** Air placement is on, but terrain in front of the layer took the cursor. */
    private boolean terrainInFront;

    public RtsScreen(RtsController controller) {
        super(Text.literal("RTS"));
        this.controller = controller;
        this.session = controller.session();
    }

    // ------------------------------------------------------------------ layout

    private int dockH() {
        return PAD + SLOT + 4 + 14 + 4 + GRID_ROWS * SLOT + PAD;
    }

    private int bottomY() {
        return this.height - dockH();
    }

    private int gridCols() {
        return Math.max(1, (this.width - 2 * PAD - 4) / SLOT);
    }

    private int pinnedY() {
        return bottomY() + PAD;
    }

    private int headerY() {
        return pinnedY() + SLOT + 4;
    }

    private int gridY() {
        return headerY() + 14 + 4;
    }

    private int gridX() {
        return PAD + 2;
    }

    private int rightX() {
        return this.width - RIGHT_W - PAD;
    }

    private int rightBottom() {
        return Math.min(bottomY() - PAD - 14, TOP_H + PAD + RIGHT_BUTTONS_H + ORDER_CARDS * (CARD_H + 2) + STORAGE_H);
    }

    private boolean hasLeftPanel() {
        ToolMode t = this.session.tool;
        return t.usesShapes() || t == ToolMode.BLUEPRINT || t == ToolMode.TERRAIN;
    }

    private boolean overUi(double mx, double my) {
        if (my < TOP_H || my >= bottomY()) {
            return true;
        }
        if (hasLeftPanel() && mx < PAD + LEFT_W && my < this.leftPanelBottom) {
            return true;
        }
        return mx >= rightX() && my < rightBottom();
    }

    // ------------------------------------------------------------------ widgets

    private RtsButton add(RtsButton button) {
        return addDrawableChild(button);
    }

    /** The Terrain tool's Blend brush is in hand: the left button paints instead of clicking corners. */
    private boolean blendBrush() {
        return this.session.tool == ToolMode.TERRAIN && this.session.terrainMode.brush();
    }

    private RtsButton button(String label, @Nullable String tip, int x, int y, int w, int h, Runnable action) {
        return add(new RtsButton(x, y, w, h, label, action).tip(tip));
    }

    private static ItemStack toolIcon(ToolMode tool) {
        return new ItemStack(switch (tool) {
            case BUILD -> Items.BRICKS;
            case DESTROY -> Items.IRON_PICKAXE;
            case INTERACT -> Items.LEVER;
            case LINK -> Items.CHEST;
            case BLUEPRINT -> Items.MAP;
            case TERRAIN -> Items.GRASS_BLOCK;
            case MEASURE -> Items.SPYGLASS;
            case PROTECT -> Items.SHIELD;
        });
    }

    private static Glyphs shapeGlyph(BuildShape shape) {
        return switch (shape) {
            case BLOCK -> Glyphs.BLOCK;
            case LINE -> Glyphs.LINE;
            case FLOOR -> Glyphs.FLOOR;
            case WALL -> Glyphs.WALL;
            case BOX -> Glyphs.BOX;
            case CIRCLE -> Glyphs.CIRCLE;
            case CYLINDER -> Glyphs.CYLINDER;
            case SPHERE -> Glyphs.SPHERE;
            case DOME -> Glyphs.DOME;
            case PYRAMID -> Glyphs.PYRAMID;
            case CONE -> Glyphs.CONE;
            case TORUS -> Glyphs.TORUS;
            case ARCH -> Glyphs.ARCH;
            case BRIDGE -> Glyphs.BRIDGE;
            case STAIRS -> Glyphs.STAIRS;
            case SPIRAL -> Glyphs.SPIRAL;
            case VEIN -> Glyphs.VEIN;
            case HOLE -> Glyphs.HOLE;
            case ISLAND -> Glyphs.ISLAND;
            case MOUNTAIN -> Glyphs.MOUNTAIN;
            case GIANT_TREE -> Glyphs.GIANT_TREE;
            case ROCKS -> Glyphs.ROCKS;
            case MAZE -> Glyphs.MAZE;
            case BACKROOMS -> Glyphs.BACKROOMS;
        };
    }

    @Override
    protected void init() {
        // Small window or big GUI scale: lay the UI out for at least MIN_W x MIN_H and draw it scaled down
        // to fit, instead of panels overlapping or running off the screen. Always from the real size, since
        // init() also runs again on tool changes.
        int realW = this.client.getWindow().getScaledWidth();
        int realH = this.client.getWindow().getScaledHeight();
        this.uiScale = Math.min(1.0F, Math.min((float) realW / MIN_W, (float) realH / MIN_H));
        this.width = Math.round(realW / this.uiScale);
        this.height = Math.round(realH / this.uiScale);
        this.lastFrameNanos = System.nanoTime();
        this.actions = new WorldActions(this.client, this.controller);

        // Top bar: tools with icons (icons only when the window is narrow).
        int toolsRoom = this.width - 2 * PAD - 48 - 120;
        int fullWidth = 0;
        for (ToolMode tool : ToolMode.values()) {
            fullWidth += this.textRenderer.getWidth(tool.label()) + 26 + 2;
        }
        boolean compact = fullWidth > toolsRoom;
        int x = PAD;
        for (ToolMode tool : ToolMode.values()) {
            int w = compact ? 22 : this.textRenderer.getWidth(tool.label()) + 26;
            RtsButton b = button(tool.label(), tool.label() + " - " + tool.description() + " [" + (tool.ordinal() + 1) + "]",
                    x, 2, w, 20, () -> selectTool(tool)).icon(toolIcon(tool)).selected(() -> this.session.tool == tool);
            if (compact) {
                b.iconOnly();
            }
            x += w + 2;
        }
        button("Settings", "Settings: engine, layers, previews, camera...", this.width - PAD - 20, 2, 20, 20,
                () -> this.client.setScreen(new RtsSettingsScreen(this))).glyph(Glyphs.GEAR, Theme.TEXT).iconOnly();
        button("Controls", "Every key and mouse control [F1]", this.width - PAD - 42, 2, 20, 20,
                () -> this.showControls = !this.showControls).glyph(Glyphs.HELP, Theme.TEXT).iconOnly()
                .selected(() -> this.showControls);

        initRightPanel();
        initLeftPanel();
        initDock();
        refreshPalette();
    }

    private void initRightPanel() {
        int rx = rightX() + PAD;
        int bw = RIGHT_W - 2 * PAD;
        int half = (bw - 2) / 2;
        int y = TOP_H + PAD + Theme.HEADER_H + 3;
        button("Engine", "Who walks, builds and mines. Auto = Baritone when installed, otherwise the built-in engine.",
                rx, y, bw - 22, 16, () -> {
                    Engine.cycle();
                    Messages.info("Engine: " + Engine.configured().label() + " (using " + Engine.activeName() + ")");
                }).dynamic(() -> "Engine: " + Engine.configured().label()).left();
        button("Undo", "Put back what your last order changed [Ctrl+Z]", rx + bw - 20, y, 20, 16, () -> {
            if (this.client.world != null) {
                UndoHistory.undoLast(this.client.world);
            }
        }).glyph(Glyphs.UNDO, Theme.TEXT).iconOnly();
        y += 18;
        button("Store inv", "Walk to linked storage and put away everything outside your hotbar (pinned items stay)",
                rx, y, half, 16, () -> WorldActions.queue(new DepositTask())).icon(new ItemStack(Items.CHEST));
        button("Stop all", "Cancel every order and stop walking/building", rx + half + 2, y, half, 16, () -> {
            TaskQueue.get().clear(this.client);
            Messages.info("All orders cancelled");
        }).glyph(Glyphs.STOP, Theme.BAD);
        y += 18;
        button("Materials", "Everything your queued orders still need, what you carry and what's in storage. Fetch it all in one trip.",
                rx, y, half, 16, () -> this.client.setScreen(new MaterialsScreen(this))).glyph(Glyphs.LIST, Theme.GOLD);
        button("Repair", "Check finished builds for missing or broken blocks and queue orders to fill them in",
                rx + half + 2, y, half, 16, () -> CompletedBuilds.queueRepairs(this.client.world, false)).glyph(Glyphs.WRENCH, Theme.ACCENT);
        y += 18;
        button("Save queue", "Save every queued order to load again later in this world. Orders still queued when you "
                + "leave are kept as \"Last session\" by themselves.", rx, y, half, 16, () -> {
            if (TaskQueue.get().isEmpty()) {
                Messages.info("Nothing queued to save");
                return;
            }
            String suggested = "Queue " + new java.text.SimpleDateFormat("MMM d HH-mm").format(new java.util.Date());
            this.client.setScreen(new NameScreen(this, "Save queue", suggested, name -> {
                try {
                    int[] r = com.testrts.rtsclient.task.QueueStore.save(name);
                    Messages.success("Saved " + r[0] + " order(s) as \"" + name + "\""
                            + (r[1] > 0 ? " (" + r[1] + " helper step(s) left out; they come back on their own)" : ""));
                } catch (java.io.IOException ex) {
                    Messages.error("Couldn't save the queue: " + ex.getMessage());
                }
            }));
        }).glyph(Glyphs.FOLDER, Theme.GOOD);
        button("Load queue", "Add the orders of a saved queue to the end of this one", rx + half + 2, y, half, 16,
                () -> this.client.setScreen(new QueueScreen(this))).glyph(Glyphs.LIST, Theme.ACCENT);
        y += 18;
        button("Start", "Run the queued orders (they wait until you press this)", rx, y, half, 16, () -> {
            if (TaskQueue.get().isEmpty()) {
                Messages.info("Nothing queued yet");
            } else if (!TaskQueue.get().isStarted()) {
                TaskQueue.get().start();
                Messages.info("Orders started");
            }
        }).glyph(Glyphs.PLAY, Theme.GOOD).selected(() -> TaskQueue.get().isStarted())
                .dynamic(() -> TaskQueue.get().isStarted() ? "Running" : "Start");
        button("Pause", "Stop every order right where it is; Start carries on", rx + half + 2, y, half, 16, () -> {
            if (TaskQueue.get().isStarted()) {
                TaskQueue.get().pauseAll(this.client);
                Messages.info("Orders paused - Start to carry on");
            }
        }).glyph(Glyphs.PAUSE, Theme.GOLD).selected(() -> !TaskQueue.get().isStarted() && !TaskQueue.get().isEmpty());
        y += 20;
        this.ordersTop = y + 22;

        int bottom = rightBottom();
        int sy = bottom - PAD - 3 * 18;
        this.storageTop = sy - (meStatus() != null ? 34 : 24); // room for the AE2 line
        this.ordersBottom = this.storageTop - 4;
        button("Scan nearby", "Link every chest, barrel, shulker box and modded storage block within " + RtsConfig.get().scanRadius
                + " blocks, then open the new ones to read what they hold", rx, sy, half, 16, () -> this.actions.scanNearby());
        button("Index new", "Open linked containers we haven't seen inside yet", rx + half + 2, sy, half, 16,
                () -> this.actions.indexStorage(true));
        button("Re-index", "Visit every linked container again to refresh counts", rx, sy + 18, half, 16,
                () -> this.actions.indexStorage(false));
        button("Sort", "Reorganise linked chests so each item type has its own chest (labels show over them)",
                rx, sy + 36, half, 16, () -> WorldActions.queue(new com.testrts.rtsclient.task.SortStorageTask()))
                .glyph(Glyphs.LIST, Theme.GOLD);
        button("Smelt", "Collect finished smelting from nearby furnaces, then load them with ores/food from storage and fuel",
                rx + half + 2, sy + 36, half, 16, () -> WorldActions.queue(new com.testrts.rtsclient.task.SmeltTask()))
                .icon(new ItemStack(Items.FURNACE));
        button("Forget all", "Forget every linked storage in this world: chests and other containers, ME terminals, RS grids, "
                + "what they hold and the wireless terminals that worked. Click twice to confirm.", rx + half + 2, sy + 18, half, 16, () -> {
            long now = System.currentTimeMillis();
            if (now - this.forgetArmedAt > 3000) {
                this.forgetArmedAt = now;
                Messages.warn("Click Forget all again to forget every linked storage in this world");
                return;
            }
            this.forgetArmedAt = 0;
            StorageManager.get().unlinkAll();
            for (com.testrts.rtsclient.storage.MeNetwork n : com.testrts.rtsclient.storage.MeNetwork.all()) {
                n.reset();
            }
            Messages.info("Forgot every linked storage");
        }).glyph(Glyphs.CROSS, Theme.BAD).dynamic(() -> System.currentTimeMillis() - this.forgetArmedAt <= 3000 ? "Sure?" : "Forget all");
    }

    private void section(int y, String name) {
        this.leftSections.add(new int[]{y});
        this.leftSectionNames.add(name);
    }

    private void initLeftPanel() {
        this.leftSections.clear();
        this.leftSectionNames.clear();
        this.heightRowY = -1;
        this.strengthRowY = -1;
        this.levelRowY = -1;
        this.clipboardInfoY = -1;
        this.shapeNameY = -1;
        BuildSession s = this.session;
        int x = PAD + 4;
        int full = LEFT_W - 8;
        int col = (full - 2) / 2;
        int y = TOP_H + PAD + Theme.HEADER_H + 4;
        switch (s.tool) {
            case BUILD, DESTROY -> {
                // Compact picker: four icon buttons per row (tooltips name them), the chosen shape spelled out below.
                BuildShape[] shapes = BuildShape.values();
                int perRow = 4;
                int cw = (full - (perRow - 1) * 2) / perRow;
                for (int i = 0; i < shapes.length; i++) {
                    BuildShape shape = shapes[i];
                    button(shape.label(), shape.label() + ": " + shape.description() + "  [ and ] cycle",
                            x + (i % perRow) * (cw + 2), y + (i / perRow) * ROW, cw, ROW - 1, () -> {
                                s.shape = shape;
                                s.clearSelection();
                            }).glyph(shapeGlyph(shape), Theme.TEXT_DIM).iconOnly().selected(() -> s.shape == shape);
                }
                y += ((shapes.length + perRow - 1) / perRow) * ROW + 1;
                this.shapeNameY = y;
                y += 13;
                section(y, "OPTIONS");
                y += 11;
                if (s.tool == ToolMode.BUILD) {
                    button("Mode", s.mode.description() + " [M]", x, y, col, ROW - 1, () -> {
                        s.mode = s.mode.next();
                        s.clearSelection();
                        clearAndInit();
                    }).dynamic(() -> "Mode: " + s.mode.label()).left();
                    // Style of the Bridge or Island shape, whichever is picked.
                    button("Style", "Look of the Bridge or Island shape, each with its own blocks (click to cycle)", x + col + 2, y, full - col - 20, ROW - 1,
                            () -> {
                                if (s.shape == BuildShape.GIANT_TREE) {
                                    s.treeKind = s.treeKind.next();
                                    Messages.info("Giant tree: " + s.treeKind.label() + " - " + s.treeKind.description());
                                } else if (s.shape == BuildShape.MAZE) {
                                    s.mazeStyle = s.mazeStyle.next();
                                    Messages.info("Maze: " + s.mazeStyle.label() + " - " + s.mazeStyle.description());
                                } else if (s.shape == BuildShape.BACKROOMS) {
                                    s.backroomsLevel = s.backroomsLevel.next();
                                    Messages.info("Backrooms: " + s.backroomsLevel.label() + " - " + s.backroomsLevel.description());
                                } else if (s.shape == BuildShape.ROCKS) {
                                    s.rockForm = s.rockForm.next();
                                    Messages.info("Rocks: " + s.rockForm.label() + " - " + s.rockForm.description());
                                } else if (s.shape == BuildShape.ISLAND || s.shape == BuildShape.MOUNTAIN) {
                                    s.islandStyle = s.islandStyle.next();
                                    Messages.info(s.shape.label() + ": " + s.islandStyle.label() + " - " + s.islandStyle.description());
                                } else {
                                    s.bridgeStyle = s.bridgeStyle.next();
                                    Messages.info("Bridge: " + s.bridgeStyle.label() + " - " + s.bridgeStyle.description());
                                }
                            })
                            .enabled(() -> s.shape == BuildShape.BRIDGE || s.shape == BuildShape.ISLAND || s.shape == BuildShape.MOUNTAIN
                                    || s.shape == BuildShape.GIANT_TREE || s.shape == BuildShape.ROCKS || s.shape == BuildShape.MAZE
                                    || s.shape == BuildShape.BACKROOMS)
                            .dynamic(() -> s.shape == BuildShape.GIANT_TREE ? s.treeKind.label()
                                    : s.shape == BuildShape.MAZE ? s.mazeStyle.label()
                                    : s.shape == BuildShape.BACKROOMS ? s.backroomsLevel.label()
                                    : s.shape == BuildShape.ROCKS ? s.rockForm.label()
                                    : s.shape == BuildShape.ISLAND || s.shape == BuildShape.MOUNTAIN ? s.islandStyle.label()
                                    : s.bridgeStyle.label()).left();
                    button("Randomize", "Island, Mountain, Giant tree, Rocks, Maze or Backrooms: a new random design [N]", x + full - 16, y, 16, ROW - 1,
                            this::randomizeIsland)
                            .glyph(Glyphs.DICE, Theme.TEXT).iconOnly().enabled(() -> randomized(s.shape));
                    y += ROW;
                }
                button("Hollow", "Only the outside of floors, boxes, circles and spheres [H]", x, y, col, ROW - 1,
                        () -> s.hollow = !s.hollow).selected(() -> s.hollow).dynamic(() -> s.hollow ? "Hollow: on" : "Hollow: off").left();
                // Islands don't turn over, so the same button picks their underside.
                button("Inverted", "Dome, pyramid and cone upside down: where you click is the bottom (the tip) and it widens upward - a funnel "
                                + "or bowl to build, or a pit to dig (click its deepest point; cut view [V] helps). "
                                + "Bridge: hump up or sag down in the middle. Island: the shape of its underside (Pointed, Rounded, Stepped, Torn out). Mountain: its form (Peaked, Rounded, Mesa, Volcano, Jagged, Ridge) [I]; a new design [N]",
                        x + col + 2, y, full - col - 2, ROW - 1, this::toggleInverted)
                        .selected(() -> s.inverted && (s.shape.invertible() || s.shape == BuildShape.BRIDGE))
                        .enabled(() -> s.shape.invertible() || s.shape == BuildShape.ISLAND || s.shape == BuildShape.BRIDGE
                                || s.shape == BuildShape.MOUNTAIN || s.shape == BuildShape.ROCKS || s.shape == BuildShape.MAZE)
                        .dynamic(() -> s.shape == BuildShape.ISLAND ? "Bottom: " + s.islandBottom.label()
                                : s.shape == BuildShape.MAZE ? "Layout: " + s.mazeLayout.label()
                                : s.shape == BuildShape.ROCKS ? "Look: " + s.rockLook.label()
                                : s.shape == BuildShape.MOUNTAIN ? "Form: " + s.mountainForm.label()
                                : s.shape == BuildShape.BRIDGE ? (s.inverted ? "Sag" : "Hump")
                                : s.inverted ? "Inverted" : "Upright").left();
                y += ROW;
                button("-", "Move the air layer down [Alt+scroll]", x, y, 16, ROW - 1, () -> s.airY--).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly()
                        .enabled(() -> s.airPlace);
                button("Air", "Air placement: point at empty space on a flat layer to build in mid-air [B]",
                        x + 18, y, full - 36, ROW - 1, this::toggleAir)
                        .selected(() -> s.airPlace).dynamic(() -> s.airPlace ? "Air: Y " + s.airY : "Air: off");
                button("+", "Move the air layer up [Alt+scroll]", x + full - 16, y, 16, ROW - 1, () -> s.airY++).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly()
                        .enabled(() -> s.airPlace);
                y += ROW;
                button("-", "Lower", x, y, 16, ROW - 1, () -> s.adjustHeight(-1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly()
                        .enabled(() -> s.shape.usesHeight() || s.circleHoleMode());
                button("+", "Height for walls, boxes and cylinders. Negative digs down. Circle (hollow off): a hole from the centre. [Ctrl+scroll]", x + full - 16, y, 16, ROW - 1,
                        () -> s.adjustHeight(1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly()
                        .enabled(() -> s.shape.usesHeight() || s.circleHoleMode());
                this.heightRowY = y;
                y += ROW;
                if (s.tool == ToolMode.BUILD) {
                    button("Rotate", "Turn directional blocks (stairs, logs, furnaces) [R]", x, y, full, ROW - 1, s.orientation::cycle)
                            .glyph(Glyphs.ROTATE, Theme.TEXT).left();
                    y += ROW;
                    // Pattern brush: a weighted random mix of blocks.
                    button("+ Mix", "Add the selected block to the mix (click again to add more of it)", x, y, col, ROW - 1, () -> {
                        if (s.brush.getItem() instanceof BlockItem) {
                            s.mix.merge(s.brush.getItem(), 1, Integer::sum);
                            Messages.info("Mix: " + s.mixDescription());
                        } else {
                            Messages.warn("Pick a block first, then add it to the mix");
                        }
                    }).selected(() -> !s.mix.isEmpty());
                    button("Clear mix", "Build with a single block again", x + col + 2, y, col, ROW - 1, s.mix::clear);
                    y += ROW;
                    button("Pattern", "How the mix is laid out: Random, Clusters, Gradient (bottom to top, in the order the "
                            + "blocks were added), Layers, Stripes or Checker. Add blocks with + Mix first.",
                            x, y, full, ROW - 1, () -> s.pattern = s.pattern.next())
                            .dynamic(() -> "Pattern: " + s.pattern.label()).enabled(() -> !s.mix.isEmpty())
                            .glyph(Glyphs.LIST, Theme.ACCENT).left();
                    y += ROW;
                    button("Roof", "Smart roof: click the top of a room's wall and get a roof sized to the room "
                            + "(use stairs as the block for a sloped roof)", x, y, col, ROW - 1, () -> {
                        s.roofPick = !s.roofPick;
                        if (s.roofPick) {
                            Messages.info("Roof: click the top of a wall of the room");
                        }
                    }).selected(() -> s.roofPick).icon(new ItemStack(Items.OAK_STAIRS));
                    button("Style", "Roof style (click to cycle): Gable, Steep gable, Barn, Lean-to, Hip, Mansard, Flat", x + col + 2, y, col, ROW - 1,
                            () -> {
                                s.roofStyle = s.roofStyle.next();
                                Messages.info("Roof: " + s.roofStyle.label() + " - " + s.roofStyle.description());
                            }).dynamic(() -> s.roofStyle.label());
                    y += ROW;
                    button("Look", "Roof look (click to cycle): Brush (your block), Cottage, Witch, Snowy, Medieval", x, y, full, ROW - 1,
                            () -> {
                                s.roofLook = s.roofLook.next();
                                Messages.info("Roof look: " + s.roofLook.label() + " - " + s.roofLook.description());
                            }).dynamic(() -> "Look: " + s.roofLook.label()).left();
                    y += ROW + 3;
                    y = initStatePicker(x, y, full);
                }
            }
            case BLUEPRINT -> {
                if (!this.blueprintsLoaded) {
                    this.blueprints = Blueprint.loadAll();
                    this.blueprintsLoaded = true;
                }
                button("Copy", "Select a region with two clicks to copy it", x, y, col, ROW - 1, () -> {
                    s.cutOnCopy = false;
                    s.clipboard = null;
                    s.pasteAnchor = null;
                    s.clearSelection();
                }).selected(() -> s.clipboard == null && !s.cutOnCopy);
                button("Move", "Pick up a building: select it with two clicks, place it somewhere else; the original is "
                        + "taken down and rebuilt there", x + col + 2, y, col, ROW - 1, () -> {
                    s.cutOnCopy = true;
                    s.clipboard = null;
                    s.pasteAnchor = null;
                    s.clearSelection();
                }).selected(() -> s.clipboard == null && s.cutOnCopy);
                y += ROW;
                button("Rotate", "Turn the blueprint 90 degrees [R]", x, y, col, ROW - 1, () -> s.pasteTurns++)
                        .glyph(Glyphs.ROTATE, Theme.TEXT);
                button("Mirror", "Flip the blueprint left-right [N]", x + col + 2, y, col, ROW - 1, () -> s.pasteMirror = !s.pasteMirror)
                        .glyph(Glyphs.MIRROR, Theme.TEXT).selected(() -> s.pasteMirror);
                y += ROW;
                button("Air", "clear: also dig out the blueprint's empty spaces (rooms, doorways) - for pasting into a hillside or a wall."
                                + " keep: leave them. Solid blocks where the blueprint has a block are always cleared first.",
                        x, y, full, ROW - 1, () -> s.pasteClearsAir = !s.pasteClearsAir)
                        .dynamic(() -> s.pasteClearsAir ? "Air: clear" : "Air: keep").left();
                y += ROW;
                // Repeat: a row or grid of copies.
                int third = (full - 4) / 3;
                button("-", "Fewer copies along X", x, y, 12, ROW - 1, () -> s.arrayX = Math.max(1, s.arrayX - 1))
                        .glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                button("X", "Copies along X (click to reset)", x + 13, y, third - 26, ROW - 1, () -> s.arrayX = 1)
                        .dynamic(() -> "X" + s.arrayX).selected(() -> s.arrayX > 1);
                button("+", "More copies along X", x + third - 12, y, 12, ROW - 1, () -> s.arrayX = Math.min(32, s.arrayX + 1))
                        .glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                int zx = x + third + 2;
                button("-", "Fewer copies along Z", zx, y, 12, ROW - 1, () -> s.arrayZ = Math.max(1, s.arrayZ - 1))
                        .glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                button("Z", "Copies along Z (click to reset)", zx + 13, y, third - 26, ROW - 1, () -> s.arrayZ = 1)
                        .dynamic(() -> "Z" + s.arrayZ).selected(() -> s.arrayZ > 1);
                button("+", "More copies along Z", zx + third - 12, y, 12, ROW - 1, () -> s.arrayZ = Math.min(32, s.arrayZ + 1))
                        .glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                int gx = x + 2 * third + 4;
                int gw = x + full - gx;
                button("Gap", "Space between copies: click to cycle 0-8", gx, y, gw, ROW - 1, () -> s.arrayGap = (s.arrayGap + 1) % 9)
                        .dynamic(() -> "gap " + s.arrayGap);
                y += ROW;
                RtsButton save = button("Save...", "Keep this blueprint (copies, presets, schematics and roofs) in the saved list, with a name",
                        x, y, col, ROW - 1, () -> {
                            if (s.clipboard == null) {
                                return;
                            }
                            this.client.setScreen(new NameScreen(this, "Save blueprint", s.clipboard.name(), name -> {
                                var bp = s.clipboard.renamed(name);
                                bp.save();
                                s.clipboard = bp;
                                this.blueprintsLoaded = false;
                                Messages.success("Saved \"" + name + "\"");
                                clearAndInit();
                            }));
                        }).glyph(Glyphs.FOLDER, Theme.GOOD).left();
                save.active = s.clipboard != null;
                RtsButton export = button("Export...", "Write it as a schematic file in .minecraft/schematics that WorldEdit, "
                        + "Litematica and others can open (.schem). Shift+click: vanilla structure .nbt.",
                        x + col + 2, y, col, ROW - 1, () -> {
                            if (s.clipboard == null) {
                                return;
                            }
                            com.testrts.rtsclient.build.SchematicWriter.Format format = hasShiftDown()
                                    ? com.testrts.rtsclient.build.SchematicWriter.Format.NBT
                                    : com.testrts.rtsclient.build.SchematicWriter.Format.SCHEM;
                            Blueprint bp = s.clipboard;
                            this.client.setScreen(new NameScreen(this, "Export as " + format.label(),
                                    com.testrts.rtsclient.build.SchematicWriter.suggestName(bp.name()), name -> {
                                        try {
                                            java.nio.file.Path file = com.testrts.rtsclient.build.SchematicWriter.write(
                                                    bp, SchematicBrowserScreen.folder(), name, format);
                                            Messages.success("Exported " + file.getFileName() + " to schematics/");
                                        } catch (java.io.IOException ex) {
                                            com.testrts.rtsclient.RtsClientMod.LOGGER.warn("Could not export schematic", ex);
                                            Messages.error("Export failed: " + ex.getMessage());
                                        }
                                    }));
                        }).glyph(Glyphs.FOLDER, Theme.GOLD);
                export.active = s.clipboard != null;
                y += ROW;
                button("Presets...", "Ready-made buildings: house, smelter room, storage, enchanting room and more [P]",
                        x, y, full, ROW - 1, () -> this.client.setScreen(new PresetScreen(this))).glyph(Glyphs.BOX, Theme.ACCENT).left();
                y += ROW;
                button("Load schematic...", "Litematica .litematic, WorldEdit .schem or structure .nbt from .minecraft/schematics",
                        x, y, full, ROW - 1, () -> this.client.setScreen(new SchematicBrowserScreen(this))).glyph(Glyphs.FOLDER, Theme.GOLD).left();
                y += ROW + 3;
                this.clipboardInfoY = y;
                y += 22;
                section(y, "SAVED");
                y += 11;
                int shown = 0;
                for (Blueprint bp : this.blueprints) {
                    if (shown++ >= 8 || y + ROW > bottomY() - 20) {
                        break;
                    }
                    button(bp.name(), bp.size() + ", " + bp.blockCount() + " blocks. Click to place, Shift+click to delete.",
                            x, y, full, ROW - 1, () -> {
                                if (hasShiftDown()) {
                                    bp.delete();
                                    if (s.clipboard == bp) {
                                        s.clipboard = null;
                                    }
                                    this.blueprints = Blueprint.loadAll();
                                    Messages.info("Deleted " + bp.name());
                                    clearAndInit();
                                } else {
                                    s.clipboard = bp;
                                    s.pasteTurns = 0;
                                    s.pasteAnchor = null;
                                    s.clearSelection();
                                }
                            }).selected(() -> s.clipboard == bp).left();
                    y += ROW;
                }
                if (this.blueprints.isEmpty()) {
                    y += 10;
                }
            }
            case TERRAIN -> {
                for (TerrainMode mode : TerrainMode.values()) {
                    int i = mode.ordinal();
                    button(mode.label(), mode.description() + " [M]", x + (i % 2) * (col + 2), y + (i / 2) * (ROW + 5), col, ROW + 3, () -> {
                        s.terrainMode = mode;
                        s.clearSelection();
                        clearAndInit();
                    }).selected(() -> s.terrainMode == mode)
                            .icon(new ItemStack(switch (mode) {
                                case FLATTEN -> Items.IRON_SHOVEL;
                                case BLEND -> Items.BRUSH;
                                case LEVEL -> Items.STONE_SHOVEL;
                                case LIGHT_UP -> Items.TORCH;
                                case QUARRY -> Items.IRON_PICKAXE;
                                case FELL_TREES -> Items.IRON_AXE;
                                case HARVEST -> Items.WHEAT;
                                case PLANT_TREES -> Items.OAK_SAPLING;
                                case ROAD -> Items.DIRT_PATH;
                                case ROOM -> Items.CHEST;
                                case FARM -> Items.IRON_HOE;
                                case MOB_PROOF -> Items.SMOOTH_STONE_SLAB;
                                case RESTYLE -> Items.CYAN_DYE;
                                case HILL -> Items.GRASS_BLOCK;
                                case LAKE -> Items.WATER_BUCKET;
                                case RIVER -> Items.SAND;
                                case TERRACE -> Items.STONE_STAIRS;
                                case CAVE -> Items.STONE_PICKAXE;
                                case ISLAND_BRIDGE -> Items.OAK_FENCE;
                                case DECORATE -> Items.POPPY;
                            }));
                }
                y += ((TerrainMode.values().length + 1) / 2) * (ROW + 5) + 2;
                if (s.terrainMode == TerrainMode.FLATTEN || s.terrainMode == TerrainMode.QUARRY) {
                    section(y, s.terrainMode == TerrainMode.QUARRY ? "DIG DEPTH" : "MINE ABOVE");
                    y += 11;
                    button("-", "Clear less above the level", x, y, 16, ROW - 1,
                            () -> s.terrainClearHeight = Math.max(1, s.terrainClearHeight - 4)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                    button("+", "How far above the level to mine [Ctrl+scroll]", x + full - 16, y, 16, ROW - 1,
                            () -> s.terrainClearHeight = Math.min(64, s.terrainClearHeight + 4)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                    this.heightRowY = y;
                    y += ROW + 2;
                }
                if (s.terrainMode.brush()) {
                    section(y, "BRUSH SIZE");
                    y += 11;
                    button("-", "Smaller brush [Ctrl+scroll]", x, y, 16, ROW - 1,
                            () -> s.blendRadius = Math.max(0, s.blendRadius - 1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                    button("+", "Bigger brush [Ctrl+scroll]", x + full - 16, y, 16, ROW - 1,
                            () -> s.blendRadius = Math.min(12, s.blendRadius + 1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                    this.heightRowY = y;
                    y += ROW + 2;
                    if (s.terrainMode == TerrainMode.LEVEL) {
                        section(y, "LEVEL Y");
                        y += 11;
                        button("-", "Level lower (set to where you start painting)", x, y, 16, ROW - 1,
                                () -> s.levelY--).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                        button("+", "Level higher (set to where you start painting)", x + full - 16, y, 16, ROW - 1,
                                () -> s.levelY++).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                        this.levelRowY = y;
                        y += ROW + 2;
                        section(y, "EDGE");
                        y += 11;
                        button("-", "Steeper edge (0: a sharp step)", x, y, 16, ROW - 1,
                                () -> s.levelRamp = Math.max(0, s.levelRamp - 1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                        button("+", "Wider slope from the level into the land around it", x + full - 16, y, 16, ROW - 1,
                                () -> s.levelRamp = Math.min(10, s.levelRamp + 1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                    } else {
                        section(y, "STRENGTH");
                        y += 11;
                        button("-", "Gentler: only the sharpest bumps and dips change", x, y, 16, ROW - 1,
                                () -> s.blendStrength = Math.max(1, s.blendStrength - 1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                        button("+", "Stronger: smooths further and spreads slopes wider", x + full - 16, y, 16, ROW - 1,
                                () -> s.blendStrength = Math.min(10, s.blendStrength + 1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                    }
                    this.strengthRowY = y;
                    y += ROW + 2;
                    button("Top", "keep: top raised ground with the surface block (grass, podzol...) - "
                                    + "in survival that needs those blocks (Silk Touch). dirt: leave plain dirt, grass grows back by itself.",
                            x, y, full, ROW - 1, () -> s.blendKeepTop = !s.blendKeepTop)
                            .dynamic(() -> s.blendKeepTop ? "Top: keep surface" : "Top: dirt").left();
                    y += ROW;
                    button("Clear", "Forget the painted area [X]", x, y, full, ROW - 1, s::clearSelection).left();
                    y += ROW + 2;
                }
                if (s.terrainMode.sizeLabel() != null) {
                    section(y, s.terrainMode.sizeLabel());
                    y += 11;
                    button("-", "Smaller [Ctrl+scroll]", x, y, 16, ROW - 1,
                            () -> s.terrainSize = Math.max(1, s.terrainSize - 1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                    button("+", "Bigger [Ctrl+scroll]", x + full - 16, y, 16, ROW - 1,
                            () -> s.terrainSize = Math.min(32, s.terrainSize + 1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                    this.heightRowY = y;
                    y += ROW + 2;
                }
                if (s.terrainMode == TerrainMode.PLANT_TREES) {
                    section(y, "SPACING");
                    y += 11;
                    button("-", "Plant closer together", x, y, 16, ROW - 1,
                            () -> s.treeSpacing = Math.max(2, s.treeSpacing - 1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                    button("+", "Leave more room between trees (big trees like more than 6)", x + full - 16, y, 16, ROW - 1,
                            () -> s.treeSpacing = Math.min(16, s.treeSpacing + 1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                    this.heightRowY = y;
                    y += ROW + 2;
                }
                this.clipboardInfoY = y;
                y += 22;
            }
            default -> {
            }
        }
        this.leftPanelBottom = y + PAD;
    }

    private static String sizeText(BuildSession s) {
        return switch (s.terrainMode) {
            case ROAD, RIVER, ISLAND_BRIDGE -> s.terrainSize + " wide";
            case HILL -> s.terrainSize + " high";
            case LAKE -> s.terrainSize + " deep";
            case TERRACE -> s.terrainSize + " per step";
            case CAVE -> s.terrainSize + " across";
            case DECORATE -> s.terrainSize + " / 10";
            default -> String.valueOf(s.terrainSize);
        };
    }

    private static String brushName(BuildSession s, String fallback) {
        return s.brush.getItem() instanceof BlockItem ? s.brush.getName().getString() : fallback;
    }

    /** One button per block-state property of the brush (slab half, stair shape...), cycling through values. */
    private int initStatePicker(int x, int y, int width) {
        BlockState state = this.session.brushState(this.controller.camera().yaw());
        if (state == null) {
            return y;
        }
        boolean any = false;
        int shown = 0;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            if (name.equals("waterlogged") || name.equals("powered") || name.equals("distance") || name.equals("persistent")) {
                continue;
            }
            if (shown++ >= 6 || y + ROW > bottomY() - 20) {
                break;
            }
            if (!any) {
                section(y, "BLOCK STATE");
                y += 11;
                any = true;
            }
            button(name, "Click to change this block property (auto = decided by where you place it)",
                    x, y, width, ROW - 1, () -> cycleProperty(property))
                    .dynamic(() -> name + ": " + this.session.stateOverrides.getOrDefault(name, "auto")).left();
            y += ROW;
        }
        if (!this.session.stateOverrides.isEmpty()) {
            button("Reset state", "Back to automatic block states", x, y, width, ROW - 1, () -> {
                this.session.stateOverrides.clear();
                clearAndInit();
            });
            y += ROW;
        }
        return y;
    }

    private <T extends Comparable<T>> void cycleProperty(Property<T> property) {
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

    private void initDock() {
        int searchW = Math.min(150, this.width / 3);
        this.search = new TextFieldWidget(this.textRenderer, PAD + 6, headerY() + 3, searchW - 8, 10, Text.literal("Search"));
        this.search.setMaxLength(50);
        this.search.setDrawsBackground(false);
        // init() runs again every time the screen comes back (after a chest, chat...), keep the query.
        this.search.setText(this.searchText);
        this.search.setPlaceholder(Text.literal("Search items...  [F]").formatted(Formatting.DARK_GRAY));
        this.search.setChangedListener(text -> {
            this.searchText = text;
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
            int w = this.textRenderer.getWidth(label) + 12;
            button(label, null, tx, headerY(), w, 14, () -> {
                this.tab = t;
                this.gridScroll = 0;
                refreshPalette();
            }).selected(() -> this.tab == t);
            tx += w + 2;
        }
        // Kind of item: placeable blocks, tools (anything that wears out), the rest. Again to show all kinds.
        tx += 6;
        for (Category c : Category.values()) {
            String label = switch (c) {
                case BLOCKS -> "Blocks";
                case TOOLS -> "Tools";
                case MISC -> "Misc";
            };
            String tip = switch (c) {
                case BLOCKS -> "Only blocks you can place (click again for every kind)";
                case TOOLS -> "Only tools, weapons and other gear that wears out (click again for every kind)";
                case MISC -> "Only everything else: food, materials, items (click again for every kind)";
            };
            int w = this.textRenderer.getWidth(label) + 12;
            button(label, tip, tx, headerY(), w, 14, () -> {
                this.category = this.category == c ? null : c;
                this.gridScroll = 0;
                refreshPalette();
            }).selected(() -> this.category == c);
            tx += w + 2;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.controller.disable(this.client);
    }

    // ------------------------------------------------------------------ state changes

    private void selectTool(ToolMode tool) {
        if (this.session.tool != tool) {
            this.session.tool = tool;
            this.session.clearSelection();
            this.session.pasteAnchor = null;
            clearAndInit();
        }
    }

    private void selectBrush(Item item) {
        if (this.session.brush.getItem() != item) {
            // Picked properties (slab half...) belong to the old block.
            this.session.stateOverrides.clear();
        }
        this.session.brush = new ItemStack(item);
        if (item instanceof BlockItem && !this.session.tool.usesShapes() && this.session.tool != ToolMode.TERRAIN) {
            selectTool(ToolMode.BUILD);
        } else {
            clearAndInit();
        }
    }

    /**
     * The block under the cursor. With air placement on (or while drawing a shape when the cursor
     * points at nothing), the cursor can also target empty cells on a flat layer in mid-air.
     */
    @Nullable
    /** The first ghost block of a queued build along the cursor ray, if it's nearer than {@code real}. */
    private BlockHitResult pickGhost(double mx, double my, BlockHitResult real) {
        var world = this.client.world;
        if (world == null || TaskQueue.get().isEmpty()) {
            return null;
        }
        var cam = this.controller.camera();
        net.minecraft.util.math.Vec3d start = cam.position();
        net.minecraft.util.math.Vec3d dir = MouseRay.direction(cam, mx, my, this.width, this.height);
        double reach = real != null ? Math.sqrt(real.getPos().squaredDistanceTo(start)) + 0.5 : 256.0;
        net.minecraft.util.math.Vec3d end = start.add(dir.multiply(reach));
        BlockPos ghost = net.minecraft.world.BlockView.raycast(start, end, world, (w, p) -> WorldActions.isQueuedGhost(world, p) ? p.toImmutable() : null, w -> null);
        if (ghost == null) {
            return null;
        }
        // The face the ray goes in through, so a build started on the hologram sits on top of it (or against
        // the side you're pointing at), not on whichever side faces the camera.
        BlockHitResult in = net.minecraft.util.math.Box.raycast(java.util.List.of(new net.minecraft.util.math.Box(ghost)), start, end, ghost);
        if (in != null) {
            return in;
        }
        net.minecraft.util.math.Direction face = net.minecraft.util.math.Direction.getFacing(-dir.x, -dir.y, -dir.z);
        net.minecraft.util.math.Vec3d at = net.minecraft.util.math.Vec3d.ofCenter(ghost).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        return new net.minecraft.util.hit.BlockHitResult(at, face, ghost, false);
    }

    private BlockHitResult pick(double mx, double my) {
        var cam = this.controller.camera();
        BlockHitResult hit = MouseRay.pick(cam, mx, my, this.width, this.height, false);
        BuildSession s = this.session;
        if (s.tool == ToolMode.DESTROY || s.tool == ToolMode.BUILD || s.tool == ToolMode.BLUEPRINT) {
            // The hologram of queued builds is pointed at like real blocks in front of it: Destroy cuts blocks
            // out of it, Build and Blueprint (and roofs) go on top of it or against it.
            BlockHitResult ghost = pickGhost(mx, my, hit);
            if (ghost != null) {
                return ghost;
            }
        }
        this.hoverOnLayer = false;
        this.terrainInFront = false;
        // Drawing a shape in air mode: the next point stays on the air layer even where terrain is in
        // front of it, so a dome or sphere gets the radius you see instead of jumping to the ground.
        if (s.airPlace && s.anchorA != null && s.tool == ToolMode.BUILD) {
            BlockHitResult onLayer = MouseRay.pickLayer(cam, mx, my, this.width, this.height, s.airY);
            if (onLayer != null) {
                this.hoverOnLayer = true;
                return onLayer;
            }
        }
        Integer layer = s.airPlace ? Integer.valueOf(s.airY)
                : hit == null && s.anchorA != null ? Integer.valueOf(s.anchorA.getY()) : null;
        if (layer == null) {
            return hit;
        }
        BlockHitResult air = MouseRay.pickLayer(cam, mx, my, this.width, this.height, layer);
        if (air == null) {
            return hit;
        }
        if (hit != null && hit.getPos().squaredDistanceTo(cam.position()) < air.getPos().squaredDistanceTo(cam.position())) {
            this.terrainInFront = s.airPlace;
            return hit; // terrain in front of the layer
        }
        this.hoverOnLayer = true;
        return air;
    }

    /** The mob under the cursor, if nothing solid is in front of it. */
    @Nullable
    private net.minecraft.entity.Entity pickEntity(double mx, double my) {
        BlockHitResult block = pick(mx, my);
        double max = block != null ? block.getPos().distanceTo(this.controller.camera().position()) + 0.5 : 128.0;
        return com.testrts.rtsclient.util.MouseRay.pickEntity(this.controller.camera(), mx, my, this.width, this.height, max);
    }

    /** Randomize button / [N]: the island being placed gets a new design (the preview updates). */
    private void randomizeIsland() {
        this.session.islandSeed = new java.util.Random().nextLong();
        Messages.info("New " + this.session.shape.label().toLowerCase(java.util.Locale.ROOT) + " design");
    }

    /** Shapes with a random design per placement (the dice button, [N]). */
    private static boolean randomized(BuildShape shape) {
        return shape == BuildShape.ISLAND || shape == BuildShape.MOUNTAIN || shape == BuildShape.GIANT_TREE || shape == BuildShape.ROCKS
                || shape == BuildShape.MAZE || shape == BuildShape.BACKROOMS;
    }

    /** [I]: dome, pyramid and cone upside down; bridges hump or sag; for the Island shape, the next underside. */
    private void toggleInverted() {
        BuildSession s = this.session;
        if (s.shape == BuildShape.MAZE) {
            s.mazeLayout = s.mazeLayout.next();
            Messages.info("Maze layout: " + s.mazeLayout.label() + " - " + s.mazeLayout.description());
        } else if (s.shape == BuildShape.ROCKS) {
            s.rockLook = s.rockLook.next();
            Messages.info("Rocks look: " + s.rockLook.label());
        } else if (s.shape == BuildShape.MOUNTAIN) {
            s.mountainForm = s.mountainForm.next();
            Messages.info("Mountain form: " + s.mountainForm.label() + " - " + s.mountainForm.description());
        } else if (s.shape == BuildShape.ISLAND) {
            s.islandBottom = s.islandBottom.next();
            Messages.info("Island bottom: " + s.islandBottom.label() + " - " + s.islandBottom.description());
        } else if (s.shape == BuildShape.BRIDGE) {
            s.inverted = !s.inverted;
            Messages.info(s.inverted ? "Bridge sags down in the middle" : "Bridge humps up in the middle");
        } else if (s.shape.invertible()) {
            s.inverted = !s.inverted;
            Messages.info(s.shape.label() + (s.inverted ? " upside down" : " upright"));
        }
    }

    private void toggleAir() {
        BuildSession s = this.session;
        s.airPlace = !s.airPlace;
        if (s.airPlace) {
            BlockHitResult h = this.controller.hover();
            if (h != null) {
                s.airY = WorldOverlay.targetFor(s, h).getY();
            } else if (this.client.player != null) {
                s.airY = this.client.player.getBlockY();
            }
            Messages.info("Air placement on at Y " + s.airY + " - Alt+scroll or the -/+ buttons move the layer, B turns it off");
        } else {
            Messages.info("Air placement off");
        }
    }

    // ------------------------------------------------------------------ palette

    private static Category categoryOf(PaletteEntry e) {
        if (e.item() instanceof BlockItem) {
            return Category.BLOCKS;
        }
        // Tools, weapons, shears, armour, modded gear: anything with durability.
        return e.display().isDamageable() ? Category.TOOLS : Category.MISC;
    }

    private void refreshPalette() {
        this.paletteRefresh = 0;
        if (this.client == null || this.client.player == null) {
            this.palette = new ArrayList<>();
            return;
        }
        Map<Item, Integer> inv = new HashMap<>();
        PlayerInventory pi = this.client.player.getInventory();
        for (ItemStack st : pi.main) {
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
            if (this.category != null && categoryOf(e) != this.category) {
                continue;
            }
            if (query.isEmpty() || e.display().getName().getString().toLowerCase(Locale.ROOT).contains(query)
                    || Registries.ITEM.getId(e.item()).getPath().contains(query)) {
                list.add(e);
            }
        }
        // Blocks first (they're what you build with), then by name.
        list.sort(Comparator.comparing((PaletteEntry e) -> !(e.item() instanceof BlockItem))
                .thenComparing(e -> e.display().getName().getString()));
        this.palette = list;
        int maxScroll = Math.max(0, (list.size() + gridCols() - 1) / gridCols() - GRID_ROWS);
        this.gridScroll = MathHelper.clamp(this.gridScroll, 0, maxScroll);
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

    @Nullable
    private Item pinnedAt(double mx, double my) {
        int y = pinnedY();
        if (my < y || my >= y + SLOT || mx < gridX()) {
            return null;
        }
        int idx = (int) ((mx - gridX()) / SLOT);
        List<String> pinned = RtsConfig.get().pinned;
        if (idx < 0 || idx >= pinned.size()) {
            return null;
        }
        Identifier id = Identifier.tryParse(pinned.get(idx));
        return id != null && Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
    }

    @Nullable
    private PaletteEntry gridAt(double mx, double my) {
        int y0 = gridY();
        if (my < y0 || my >= y0 + GRID_ROWS * SLOT || mx < gridX() || mx >= gridX() + gridCols() * SLOT) {
            return null;
        }
        int col = (int) ((mx - gridX()) / SLOT);
        int row = (int) ((my - y0) / SLOT);
        int idx = (row + this.gridScroll) * gridCols() + col;
        return idx >= 0 && idx < this.palette.size() ? this.palette.get(idx) : null;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= this.uiScale;
        my /= this.uiScale;
        if (this.showControls && !(my < TOP_H && mx >= this.width - PAD - 42 && mx < this.width - PAD - 20)) {
            this.showControls = false;
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (this.search != null && this.search.isFocused() && !this.search.isMouseOver(mx, my)) {
            this.search.setFocused(false);
            setFocused(null);
        }
        Item pinned = pinnedAt(mx, my);
        if (pinned != null) {
            if (button == 0 && hasControlDown()) {
                this.actions.fetch(pinned);
            } else if (button == 0) {
                selectBrush(pinned);
            } else if (button == 1) {
                togglePin(pinned);
            }
            return true;
        }
        PaletteEntry entry = gridAt(mx, my);
        if (entry != null) {
            if (button == 0 && hasControlDown()) {
                this.actions.fetch(entry.item());
            } else if (button == 0) {
                selectBrush(entry.item());
            } else {
                togglePin(entry.item());
            }
            return true;
        }
        if (clickOrders(mx, my) || overUi(mx, my)) {
            return true;
        }
        this.pressedButton = button;
        this.pressX = mx;
        this.pressY = my;
        this.dragging = false;
        if (button == 0) {
            if (this.session.tool == ToolMode.INTERACT) {
                // A mob in front of the block under the cursor gets the click.
                var mob = pickEntity(mx, my);
                if (mob != null) {
                    this.actions.interactEntity(mob);
                    return true;
                }
            }
            BlockHitResult hit = pick(mx, my);
            if (blendBrush()) {
                this.blendPainting = true;
                if (hit != null) {
                    this.session.paintBlend(hit.getBlockPos(), hasShiftDown());
                }
            } else if (hit != null && hasShiftDown() && this.session.tool == ToolMode.BUILD && this.session.anchorA == null) {
                // Shift+click while building: mine that block without switching tools.
                this.actions.destroyAt(hit.getBlockPos());
            } else if (hit != null) {
                this.actions.primary(hit);
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        mx /= this.uiScale;
        my /= this.uiScale;
        dx /= this.uiScale;
        dy /= this.uiScale;
        if (this.dragTask != null && button == 0) {
            if (Math.abs(my - this.dragFromY) > DRAG_THRESHOLD) {
                this.dragMoved = true;
            }
            return true;
        }
        if (button == 0 && this.blendPainting) {
            BlockHitResult hit = pick(mx, my);
            if (hit != null) {
                this.session.paintBlend(hit.getBlockPos(), hasShiftDown());
            }
            return true;
        }
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
        mx /= this.uiScale;
        my /= this.uiScale;
        if (this.dragTask != null && button == 0) {
            if (this.dragMoved) {
                int row = Math.max(0, (int) ((my - this.ordersTop + (cardH() + 2) / 2.0) / (cardH() + 2)));
                List<RtsTask> tasks = TaskQueue.get().snapshot();
                int from = tasks.indexOf(this.dragTask);
                int to = this.taskScroll + row;
                if (from >= 0 && to > from) {
                    to--; // the card leaves its old slot first
                }
                TaskQueue.get().move(this.dragTask, to);
            }
            this.dragTask = null;
            this.dragMoved = false;
            return true;
        }
        if (button == 0) {
            this.blendPainting = false;
        }
        if (button == this.pressedButton) {
            this.pressedButton = -1;
            if (!this.dragging) {
                BlockHitResult hit = pick(mx, my);
                if (hit != null && button == 1) {
                    this.actions.moveTo(hit);
                } else if (hit != null && button == 2) {
                    Item item = this.actions.pickBlock(hit);
                    if (item != null) {
                        selectBrush(item);
                        selectTool(ToolMode.BUILD);
                        Messages.info("Selected " + item.getName().getString());
                    }
                }
            }
            this.dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        mx /= this.uiScale;
        my /= this.uiScale;
        if (my >= bottomY()) {
            int rows = (this.palette.size() + gridCols() - 1) / gridCols();
            this.gridScroll = MathHelper.clamp(this.gridScroll - (int) Math.signum(amount), 0, Math.max(0, rows - GRID_ROWS));
            return true;
        }
        if (mx >= rightX() && my < rightBottom()) {
            this.taskScroll = Math.max(0, this.taskScroll - (int) Math.signum(amount));
            return true;
        }
        if (hasAltDown() && this.session.airPlace) {
            this.session.airY += amount > 0 ? 1 : -1;
            return true;
        }
        if (hasControlDown()) {
            if (blendBrush()) {
                this.session.blendRadius = MathHelper.clamp(this.session.blendRadius + (amount > 0 ? 1 : -1), 0, 12);
            } else if (this.session.tool == ToolMode.TERRAIN && this.session.terrainMode.sizeLabel() != null) {
                this.session.terrainSize = MathHelper.clamp(this.session.terrainSize + (amount > 0 ? 1 : -1), 1, 32);
            } else if (this.session.tool == ToolMode.TERRAIN) {
                this.session.terrainClearHeight = MathHelper.clamp(this.session.terrainClearHeight + (amount > 0 ? 1 : -1), 1, 64);
            } else if (this.session.circleHoleMode()) {
                this.session.adjustHeight(amount > 0 ? 1 : -1);
                Messages.info("Circle hole " + (this.session.circleHole == 0 ? "off" : this.session.circleHole + " wide from the centre"));
            } else if (this.session.shape.usesHeight()) {
                this.session.adjustHeight(amount > 0 ? 1 : -1);
                Messages.info(this.session.shape.label() + " height " + this.session.height);
            } else {
                Messages.info(this.session.shape.label() + " has no height: its size comes from the second point you click");
            }
            return true;
        }
        this.controller.camera().zoom(this.client, amount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        BuildSession s = this.session;
        if (keyCode == GLFW.GLFW_KEY_F1 || this.showControls && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.showControls = keyCode == GLFW.GLFW_KEY_F1 && !this.showControls;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.search.isFocused()) {
                this.search.setFocused(false);
                setFocused(null);
            } else if (s.anchorA != null || s.pasteAnchor != null || !s.blendPaint.isEmpty()) {
                s.clearSelection();
                s.pasteAnchor = null;
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
        if (RtsClientMod.skipLayerKey.matchesKey(keyCode, scanCode)) {
            RtsClientMod.skipLayer(this.client);
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
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            int digit = keyCode - GLFW.GLFW_KEY_0;
            if (hasControlDown()) {
                this.actions.saveBookmark(digit);
            } else if (hasShiftDown()) {
                this.actions.gotoBookmark(digit);
            } else if (digit >= 1 && digit <= ToolMode.values().length) {
                selectTool(ToolMode.values()[digit - 1]);
            }
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_R -> {
                if (s.tool == ToolMode.BLUEPRINT) {
                    s.pasteTurns++;
                } else {
                    s.orientation.cycle();
                }
            }
            case GLFW.GLFW_KEY_N -> {
                if (s.tool == ToolMode.BLUEPRINT) {
                    s.pasteMirror = !s.pasteMirror;
                } else if (s.tool.usesShapes() && randomized(s.shape)) {
                    randomizeIsland();
                }
            }
            case GLFW.GLFW_KEY_M -> {
                if (s.tool == ToolMode.BUILD) {
                    s.mode = s.mode.next();
                    s.clearSelection();
                    Messages.info("Build mode: " + s.mode.label() + " - " + s.mode.description());
                    clearAndInit();
                } else if (s.tool == ToolMode.TERRAIN) {
                    s.terrainMode = s.terrainMode.next();
                    s.clearSelection();
                    clearAndInit();
                }
            }
            case GLFW.GLFW_KEY_H -> s.hollow = !s.hollow;
            case GLFW.GLFW_KEY_I -> toggleInverted();
            case GLFW.GLFW_KEY_B -> toggleAir();
            case GLFW.GLFW_KEY_P -> this.client.setScreen(new PresetScreen(this));
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> this.actions.confirm();
            case GLFW.GLFW_KEY_Z -> {
                if (hasControlDown() && this.client.world != null) {
                    UndoHistory.undoLast(this.client.world);
                }
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_BACKSPACE -> {
                if (s.tool == ToolMode.INTERACT && !s.brush.isEmpty()) {
                    s.brush = ItemStack.EMPTY;
                    Messages.info("Interact: empty hand");
                }
                s.clearSelection();
                s.pasteAnchor = null;
            }
            case GLFW.GLFW_KEY_LEFT_BRACKET -> {
                s.shape = s.shape.previous();
                s.clearSelection();
            }
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> {
                s.shape = s.shape.next();
                s.clearSelection();
            }
            case GLFW.GLFW_KEY_Q -> this.controller.camera().rotateYaw(-45.0F);
            case GLFW.GLFW_KEY_V -> this.actions.toggleCut(this.controller.hover());
            case GLFW.GLFW_KEY_PAGE_UP -> this.actions.moveCut(1);
            case GLFW.GLFW_KEY_PAGE_DOWN -> this.actions.moveCut(-1);
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> nudge(keyCode);
            case GLFW.GLFW_KEY_F -> {
                this.search.setFocused(true);
                setFocused(this.search);
            }
            default -> {
            }
        }
        return true;
    }

    private void nudge(int keyCode) {
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
        this.actions.nudge(d);
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
        if (this.uiScale >= 1.0F) {
            renderScaled(ctx, mouseX, mouseY, delta);
            return;
        }
        ctx.getMatrices().push();
        ctx.getMatrices().scale(this.uiScale, this.uiScale, 1.0F);
        renderScaled(ctx, Math.round(mouseX / this.uiScale), Math.round(mouseY / this.uiScale), delta);
        ctx.getMatrices().pop();
    }

    private void renderScaled(DrawContext ctx, int mouseX, int mouseY, float delta) {
        moveCamera();
        this.controller.setHover(overUi(mouseX, mouseY) ? null : pick(mouseX, mouseY));
        this.hoverEntity = this.session.tool == ToolMode.INTERACT && !overUi(mouseX, mouseY) ? pickEntity(mouseX, mouseY) : null;

        // Panels first, widgets on top.
        Theme.panel(ctx, this.textRenderer, 0, 0, this.width, TOP_H, null);
        Theme.panel(ctx, this.textRenderer, 0, bottomY(), this.width, this.height, null);
        if (hasLeftPanel()) {
            String title = switch (this.session.tool) {
                case BUILD -> "BUILD";
                case DESTROY -> "DESTROY";
                case BLUEPRINT -> "BLUEPRINT";
                case TERRAIN -> "TERRAIN";
                default -> "";
            };
            Theme.panel(ctx, this.textRenderer, PAD, TOP_H + PAD, PAD + LEFT_W, this.leftPanelBottom, title);
        }
        Theme.panel(ctx, this.textRenderer, rightX(), TOP_H + PAD, this.width - PAD, rightBottom(), "COMMAND");
        drawDockBackgrounds(ctx);

        super.render(ctx, mouseX, mouseY, delta);

        drawStatus(ctx);
        drawCompass(ctx);
        if (hasLeftPanel()) {
            drawLeftPanel(ctx);
        }
        drawOrders(ctx, mouseX, mouseY);
        drawStorage(ctx);
        drawPinned(ctx, mouseX, mouseY);
        drawGrid(ctx, mouseX, mouseY);
        drawHints(ctx);
        drawToasts(ctx);
        drawItemTooltip(ctx, mouseX, mouseY);
        if (!overUi(mouseX, mouseY)) {
            drawCursorInfo(ctx, mouseX, mouseY);
        }
        if (this.showControls) {
            drawControls(ctx);
        }
    }

    // ------------------------------------------------------------------ controls panel (F1 / ?)

    /** The controls panel is open (F1 or the ? button; any click or Esc closes it). */
    private boolean showControls;

    private static final String[][] CONTROLS = {
            {"G / Esc", "Leave RTS mode"},
            {"WASD, Space, Shift", "Fly the camera (Ctrl = faster)"},
            {"Scroll", "Zoom toward the cursor"},
            {"Right-drag / Q", "Rotate the camera"},
            {"Middle-drag", "Pan the camera"},
            {"Left click", "Use the current tool"},
            {"Right click", "Walk your character there"},
            {"Middle click", "Pick that block as the brush"},
            {"1-8", "Build, Destroy, Interact, Link, Blueprint, Terrain, Measure, Protect"},
            {"[  ]", "Previous / next shape"},
            {"M", "Build mode / terrain mode"},
            {"R / H", "Rotate / hollow on-off"},
            {"I", "Dome, pyramid, cone upside down"},
            {"N", "Mirror the blueprint being pasted"},
            {"B, Alt+scroll", "Mid-air placing, move its layer"},
            {"Ctrl+scroll", "Shape height (Blend / Level: brush size)"},
            {"Arrows, Shift+Up/Down", "Nudge the shape"},
            {"Enter / X", "Confirm / cancel a shape"},
            {"V, PgUp / PgDn", "Cut view, move the cut"},
            {"P", "Preset buildings"},
            {"F", "Search items"},
            {"Ctrl+click an item", "Fetch a stack from storage"},
            {"Ctrl+0-9 / Shift+0-9", "Save / jump to a camera spot"},
            {"Ctrl+Z", "Undo the last order"},
            {"E, T, /", "Inventory, chat, command"},
            {"Start / Pause", "Run the queue / stop everything (right panel)"},
            {"H (outside RTS)", "Show / hide the build hologram"},
            {"Hold Z (outside RTS)", "Place the hologram's blocks in reach"},
            {"Hold X (outside RTS)", "Place the hologram block you look at"},
            {"F1", "Show / hide these controls"},
    };

    private void drawControls(DrawContext ctx) {
        int row = 11;
        int keyW = 0;
        int actW = 0;
        for (String[] c : CONTROLS) {
            keyW = Math.max(keyW, this.textRenderer.getWidth(c[0]));
            actW = Math.max(actW, this.textRenderer.getWidth(c[1]));
        }
        int colW = keyW + 10 + actW;
        // One column if it fits the height, otherwise two side by side.
        int cols = CONTROLS.length * row + 40 <= this.height - 20 ? 1 : 2;
        int perCol = (CONTROLS.length + cols - 1) / cols;
        int w = Math.min(this.width - 16, cols * colW + (cols - 1) * 16 + 20);
        int h = perCol * row + 34;
        int x0 = (this.width - w) / 2;
        int y0 = Math.max(4, (this.height - h) / 2);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        Theme.panel(ctx, this.textRenderer, x0, y0, x0 + w, y0 + h, "CONTROLS");
        for (int i = 0; i < CONTROLS.length; i++) {
            int col = i / perCol;
            int x = x0 + 10 + col * (colW + 16);
            int y = y0 + Theme.HEADER_H + 8 + (i % perCol) * row;
            ctx.drawText(this.textRenderer, CONTROLS[i][0], x, y, Theme.ACCENT, false);
            ctx.drawText(this.textRenderer, CONTROLS[i][1], x + keyW + 10, y, Theme.TEXT, false);
        }
        String close = "Click anywhere or press F1 to close";
        ctx.drawText(this.textRenderer, close, x0 + (w - this.textRenderer.getWidth(close)) / 2, y0 + h - 12, Theme.TEXT_FAINT, false);
        ctx.getMatrices().pop();
    }

    private void drawStatus(DrawContext ctx) {
        if (this.client.player == null) {
            return;
        }
        int x = PAD;
        for (var child : children()) {
            if (child instanceof RtsButton b && b.getY() == 2 && b.getX() < this.width - 40) {
                x = Math.max(x, b.getX() + b.getWidth());
            }
        }
        x += 10;
        int limit = this.width - PAD - 26;
        float hp = this.client.player.getHealth();
        int food = this.client.player.getHungerManager().getFoodLevel();
        if (x + 70 > limit) {
            return;
        }
        Glyphs.HEART.draw(ctx, x, 9, hp <= 6 ? 0xFFFF5252 : 0xFFE53935);
        String hpText = String.format(Locale.ROOT, "%.0f", hp);
        ctx.drawText(this.textRenderer, hpText, x + 10, 8, Theme.TEXT, false);
        x += 14 + this.textRenderer.getWidth(hpText);
        Glyphs.FOOD.draw(ctx, x, 9, 0xFFC68A3C);
        String foodText = Integer.toString(food);
        ctx.drawText(this.textRenderer, foodText, x + 10, 8, Theme.TEXT, false);
        x += 18 + this.textRenderer.getWidth(foodText);

        Integer cut = this.controller.cutY();
        if (cut != null && x + 60 < limit) {
            x += Theme.chip(ctx, this.textRenderer, "CUT Y" + cut, x, 7, 0xFF3A2E10, Theme.GOLD) + 4;
        }
        String engine = Engine.activeName() + ": " + Engine.status();
        if (limit - x > 60) {
            engine = trim(engine, limit - x - 12);
            boolean busy = !TaskQueue.get().isEmpty();
            Theme.light(ctx, x + 3, 12, busy ? Theme.GOOD : Theme.TEXT_FAINT, busy);
            ctx.drawText(this.textRenderer, engine, x + 9, 8, busy ? Theme.TEXT : Theme.TEXT_DIM, false);
        }
    }

    /** A compass strip under the top bar (follows the camera), with the cursor's block position below it. */
    private void drawCompass(DrawContext ctx) {
        int left = hasLeftPanel() ? PAD + LEFT_W : 0;
        int right = rightX();
        int w = Math.min(150, right - left - 20);
        if (w < 80) {
            return;
        }
        int cx = (left + right) / 2;
        int x0 = cx - w / 2;
        int y0 = TOP_H + 3;
        int h = 13;
        Theme.round(ctx, x0, y0, w, h, 0xB80E141B);
        Theme.outline(ctx, x0, y0, w, h, Theme.BORDER);
        float yaw = MathHelper.wrapDegrees(this.controller.camera().yaw());
        float span = 70.0F;
        float ppd = (w - 8) / (2.0F * span);
        ctx.enableScissor(x0 + 1, y0, x0 + w - 1, y0 + h);
        int start = (int) Math.floor((yaw - span) / 15.0F) * 15;
        for (int a = start; a <= yaw + span; a += 15) {
            int px = Math.round(cx + (a - yaw) * ppd);
            int deg = Math.floorMod(a, 360);
            String label = switch (deg) {
                case 0 -> "S";
                case 90 -> "W";
                case 180 -> "N";
                case 270 -> "E";
                case 45 -> "SW";
                case 135 -> "NW";
                case 225 -> "NE";
                case 315 -> "SE";
                default -> null;
            };
            float fade = 1.0F - Math.min(1.0F, Math.abs(a - yaw) / span);
            if (label != null) {
                boolean cardinal = deg % 90 == 0;
                int color = deg == 180 ? Theme.BAD : cardinal ? Theme.TEXT : Theme.TEXT_DIM;
                int tw = this.textRenderer.getWidth(label);
                ctx.drawText(this.textRenderer, label, px - tw / 2, y0 + 3, Theme.withAlpha(color, 0.25F + 0.75F * fade), false);
            } else {
                ctx.fill(px, y0 + 4, px + 1, y0 + h - 4, Theme.withAlpha(Theme.TEXT_FAINT, 0.3F + 0.7F * fade));
            }
        }
        ctx.disableScissor();
        // Heading marker.
        ctx.fill(cx - 2, y0 - 1, cx + 3, y0, Theme.ACCENT);
        ctx.fill(cx - 1, y0, cx + 2, y0 + 1, Theme.ACCENT);
        ctx.fill(cx, y0 + 1, cx + 1, y0 + 2, Theme.ACCENT);

        BlockHitResult hover = this.controller.hover();
        if (hover != null && this.client.player != null) {
            BlockPos t = WorldOverlay.targetFor(this.session, hover);
            double dist = Math.sqrt(t.getSquaredDistance(this.client.player.getX(), this.client.player.getY(), this.client.player.getZ()));
            String coords = t.getX() + "  " + t.getY() + "  " + t.getZ();
            String d = String.format(Locale.ROOT, "%.0fm", dist) + (this.terrainInFront ? "  (ground in front of the air layer)" : "");
            int cw = this.textRenderer.getWidth(coords) + this.textRenderer.getWidth(d) + 34;
            int bx = cx - cw / 2;
            int by = y0 + h + 2;
            Theme.round(ctx, bx, by, cw, 11, 0xA00E141B);
            int tx = bx + 4;
            String tag = this.hoverOnLayer ? "AIR" : "XYZ";
            ctx.drawText(this.textRenderer, tag, tx, by + 2, this.hoverOnLayer ? Theme.GOOD : this.terrainInFront ? Theme.GOLD : Theme.ACCENT, false);
            tx += this.textRenderer.getWidth(tag) + 5;
            ctx.drawText(this.textRenderer, coords, tx, by + 2, Theme.TEXT, false);
            tx += this.textRenderer.getWidth(coords) + 6;
            ctx.drawText(this.textRenderer, d, tx, by + 2, Theme.TEXT_DIM, false);
        }
    }

    private void drawLeftPanel(DrawContext ctx) {
        int x0 = PAD + 4;
        int x1 = PAD + LEFT_W - 4;
        for (int i = 0; i < this.leftSections.size(); i++) {
            Theme.section(ctx, this.textRenderer, x0, x1, this.leftSections.get(i)[0], this.leftSectionNames.get(i));
        }
        BuildSession s = this.session;
        if (this.shapeNameY >= 0) {
            String name = s.shape.label();
            ctx.drawText(this.textRenderer, name, x0 + 1, this.shapeNameY + 2, Theme.ACCENT, false);
            String what = s.shape.description();
            int nx = x0 + 1 + this.textRenderer.getWidth(name) + 5;
            ctx.drawText(this.textRenderer, trim(what, x1 - nx), nx, this.shapeNameY + 2, Theme.TEXT_FAINT, false);
        }
        if (this.heightRowY >= 0) {
            boolean terrain = s.tool == ToolMode.TERRAIN;
            String h = terrain ? s.terrainMode.sizeLabel() != null ? sizeText(s)
                    : s.terrainMode == TerrainMode.PLANT_TREES ? s.treeSpacing + " apart"
                    : s.terrainMode.brush() ? (s.blendRadius * 2 + 1) + " wide" : s.terrainClearHeight + " blocks"
                    : s.circleHoleMode() ? "Hole " + s.circleHole : s.shape.heightLabel() + " " + s.height;
            boolean active = terrain || s.shape.usesHeight() || s.circleHoleMode();
            ctx.drawCenteredTextWithShadow(this.textRenderer, h, (x0 + x1) / 2, this.heightRowY + 3, active ? Theme.TEXT : Theme.TEXT_FAINT);
        }
        if (this.strengthRowY >= 0) {
            String v = s.terrainMode == TerrainMode.LEVEL ? s.levelRamp == 0 ? "sharp" : s.levelRamp + " wide" : s.blendStrength + " / 10";
            ctx.drawCenteredTextWithShadow(this.textRenderer, v, (x0 + x1) / 2, this.strengthRowY + 3, Theme.TEXT);
        }
        if (this.levelRowY >= 0) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, "Y " + s.levelY, (x0 + x1) / 2, this.levelRowY + 3, Theme.TEXT);
        }
        if (this.clipboardInfoY >= 0) {
            if (s.tool == ToolMode.BLUEPRINT) {
                Blueprint bp = s.clipboard;
                String line1 = bp == null ? (s.cutOnCopy ? "Move: select the building" : "Copy: click two corners") : bp.name();
                String line2 = bp == null ? "of the region" : bp.size(s.pasteTurns) + ", " + bp.blockCount() + " blocks";
                ctx.drawText(this.textRenderer, trim(line1, x1 - x0), x0, this.clipboardInfoY, bp == null ? Theme.TEXT_DIM : Theme.GOLD, false);
                ctx.drawText(this.textRenderer, trim(line2, x1 - x0), x0, this.clipboardInfoY + 10, Theme.TEXT_FAINT, false);
            } else if (s.tool == ToolMode.TERRAIN) {
                String fill = switch (s.terrainMode) {
                    case FLATTEN -> "Fill: " + WorldActions.flattenFill(s).getBlock().getName().getString();
                    case BLEND, LEVEL -> "Fill: matching ground";
                    case LIGHT_UP -> "Uses torches";
                    case QUARRY -> "Dig " + s.terrainClearHeight + " deep from the top";
                    case FELL_TREES -> "Replants saplings";
                    case HARVEST -> "Replants, stores produce";
                    case PLANT_TREES -> "Plants " + WorldActions.treeSapling(s).getName().getString();
                    case ROAD -> "Road: " + brushName(s, "Cobblestone");
                    case ROOM -> "Floor: " + brushName(s, "Spruce Planks");
                    case FARM -> "Sows " + (s.brush.isEmpty() ? "wheat" : s.brush.getName().getString());
                    case MOB_PROOF -> "Covers with " + brushName(s, "Smooth Stone Slab");
                    case RESTYLE -> "Into " + brushName(s, "(pick a block)");
                    case HILL -> "Grass, dirt, stone";
                    case LAKE, RIVER -> "Water from buckets";
                    case TERRACE -> "Grass steps";
                    case CAVE -> "Lit every 8 blocks";
                    case ISLAND_BRIDGE -> "Look: " + s.bridgeStyle.label();
                    case DECORATE -> "Plants and rocks";
                };
                ctx.drawText(this.textRenderer, trim(fill, x1 - x0), x0, this.clipboardInfoY, Theme.TEXT_DIM, false);
                ctx.drawText(this.textRenderer, s.terrainMode.brush() ? "Drag to paint, Enter" : s.terrainMode.line() ? "Click two points" : "Click two corners",
                        x0, this.clipboardInfoY + 10, Theme.TEXT_FAINT, false);
            }
        }
    }

    private int visibleCards() {
        return Math.max(1, (this.ordersBottom - this.ordersTop) / (cardH() + 2));
    }

    /** Cards are slimmed to one line when the screen is too short for a few full ones. */
    private boolean compactCards() {
        return (this.ordersBottom - this.ordersTop) / (CARD_H + 2) < MIN_FULL_CARDS;
    }

    private int cardH() {
        return compactCards() ? COMPACT_H : CARD_H;
    }

    private void drawOrders(DrawContext ctx, int mx, int my) {
        int x0 = rightX() + PAD;
        int x1 = this.width - 2 * PAD;
        List<RtsTask> tasks = TaskQueue.get().snapshot();
        Theme.section(ctx, this.textRenderer, x0, x1, this.ordersTop - 22, "ORDERS" + (tasks.isEmpty() ? "" : " (" + tasks.size() + ")"));
        // What the character is doing right now.
        boolean working = !tasks.isEmpty() && TaskQueue.get().isStarted();
        String activity = working ? Engine.activeName() + ": " + Engine.status()
                : tasks.isEmpty() ? "Idle" : "Waiting - press Start";
        Theme.light(ctx, x0 + 3, this.ordersTop - 7, working ? Theme.GOOD : Theme.TEXT_FAINT, working);
        ctx.drawText(this.textRenderer, trim(activity, x1 - x0 - 12), x0 + 9, this.ordersTop - 11, working ? Theme.TEXT_DIM : Theme.TEXT_FAINT, false);
        int visible = visibleCards();
        this.taskScroll = MathHelper.clamp(this.taskScroll, 0, Math.max(0, tasks.size() - visible));
        int y = this.ordersTop;
        if (tasks.isEmpty()) {
            ctx.drawText(this.textRenderer, "Nothing queued", x0 + 2, y + 2, Theme.TEXT_FAINT, false);
            ctx.drawText(this.textRenderer, "Left-click the world to queue orders", x0 + 2, y + 12, Theme.TEXT_FAINT, false);
        }
        TaskQueue queue = TaskQueue.get();
        RtsTask active = null;
        for (RtsTask t : tasks) {
            if (!queue.isPaused(t)) {
                active = t;
                break;
            }
        }
        for (int i = this.taskScroll; i < tasks.size() && i < this.taskScroll + visible; i++) {
            RtsTask task = tasks.get(i);
            boolean running = task == active;
            boolean paused = queue.isPaused(task);
            if (task == this.dragTask && this.dragMoved) {
                // Leave a slot where the card was; the card itself follows the mouse.
                Theme.outline(ctx, x0, y, x1 - x0, cardH(), Theme.withAlpha(Theme.ACCENT, 0.4F));
                y += cardH() + 2;
                continue;
            }
            boolean cardHover = mx >= x0 && mx < x1 && my >= y && my < y + cardH();
            int cardBg = running ? 0xFF16283A : cardHover ? 0xFF1F2834 : 0xFF19212B;
            Theme.round(ctx, x0, y, x1 - x0, cardH(), cardBg);
            ctx.fillGradient(x0 + 1, y + 1, x1 - 1, y + cardH() / 2, Theme.mix(cardBg, 0xFFFFFFFF, 0.05F), cardBg);
            Theme.outline(ctx, x0, y, x1 - x0, cardH(), running
                    ? Theme.withAlpha(Theme.ACCENT, 0.35F + 0.4F * Theme.pulse(1600)) : Theme.BORDER);
            if (running) {
                ctx.fill(x0, y + 2, x0 + 2, y + cardH() - 2, Theme.ACCENT);
            }
            ctx.drawText(this.textRenderer, trim(task.label(), x1 - x0 - 40), x0 + 5, y + 3,
                    paused ? Theme.TEXT_FAINT : running ? Theme.TEXT : 0xFFC9D3DD, false);
            if (!compactCards()) {
                ctx.drawText(this.textRenderer, trim(paused ? "paused" : task.detail(), x1 - x0 - 10), x0 + 5, y + 13,
                        paused ? Theme.GOLD : Theme.TEXT_DIM, false);
            }
            if (task instanceof BuildTask build && build.progress() >= 0) {
                Theme.bar(ctx, x0 + 4, y + cardH() - (compactCards() ? 3 : 4), x1 - x0 - 8, 2, build.progress(), build.clearing() ? Theme.BAD : Theme.GOOD, running);
            }
            int bx = x1 - 11;
            boolean hot = mx >= bx && mx < bx + 9 && my >= y + 2 && my < y + 11;
            Glyphs.CROSS.draw(ctx, bx + 1, y + 3, hot ? Theme.BAD : Theme.TEXT_FAINT);
            int px = x1 - 22;
            boolean hotPause = mx >= px && mx < px + 9 && my >= y + 2 && my < y + 11;
            (paused ? Glyphs.PLAY : Glyphs.PAUSE).draw(ctx, px + 1, y + 3, hotPause ? Theme.TEXT : paused ? Theme.GOLD : Theme.TEXT_FAINT);
            if (i > 0) {
                int tx = x1 - 33;
                boolean hotTop = mx >= tx && mx < tx + 9 && my >= y + 2 && my < y + 11;
                Glyphs.TOP.draw(ctx, tx + 1, y + 3, hotTop ? Theme.ACCENT : Theme.TEXT_FAINT);
            }
            y += cardH() + 2;
        }
        if (this.dragTask != null && this.dragMoved && tasks.contains(this.dragTask)) {
            int row = MathHelper.clamp((int) ((my - this.ordersTop + (cardH() + 2) / 2) / (cardH() + 2)), 0, visible);
            int ly = this.ordersTop + row * (cardH() + 2) - 2;
            ctx.fill(x0, ly, x1, ly + 1, Theme.ACCENT);
            int gy = my - cardH() / 2;
            Theme.round(ctx, x0 + 3, gy, x1 - x0, cardH(), 0xE0243246);
            Theme.outline(ctx, x0 + 3, gy, x1 - x0, cardH(), Theme.ACCENT);
            ctx.drawText(this.textRenderer, trim(this.dragTask.label(), x1 - x0 - 10), x0 + 8, gy + (cardH() - 8) / 2, Theme.TEXT, false);
        }
        if (tasks.size() > visible) {
            // Scrollbar along the right edge of the list: where we are in the queue (scroll over the panel to move).
            int top = this.ordersTop;
            int track = visible * (cardH() + 2) - 2;
            int thumb = Math.max(8, track * visible / tasks.size());
            int thumbY = top + (track - thumb) * this.taskScroll / Math.max(1, tasks.size() - visible);
            ctx.fill(x1 + 1, top, x1 + 3, top + track, Theme.withAlpha(Theme.BORDER, 0.6F));
            ctx.fill(x1 + 1, thumbY, x1 + 3, thumbY + thumb, Theme.ACCENT);
            String more = (this.taskScroll + 1) + "-" + Math.min(tasks.size(), this.taskScroll + visible) + " of " + tasks.size();
            ctx.drawText(this.textRenderer, more, x1 - this.textRenderer.getWidth(more), this.ordersTop - 22, Theme.TEXT_FAINT, false);
        }
    }

    private boolean clickOrders(double mx, double my) {
        if (mx < rightX() || my < this.ordersTop || my >= this.ordersBottom) {
            return false;
        }
        List<RtsTask> tasks = TaskQueue.get().snapshot();
        int row = (int) ((my - this.ordersTop) / (cardH() + 2));
        int idx = this.taskScroll + row;
        if (row >= visibleCards() || idx < 0 || idx >= tasks.size()) {
            return false;
        }
        int x1 = this.width - 2 * PAD;
        double cardY = this.ordersTop + row * (cardH() + 2);
        if (my >= cardY + cardH()) {
            return false;
        }
        RtsTask task = tasks.get(idx);
        boolean iconRow = my >= cardY + 2 && my < cardY + 11;
        if (iconRow && mx >= x1 - 11 && mx < x1 - 2) {
            TaskQueue.get().remove(this.client, task);
            return true;
        }
        if (iconRow && mx >= x1 - 22 && mx < x1 - 13) {
            TaskQueue.get().togglePause(task);
            Messages.info((TaskQueue.get().isPaused(task) ? "Paused " : "Resumed ") + task.label());
            return true;
        }
        if (iconRow && idx > 0 && mx >= x1 - 33 && mx < x1 - 24) {
            TaskQueue.get().runFirst(task);
            this.taskScroll = 0;
            Messages.info("Running next: " + task.label());
            return true;
        }
        // Grab the card: dragging it up or down reorders the queue.
        this.dragTask = task;
        this.dragFromY = my;
        this.dragMoved = false;
        return true;
    }

    private void drawStorage(DrawContext ctx) {
        int x0 = rightX() + PAD;
        int x1 = this.width - 2 * PAD;
        Theme.section(ctx, this.textRenderer, x0, x1, this.storageTop, "STORAGE");
        int linked = StorageManager.get().linked().size();
        int indexed = linked - StorageManager.get().unindexed().size();
        int kinds = StorageManager.get().totals().size();
        ctx.drawText(this.textRenderer, linked + " linked, " + indexed + " read, " + kinds + " item types", x0 + 2,
                this.storageTop + 11, Theme.TEXT_DIM, false);
        String me = meStatus();
        if (me != null) {
            ctx.drawText(this.textRenderer, me, x0 + 2, this.storageTop + 21, Theme.TEXT_DIM, false);
        }
    }

    /** One line about the AE2 network, or null without AE2 (or with it turned off). */
    private String meStatus() {
        if (this.client == null || this.client.player == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (com.testrts.rtsclient.storage.MeNetwork me : com.testrts.rtsclient.storage.MeNetwork.all()) {
            if (!me.enabled()) {
                continue;
            }
            String name = me.mod().name();
            boolean wireless = me.wirelessUsable(this.client);
            int terminals = me.terminals().size();
            if (!wireless && terminals == 0) {
                parts.add(name + ": carry a wireless " + me.mod().terminalName() + " or link one");
                continue;
            }
            if (me.mod().portable()) {
                parts.add(name + ": " + (me.indexed() ? me.items().size() + " types" : "not opened yet"));
                continue;
            }
            String how = wireless ? "wireless" : terminals + " linked";
            parts.add(name + " (" + how + "): " + (me.indexed() ? me.items().size() + " types" : "not opened yet"));
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    private void drawDockBackgrounds(DrawContext ctx) {
        // Search box.
        int searchW = Math.min(150, this.width / 3);
        Theme.round(ctx, PAD, headerY(), searchW, 14, Theme.SLOT);
        Theme.outline(ctx, PAD, headerY(), searchW, 14, this.search != null && this.search.isFocused() ? Theme.ACCENT : Theme.SLOT_EDGE);
    }

    private void slot(DrawContext ctx, int x, int y, boolean hover, boolean selected) {
        Theme.round(ctx, x + 1, y + 1, SLOT - 2, SLOT - 2, hover ? Theme.SLOT_HOVER : Theme.SLOT);
        Theme.outline(ctx, x + 1, y + 1, SLOT - 2, SLOT - 2, selected ? Theme.ACCENT : Theme.SLOT_EDGE);
    }

    private void drawPinned(DrawContext ctx, int mx, int my) {
        int y = pinnedY();
        List<String> pinned = RtsConfig.get().pinned;
        for (int i = 0; i < gridCols(); i++) {
            int x = gridX() + i * SLOT;
            Item item = null;
            if (i < pinned.size()) {
                Identifier id = Identifier.tryParse(pinned.get(i));
                if (id != null && Registries.ITEM.containsId(id)) {
                    item = Registries.ITEM.get(id);
                }
            }
            boolean hot = mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
            if (item == null && (pinned.isEmpty() || i >= 9)) {
                continue; // a few empty slots invite pinning; a whole row of them is noise
            }
            slot(ctx, x, y, hot && item != null, item != null && this.session.brush.getItem() == item);
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            ctx.drawItem(stack, x + 1, y + 1);
            int count = (this.client.player == null ? 0 : this.client.player.getInventory().count(item)) + StorageManager.get().total(item);
            drawCount(ctx, stack, x + 1, y + 1, count == 0 ? "§c0" : shortCount(count));
        }
        if (pinned.isEmpty()) {
            ctx.drawText(this.textRenderer, "Quick bar - right-click items below to pin them here", gridX() + 6, y + 5, Theme.TEXT_FAINT, false);
        }
    }

    private void drawGrid(DrawContext ctx, int mx, int my) {
        int cols = gridCols();
        int y0 = gridY();
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < cols; col++) {
                int x = gridX() + col * SLOT;
                int y = y0 + row * SLOT;
                int idx = (row + this.gridScroll) * cols + col;
                PaletteEntry e = idx < this.palette.size() ? this.palette.get(idx) : null;
                boolean hot = e != null && mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
                if (e == null) {
                    continue; // only filled slots get a frame
                }
                slot(ctx, x, y, hot, this.session.brush.getItem() == e.item());
                ctx.drawItem(e.display(), x + 1, y + 1);
                String count = shortCount(e.total());
                // Gold count: only in storage, a build will fetch it.
                drawCount(ctx, e.display(), x + 1, y + 1, e.inventory() == 0 ? "§6" + count : count);
            }
        }
        if (this.palette.isEmpty()) {
            ctx.drawText(this.textRenderer,
                    this.tab == Tab.STORAGE ? "No storage read yet - link containers and press Scan nearby" : "No items",
                    gridX() + 6, y0 + 5, Theme.TEXT_FAINT, false);
        }
        int rows = (this.palette.size() + cols - 1) / cols;
        if (rows > GRID_ROWS) {
            String page = (this.gridScroll + 1) + "/" + (rows - GRID_ROWS + 1);
            ctx.drawText(this.textRenderer, page, this.width - PAD - 4 - this.textRenderer.getWidth(page), headerY() + 3, Theme.TEXT_FAINT, false);
        }
    }

    private void drawHints(DrawContext ctx) {
        BuildSession s = this.session;
        int y = bottomY() - 13;
        int x = hasLeftPanel() ? PAD + LEFT_W + 6 : PAD + 2;
        String brush = s.brush.isEmpty() ? "no block" : s.brush.getName().getString();
        List<String> chips = new ArrayList<>();
        switch (s.tool) {
            case BUILD -> {
                chips.add(brush);
                if (s.brush.getItem() instanceof BlockItem bi) {
                    String d = s.orientation.describe(bi.getBlock(), this.controller.camera().yaw());
                    if (!d.isEmpty()) {
                        chips.add(d);
                    }
                }
                chips.add(s.mode.label());
                chips.add(s.shape.label() + (s.hollow && s.shape.supportsHollow() ? " hollow" : ""));
                if (s.shape.usesHeight()) {
                    chips.add("h" + s.height);
                } else if (s.circleHoleMode() && s.circleHole > 0) {
                    chips.add("hole " + s.circleHole);
                }
            }
            case DESTROY -> {
                chips.add(s.shape.label() + (s.hollow && s.shape.supportsHollow() ? " hollow" : ""));
                if (s.shape.usesHeight()) {
                    chips.add("h" + s.height);
                } else if (s.circleHoleMode() && s.circleHole > 0) {
                    chips.add("hole " + s.circleHole);
                }
            }
            case INTERACT -> {
                Item use = WorldActions.interactItem(s);
                chips.add(use == null ? "Click a block or mob - pick an item below to use it on them"
                        : "Using " + new ItemStack(use).getName().getString() + " - click a mob or block");
                if (use != null) {
                    chips.add("X: empty hand");
                }
            }
            case LINK -> chips.add("Click containers to link / unlink");
            case BLUEPRINT -> {
                if (s.clipboard == null) {
                    chips.add((s.cutOnCopy ? "Move" : "Copy") + ": click two corners");
                } else {
                    chips.add(s.clipboard.name());
                    chips.add(s.pasteAnchor == null ? "click to place" : "arrows nudge, click/Enter builds");
                    if (s.pasteMirror) {
                        chips.add("mirrored");
                    }
                    if (s.arrayX > 1 || s.arrayZ > 1) {
                        chips.add(s.arrayX + " x " + s.arrayZ + " copies");
                    }
                    if (s.moving()) {
                        chips.add("moving");
                    }
                }
            }
            case TERRAIN -> chips.add(s.terrainMode.brush()
                    ? s.blendPaint.isEmpty() ? s.terrainMode.label() + ": drag over the ground to paint, Shift+drag erases"
                    : s.terrainMode.label() + ": " + s.blendPaint.size() + " columns painted - Enter to "
                            + s.terrainMode.label().toLowerCase(Locale.ROOT) + ", X to clear"
                    : s.terrainMode.label() + (s.terrainMode.line() ? ": click two points" : ": click two corners"));
            case MEASURE -> chips.add("Click two points");
            case PROTECT -> chips.add(com.testrts.rtsclient.build.Protection.get().areas().isEmpty() ? "Click two corners of your base"
                    : com.testrts.rtsclient.build.Protection.get().areas().size() + " protected - click inside one to remove it");
        }
        if (s.roofPick) {
            chips.add("click a wall top for the " + s.roofStyle.label().toLowerCase(Locale.ROOT) + " roof");
        }
        if (!s.mix.isEmpty() && s.tool == ToolMode.BUILD) {
            chips.add("mix: " + s.mixDescription());
        }
        if (s.airPlace) {
            chips.add("air Y " + s.airY);
        }
        for (String c : chips) {
            int w = this.textRenderer.getWidth(c) + 6;
            if (x + w > rightX() - 4) {
                break;
            }
            x += Theme.chip(ctx, this.textRenderer, c, x, y, 0xC0121820, Theme.TEXT) + 3;
        }
    }

    private void drawToasts(DrawContext ctx) {
        List<Messages.Entry> lines = Messages.visible();
        int left = hasLeftPanel() ? PAD + LEFT_W + 6 : PAD + 2;
        int y = bottomY() - 30 - lines.size() * 14;
        for (Messages.Entry e : lines) {
            float alpha = Messages.alpha(e);
            if (alpha < 0.03F) {
                y += 14;
                continue;
            }
            int stripe = switch (e.kind()) {
                case SUCCESS -> Theme.GOOD;
                case WARN -> Theme.GOLD;
                case ERROR -> Theme.BAD;
                case CHAT -> Theme.TEXT_FAINT;
                default -> Theme.ACCENT;
            };
            // Slide in from the left with an ease-out over the first 220 ms.
            float in = Math.min(1.0F, (System.currentTimeMillis() - e.createdAt()) / 220.0F);
            float ease = 1.0F - (1.0F - in) * (1.0F - in) * (1.0F - in);
            int lx = left - Math.round((1.0F - ease) * 24.0F);
            alpha *= ease;
            int w = Math.min(rightX() - left - 6, this.textRenderer.getWidth(e.text()) + 14);
            Theme.round(ctx, lx, y, w, 12, Theme.withAlpha(0xE0101820, alpha));
            ctx.fillGradient(lx + 1, y + 1, lx + w - 1, y + 6, Theme.withAlpha(0x20FFFFFF, alpha), Theme.withAlpha(0x00FFFFFF, alpha));
            ctx.fill(lx, y + 1, lx + 2, y + 11, Theme.withAlpha(stripe, alpha));
            ctx.fill(lx + 2, y + 1, lx + 4, y + 11, Theme.withAlpha(stripe, alpha * 0.25F));
            ctx.enableScissor(lx, y, lx + w - 2, y + 12);
            ctx.drawText(this.textRenderer, e.text(), lx + 8, y + 2, Theme.withAlpha(0xFFFFFFFF, alpha), false);
            ctx.disableScissor();
            y += 14;
        }
    }

    private void drawItemTooltip(DrawContext ctx, int mx, int my) {
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
        lines.add(Text.literal("Click: use  Right-click: " + (e != null ? "pin" : "unpin")).formatted(Formatting.DARK_GRAY));
        if (stored > 0) {
            lines.add(Text.literal("Ctrl+click: fetch a stack from storage").formatted(Formatting.DARK_GRAY));
        }
        ctx.drawTooltip(this.textRenderer, lines, mx, my);
    }

    /**
     * Next to the cursor: size, block count and materials for the shape/paste being placed,
     * measurements, or what the Terrain tool will do.
     */
    private void drawCursorInfo(DrawContext ctx, int mx, int my) {
        BuildSession s = this.session;
        BlockHitResult hover = this.controller.hover();
        List<Text> lines = new ArrayList<>();
        Map<Item, Integer> materials = Map.of();
        if (s.tool == ToolMode.MEASURE && s.anchorA != null) {
            BlockPos b = s.anchorB != null ? s.anchorB : hover != null ? hover.getBlockPos() : null;
            if (b == null) {
                return;
            }
            lines.add(Text.literal(WorldActions.describeMeasure(s.anchorA, b)).formatted(Formatting.LIGHT_PURPLE));
        } else if (s.tool == ToolMode.BLUEPRINT && s.clipboard != null) {
            int copies = Math.max(1, s.arrayX) * Math.max(1, s.arrayZ);
            lines.add(Text.literal(s.clipboard.name() + "  " + s.clipboard.size(s.pasteTurns)
                    + (copies > 1 ? "  x" + copies : "") + "  |  "
                    + String.format(Locale.ROOT, "%,d", s.clipboard.blockCount() * copies) + " blocks").formatted(Formatting.GOLD));
            materials = new java.util.LinkedHashMap<>();
            for (var e : s.clipboard.materials().entrySet()) {
                materials.put(e.getKey(), e.getValue() * copies);
            }
        } else if (s.tool == ToolMode.TERRAIN && s.terrainMode.brush()) {
            if (!s.blendPaint.isEmpty()) {
                int[] counts = WorldOverlay.terrainCounts();
                String what = counts[0] < 0 || counts[1] < 0 ? "planning..." : "mine " + counts[0] + ", fill " + counts[1];
                String how = s.terrainMode == TerrainMode.LEVEL ? "to Y " + s.levelY + ", edge " + s.levelRamp
                        : "strength " + s.blendStrength;
                lines.add(Text.literal(s.terrainMode.label() + " " + s.blendPaint.size() + " columns, " + how + "  |  " + what)
                        .formatted(Formatting.GREEN));
                Map<Item, Integer> need = WorldOverlay.blendMaterials();
                if (!need.isEmpty()) {
                    materials = need;
                }
            }
        } else if (s.tool == ToolMode.TERRAIN && s.anchorA != null && (s.anchorB != null || hover != null)) {
            BlockPos b = s.anchorB != null ? s.anchorB : hover.getBlockPos();
            int w = Math.abs(s.anchorA.getX() - b.getX()) + 1;
            int l = Math.abs(s.anchorA.getZ() - b.getZ()) + 1;
            int[] counts = WorldOverlay.terrainCounts();
            String what = switch (s.terrainMode) {
                case FLATTEN -> counts[0] < 0 ? "planning..." : "mine " + counts[0] + ", fill " + counts[1] + " at Y" + s.anchorA.getY();
                case LIGHT_UP -> counts[1] < 0 ? "planning..." : counts[1] + " torches";
                case QUARRY -> s.terrainClearHeight + " deep, " + (w * l * s.terrainClearHeight) + " blocks at most";
                case PLANT_TREES -> "about " + ((w / s.treeSpacing + 1) * (l / s.treeSpacing + 1)) + " trees, "
                        + s.treeSpacing + " apart";
                default -> w * l + " columns";
            };
            lines.add(Text.literal(s.terrainMode.label() + " " + w + " x " + l + "  |  " + what).formatted(Formatting.GREEN));
            if (s.terrainMode == TerrainMode.LIGHT_UP && counts[1] > 0) {
                materials = Map.of(Items.TORCH, counts[1]);
            } else if (s.terrainMode == TerrainMode.FLATTEN && counts[1] > 0) {
                materials = Map.of(WorldActions.flattenFill(s).getBlock().asItem(), counts[1]);
            }
        } else if (s.tool == ToolMode.INTERACT && this.hoverEntity != null) {
            Item use = WorldActions.interactItem(s);
            boolean attack = com.testrts.rtsclient.task.EntityTask.attacks(use, this.hoverEntity);
            lines.add(Text.literal(com.testrts.rtsclient.task.EntityTask.describe(use, this.hoverEntity))
                    .formatted(attack ? Formatting.RED : Formatting.AQUA));
        } else if (s.tool.usesShapes() && s.anchorA != null) {
            OrderPlanner.Plan plan = WorldOverlay.lastPlan();
            if (plan == null) {
                return;
            }
            if (plan.truncated()) {
                lines.add(Text.literal("Too big: over " + RtsConfig.get().maxShapeBlocks + " blocks").formatted(Formatting.RED));
            } else {
                String verb = s.tool == ToolMode.DESTROY ? " to mine" : " to place";
                lines.add(Text.literal(plan.dimensions() + "  |  " + plan.changes().size() + verb)
                        .formatted(s.tool == ToolMode.DESTROY ? Formatting.RED : Formatting.AQUA));
                materials = plan.materials();
            }
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

    private String trim(String s, int width) {
        if (this.textRenderer.getWidth(s) <= width) {
            return s;
        }
        return this.textRenderer.trimToWidth(s, Math.max(0, width - this.textRenderer.getWidth("..."))) + "...";
    }

    /**
     * The count in a slot's corner, shrunk when it's wider than the slot so it never runs into the
     * next one (vanilla's count does past two digits).
     */
    private void drawCount(DrawContext ctx, ItemStack stack, int x, int y, String text) {
        // Durability bar and the like, without vanilla's count.
        ctx.drawItemInSlot(this.textRenderer, stack, x, y, "");
        int w = this.textRenderer.getWidth(text);
        float scale = w > 16 ? Math.max(0.5F, 16.0F / w) : 1.0F;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + 17.0F, y + 17.0F, 200.0F);
        ctx.getMatrices().scale(scale, scale, 1.0F);
        ctx.drawText(this.textRenderer, text, -w, -8, 0xFFFFFFFF, true);
        ctx.getMatrices().pop();
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
