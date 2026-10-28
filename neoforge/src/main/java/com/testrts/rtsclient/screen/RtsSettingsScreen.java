package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Settings page in the RTS style; everything here is saved to config/rtsclient/config.json. */
public final class RtsSettingsScreen extends Screen {
    private static final int[] GHOST_LIMITS = {4_000, 12_000, 24_000, 60_000, 150_000};
    private static final int[] SHAPE_LIMITS = {16_384, 65_536, 262_144, 1_000_000, 4_000_000};
    private static final double[] SPEEDS = {8, 12, 18, 26, 40};
    private static final double[] TURN_SPEEDS = {15, 30, 45, 90, 0};
    private static final double[] AUTO_PLACE_RANGES = {2, 3, 4, 0};
    private static final double[] AUTO_PLACE_SPEEDS = {2, 3, 5, 10, 20, 40, 60, 100};

    private static final int COL_W = 196;
    private static final int ROW_H = 18;
    private static final int ROW_STEP = 20;
    private static final int SECTION_H = 14;
    private static final int PAD = 8;
    /** Space kept free around the panel. */
    private static final int MARGIN = 4;
    private static final int MAX_COLUMNS = 3;

    private record Setting(String name, Supplier<String> value, String tip, Runnable action) {
    }

    private record Group(String title, List<Setting> settings) {
        int height() {
            return SECTION_H + this.settings.size() * ROW_STEP;
        }
    }

    private record Header(String title, int x, int y, int w) {
    }

    private final Screen parent;
    private final List<Header> headers = new ArrayList<>();
    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;
    /** Below 1 when the page is drawn shrunk to fit the window. */
    private float uiScale = 1.0F;

    public RtsSettingsScreen(Screen parent) {
        super(Component.literal("RTS settings"));
        this.parent = parent;
    }

