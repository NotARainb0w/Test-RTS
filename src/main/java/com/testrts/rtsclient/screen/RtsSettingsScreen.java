package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Settings page in the RTS style; everything here is saved to config/rtsclient/config.json. */
public final class RtsSettingsScreen extends Screen {
    private static final int[] GHOST_LIMITS = {4_000, 12_000, 24_000, 60_000, 150_000};
    private static final int[] SHAPE_LIMITS = {16_384, 65_536, 262_144, 1_000_000, 4_000_000};
    private static final double[] SPEEDS = {8, 12, 18, 26, 40};

    private static final int COL_W = 196;
    private static final int ROW_H = 18;
    private static final int ROW_STEP = 20;
    private static final int SECTION_H = 14;
    private static final int PAD = 8;

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

    public RtsSettingsScreen(Screen parent) {
        super(Text.literal("RTS settings"));
        this.parent = parent;
    }

    private static List<Group> groups() {
        RtsConfig c = RtsConfig.get();
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("ENGINE", List.of(
                new Setting("Engine", () -> Engine.configured().label(),
                        "Auto = Baritone when installed, else built-in. Built-in walks, builds and mines without Baritone.",
                        Engine::cycle),
                new Setting("Manage Baritone", () -> onOff(c.manageBaritoneSettings),
                        "Turns on Baritone's allowPlace/allowBreak/allowInventory and layer building for RTS orders.",
                        () -> c.manageBaritoneSettings = !c.manageBaritoneSettings))));
        groups.add(new Group("BUILDING", List.of(
                new Setting("Build in layers", () -> onOff(c.buildInLayers),
                        "Finish one Y level before the next (bottom-up; clearing goes top-down). Fewer missed blocks.",
                        () -> c.buildInLayers = !c.buildInLayers),
                new Setting("Exact rotation", () -> onOff(c.strictOrientation),
                        "On: stairs/logs must face exactly as previewed or they're skipped. Off: placed as close as possible.",
                        () -> c.strictOrientation = !c.strictOrientation),
                new Setting("Buildable shells", () -> onOff(c.buildableShells),
                        "Hollow spheres and circles get extra blocks on the inside where needed so every block can be placed.",
                        () -> c.buildableShells = !c.buildableShells),
                new Setting("Remove scaffolding", () -> onOff(c.removeScaffolding),
                        "The built-in engine adds temporary dirt/cobblestone to reach and support blocks; mine it out afterwards.",
                        () -> c.removeScaffolding = !c.removeScaffolding),
                new Setting("Pick up drops", () -> onOff(c.collectDrops),
                        "Walk over the items a clear order dropped.",
                        () -> c.collectDrops = !c.collectDrops),
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

    @Override
    protected void init() {
        this.headers.clear();
        List<Group> groups = groups();
        int total = 0;
        for (Group g : groups) {
            total += g.height();
        }
        int available = this.height - 40 - 30;
        boolean twoColumns = total > available && this.width >= 2 * COL_W + 3 * PAD + 10;

        List<List<Group>> columns = new ArrayList<>();
        if (twoColumns) {
            // Split where the two halves are closest in height.
            int best = 1;
            int bestDiff = Integer.MAX_VALUE;
            for (int split = 1; split < groups.size(); split++) {
                int left = 0;
                for (int i = 0; i < split; i++) {
                    left += groups.get(i).height();
                }
                int diff = Math.abs(total - 2 * left);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = split;
                }
            }
            columns.add(groups.subList(0, best));
            columns.add(groups.subList(best, groups.size()));
        } else {
            columns.add(groups);
        }
        int columnHeight = 0;
        for (List<Group> col : columns) {
            int h = 0;
            for (Group g : col) {
                h += g.height();
            }
            columnHeight = Math.max(columnHeight, h);
        }

        int panelW = columns.size() * COL_W + (columns.size() + 1) * PAD;
        int panelH = Theme.HEADER_H + 6 + columnHeight + ROW_STEP + PAD;
        this.panelX0 = this.width / 2 - panelW / 2;
        this.panelY0 = Math.max(4, this.height / 2 - panelH / 2);
        this.panelX1 = this.panelX0 + panelW;
        this.panelY1 = this.panelY0 + panelH;

        for (int c = 0; c < columns.size(); c++) {
            int x = this.panelX0 + PAD + c * (COL_W + PAD);
            int y = this.panelY0 + Theme.HEADER_H + 6;
            for (Group g : columns.get(c)) {
                this.headers.add(new Header(g.title(), x, y, COL_W));
                y += SECTION_H;
                for (Setting s : g.settings()) {
                    addDrawableChild(new SettingRow(x, y, COL_W, ROW_H, s));
                    y += ROW_STEP;
                }
            }
        }
        int doneW = 80;
        addDrawableChild(new RtsButton(this.panelX1 - PAD - doneW, this.panelY1 - PAD - ROW_H, doneW, ROW_H,
                "Done", this::close).selected(() -> true));
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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.textRenderer, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "RTS SETTINGS");
        for (Header h : this.headers) {
            Theme.section(ctx, this.textRenderer, h.x(), h.x() + h.w(), h.y() + 2, h.title());
        }
        ctx.drawText(this.textRenderer, "Click a setting to change it. Saved automatically.",
                this.panelX0 + PAD, this.panelY1 - PAD - ROW_H + 5, Theme.TEXT_FAINT, false);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        RtsConfig.save();
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** One clickable setting: name on the left, current value on the right. */
    private static final class SettingRow extends PressableWidget {
        private final Setting setting;

        SettingRow(int x, int y, int w, int h, Setting setting) {
            super(x, y, w, h, Text.literal(setting.name()));
            this.setting = setting;
            setTooltip(Tooltip.of(Text.literal(setting.tip())));
        }

        @Override
        public void onPress() {
            this.setting.action().run();
            RtsConfig.save();
        }

        @Override
        protected void renderButton(DrawContext ctx, int mouseX, int mouseY, float delta) {
            TextRenderer font = MinecraftClient.getInstance().textRenderer;
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean hover = isHovered();
            Theme.round(ctx, x, y, w, h, hover ? Theme.BTN_HOVER : Theme.BTN);
            Theme.outline(ctx, x, y, w, h, hover ? 0xFF3C4A5C : 0xFF2A3441);
            ctx.drawText(font, this.setting.name(), x + 6, y + (h - 8) / 2, hover ? Theme.TEXT : 0xFFC9D3DD, false);
            String value = this.setting.value().get();
            int color = "ON".equals(value) ? Theme.GOOD : "OFF".equals(value) ? Theme.TEXT_FAINT : Theme.ACCENT;
            int vw = font.getWidth(value);
            int chipX = x + w - vw - 12;
            Theme.round(ctx, chipX, y + 3, vw + 8, h - 6, 0xFF151B23);
            ctx.drawText(font, value, chipX + 4, y + (h - 8) / 2, color, false);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
