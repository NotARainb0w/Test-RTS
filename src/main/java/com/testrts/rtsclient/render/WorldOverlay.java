package com.testrts.rtsclient.render;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.OrderPlanner;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.NativeEngine;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.MoveTask;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Everything drawn in the world while RTS mode is on: previews, orders, storage, cursor, the player. */
public final class WorldOverlay {
    private static final PreviewMesh SHAPE_GHOST = new PreviewMesh(PreviewMesh.Style.GHOST);
    private static final PreviewMesh SHAPE_CLEAR = new PreviewMesh(PreviewMesh.Style.CLEAR);
    private static final PreviewMesh PASTE = new PreviewMesh(PreviewMesh.Style.GHOST);
    private static final PreviewMesh TERRAIN_CLEAR = new PreviewMesh(PreviewMesh.Style.CLEAR);
    private static final PreviewMesh TERRAIN_FILL = new PreviewMesh(PreviewMesh.Style.GHOST);
    /** Blend brush: the ground painted so far. */
    private static final PreviewMesh BLEND_PAINT = new PreviewMesh(PreviewMesh.Style.SELECT);
    private static final Map<BuildTask, PreviewMesh> TASK_MESHES = new IdentityHashMap<>();
    private static final Map<BuildTask, Long> TASK_REFRESH = new IdentityHashMap<>();
    /** Glow layer: the layer of each build hold-Z places next, drawn over its ghosts (outside RTS mode). */
    private static final Map<BuildTask, PreviewMesh> GLOW_MESHES = new IdentityHashMap<>();
    /** Clear orders are re-meshed now and then so mined blocks drop out of the red preview. */
    private static final long CLEAR_REFRESH_MS = 2500;
    private static final int CLEAR_REFRESH_MAX = 60_000;
    /** Build orders: how often placed blocks are taken out of the ghost. */
    private static final long BUILD_REFRESH_MS = 1000;
    /** One layer (hologram outside RTS mode, Glow layer): cheap to rebuild, so placed blocks drop out quickly. */
    private static final long LAYER_REFRESH_MS = 150;

    private WorldOverlay() {
    }

    /** Outside RTS mode: keep preview uploads moving (the preview worker waits for them), draw nothing. */
    public static void idle() {
        PreviewMesh.pump();
        closeTaskMeshes(List.of());
    }

