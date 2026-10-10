package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** A small themed dialog asking for a name (e.g. to save a blueprint). */
public final class NameScreen extends Screen {
    private static final int W = 220;
    private static final int H = 74;

    private final Screen parent;
    private final String heading;
    private final String initial;
    private final Consumer<String> onDone;
    private EditBox field;
    private int x0;
    private int y0;

    public NameScreen(Screen parent, String heading, String initial, Consumer<String> onDone) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.initial = initial;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        this.x0 = this.width / 2 - W / 2;
        this.y0 = this.height / 2 - H / 2;
        this.field = new EditBox(this.font, this.x0 + 10, this.y0 + Theme.HEADER_H + 10, W - 20, 16, Component.literal("Name"));
        this.field.setMaxLength(48);
        this.field.setValue(this.field.getValue().isEmpty() ? this.initial : this.field.getValue());
        addRenderableWidget(this.field);
        setInitialFocus(this.field);
        addRenderableWidget(new RtsButton(this.x0 + W - 10 - 124, this.y0 + H - 24, 60, 16, "Save", this::done).selected(() -> true));
        addRenderableWidget(new RtsButton(this.x0 + W - 10 - 60, this.y0 + H - 24, 60, 16, "Cancel", this::onClose));
    }

    private void done() {
        String name = this.field.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        this.minecraft.setScreen(this.parent);
        this.onDone.accept(name);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            done();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // A plain dim instead of 1.21's blur, then the panel; widgets are drawn on top by render().
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.font, this.x0, this.y0, this.x0 + W, this.y0 + H, this.heading.toUpperCase(java.util.Locale.ROOT));
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
