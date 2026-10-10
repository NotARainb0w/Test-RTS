package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.SchematicLoader;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.screen.ui.Glyphs;
import com.testrts.rtsclient.screen.ui.RtsButton;
import com.testrts.rtsclient.screen.ui.Theme;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Lists schematic files (Litematica .litematic, WorldEdit .schem, structure .nbt) from the game's
 * {@code schematics} folder and loads one into the Blueprint tool for placing.
 */
public final class SchematicBrowserScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int PAD = 8;

    private record Entry(Path path, String name, String type, long kb) {
    }

    private final Screen parent;
    private final List<Entry> files = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private EditBox search;
    private String filter = "";
    private int scroll;
    private boolean loading;
    private String status = "";
    private int statusColor = Theme.GOLD;

    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;
    private int listY0;
    private int listY1;

    public SchematicBrowserScreen(Screen parent) {
        super(Component.literal("Load schematic"));
        this.parent = parent;
    }

    public static Path folder() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
    }

    private void scan() {
        this.files.clear();
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> walk = Files.walk(dir, 4)) {
                walk.filter(Files::isRegularFile).filter(SchematicLoader::supported)
                        .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString()))
                        .forEach(p -> {
                            String name = dir.relativize(p).toString();
                            int dot = name.lastIndexOf('.');
                            String type = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
                            long kb = 0;
                            try {
                                kb = Math.max(1, Files.size(p) / 1024);
                            } catch (IOException ignored) {
                                // size is only informational
                            }
                            this.files.add(new Entry(p, dot >= 0 ? name.substring(0, dot) : name, type, kb));
                        });
            }
        } catch (IOException e) {
            setStatus("Couldn't read " + dir + ": " + e.getMessage(), Theme.BAD);
        }
        applyFilter();
    }

    private void applyFilter() {
        this.shown.clear();
        String f = this.filter.toLowerCase(Locale.ROOT);
        for (Entry e : this.files) {
            if (f.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(f)) {
                this.shown.add(e);
            }
        }
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
    }

    private int visibleRows() {
        return Math.max(1, (this.listY1 - this.listY0) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, this.shown.size() - visibleRows());
    }

    private void setStatus(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    @Override
    protected void init() {
        if (this.files.isEmpty()) {
            scan();
        }
        int w = Math.min(340, this.width - 24);
        int h = Math.min(this.height - 16, 300);
        this.panelX0 = this.width / 2 - w / 2;
        this.panelX1 = this.panelX0 + w;
        this.panelY0 = this.height / 2 - h / 2;
        this.panelY1 = this.panelY0 + h;
        int x = this.panelX0 + PAD;
        int inner = w - 2 * PAD;

        int y = this.panelY0 + Theme.HEADER_H + 6;
        this.search = new EditBox(this.font, x + 14, y + 2, inner - 16, 10, Component.literal("Search"));
        this.search.setBordered(false);
        this.search.setValue(this.filter);
        this.search.setHint(Component.literal("Search schematics...").withStyle(s -> s.withColor(Theme.TEXT_FAINT & 0xFFFFFF)));
        this.search.setResponder(text -> {
            this.filter = text;
            this.scroll = 0;
            applyFilter();
        });
        addRenderableWidget(this.search);

        this.listY0 = y + 18;
        this.listY1 = this.panelY1 - PAD - ROW_H - 16;
        applyFilter();

        int by = this.panelY1 - PAD - ROW_H;
        addRenderableWidget(new RtsButton(x, by, 92, ROW_H, "Open folder",
                () -> Util.getPlatform().openFile(folder().toFile())).glyph(Glyphs.FOLDER, Theme.GOLD).left());
        addRenderableWidget(new RtsButton(x + 96, by, 70, ROW_H, "Refresh", () -> {
            scan();
            setStatus("Found " + this.files.size() + " file(s)", Theme.TEXT_DIM);
        }).glyph(Glyphs.ROTATE, Theme.ACCENT).left());
        addRenderableWidget(new RtsButton(this.panelX1 - PAD - 60, by, 60, ROW_H, "Back", this::onClose));
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < this.panelX0 + PAD || mouseX >= this.panelX1 - PAD - 4 || mouseY < this.listY0 || mouseY >= this.listY1) {
            return -1;
        }
        int i = this.scroll + (int) ((mouseY - this.listY0) / ROW_H);
        return i < this.shown.size() && (int) ((mouseY - this.listY0) / ROW_H) < visibleRows() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int row = rowAt(mouseX, mouseY);
        if (button == 0 && row >= 0 && !this.loading) {
            load(this.shown.get(row).path());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll - (int) Math.signum(scrollY) * 3));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.shown.size() == 1 && !this.loading) {
            load(this.shown.get(0).path());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void load(Path file) {
        if (this.loading) {
            return;
        }
        this.loading = true;
        setStatus("Loading " + file.getFileName() + "...", Theme.GOLD);
        int limit = RtsConfig.get().maxShapeBlocks;
        CompletableFuture.supplyAsync(() -> {
            try {
                return SchematicLoader.load(file, limit);
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }).whenComplete((bp, error) -> this.minecraft.execute(() -> {
            this.loading = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                RtsClientMod.LOGGER.warn("Loading schematic {} failed", file, cause);
                setStatus("Couldn't load: " + cause.getMessage(), Theme.BAD);
                return;
            }
            BuildSession s = RtsController.get().session();
            s.tool = ToolMode.BLUEPRINT;
            s.clipboard = bp;
            s.pasteTurns = 0;
            s.pasteMirror = false;
            s.pasteAnchor = null;
            s.clearSelection();
            Messages.success("Loaded " + bp.name() + " (" + bp.size(0) + ", " + String.format(Locale.ROOT, "%,d", bp.blockCount())
                    + " blocks) - click to position it, Enter to build");
            onClose();
        }));
    }

    /**
     * Screen.render() calls this before drawing the widgets. A flat dim (instead of the 1.21 blur), then the
     * panel, search box frame and file list on top of it, so the widgets end up on top: dim, panel, widgets.
     */
    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        Theme.panel(ctx, this.font, this.panelX0, this.panelY0, this.panelX1, this.panelY1, "LOAD SCHEMATIC");
        int x0 = this.panelX0 + PAD;
        int x1 = this.panelX1 - PAD;

        // Search box
        int sy = this.panelY0 + Theme.HEADER_H + 6;
        Theme.round(ctx, x0, sy - 1, x1 - x0, 13, Theme.SLOT);
        Theme.outline(ctx, x0, sy - 1, x1 - x0, 13, this.search.isFocused() ? Theme.withAlpha(Theme.ACCENT, 0.7F) : Theme.SLOT_EDGE);
        Glyphs.EYE.draw(ctx, x0 + 4, sy + 2, Theme.TEXT_FAINT);

        // File list
        ctx.fill(x0, this.listY0, x1, this.listY1, 0x40000000);
        if (this.shown.isEmpty()) {
            String msg = this.files.isEmpty() ? "No .litematic / .schem / .nbt files yet" : "Nothing matches \"" + this.filter + "\"";
            ctx.drawCenteredString(this.font, msg, this.width / 2, this.listY0 + 10, Theme.TEXT_DIM);
            if (this.files.isEmpty()) {
                String where = folder().toString();
                ctx.drawCenteredString(this.font, this.font.plainSubstrByWidth("Put them in " + where, x1 - x0 - 8),
                        this.width / 2, this.listY0 + 24, Theme.TEXT_FAINT);
            }
        }
        int hovered = rowAt(mouseX, mouseY);
        int rows = visibleRows();
        for (int r = 0; r < rows && this.scroll + r < this.shown.size(); r++) {
            Entry e = this.shown.get(this.scroll + r);
            int y = this.listY0 + r * ROW_H;
            boolean hover = this.scroll + r == hovered && !this.loading;
            if (hover) {
                Theme.round(ctx, x0 + 1, y + 1, x1 - x0 - 6, ROW_H - 2, Theme.BTN_HOVER);
            }
            int typeColor = switch (e.type()) {
                case "litematic" -> Theme.ACCENT;
                case "schem", "schematic" -> Theme.GOLD;
                default -> Theme.GOOD;
            };
            Glyphs.BOX.draw(ctx, x0 + 5, y + (ROW_H - Glyphs.SIZE) / 2, typeColor);
            String size = e.kb() + " KB";
            int sizeW = this.font.width(size);
            int typeW = this.font.width(e.type());
            int nameRoom = x1 - x0 - 30 - sizeW - typeW - 16;
            String name = this.font.width(e.name()) > nameRoom
                    ? this.font.plainSubstrByWidth(e.name(), Math.max(0, nameRoom - 6)) + "..." : e.name();
            ctx.drawString(this.font, name, x0 + 16, y + 5, hover ? 0xFFFFFFFF : Theme.TEXT, false);
            ctx.drawString(this.font, e.type(), x1 - 10 - sizeW - typeW - 8, y + 5, typeColor, false);
            ctx.drawString(this.font, size, x1 - 10 - sizeW, y + 5, Theme.TEXT_FAINT, false);
        }
        // Scrollbar
        if (this.shown.size() > rows) {
            int trackH = this.listY1 - this.listY0;
            int thumbH = Math.max(10, trackH * rows / this.shown.size());
            int thumbY = this.listY0 + (trackH - thumbH) * this.scroll / Math.max(1, maxScroll());
            ctx.fill(x1 - 3, this.listY0, x1 - 1, this.listY1, 0x40FFFFFF);
            ctx.fill(x1 - 3, thumbY, x1 - 1, thumbY + thumbH, Theme.ACCENT);
        }

        String footer = this.status.isEmpty()
                ? this.shown.size() + " of " + this.files.size() + " file(s) - click one to load"
                : this.status;
        ctx.drawString(this.font, this.font.plainSubstrByWidth(footer, x1 - x0), x0, this.listY1 + 4,
                this.status.isEmpty() ? Theme.TEXT_FAINT : this.statusColor, false);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // renderBackground() (dim + panel + list), then the widgets.
        super.render(ctx, mouseX, mouseY, delta);
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
