package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
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
    private TextFieldWidget field;
    private int x0;
    private int y0;

    public NameScreen(Screen parent, String heading, String initial, Consumer<String> onDone) {
        super(Text.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.initial = initial;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        this.x0 = this.width / 2 - W / 2;
        this.y0 = this.height / 2 - H / 2;
        this.field = new TextFieldWidget(this.textRenderer, this.x0 + 10, this.y0 + Theme.HEADER_H + 10, W - 20, 16, Text.literal("Name"));
        this.field.setMaxLength(48);
        this.field.setText(this.field.getText().isEmpty() ? this.initial : this.field.getText());
        addDrawableChild(this.field);
        setInitialFocus(this.field);
        addDrawableChild(new RtsButton(this.x0 + W - 10 - 124, this.y0 + H - 24, 60, 16, "Save", this::done).selected(() -> true));
        addDrawableChild(new RtsButton(this.x0 + W - 10 - 60, this.y0 + H - 24, 60, 16, "Cancel", this::close));
    }

    private void done() {
        String name = this.field.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        this.client.setScreen(this.parent);
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
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.textRenderer, this.x0, this.y0, this.x0 + W, this.y0 + H, this.heading.toUpperCase(java.util.Locale.ROOT));
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