    /**
     * Outside RTS mode, with the hologram on: just the ghosts of queued builds, to place blocks by hand.
     * Only our own preview meshes are drawn (with the given matrices); nothing else of the render state
     * or the shared buffers is touched.
     */
    public static void hologram(MatrixStack matrices, Matrix4f projection, Vec3d cam) {
        PreviewMesh.pump();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            closeTaskMeshes(List.of());
            return;
        }
        drawTaskMeshes(client, matrices, projection, cam, RtsConfig.get().autoPlaceLayers);
    }

    /** Whether there's anything for {@link #hologram} to show. */
    public static boolean hasHologram() {
        if (!RtsConfig.get().showHologram) {
            return false;
        }
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask) {
                return true;
            }
        }
        return false;
    }

    /** Per build: whether its mesh shows one layer (hologram outside RTS mode) or all of it. */
    private static final Map<BuildTask, Boolean> TASK_BY_LAYER = new java.util.HashMap<>();

    /**
     * Ghosts of queued build (green) and clear (red) orders; done blocks drop out every second or so.
     * {@code byLayer}: builds show only the layer hold-Z is working on (Hold-Z in layers, outside RTS mode).
     */
    private static void drawTaskMeshes(MinecraftClient client, MatrixStack matrices, Matrix4f projection, Vec3d cam, boolean byLayer) {
        List<BuildTask> builds = new ArrayList<>();
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build) {
                builds.add(build);
            }
        }
        closeTaskMeshes(builds);
        long now = System.currentTimeMillis();
        for (BuildTask build : builds) {
            PreviewMesh mesh = TASK_MESHES.computeIfAbsent(build,
                    b -> new PreviewMesh(b.clearing() ? PreviewMesh.Style.CLEAR : PreviewMesh.Style.GHOST));
            boolean layer = byLayer && !build.clearing();
            boolean big = build.blocks().size() > CLEAR_REFRESH_MAX;
            if (!big || layer) {
                long last = TASK_REFRESH.getOrDefault(build, 0L);
                // Switching between one layer and the whole build shows right away.
                if (TASK_BY_LAYER.put(build, layer) != Boolean.valueOf(layer)) {
                    last = 0;
                }
                if (layer && last < pokedAt || now - last > (build.clearing() ? CLEAR_REFRESH_MS
                        : layer ? (big ? 4 * LAYER_REFRESH_MS : LAYER_REFRESH_MS) : big ? 3 * BUILD_REFRESH_MS : BUILD_REFRESH_MS)) {
                    TASK_REFRESH.put(build, now);
                    if (build.clearing()) {
                        List<BlockPos> left = new ArrayList<>();
                        for (BlockPos p : build.blocks().keySet()) {
                            if (!client.world.getBlockState(p).isAir()) {
                                left.add(p);
                            }
                        }
                        mesh.request(List.of(build, left.size()),
                                () -> PreviewMesh.Blocks.of(left, net.minecraft.block.Blocks.AIR.getDefaultState(), false), true);
                    } else {
                        // Only what's still to place: blocks placed by the builder or by hand drop out. By layer:
                        // only the layer hold-Z is on, so the hologram shows where it will place next.
                        int y = layer ? com.testrts.rtsclient.exec.AutoPlace.lowestUnfinished(client.world, build) : 0;
                        Map<BlockPos, net.minecraft.block.BlockState> left = new java.util.LinkedHashMap<>();
                        for (Map.Entry<BlockPos, net.minecraft.block.BlockState> e : build.blocks().entrySet()) {
                            if ((!layer || e.getKey().getY() == y)
                                    && !com.testrts.rtsclient.task.MaterialPlanner.matches(client.world.getBlockState(e.getKey()), e.getValue())) {
                                left.put(e.getKey(), e.getValue());
                            }
                        }
                        mesh.request(List.of(build, left.size(), layer ? y : Integer.MIN_VALUE, build.version()), () -> new PreviewMesh.Blocks(left, false), true);
                        if (layer && RtsConfig.get().glowLayer) {
                            GLOW_MESHES.computeIfAbsent(build, k -> new PreviewMesh(PreviewMesh.Style.GLOW))
                                    .request(List.of(build, left.size(), y, build.version()), () -> new PreviewMesh.Blocks(left, false), true);
                        }
                    }
                }
            } else {
                mesh.request(build, () -> new PreviewMesh.Blocks(build.blocks(), false), true);
            }
            mesh.draw(matrices, projection, cam);
            PreviewMesh glow = GLOW_MESHES.get(build);
            if (glow != null) {
                if (byLayer && !build.clearing() && RtsConfig.get().glowLayer) {
                    glow.draw(matrices, projection, cam);
                } else {
                    glow.close();
                    GLOW_MESHES.remove(build);
                }
            }
        }
    }

    public static void render(WorldRenderContext context) {
        // Uploads must run even when RTS is off, so the preview worker never waits forever.
        PreviewMesh.pump();
        RtsController controller = RtsController.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!controller.isActive() || client.world == null || client.player == null) {
            if (!controller.isActive() && hasHologram() && client.world != null && client.player != null) {
                drawTaskMeshes(client, context.matrixStack(), context.projectionMatrix(), context.camera().getPos(),
                        RtsConfig.get().autoPlaceLayers);
                return;
            }
            closeTaskMeshes(List.of());
            return;
        }
        MatrixStack matrices = context.matrixStack();
        Matrix4f projection = context.projectionMatrix();
        Vec3d cam = context.camera().getPos();
        BuildSession session = controller.session();
        BlockHitResult hover = controller.hover();

        // ---- meshes (GPU buffers) ----
        drawTaskMeshes(client, matrices, projection, cam, false);

        boolean showShape = session.tool.usesShapes() && session.anchorA != null;
        if (showShape) {
            BlockPos b = session.anchorB != null ? session.anchorB : hover != null ? targetFor(session, hover) : session.anchorA;
            requestShapePreview(session, session.anchorA, b, controller.camera().yaw());
            if (session.tool == ToolMode.DESTROY) {
                // The whole shape: glass where it's empty, red over what will be mined.
                SHAPE_GHOST.draw(matrices, projection, cam);
                SHAPE_CLEAR.draw(matrices, projection, cam);
            } else {
                SHAPE_CLEAR.clear();
                SHAPE_GHOST.draw(matrices, projection, cam);
            }
        } else {
            SHAPE_GHOST.clear();
            SHAPE_CLEAR.clear();
        }

        if (session.tool == ToolMode.BLUEPRINT && session.clipboard != null && (hover != null || session.pasteAnchor != null)) {
            BlockPos origin = session.pasteAnchor != null ? session.pasteAnchor
                    : session.clipboard.originFor(targetFor(session, hover), session.pasteTurns);
            var bp = session.clipboard;
            // Not keyed on the position: moving the paste only shifts the mesh (big schematics stay visible
            // and follow the cursor at once); it's built again only for a turn, mirror or array change.
            List<Object> key = List.of(bp, session.pasteTurns, session.pasteMirror, session.arrayX, session.arrayZ, session.arrayGap);
            if (!PASTE.isRequested(key)) {
                // Copy the settings now; the mesh is built on the worker thread.
                com.testrts.rtsclient.build.BuildSession snap = new com.testrts.rtsclient.build.BuildSession();
                snap.pasteTurns = session.pasteTurns;
                snap.pasteMirror = session.pasteMirror;
                snap.arrayX = session.arrayX;
                snap.arrayZ = session.arrayZ;
                snap.arrayGap = session.arrayGap;
                PASTE.request(key, () -> new PreviewMesh.Blocks(
                        com.testrts.rtsclient.screen.WorldActions.pasteBlocks(snap, bp, origin), false), true, origin);
            }
            BlockPos built = PASTE.shownAnchor();
            if (built != null) {
                PASTE.setShift(origin.getX() - built.getX(), origin.getY() - built.getY(), origin.getZ() - built.getZ());
            }
            PASTE.draw(matrices, projection, cam);
        } else {
            PASTE.clear();
        }

        // Blend brush: the painted ground, and what blending it will mine (red) and fill.
        if (session.tool == ToolMode.TERRAIN && session.terrainMode.brush()) {
            if (session.blendPaint.isEmpty()) {
                BLEND_PAINT.clear();
                TERRAIN_CLEAR.clear();
                TERRAIN_FILL.clear();
            } else {
                var world = client.world;
                Object key = brushKey(session);
                long sinceChange = System.currentTimeMillis() - session.blendChangedAt;
                List<Object> paintKey = List.of("blend-paint", session.blendVersion);
                if (sinceChange > 60 && !BLEND_PAINT.isRequested(paintKey)) {
                    Map<Long, Integer> paint = new java.util.HashMap<>(session.blendPaint);
                    BLEND_PAINT.request(paintKey, () -> new PreviewMesh.Blocks(
                            com.testrts.rtsclient.build.TerrainBlend.surface(world, paint), false), true);
                }
                // Plan once the stroke pauses: planning (and meshing the result) on every mouse move while
                // dragging kept the worker and the uploads busy and dropped frames.
                if (sinceChange > 250 && !TERRAIN_CLEAR.isRequested(key)) {
                    // Copy the paint now; plans are made on the worker thread.
                    Map<Long, Integer> paint = new java.util.HashMap<>(session.blendPaint);
                    var mode = session.terrainMode;
                    int strength = session.blendStrength;
                    int levelY = session.levelY;
                    int ramp = session.levelRamp;
                    boolean keepTop = session.blendKeepTop;
                    int limit = RtsConfig.get().maxShapeBlocks;
                    java.util.function.Supplier<com.testrts.rtsclient.build.TerrainBlend.Plan> plan =
                            () -> blendPlan(key, () -> com.testrts.rtsclient.screen.WorldActions.brushPlan(
                                    world, paint, mode, strength, levelY, ramp, keepTop, limit));
                    TERRAIN_CLEAR.request(key, () -> new PreviewMesh.Blocks(plan.get().clear(), plan.get().truncated()), true);
                    TERRAIN_FILL.request(key, () -> new PreviewMesh.Blocks(plan.get().fill(), plan.get().truncated()), true);
                }
                BLEND_PAINT.draw(matrices, projection, cam);
                TERRAIN_CLEAR.draw(matrices, projection, cam);
                TERRAIN_FILL.draw(matrices, projection, cam);
            }
        } else if (session.tool == ToolMode.TERRAIN && session.anchorA != null && (session.anchorB != null || hover != null)) {
            BlockPos a = session.anchorA;
            BlockPos b = session.anchorB != null ? session.anchorB : hover.getBlockPos();
            var world = client.world;
            if (session.terrainMode == com.testrts.rtsclient.build.TerrainMode.FLATTEN) {
                BlockState fill = com.testrts.rtsclient.screen.WorldActions.flattenFill(session);
                int height = session.terrainClearHeight;
                int limit = RtsConfig.get().maxShapeBlocks;
                TERRAIN_CLEAR.request(List.of("clear", a, b, height), () -> {
                    var plan = com.testrts.rtsclient.build.TerrainPlanner.flatten(world, a, b, fill, height, limit);
                    return new PreviewMesh.Blocks(plan.clear(), plan.truncated());
                }, true);
                TERRAIN_FILL.request(List.of("fill", a, b, height, fill), () -> {
                    var plan = com.testrts.rtsclient.build.TerrainPlanner.flatten(world, a, b, fill, height, limit);
                    return new PreviewMesh.Blocks(plan.fill(), plan.truncated());
                }, true);
                TERRAIN_CLEAR.draw(matrices, projection, cam);
            } else if (session.terrainMode == com.testrts.rtsclient.build.TerrainMode.LIGHT_UP) {
                TERRAIN_CLEAR.clear();
                TERRAIN_FILL.request(List.of("light", a, b), () -> PreviewMesh.Blocks.of(
                        com.testrts.rtsclient.build.TerrainPlanner.lightUp(world, a, b),
                        net.minecraft.block.Blocks.TORCH.getDefaultState(), false), true);
            } else if (com.testrts.rtsclient.build.TerrainShapes.planned(session.terrainMode)) {
                var mode = session.terrainMode;
                int size = session.terrainSize;
                BlockState brush = com.testrts.rtsclient.screen.WorldActions.terrainBrush(session);
                java.util.function.Supplier<com.testrts.rtsclient.build.TerrainShapes.Plan> plan = () ->
                        com.testrts.rtsclient.build.TerrainShapes.plan(mode, world, a, b, size, brush);
                TERRAIN_CLEAR.request(List.of("t-clear", mode, a, b, size, brush), () -> new PreviewMesh.Blocks(plan.get().clear(), false), true);
                TERRAIN_FILL.request(List.of("t-fill", mode, a, b, size, brush), () -> {
                    var p = plan.get();
                    java.util.Map<BlockPos, BlockState> show = new java.util.LinkedHashMap<>(p.fill());
                    // Water to be poured shows as glass-blue water.
                    p.water().forEach(w -> show.putIfAbsent(w, net.minecraft.block.Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState()));
                    return new PreviewMesh.Blocks(show, false);
                }, true);
                TERRAIN_CLEAR.draw(matrices, projection, cam);
            } else if (session.terrainMode == com.testrts.rtsclient.build.TerrainMode.ISLAND_BRIDGE) {
                TERRAIN_CLEAR.clear();
                TERRAIN_FILL.request(List.of("t-bridge", a, b, session.terrainSize, session.bridgeStyle), () -> {
                    BlockPos[] ends = com.testrts.rtsclient.build.TerrainShapes.islandEdges(world, a, b);
                    var p = com.testrts.rtsclient.build.OrderPlanner.plan(world,
                            com.testrts.rtsclient.screen.WorldActions.islandBridgeRequest(session, ends[0], ends[1]));
                    return new PreviewMesh.Blocks(p.changes(), p.truncated());
                }, true);
            } else {
                // Quarry, tree felling, harvesting and restyling only show the area box.
                TERRAIN_CLEAR.clear();
                TERRAIN_FILL.clear();
            }
            TERRAIN_FILL.draw(matrices, projection, cam);
            BLEND_PAINT.clear();
        } else {
            TERRAIN_CLEAR.clear();
            TERRAIN_FILL.clear();
            BLEND_PAINT.clear();
        }

        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        renderCursorBlock(client, session, hover, matrices, cam, immediate, controller.camera().yaw());

        // ---- lines (immediate) ----
        VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        // Linked storage: gold, brighter in Link mode.
        float storageAlpha = session.tool == ToolMode.LINK ? 1.0F : 0.45F;
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            BlockPos p = c.pos();
            if (p.getSquaredDistance(cam.x, cam.y, cam.z) > 160 * 160) {
                continue;
            }
            Box box = new Box(p).expand(0.02);
            for (BlockPos n : new BlockPos[]{p.north(), p.south(), p.east(), p.west()}) {
                if (client.world.isChunkLoaded(n) && StorageManager.canonical(client.world, n).equals(p) && !n.equals(p)) {
                    box = box.union(new Box(n).expand(0.02));
                }
            }
            WorldRenderer.drawBox(matrices, lines, box, 1.0F, c.indexed() ? 0.75F : 0.5F, 0.1F, storageAlpha);
        }
        // Protected areas: red columns around the camera's height (they go from the bottom of the world to the top).
        float protectAlpha = session.tool == ToolMode.PROTECT ? 0.9F : 0.35F;
        for (com.testrts.rtsclient.build.Protection.Area area : com.testrts.rtsclient.build.Protection.get().areas()) {
            double cx = Math.max(area.minX(), Math.min(cam.x, area.maxX() + 1));
            double cz = Math.max(area.minZ(), Math.min(cam.z, area.maxZ() + 1));
            if ((cx - cam.x) * (cx - cam.x) + (cz - cam.z) * (cz - cam.z) > 200 * 200) {
                continue;
            }
            int y0 = (int) Math.floor(cam.y) - 48;
            Box box = new Box(area.minX(), y0, area.minZ(), area.maxX() + 1, y0 + 96, area.maxZ() + 1);
            WorldRenderer.drawBox(matrices, lines, box, 1.0F, 0.25F, 0.25F, protectAlpha);
        }
        // Linked storage-network terminals: AE2 ones as a plate on the terminal's face (cyan), RS grids as
        // the whole block (orange).
        for (com.testrts.rtsclient.storage.MeNetwork network : com.testrts.rtsclient.storage.MeNetwork.all()) {
            int rgb = network.mod().color();
            float r = (rgb >> 16 & 255) / 255.0F;
            float g = (rgb >> 8 & 255) / 255.0F;
            float bl = (rgb & 255) / 255.0F;
            boolean plateOnFace = network.mod() == com.testrts.rtsclient.storage.Ae2.GRID;
            for (com.testrts.rtsclient.storage.MeNetwork.Terminal t : network.terminals()) {
                BlockPos p = t.pos();
                if (p.getSquaredDistance(cam.x, cam.y, cam.z) > 160 * 160) {
                    continue;
                }
                Box plate = new Box(p).expand(0.03);
                if (plateOnFace) {
                    net.minecraft.util.math.Direction d = t.side();
                    double inset = 0.85;
                    plate = new Box(p).contract(0.1).expand(0.02).shrink(-d.getOffsetX() * inset, -d.getOffsetY() * inset, -d.getOffsetZ() * inset);
                }
                WorldRenderer.drawBox(matrices, lines, plate, r, g, bl, storageAlpha);
            }
        }

        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.blocks().isEmpty()) {
                if (build.clearing()) {
                    WorldRenderer.drawBox(matrices, lines, build.bounds().expand(0.03), 1.0F, 0.3F, 0.3F, 0.5F);
                } else {
                    WorldRenderer.drawBox(matrices, lines, build.bounds().expand(0.03), 0.3F, 1.0F, 0.4F, 0.5F);
                }
            } else if (task instanceof MoveTask move) {
                BlockPos t = move.target();
                WorldRenderer.drawBox(matrices, lines, new Box(t.getX() + 0.2, t.getY(), t.getZ() + 0.2,
                        t.getX() + 0.8, t.getY() + 0.1, t.getZ() + 0.8), 0.4F, 0.8F, 1.0F, 1.0F);
            }
        }

        // Built-in engine path.
        List<BlockPos> path = NativeEngine.get().currentPath();
        if (path != null) {
            for (BlockPos p : path) {
                WorldRenderer.drawBox(matrices, lines, new Box(p.getX() + 0.35, p.getY(), p.getZ() + 0.35,
                        p.getX() + 0.65, p.getY() + 0.08, p.getZ() + 0.65), 0.4F, 0.8F, 1.0F, 0.9F);
            }
        }

        // Shape outline and anchors.
        if (showShape) {
            PreviewMesh mesh = session.tool == ToolMode.DESTROY ? SHAPE_CLEAR : SHAPE_GHOST;
            if (mesh.truncated() && mesh.bounds() != null) {
                WorldRenderer.drawBox(matrices, lines, mesh.bounds(), 1.0F, 0.2F, 0.2F, 1.0F);
            }
            WorldRenderer.drawBox(matrices, lines, new Box(session.anchorA).expand(0.06), 1.0F, 1.0F, 0.2F, 1.0F);
            if (session.anchorB != null) {
                WorldRenderer.drawBox(matrices, lines, new Box(session.anchorB).expand(0.06), 1.0F, 0.85F, 0.2F, 1.0F);
            }
        }
        boolean regionTool = session.tool == ToolMode.BLUEPRINT && session.clipboard == null
                || session.tool == ToolMode.TERRAIN || session.tool == ToolMode.MEASURE;
        if (regionTool && session.anchorA != null) {
            BlockPos a = session.anchorA;
            BlockPos b = session.anchorB != null ? session.anchorB : hover != null ? hover.getBlockPos() : a;
            Box sel = new Box(a).union(new Box(b)).expand(0.02);
            float[] c = session.tool == ToolMode.MEASURE ? new float[]{0.85F, 0.55F, 1.0F}
                    : session.tool == ToolMode.TERRAIN ? new float[]{0.55F, 0.85F, 0.35F} : new float[]{1.0F, 0.84F, 0.3F};
            WorldRenderer.drawBox(matrices, lines, sel, c[0], c[1], c[2], 1.0F);
            WorldRenderer.drawBox(matrices, lines, new Box(a).expand(0.06), 1.0F, 1.0F, 0.2F, 1.0F);
            if (session.tool == ToolMode.MEASURE) {
                // The centre block: one block on an odd side, the middle two on an even side.
                int sx = a.getX() + b.getX();
                int sy = a.getY() + b.getY();
                int sz = a.getZ() + b.getZ();
                Box mid = new Box(Math.floorDiv(sx, 2), Math.floorDiv(sy, 2), Math.floorDiv(sz, 2),
                        Math.floorDiv(sx + 1, 2) + 1, Math.floorDiv(sy + 1, 2) + 1, Math.floorDiv(sz + 1, 2) + 1);
                WorldRenderer.drawBox(matrices, lines, mid.expand(0.04), 1.0F, 1.0F, 1.0F, 1.0F);
                // A small dot at the exact centre point.
                double px = (sx + 1) / 2.0;
                double py = (sy + 1) / 2.0;
                double pz = (sz + 1) / 2.0;
                WorldRenderer.drawBox(matrices, lines, new Box(px - 0.1, py - 0.1, pz - 0.1, px + 0.1, py + 0.1, pz + 0.1),
                        0.85F, 0.55F, 1.0F, 1.0F);
            }
        }
        if (session.moving()) {
            // The building being moved: it comes down once the copy is placed.
            Box from = new Box(session.moveFrom).union(new Box(session.moveTo)).expand(0.04);
            float movePulse = (float) (0.5 - 0.5 * Math.cos((System.currentTimeMillis() % 1000) / 1000.0 * Math.PI * 2.0));
            WorldRenderer.drawBox(matrices, lines, from, 1.0F, 0.35F, 0.3F, 0.5F + 0.5F * movePulse);
        }
        if (session.tool == ToolMode.BLUEPRINT && session.clipboard != null && PASTE.bounds() != null) {
            WorldRenderer.drawBox(matrices, lines, PASTE.bounds().expand(0.03), 1.0F, 0.84F, 0.3F,
                    session.pasteAnchor != null ? 1.0F : 0.5F);
        }
        if (hover != null && !showShape) {
            BlockPos target = targetFor(session, hover);
            float[] c = switch (session.tool) {
                case BUILD -> new float[]{0.3F, 0.85F, 1.0F};
                case DESTROY -> new float[]{1.0F, 0.3F, 0.3F};
                case INTERACT -> new float[]{1.0F, 1.0F, 1.0F};
                case LINK -> new float[]{1.0F, 0.75F, 0.1F};
                case BLUEPRINT -> new float[]{1.0F, 0.84F, 0.3F};
                case TERRAIN -> new float[]{0.55F, 0.85F, 0.35F};
                case MEASURE -> new float[]{0.85F, 0.55F, 1.0F};
                case PROTECT -> new float[]{1.0F, 0.25F, 0.25F};
            };
            // Pulsing outline with a faint outer glow, so the cursor reads at a glance.
            float pulse = (float) (0.5 - 0.5 * Math.cos((System.currentTimeMillis() % 1200) / 1200.0 * Math.PI * 2.0));
            WorldRenderer.drawBox(matrices, lines, new Box(target).expand(0.01), c[0], c[1], c[2], 0.65F + 0.35F * pulse);
            WorldRenderer.drawBox(matrices, lines, new Box(target).expand(0.04 + 0.03 * pulse), c[0], c[1], c[2], 0.25F * (1.0F - pulse));
        }

        if (hover != null && session.tool == ToolMode.TERRAIN && session.terrainMode.brush()) {
            drawBrushRing(matrices, lines, hover.getBlockPos(), session.blendRadius);
        }

        if (session.airPlace && hover != null) {
            drawAirLayer(client, matrices, lines, targetFor(session, hover), session.airY);
        }

        // With the Link tool, label each linked container with what it mostly holds (drawn last:
        // text uses other buffers than the lines above).
        if (session.tool == ToolMode.LINK) {
            for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
                BlockPos p = c.pos();
                if (p.getSquaredDistance(cam.x, cam.y, cam.z) > 32 * 32) {
                    continue;
                }
                net.minecraft.item.Item item = StorageManager.get().labelFor(p);
                if (item == null) {
                    continue;
                }
                String text = item.getName().getString();
                matrices.push();
                matrices.translate(p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5);
                matrices.multiply(context.camera().getRotation());
                matrices.scale(-0.025F, -0.025F, 0.025F);
                float x = -client.textRenderer.getWidth(text) / 2.0F;
                client.textRenderer.draw(text, x, 0, 0xFFFFE08A, false, matrices.peek().getPositionMatrix(), immediate,
                        net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH, 0x80101820, LightmapTextureManager.MAX_LIGHT_COORDINATE);
                matrices.pop();
            }
        }

        matrices.pop();
        immediate.draw();
    }

    /** The Blend brush outline: the edge columns of the round brush, flat on top of the block under the cursor. */
    private static void drawBrushRing(MatrixStack matrices, VertexConsumer lines, BlockPos center, int radius) {
        double r2 = (radius + 0.5) * (radius + 0.5);
        double y = center.getY() + 1.02;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                boolean edge = (dx + 1) * (dx + 1) + dz * dz > r2 || (dx - 1) * (dx - 1) + dz * dz > r2
                        || dx * dx + (dz + 1) * (dz + 1) > r2 || dx * dx + (dz - 1) * (dz - 1) > r2;
                if (edge) {
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    WorldRenderer.drawBox(matrices, lines, new Box(x, y, z, x + 1, y, z + 1), 0.55F, 0.85F, 0.35F, 0.9F);
                }
            }
        }
    }

    /** A fading grid on the air layer around the cursor, and a drop line to the ground below it. */
    private static void drawAirLayer(MinecraftClient client, MatrixStack matrices, VertexConsumer lines, BlockPos target, int y) {
        if (target.getY() != y || client.world == null) {
            return;
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                float fade = 1.0F - Math.max(Math.abs(dx), Math.abs(dz)) / 4.0F;
                int x = target.getX() + dx;
                int z = target.getZ() + dz;
                WorldRenderer.drawBox(matrices, lines, new Box(x, y, z, x + 1, y, z + 1), 1.0F, 1.0F, 1.0F, 0.35F * fade);
            }
        }
        BlockPos.Mutable p = target.mutableCopy();
        int ground = y;
        for (int i = 0; i < 128 && p.getY() > client.world.getBottomY(); i++) {
            p.move(0, -1, 0);
            if (!client.world.getBlockState(p).isAir()) {
                ground = p.getY() + 1;
                break;
            }
        }
        if (ground < y) {
            double cx = target.getX() + 0.5;
            double cz = target.getZ() + 0.5;
            WorldRenderer.drawBox(matrices, lines, new Box(cx - 0.04, ground, cz - 0.04, cx + 0.04, y, cz + 0.04), 1.0F, 1.0F, 1.0F, 0.6F);
            WorldRenderer.drawBox(matrices, lines, new Box(cx - 0.3, ground, cz - 0.3, cx + 0.3, ground + 0.02, cz + 0.3), 1.0F, 1.0F, 1.0F, 0.6F);
        }
    }

    /**
     * The exact block the next click places, drawn under the cursor every frame (not baked), so
     * rotating it, changing a property or turning the camera shows up immediately.
     */
    private static void renderCursorBlock(MinecraftClient client, BuildSession session, BlockHitResult hover, MatrixStack matrices,
                                          Vec3d cam, VertexConsumerProvider.Immediate immediate, float yaw) {
        if (session.tool != ToolMode.BUILD || hover == null || client.world == null) {
            return;
        }
        if (session.anchorA != null && !session.shape.singleClick() || session.shape == com.testrts.rtsclient.build.BuildShape.VEIN) {
            return; // the shape preview shows it
        }
        BlockState state = session.brushState(yaw);
        if (state == null) {
            return;
        }
        BlockPos pos = session.mode.targetsExisting() ? hover.getBlockPos() : targetFor(session, hover);
        int light = WorldRenderer.getLightmapCoordinates(client.world, pos);
        int block = Math.max(LightmapTextureManager.getBlockLightCoordinates(light), 10);
        int sky = Math.max(LightmapTextureManager.getSkyLightCoordinates(light), 10);
        matrices.push();
        matrices.translate(pos.getX() - cam.x + 0.5, pos.getY() - cam.y + 0.5, pos.getZ() - cam.z + 0.5);
        // A hair bigger than a block, so it shows over the block it would replace.
        matrices.scale(1.002F, 1.002F, 1.002F);
        matrices.translate(-0.5, -0.5, -0.5);
        try {
            client.getBlockRenderManager().renderBlockAsEntity(state, matrices, immediate,
                    LightmapTextureManager.pack(block, sky), OverlayTexture.DEFAULT_UV);
        } catch (RuntimeException ignored) {
            // Some modded blocks can't be drawn outside the world; the outline still shows.
        }
        matrices.pop();
    }

    /** Forget all baked previews (after a setting that changes how they look). */
    public static void invalidate() {
        SHAPE_GHOST.clear();
        SHAPE_CLEAR.clear();
        PASTE.clear();
        TERRAIN_CLEAR.clear();
        TERRAIN_FILL.clear();
        BLEND_PAINT.clear();
        closeTaskMeshes(List.of());
    }

    private static void requestShapePreview(BuildSession s, BlockPos a, BlockPos b, float yaw) {
        MinecraftClient client = MinecraftClient.getInstance();
        OrderPlanner.Request request = OrderPlanner.request(s, a, b, yaw, RtsConfig.get().maxShapeBlocks);
        PreviewMesh mesh = s.tool == ToolMode.DESTROY ? SHAPE_CLEAR : SHAPE_GHOST;
        var world = client.world;
        // Planning reads the world; like vanilla's chunk builders, that's fine from the worker.
        mesh.request(request, () -> {
            OrderPlanner.Plan plan = OrderPlanner.plan(world, request);
            lastPlan = plan;
            return new PreviewMesh.Blocks(plan.changes(), plan.truncated());
        }, true);
        if (s.tool == ToolMode.DESTROY) {
            // Show the shape itself too, as glass in its empty cells, so it can be seen where there's
            // nothing (yet) to mine.
            SHAPE_GHOST.request(java.util.List.of("destroy-shape", request), () -> {
                var shape = com.testrts.rtsclient.build.ShapeGenerator.generate(request.shape(), request.a(), request.b(),
                        request.height(), request.hollow(), request.limit() + 1, request.inverted(), request.bridgeStyle(), request.islandStyle(), request.islandBottom(),
                        request.islandSeed(), request.mountainForm(),
                        request.treeKind(), request.rockForm(), request.rockLook(), request.mazeStyle(), request.mazeLayout(),
                        request.backroomsLevel());
                java.util.Map<BlockPos, net.minecraft.block.BlockState> glass = new java.util.LinkedHashMap<>();
                for (BlockPos p : shape.positions()) {
                    if (world.getBlockState(p).isAir()) {
                        glass.put(p, net.minecraft.block.Blocks.GLASS.getDefaultState());
                    }
                }
                return new PreviewMesh.Blocks(glass, shape.truncated());
            }, true);
        }
    }

    /** The most recent preview plan (sizes for the hint line). */
    private static volatile OrderPlanner.Plan lastPlan;

    public static OrderPlanner.Plan lastPlan() {
        return lastPlan;
    }

    /** Size of the shape preview, for the hint line: -1 while it's still being worked out. */
    public static int previewBlocks(BuildSession session) {
        PreviewMesh mesh = session.tool == ToolMode.DESTROY ? SHAPE_CLEAR : SHAPE_GHOST;
        if (!mesh.isReady()) {
            return -1;
        }
        return mesh.truncated() ? Integer.MAX_VALUE : mesh.blocks();
    }

    private static Object blendKey;
    private static com.testrts.rtsclient.build.TerrainBlend.Plan blendPlan;
    private static volatile Map<net.minecraft.item.Item, Integer> blendMaterials = Map.of();

    /** The blend plan for {@code key}, made once and shared by the clear and fill previews (worker thread). */
    private static synchronized com.testrts.rtsclient.build.TerrainBlend.Plan blendPlan(
            Object key, java.util.function.Supplier<com.testrts.rtsclient.build.TerrainBlend.Plan> make) {
        if (!key.equals(blendKey)) {
            blendPlan = make.get();
            blendKey = key;
            Map<net.minecraft.item.Item, Integer> need = new java.util.LinkedHashMap<>();
            for (BlockState st : blendPlan.fill().values()) {
                need.merge(st.getBlock().asItem(), 1, Integer::sum);
            }
            blendMaterials = need;
        }
        return blendPlan;
    }

    /** What the brush plan depends on: when this is unchanged, the previewed plan is still right. */
    public static Object brushKey(com.testrts.rtsclient.build.BuildSession s) {
        return List.of("blend", s.blendVersion, s.terrainMode, s.blendStrength, s.levelY, s.levelRamp, s.blendKeepTop);
    }

    /** The previewed brush plan if it was made for {@code key} (so Enter needn't plan again), else null. */
    public static synchronized com.testrts.rtsclient.build.TerrainBlend.Plan cachedBrushPlan(Object key) {
        return key.equals(blendKey) ? blendPlan : null;
    }

    /** Blocks the previewed blend would place, by item (for the materials line). */
    public static Map<net.minecraft.item.Item, Integer> blendMaterials() {
        return blendMaterials;
    }

    /** Blocks to clear and to fill in the terrain preview; -1 while still being worked out. */
    public static int[] terrainCounts() {
        return new int[]{
                TERRAIN_CLEAR.isReady() ? TERRAIN_CLEAR.blocks() : -1,
                TERRAIN_FILL.isReady() ? TERRAIN_FILL.blocks() : -1
        };
    }

    /** When a block was last placed by us (see {@link #poke}). */
    private static volatile long pokedAt;

    /**
     * A block was just placed: one-layer ghosts (and the glowing layer) are rebuilt on the next frame. Whole
     * builds keep their own pace; rebuilding those after every block would cost too much.
     */
    public static void poke() {
        pokedAt = System.currentTimeMillis();
    }

    private static void closeTaskMeshes(List<BuildTask> keep) {
        GLOW_MESHES.entrySet().removeIf(e -> {
            if (!keep.contains(e.getKey())) {
                e.getValue().close();
                return true;
            }
            return false;
        });
        TASK_MESHES.entrySet().removeIf(e -> {
            if (!keep.contains(e.getKey())) {
                e.getValue().close();
                TASK_REFRESH.remove(e.getKey());
                TASK_BY_LAYER.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /** Build mode targets the block in front of the clicked face; everything else the clicked block. */
    public static BlockPos targetFor(BuildSession session, BlockHitResult hit) {
        if (session.tool == ToolMode.BUILD || session.tool == ToolMode.BLUEPRINT && session.clipboard != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            BlockPos clicked = hit.getBlockPos();
            if (client.world != null && client.world.getBlockState(clicked).isReplaceable()) {
                return clicked;
            }
            return clicked.offset(hit.getSide());
        }
        return hit.getBlockPos();
    }
}