    private static List<Group> groups() {
        RtsConfig c = RtsConfig.get();
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("ENGINE", List.of(
                new Setting("Engine", () -> Engine.configured().label(),
                        "Auto = Baritone when installed, else built-in. Built-in walks, builds and mines without Baritone.",
                        Engine::cycle),
                new Setting("Turn speed", () -> c.turnSpeed <= 0 ? "Instant" : (int) c.turnSpeed + " deg/tick",
                        "How fast your character turns its head toward blocks it places or mines. Smooth turning looks "
                                + "natural to other players and anti-cheat; Instant snaps like before.",
                        () -> c.turnSpeed = next(TURN_SPEEDS, c.turnSpeed)),
                new Setting("Manage Baritone", () -> onOff(c.manageBaritoneSettings),
                        "Turns on Baritone's allowPlace/allowBreak/allowInventory and layer building for RTS orders.",
                        () -> c.manageBaritoneSettings = !c.manageBaritoneSettings))));
        groups.add(new Group("BUILDING", List.of(
                new Setting("Build in layers", () -> onOff(c.buildInLayers),
                        "Finish one Y level before the next (bottom-up; clearing goes top-down). Fewer missed blocks.",
                        () -> c.buildInLayers = !c.buildInLayers),
                new Setting("Build ahead", () -> onOff(c.buildAhead),
                        "Built-in engine, with Build in layers and Place in mid-air: also places blocks of the next 2 layers "
                                + "that are already in reach (fewer laps around the build). Off: strictly one layer at a time.",
                        () -> c.buildAhead = !c.buildAhead),
                new Setting("Exact rotation", () -> onOff(c.strictOrientation),
                        "On: stairs/logs must face exactly as previewed or they're skipped. Off: placed as close as possible.",
                        () -> c.strictOrientation = !c.strictOrientation),
                new Setting("Buildable shells", () -> onOff(c.buildableShells),
                        "Hollow spheres and circles get extra blocks on the inside where needed so every block can be placed.",
                        () -> c.buildableShells = !c.buildableShells),
                new Setting("Climb with scaffolding / ladders", () -> onOff(c.climbingAids),
                        "Built-in engine: to get up high, put ladders (or vines) up a wall next to the block, or pillar up on "
                                + "scaffolding blocks, when you carry them - instead of pillaring up on dirt or cobble. Scaffolding comes "
                                + "down in one go afterwards.",
                        () -> c.climbingAids = !c.climbingAids),
                new Setting("Parkour jumps", () -> onOff(c.parkour),
                        "Built-in engine: jump across gaps of 1-3 blocks (sprinting for the wider ones, when not too hungry) "
                                + "instead of walking round. A missed jump is avoided next time. Off: never jumps a gap.",
                        () -> c.parkour = !c.parkour),
                new Setting("Scaffolding", () -> onOff(c.useScaffolding),
                        "Let the builder put up temporary blocks (dirt/cobblestone, scaffolding, ladders) to climb, bridge gaps "
                                + "and hold up floating blocks. Off: it never places anything the build doesn't have; blocks it "
                                + "can't reach or attach to are skipped (Air place and flying still work). Baritone too.",
                        () -> c.useScaffolding = !c.useScaffolding),
                new Setting("Remove scaffolding", () -> onOff(c.removeScaffolding),
                        "The built-in engine adds temporary dirt/cobblestone to reach and support blocks; mine it out afterwards.",
                        () -> c.removeScaffolding = !c.removeScaffolding),
                new Setting("Pick up drops", () -> onOff(c.collectDrops),
                        "Walk over the items a clear order dropped.",
                        () -> c.collectDrops = !c.collectDrops),
                new Setting("Place in mid-air", () -> onOff(c.airPlace),
                        "Built-in engine: place blocks with nothing next to them (domes, spheres, floating builds) without scaffolding. "
                                + "Works on vanilla servers; some anti-cheat plugins may not allow it.",
                        () -> c.airPlace = !c.airPlace),
                new Setting("Creative flight", () -> onOff(c.creativeFlight),
                        "When you can fly (creative mode, a flight item like an Angel Ring or Mystical Agriculture flight armor, or Apotheosis's Flying potion), your character flies straight "
                                + "to the blocks it builds or mines instead of walking and climbing. Off: it walks, landing first if "
                                + "you're flying. In survival, mining in the air is 5x slower.",
                        () -> c.creativeFlight = !c.creativeFlight),
                new Setting("Fly above the layer", () -> onOff(c.flyAboveLayer),
                        "Flying while building in layers: hover above the layer being built (nothing is there yet, see "
                                + "Hover height), so it never catches on its own blocks; with Multi-place it places while flying "
                                + "over. Off: it stops wherever is closest.",
                        () -> c.flyAboveLayer = !c.flyAboveLayer),
                new Setting("Hover height", () -> c.hoverGap + " blocks",
                        "Fly above the layer: how high the character's feet stay over the layer's blocks. Higher keeps it clear "
                                + "of the blocks it places (no catching on them); lower reaches further to the sides.",
                        () -> c.hoverGap = c.hoverGap >= 2.0 ? 0.5 : c.hoverGap + 0.5),
                new Setting("Mining speed", () -> switch (c.miningSpeed) {
                            case 0 -> "Normal";
                            case 2 -> "Fastest";
                            default -> "Fast";
                        },
                        "Built-in engine. Normal: aims exactly and waits the game's short pause between blocks. Fast: starts once the "
                                + "block is roughly in view and skips the pause. Fastest: starts right away while still turning (like "
                                + "Baritone). Strict anti-cheat plugins may notice the missing pause.",
                        () -> c.miningSpeed = (c.miningSpeed + 1) % 3),
                new Setting("Multi-place", () -> c.multiPlace <= 1 ? "Off" : c.multiPlace + " at once",
                        "Built-in engine: after the block it turns to, also put down other blocks in reach in the same tick, "
                                + "like hold-Z (each click sends its own look, no turning). Much faster; strict anti-cheat "
                                + "plugins may notice.",
                        () -> c.multiPlace = c.multiPlace >= 8 ? 1 : c.multiPlace * 2),
                new Setting("Creative supply", () -> onOff(c.creativeSupply),
                        "In creative mode, the builder spawns the blocks (and scaffolding) it needs into your hotbar "
                                + "instead of fetching them from storage. Does nothing in survival.",
                        () -> c.creativeSupply = !c.creativeSupply),
                new Setting("Start orders right away", () -> onOff(c.autoStart),
                        "Off: orders wait in the queue until you press Start (Pause stops everything). On: they start as soon as you queue them.",
                        () -> c.autoStart = !c.autoStart),
                new Setting("Build hologram", () -> onOff(c.showHologram),
                        "Outside RTS mode, show the ghost blocks of queued builds so you can place blocks by hand. Also toggled with H outside RTS mode.",
                        () -> c.showHologram = !c.showHologram),
                new Setting("Hold-Z place range", () -> c.autoPlaceRange <= 0 ? "Full reach" : (int) c.autoPlaceRange + " blocks",
                        "How far from you hold-Z places the hologram's blocks (measured from your eyes). Shorter keeps it to the "
                                + "blocks right around you. Hold-X (the block you look at) always reaches as far as you can.",
                        () -> c.autoPlaceRange = next(AUTO_PLACE_RANGES, c.autoPlaceRange)),
                new Setting("Hold-Z in layers", () -> onOff(c.autoPlaceLayers),
                        "Hold-Z fills a build one layer at a time from the bottom: it only goes up a layer once every block of "
                                + "the one below is in. Off: any block in reach, lowest first.",
                        () -> c.autoPlaceLayers = !c.autoPlaceLayers),
                new Setting("Glow layer", () -> onOff(c.glowLayer),
                        "Outside RTS mode, with Hold-Z in layers: the blocks of the layer to place next glow and show "
                                + "through walls, so they're easy to find.",
                        () -> c.glowLayer = !c.glowLayer),
                new Setting("Hold-Z place speed", () -> (int) c.autoPlaceSpeed + " blocks/s"
                                + (c.serverSafePlacing && c.autoPlaceSpeed > 5 ? " (5 server-safe)" : ""),
                        "How many blocks a second hold-Z places. Above 20 it places several blocks per tick. Server-safe placing "
                                + "never goes faster than 5 (holding right-click).",
                        () -> c.autoPlaceSpeed = next(AUTO_PLACE_SPEEDS, c.autoPlaceSpeed)),
                new Setting("Server-safe placing", () -> onOff(c.serverSafePlacing),
                        "Hold-Z and hold-X only do what you could do by hand, so anti-cheat plugins don't object: no faked look "
                                + "direction, no mid-air placing, only block faces you can see, at most 5 blocks a second, and hold-X is a "
                                + "plain right-click on what your crosshair is on. Blocks that would face the wrong way from where you look "
                                + "are left for hold-X or the builder. Off: anything in reach, any facing, mid-air allowed (singleplayer).",
                        () -> c.serverSafePlacing = !c.serverSafePlacing),
                new Setting("Use backpacks", () -> onOff(c.useBackpacks),
                        "Take building materials out of the backpack you wear or carry (Sophisticated Backpacks) before anything else - "
                                + "no walking. It opens for a moment and closes again.",
                        () -> c.useBackpacks = !c.useBackpacks),
                new Setting("Use AE2 for materials", () -> onOff(c.useAe2),
                        "With Applied Energistics 2: fetch building materials through a wireless terminal you carry "
                                + "(works from anywhere in range), or from ME terminals you linked with the Link tool, before chests.",
                        () -> c.useAe2 = !c.useAe2),
                new Setting("Use Refined Storage", () -> onOff(c.useRefinedStorage),
                        "With Refined Storage: fetch building materials through a wireless grid or wireless crafting grid you carry, "
                                + "or from grids you linked with the Link tool, before chests.",
                        () -> c.useRefinedStorage = !c.useRefinedStorage),
                new Setting("Clear blocks in the way", () -> onOff(c.clearInTheWay),
                        "Shapes mine whatever is in their cells (terrain, trees) and put the brush block there instead of skipping them.",
                        () -> c.clearInTheWay = !c.clearInTheWay),
                new Setting("Auto-repair builds", () -> onOff(c.autoRepair),
                        "When there's nothing to do, check finished builds every 10 seconds and fill in missing or broken blocks.",
                        () -> c.autoRepair = !c.autoRepair),
                new Setting("Max shape size", () -> String.format(Locale.ROOT, "%,d", c.maxShapeBlocks),
                        "The largest order one shape or schematic can make.",
                        () -> c.maxShapeBlocks = next(SHAPE_LIMITS, c.maxShapeBlocks)))));
        groups.add(new Group("CAMERA", List.of(
                new Setting("Stops at walls", () -> onOff(c.cameraCollision),
                        "Off: the camera flies through blocks.",
                        () -> c.cameraCollision = !c.cameraCollision),
                new Setting("Speed", () -> (int) c.cameraSpeed + " b/s",
                        "Blocks per second when flying the camera (hold Sprint for faster).",
                        () -> c.cameraSpeed = next(SPEEDS, c.cameraSpeed)))));
        groups.add(new Group("PREVIEW", List.of(
                new Setting("Opacity", () -> Math.round(c.previewOpacity * 100) + "%",
                        "How solid previews look. 100% = like real blocks.",
                        () -> {
                            c.previewOpacity = c.previewOpacity >= 0.99F ? 0.35F : c.previewOpacity >= 0.85F ? 1.0F
                                    : c.previewOpacity >= 0.55F ? 0.9F : 0.6F;
                            WorldOverlay.invalidate();
                        }),
                new Setting("Real blocks up to", () -> String.format(Locale.ROOT, "%,d", c.ghostBlockLimit),
                        "Shapes up to this size are previewed as see-through blocks; bigger ones as a tinted surface.",
                        () -> {
                            c.ghostBlockLimit = next(GHOST_LIMITS, c.ghostBlockLimit);
                            WorldOverlay.invalidate();
                        }))));
        return groups;
    }

    /** One column of the page: section titles (String) and settings, top to bottom. */
    private record Layout(List<List<Object>> columns, int columnHeight) {
        int panelW() {
            return this.columns.size() * COL_W + (this.columns.size() + 1) * PAD;
        }

        int panelH() {
            return Theme.HEADER_H + 6 + this.columnHeight + ROW_STEP + PAD;
        }

        /** How far the page has to shrink to fit a {@code w} x {@code h} screen (1 = not at all). */
        float scale(int w, int h) {
            return Math.min(1.0F, Math.min((float) (w - 2 * MARGIN) / panelW(), (float) (h - 2 * MARGIN) / panelH()));
        }
    }

    /**
     * Fill columns of at most {@code maxH} top to bottom; a section that doesn't fit carries on at the top of
     * the next column under its title again. Null if it takes more than {@code maxColumns} columns.
     */
    private static Layout flow(List<Group> groups, int maxColumns, int maxH) {
        List<List<Object>> columns = new ArrayList<>();
        List<Object> col = new ArrayList<>();
        columns.add(col);
        int h = 0;
        int tallest = 0;
        for (Group g : groups) {
            for (int i = 0; i < g.settings().size(); i++) {
                boolean title = i == 0 || h == 0;
                int need = (title ? SECTION_H : 0) + ROW_STEP;
                if (h > 0 && h + need > maxH) {
                    col = new ArrayList<>();
                    columns.add(col);
                    h = 0;
                    title = true;
                    need = SECTION_H + ROW_STEP;
                }
                if (title) {
                    col.add(g.title());
                }
                col.add(g.settings().get(i));
                h += need;
                tallest = Math.max(tallest, h);
            }
        }
        return columns.size() > maxColumns ? null : new Layout(columns, tallest);
    }

    /** The most even split over {@code columns} columns: the shortest column height that still fits. */
    private static Layout balanced(List<Group> groups, int columns) {
        int total = 0;
        for (Group g : groups) {
            total += g.height();
        }
        for (int h = Math.max(SECTION_H + ROW_STEP, total / columns); ; h += 2) {
            Layout l = flow(groups, columns, h);
            if (l != null) {
                return l;
            }
        }
    }

    @Override
    protected void init() {
        // Small window or big GUI scale: the rows flow over up to three columns, and when even that doesn't
        // fit, the whole page is drawn scaled down instead of rows running off the screen. Always from the real
        // size, since init() runs again on resize.
        int realW = this.minecraft.getWindow().getGuiScaledWidth();
        int realH = this.minecraft.getWindow().getGuiScaledHeight();
        this.headers.clear();
        List<Group> groups = groups();
        Layout layout = null;
        for (int n = 1; n <= MAX_COLUMNS; n++) {
            Layout l = balanced(groups, n);
            if (layout == null || l.scale(realW, realH) > layout.scale(realW, realH) + 0.001F) {
                layout = l;
            }
        }
        this.uiScale = layout.scale(realW, realH);
        this.width = Math.round(realW / this.uiScale);
        this.height = Math.round(realH / this.uiScale);

        int panelW = layout.panelW();
        int panelH = layout.panelH();
        this.panelX0 = this.width / 2 - panelW / 2;
        this.panelY0 = Math.max(MARGIN, this.height / 2 - panelH / 2);
        this.panelX1 = this.panelX0 + panelW;
        this.panelY1 = this.panelY0 + panelH;

        for (int c = 0; c < layout.columns().size(); c++) {
            int x = this.panelX0 + PAD + c * (COL_W + PAD);
            int y = this.panelY0 + Theme.HEADER_H + 6;
            for (Object o : layout.columns().get(c)) {
                if (o instanceof Setting s) {
                    addRenderableWidget(new SettingRow(x, y, COL_W, ROW_H, s));
                    y += ROW_STEP;
                } else {
                    this.headers.add(new Header((String) o, x, y, COL_W));
                    y += SECTION_H;
                }
            }
        }
        int doneW = 80;
        addRenderableWidget(new RtsButton(this.panelX1 - PAD - doneW, this.panelY1 - PAD - ROW_H, doneW, ROW_H,
                "Done", this::onClose).selected(() -> true));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return super.mouseClicked(mx / this.uiScale, my / this.uiScale, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return super.mouseReleased(mx / this.uiScale, my / this.uiScale, button);
    }

    /** The hovered setting's explanation, drawn here so it's scaled with the page. */
    private void drawTip(GuiGraphics ctx, int mx, int my) {
        for (var child : children()) {
            if (child instanceof SettingRow row && row.isHovered()) {
                String tip = row.setting.tip();
                ctx.renderTooltip(this.font, this.font.split(Component.literal(tip), 220), mx, my);
                return;
            }
        }
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }

    private static int next(int[] values, int current) {
        for (int v : values) {
            if (v > current) {
                return v;
            }
        }
        return values[0];
    }

    private static double next(double[] values, double current) {
        for (double v : values) {
            if (v > current + 0.01) {
                return v;
            }
        }
        return values[0];
    }

    /**
     * Screen.render() calls this before drawing the widgets. A flat dim (instead of the 1.21 blur), then the
     * panel on top of it, so the widgets end up on top of the panel: dim, panel, widgets.
     */
    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.font, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "RTS SETTINGS");
        for (Header h : this.headers) {
            Theme.section(ctx, this.font, h.x(), h.x() + h.w(), h.y() + 2, h.title());
        }
        ctx.drawString(this.font, "Click a setting to change it. Saved automatically.",
                this.panelX0 + PAD, this.panelY1 - PAD - ROW_H + 5, Theme.TEXT_FAINT, false);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // renderBackground() (dim + panel), then the widgets; all scaled when the page is shrunk to fit.
        int mx = Math.round(mouseX / this.uiScale);
        int my = Math.round(mouseY / this.uiScale);
        ctx.pose().pushPose();
        ctx.pose().scale(this.uiScale, this.uiScale, 1.0F);
        super.render(ctx, mx, my, delta);
        drawTip(ctx, mx, my);
        ctx.pose().popPose();
    }

    @Override
    public void onClose() {
        RtsConfig.save();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** One clickable setting: name on the left, current value on the right. */
    private static final class SettingRow extends AbstractButton {
        private final Setting setting;

        SettingRow(int x, int y, int w, int h, Setting setting) {
            super(x, y, w, h, Component.literal(setting.name()));
            this.setting = setting;
        }

        @Override
        public void onPress() {
            this.setting.action().run();
            RtsConfig.save();
        }

        @Override
        protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
            Font font = Minecraft.getInstance().font;
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean hover = isHovered();
            Theme.round(ctx, x, y, w, h, hover ? Theme.BTN_HOVER : Theme.BTN);
            Theme.outline(ctx, x, y, w, h, hover ? 0xFF3C4A5C : 0xFF2A3441);
            ctx.drawString(font, this.setting.name(), x + 6, y + (h - 8) / 2, hover ? Theme.TEXT : 0xFFC9D3DD, false);
            String value = this.setting.value().get();
            int color = "ON".equals(value) ? Theme.GOOD : "OFF".equals(value) ? Theme.TEXT_FAINT : Theme.ACCENT;
            int vw = font.width(value);
            int chipX = x + w - vw - 12;
            Theme.round(ctx, chipX, y + 3, vw + 8, h - 6, 0xFF151B23);
            ctx.drawString(font, value, chipX + 4, y + (h - 8) / 2, color, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {
            defaultButtonNarrationText(builder);
        }
    }
}
