package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.SchematicLoader;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

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
    private static final int ROW_H = 20;

    private final Screen parent;
    private final List<Path> files = new ArrayList<>();
    private int page;
    private boolean loading;
    private String status = "";

    public SchematicBrowserScreen(Screen parent) {
        super(Text.literal("Load schematic"));
        this.parent = parent;
    }

    public static Path folder() {
        return net.minecraft.client.MinecraftClient.getInstance().runDirectory.toPath().resolve("schematics");
    }

    private void scan() {
        this.files.clear();
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> walk = Files.walk(dir, 4)) {
                walk.filter(Files::isRegularFile).filter(SchematicLoader::supported)
                        .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString()))
                        .forEach(this.files::add);
            }
        } catch (IOException e) {
            this.status = "Couldn't read " + dir + ": " + e.getMessage();
        }
    }

    private int rowsPerPage() {
        return Math.max(3, (this.height - 110) / ROW_H);
    }

    @Override
    protected void init() {
        if (this.files.isEmpty()) {
            scan();
        }
        int w = Math.min(320, this.width - 40);
        int x = this.width / 2 - w / 2;
        int y = 36;
        int perPage = rowsPerPage();
        int pages = Math.max(1, (this.files.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pages - 1);
        Path dir = folder();
        for (int i = this.page * perPage; i < Math.min(this.files.size(), (this.page + 1) * perPage); i++) {
            Path file = this.files.get(i);
            String label = dir.relativize(file).toString();
            long kb = 0;
            try {
                kb = Files.size(file) / 1024;
            } catch (IOException ignored) {
                // size is only informational
            }
            ButtonWidget b = ButtonWidget.builder(Text.literal(label), btn -> load(file))
                    .dimensions(x, y, w, ROW_H - 2)
                    .tooltip(Tooltip.of(Text.literal(label + " (" + kb + " KB)")))
                    .build();
            b.active = !this.loading;
            addDrawableChild(b);
            y += ROW_H;
        }
        int by = this.height - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            this.page = Math.max(0, this.page - 1);
            clearAndInit();
        }).dimensions(x, by, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            this.page = Math.min(pages - 1, this.page + 1);
            clearAndInit();
        }).dimensions(x + 22, by, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open folder"), b -> Util.getOperatingSystem().open(folder().toFile()))
                .dimensions(x + 46, by, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
            scan();
            clearAndInit();
        }).dimensions(x + 130, by, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(x + w - 60, by, 60, 20).build());
    }

    private void load(Path file) {
        if (this.loading) {
            return;
        }
        this.loading = true;
        this.status = "Loading " + file.getFileName() + "...";
        clearAndInit();
        int limit = RtsConfig.get().maxShapeBlocks;
        CompletableFuture.supplyAsync(() -> {
            try {
                return SchematicLoader.load(file, limit);
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }).whenComplete((bp, error) -> this.client.execute(() -> {
            this.loading = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                RtsClientMod.LOGGER.warn("Loading schematic {} failed", file, cause);
                this.status = "Couldn't load: " + cause.getMessage();
                clearAndInit();
                return;
            }
            BuildSession s = RtsController.get().session();
            s.tool = ToolMode.BLUEPRINT;
            s.clipboard = bp;
            s.pasteTurns = 0;
            s.pasteAnchor = null;
            s.clearSelection();
            Messages.success("Loaded " + bp.name() + " (" + bp.size(0) + ", " + String.format(Locale.ROOT, "%,d", bp.blockCount())
                    + " blocks) - click to position it, Enter to build");
            close();
        }));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        String sub = this.files.isEmpty()
                ? "No .litematic / .schem / .nbt files in " + folder()
                : this.files.size() + " file(s) in .minecraft/schematics - page " + (this.page + 1);
        ctx.drawCenteredTextWithShadow(this.textRenderer, sub, this.width / 2, 22, 0xA0A0A0);
        if (!this.status.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, this.status, this.width / 2, this.height - 44, 0xFFD54F);
        }
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
