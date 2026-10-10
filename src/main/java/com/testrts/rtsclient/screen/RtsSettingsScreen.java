package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Supplier;

/** Simple settings page; everything here is saved to config/rtsclient/config.json. */
public final class RtsSettingsScreen extends Screen {
    private static final int[] GHOST_LIMITS = {4_000, 12_000, 24_000, 60_000, 150_000};
    private static final int[] SHAPE_LIMITS = {16_384, 65_536, 262_144, 1_000_000, 4_000_000};
    private static final double[] SPEEDS = {8, 12, 18, 26, 40};

    private final Screen parent;

    public RtsSettingsScreen(Screen parent) {
        super(Text.literal("RTS settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        RtsConfig c = RtsConfig.get();
        int w = 220;
        int x = this.width / 2 - w / 2;
        int y = 40;
        y = row(x, y, w, () -> "Engine: " + Engine.configured().label(),
                "Auto = Baritone when installed, else built-in. Built-in walks, builds and mines without Baritone.",
                Engine::cycle);
        y = row(x, y, w, () -> "Build in layers: " + onOff(c.buildInLayers),
                "Finish one Y level before the next (bottom-up; clearing goes top-down). Fewer missed blocks.",
                () -> c.buildInLayers = !c.buildInLayers);
        y = row(x, y, w, () -> "Exact block rotation: " + onOff(c.strictOrientation),
                "On: stairs/logs must face exactly as previewed or they're skipped. Off: placed as close as possible.",
                () -> c.strictOrientation = !c.strictOrientation);
        y = row(x, y, w, () -> "Pick up drops after clearing: " + onOff(c.collectDrops),
                "Walk over the items a clear order dropped.",
                () -> c.collectDrops = !c.collectDrops);
        y = row(x, y, w, () -> "Camera stops at walls: " + onOff(c.cameraCollision),
                "Off: the camera flies through blocks.",
                () -> c.cameraCollision = !c.cameraCollision);
        y = row(x, y, w, () -> "Camera speed: " + (int) c.cameraSpeed,
                "Blocks per second when flying the camera (hold Sprint for faster).",
                () -> c.cameraSpeed = next(SPEEDS, c.cameraSpeed));
        y = row(x, y, w, () -> "Real-block preview up to: " + String.format(Locale.ROOT, "%,d", c.ghostBlockLimit),
                "Shapes up to this size are previewed as see-through blocks; bigger ones as a tinted surface.",
                () -> c.ghostBlockLimit = next(GHOST_LIMITS, c.ghostBlockLimit));
        y = row(x, y, w, () -> "Max shape size: " + String.format(Locale.ROOT, "%,d", c.maxShapeBlocks),
                "The largest order one shape can make.",
                () -> c.maxShapeBlocks = next(SHAPE_LIMITS, c.maxShapeBlocks));
        y = row(x, y, w, () -> "Let mod set Baritone options: " + onOff(c.manageBaritoneSettings),
                "Turns on Baritone's allowPlace/allowBreak/allowInventory and layer building for RTS orders.",
                () -> c.manageBaritoneSettings = !c.manageBaritoneSettings);
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(this.width / 2 - 50, y + 10, 100, 20).build());
    }

    private int row(int x, int y, int w, Supplier<String> label, String tip, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label.get()), b -> {
                    action.run();
                    RtsConfig.save();
                    b.setMessage(Text.literal(label.get()));
                })
                .dimensions(x, y, w, 20)
                .tooltip(Tooltip.of(Text.literal(tip)))
                .build());
        return y + 24;
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
        renderBackground(ctx);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFF);
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
}
