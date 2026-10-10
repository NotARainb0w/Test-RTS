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
    private static final int LEFT_W = 116;
    private static final int RIGHT_W = 164;
    private static final int SLOT = 18;
    private static final int GRID_ROWS = 3;
    private static final int PAD = 4;
    private static final int ROW = 15;
    private static final int CARD_H = 26;
    private static final int DRAG_THRESHOLD = 3;
    /** Order card being dragged to a new place in the queue. */
    @Nullable
    private RtsTask dragTask;
    private double dragFromY;
    private boolean dragMoved;

    private enum Tab { ALL, INVENTORY, STORAGE }

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
    private List<PaletteEntry> palette = new ArrayList<>();
    private int paletteRefresh;
    private int gridScroll;
    private int taskScroll;

    private int leftPanelBottom = TOP_H;
    private final List<int[]> leftSections = new ArrayList<>();
    private final List<String> leftSectionNames = new ArrayList<>();
    private int heightRowY = -1;
    private int clipboardInfoY = -1;
    private int ordersTop;
    private int ordersBottom;
    private int storageTop;
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
        return Math.min(bottomY() - PAD - 14, TOP_H + PAD + 46 + 5 * (CARD_H + 2) + 70);
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
            case VEIN -> Glyphs.VEIN;
            case HOLE -> Glyphs.HOLE;
        };
    }

    @Override
    protected void init() {
        this.lastFrameNanos = System.nanoTime();
        this.actions = new WorldActions(this.client, this.controller);

        // Top bar: tools with icons (icons only when the window is narrow).
        int toolsRoom = this.width - 2 * PAD - 26 - 120;
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
        y += 20;
        this.ordersTop = y + 11;

        int bottom = rightBottom();
        int sy = bottom - PAD - 3 * 18;
        this.storageTop = sy - 24;
        this.ordersBottom = this.storageTop - 4;
        button("Scan nearby", "Link every chest, barrel and shulker box within " + RtsConfig.get().scanRadius
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
        button("Unlink all", "Forget every storage link", rx + half + 2, sy + 18, half, 16, () -> {
            StorageManager.get().unlinkAll();
            Messages.info("Storage links cleared");
        });
    }

    private void section(int y, String name) {
        this.leftSections.add(new int[]{y});
        this.leftSectionNames.add(name);
    }

    private void initLeftPanel() {
        this.leftSections.clear();
        this.leftSectionNames.clear();
        this.heightRowY = -1;
        this.clipboardInfoY = -1;
        BuildSession s = this.session;
        int x = PAD + 4;
        int full = LEFT_W - 8;
        int col = (full - 2) / 2;
        int y = TOP_H + PAD + Theme.HEADER_H + 4;
        switch (s.tool) {
            case BUILD, DESTROY -> {
                BuildShape[] shapes = BuildShape.values();
                for (int i = 0; i < shapes.length; i++) {
                    BuildShape shape = shapes[i];
                    button(shape.label(), shape.description() + " [ and ] cycle", x + (i % 2) * (col + 2), y + (i / 2) * ROW, col, ROW - 1,
                            () -> {
                                s.shape = shape;
                                s.clearSelection();
                            }).glyph(shapeGlyph(shape), Theme.TEXT_DIM).selected(() -> s.shape == shape);
                }
                y += ((shapes.length + 1) / 2) * ROW + 4;
                section(y, "OPTIONS");
                y += 11;
                if (s.tool == ToolMode.BUILD) {
                    button("Mode", s.mode.description() + " [M]", x, y, full, ROW - 1, () -> {
                        s.mode = s.mode.next();
                        s.clearSelection();
                        clearAndInit();
                    }).dynamic(() -> "Mode: " + s.mode.label()).left();
                    y += ROW;
                }
                button("Hollow", "Only the outside of floors, boxes, circles and spheres [H]", x, y, full, ROW - 1,
                        () -> s.hollow = !s.hollow).selected(() -> s.hollow).dynamic(() -> s.hollow ? "Hollow: on" : "Hollow: off").left();
                y += ROW;
                button("-", "Move the air layer down [Alt+scroll]", x, y, 16, ROW - 1, () -> s.airY--).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                button("Air", "Air placement: point at empty space on a flat layer to build in mid-air [B]",
                        x + 18, y, full - 36, ROW - 1, this::toggleAir)
                        .selected(() -> s.airPlace).dynamic(() -> s.airPlace ? "Air: Y " + s.airY : "Air: off");
                button("+", "Move the air layer up [Alt+scroll]", x + full - 16, y, 16, ROW - 1, () -> s.airY++).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
                y += ROW;
                button("-", "Lower", x, y, 16, ROW - 1, () -> s.adjustHeight(-1)).glyph(Glyphs.MINUS, Theme.TEXT).iconOnly();
                button("+", "Height for walls, boxes and cylinders. Negative digs down. [Ctrl+scroll]", x + full - 16, y, 16, ROW - 1,
                        () -> s.adjustHeight(1)).glyph(Glyphs.PLUS, Theme.TEXT).iconOnly();
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
                    button("Roof", "Smart roof: click the top of a room's wall and get a roof sized to the room "
                            + "(use stairs as the block for a sloped roof)", x, y, col, ROW - 1, () -> {
                        s.roofPick = !s.roofPick;
                        if (s.roofPick) {
                            Messages.info("Roof: click the top of a wall of the room");
                        }
                    }).selected(() -> s.roofPick).icon(new ItemStack(Items.OAK_STAIRS));
                    button("Style", "Gable: two slopes and closed ends. Hip: slopes on all four sides.", x + col + 2, y, col, ROW - 1,
                            () -> s.roofStyle = s.roofStyle.next()).dynamic(() -> s.roofStyle.label());
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
                button("Air", "clear: also mine whatever is in the blueprint's empty spaces, for an exact copy. keep: only add blocks.",
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
                        x, y, full, ROW - 1, () -> {
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
                                case LIGHT_UP -> Items.TORCH;
                                case QUARRY -> Items.IRON_PICKAXE;
                                case FELL_TREES -> Items.IRON_AXE;
                                case HARVEST -> Items.WHEAT;
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
                this.clipboardInfoY = y;
                y += 22;
            }
            default -> {
            }
        }
        this.leftPanelBottom = y + PAD;
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
    private BlockHitResult pick(double mx, double my) {
        var cam = this.controller.camera();
        BlockHitResult hit = MouseRay.pick(cam, mx, my, this.width, this.height, false);
        BuildSession s = this.session;
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
            return hit; // terrain in front of the layer
        }
        return air;
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
            BlockHitResult hit = pick(mx, my);
            if (hit != null && hasShiftDown() && this.session.tool == ToolMode.BUILD && this.session.anchorA == null) {
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
        if (this.dragTask != null && button == 0) {
            if (Math.abs(my - this.dragFromY) > DRAG_THRESHOLD) {
                this.dragMoved = true;
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
        if (this.dragTask != null && button == 0) {
            if (this.dragMoved) {
                int row = Math.max(0, (int) ((my - this.ordersTop + (CARD_H + 2) / 2.0) / (CARD_H + 2)));
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
            if (this.session.tool == ToolMode.TERRAIN) {
                this.session.terrainClearHeight = MathHelper.clamp(this.session.terrainClearHeight + (amount > 0 ? 1 : -1), 1, 64);
            } else {
                this.session.adjustHeight(amount > 0 ? 1 : -1);
            }
            return true;
        }
        this.controller.camera().zoom(this.client, amount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        BuildSession s = this.session;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.search.isFocused()) {
                this.search.setFocused(false);
                setFocused(null);
            } else if (s.anchorA != null || s.pasteAnchor != null) {
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
            case GLFW.GLFW_KEY_B -> toggleAir();
            case GLFW.GLFW_KEY_P -> this.client.setScreen(new PresetScreen(this));
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> this.actions.confirm();
            case GLFW.GLFW_KEY_Z -> {
                if (hasControlDown() && this.client.world != null) {
                    UndoHistory.undoLast(this.client.world);
                }
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_BACKSPACE -> {
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
        moveCamera();
        this.controller.setHover(overUi(mouseX, mouseY) ? null : pick(mouseX, mouseY));

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
        if (x + 8 + this.textRenderer.getWidth(engine) < limit) {
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
            String d = String.format(Locale.ROOT, "%.0fm", dist);
            int cw = this.textRenderer.getWidth(coords) + this.textRenderer.getWidth(d) + 34;
            int bx = cx - cw / 2;
            int by = y0 + h + 2;
            Theme.round(ctx, bx, by, cw, 11, 0xA00E141B);
            int tx = bx + 4;
            ctx.drawText(this.textRenderer, "XYZ", tx, by + 2, Theme.ACCENT, false);
            tx += this.textRenderer.getWidth("XYZ") + 5;
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
        if (this.heightRowY >= 0) {
            boolean terrain = s.tool == ToolMode.TERRAIN;
            String h = terrain ? s.terrainClearHeight + " blocks" : "Height " + s.height;
            boolean active = terrain || s.shape.usesHeight();
            ctx.drawCenteredTextWithShadow(this.textRenderer, h, (x0 + x1) / 2, this.heightRowY + 3, active ? Theme.TEXT : Theme.TEXT_FAINT);
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
                    case LIGHT_UP -> "Uses torches";
                    case QUARRY -> "Dig " + s.terrainClearHeight + " deep from the top";
                    case FELL_TREES -> "Replants saplings";
                    case HARVEST -> "Replants, stores produce";
                };
                ctx.drawText(this.textRenderer, trim(fill, x1 - x0), x0, this.clipboardInfoY, Theme.TEXT_DIM, false);
                ctx.drawText(this.textRenderer, "Click two corners", x0, this.clipboardInfoY + 10, Theme.TEXT_FAINT, false);
            }
        }
    }

    private int visibleCards() {
        return Math.max(1, (this.ordersBottom - this.ordersTop) / (CARD_H + 2));
    }

    private void drawOrders(DrawContext ctx, int mx, int my) {
        int x0 = rightX() + PAD;
        int x1 = this.width - 2 * PAD;
        List<RtsTask> tasks = TaskQueue.get().snapshot();
        Theme.section(ctx, this.textRenderer, x0, x1, this.ordersTop - 11, "ORDERS" + (tasks.isEmpty() ? "" : " (" + tasks.size() + ")"));
        int visible = visibleCards();
        this.taskScroll = MathHelper.clamp(this.taskScroll, 0, Math.max(0, tasks.size() - visible));
        int y = this.ordersTop;
        if (tasks.isEmpty()) {
            ctx.drawText(this.textRenderer, "Nothing queued", x0 + 2, y + 2, Theme.TEXT_FAINT, false);
            ctx.drawText(this.textRenderer, "Left-click the world to start", x0 + 2, y + 12, Theme.TEXT_FAINT, false);
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
                Theme.outline(ctx, x0, y, x1 - x0, CARD_H, Theme.withAlpha(Theme.ACCENT, 0.4F));
                y += CARD_H + 2;
                continue;
            }
            boolean cardHover = mx >= x0 && mx < x1 && my >= y && my < y + CARD_H;
            int cardBg = running ? 0xFF16283A : cardHover ? 0xFF1F2834 : 0xFF19212B;
            Theme.round(ctx, x0, y, x1 - x0, CARD_H, cardBg);
            ctx.fillGradient(x0 + 1, y + 1, x1 - 1, y + CARD_H / 2, Theme.mix(cardBg, 0xFFFFFFFF, 0.05F), cardBg);
            Theme.outline(ctx, x0, y, x1 - x0, CARD_H, running
                    ? Theme.withAlpha(Theme.ACCENT, 0.35F + 0.4F * Theme.pulse(1600)) : Theme.BORDER);
            if (running) {
                ctx.fill(x0, y + 2, x0 + 2, y + CARD_H - 2, Theme.ACCENT);
            }
            ctx.drawText(this.textRenderer, trim(task.label(), x1 - x0 - 40), x0 + 5, y + 3,
                    paused ? Theme.TEXT_FAINT : running ? Theme.TEXT : 0xFFC9D3DD, false);
            ctx.drawText(this.textRenderer, trim(paused ? "paused" : task.detail(), x1 - x0 - 10), x0 + 5, y + 13,
                    paused ? Theme.GOLD : Theme.TEXT_DIM, false);
            if (task instanceof BuildTask build && build.progress() >= 0) {
                Theme.bar(ctx, x0 + 4, y + CARD_H - 4, x1 - x0 - 8, 2, build.progress(), build.clearing() ? Theme.BAD : Theme.GOOD, running);
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
            y += CARD_H + 2;
        }
        if (this.dragTask != null && this.dragMoved && tasks.contains(this.dragTask)) {
            int row = MathHelper.clamp((int) ((my - this.ordersTop + (CARD_H + 2) / 2) / (CARD_H + 2)), 0, visible);
            int ly = this.ordersTop + row * (CARD_H + 2) - 2;
            ctx.fill(x0, ly, x1, ly + 1, Theme.ACCENT);
            int gy = my - CARD_H / 2;
            Theme.round(ctx, x0 + 3, gy, x1 - x0, CARD_H, 0xE0243246);
            Theme.outline(ctx, x0 + 3, gy, x1 - x0, CARD_H, Theme.ACCENT);
            ctx.drawText(this.textRenderer, trim(this.dragTask.label(), x1 - x0 - 10), x0 + 8, gy + 8, Theme.TEXT, false);
        }
        if (tasks.size() > visible) {
            String more = (this.taskScroll + 1) + "-" + Math.min(tasks.size(), this.taskScroll + visible) + " of " + tasks.size();
            ctx.drawText(this.textRenderer, more, x1 - this.textRenderer.getWidth(more), this.ordersTop - 11, Theme.TEXT_FAINT, false);
        }
    }

    private boolean clickOrders(double mx, double my) {
        if (mx < rightX() || my < this.ordersTop || my >= this.ordersBottom) {
            return false;
        }
        List<RtsTask> tasks = TaskQueue.get().snapshot();
        int row = (int) ((my - this.ordersTop) / (CARD_H + 2));
        int idx = this.taskScroll + row;
        if (row >= visibleCards() || idx < 0 || idx >= tasks.size()) {
            return false;
        }
        int x1 = this.width - 2 * PAD;
        double cardY = this.ordersTop + row * (CARD_H + 2);
        if (my >= cardY + CARD_H) {
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
            slot(ctx, x, y, hot && item != null, item != null && this.session.brush.getItem() == item);
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            ctx.drawItem(stack, x + 1, y + 1);
            int count = (this.client.player == null ? 0 : this.client.player.getInventory().count(item)) + StorageManager.get().total(item);
            ctx.drawItemInSlot(this.textRenderer, stack, x + 1, y + 1, count == 0 ? "§c0" : shortCount(count));
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
                slot(ctx, x, y, hot, e != null && this.session.brush.getItem() == e.item());
                if (e == null) {
                    continue;
                }
                ctx.drawItem(e.display(), x + 1, y + 1);
                String count = shortCount(e.total());
                // Gold count: only in storage, a build will fetch it.
                ctx.drawItemInSlot(this.textRenderer, e.display(), x + 1, y + 1, e.inventory() == 0 ? "§6" + count : count);
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
                }
            }
            case DESTROY -> {
                chips.add(s.shape.label() + (s.hollow && s.shape.supportsHollow() ? " hollow" : ""));
                if (s.shape.usesHeight()) {
                    chips.add("h" + s.height);
                }
            }
            case INTERACT -> chips.add("Click a block to walk over and use it");
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
            case TERRAIN -> chips.add(s.terrainMode.label() + ": click two corners");
            case MEASURE -> chips.add("Click two points");
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
        } else if (s.tool == ToolMode.TERRAIN && s.anchorA != null && (s.anchorB != null || hover != null)) {
            BlockPos b = s.anchorB != null ? s.anchorB : hover.getBlockPos();
            int w = Math.abs(s.anchorA.getX() - b.getX()) + 1;
            int l = Math.abs(s.anchorA.getZ() - b.getZ()) + 1;
            int[] counts = WorldOverlay.terrainCounts();
            String what = switch (s.terrainMode) {
                case FLATTEN -> counts[0] < 0 ? "planning..." : "mine " + counts[0] + ", fill " + counts[1] + " at Y" + s.anchorA.getY();
                case LIGHT_UP -> counts[1] < 0 ? "planning..." : counts[1] + " torches";
                case QUARRY -> s.terrainClearHeight + " deep, " + (w * l * s.terrainClearHeight) + " blocks at most";
                default -> w * l + " columns";
            };
            lines.add(Text.literal(s.terrainMode.label() + " " + w + " x " + l + "  |  " + what).formatted(Formatting.GREEN));
            if (s.terrainMode == TerrainMode.LIGHT_UP && counts[1] > 0) {
                materials = Map.of(Items.TORCH, counts[1]);
            } else if (s.terrainMode == TerrainMode.FLATTEN && counts[1] > 0) {
                materials = Map.of(WorldActions.flattenFill(s).getBlock().asItem(), counts[1]);
            }
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
