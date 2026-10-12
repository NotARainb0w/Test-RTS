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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
    private static final Map<BuildTask, PreviewMesh> TASK_MESHES = new IdentityHashMap<>();
    private static final Map<BuildTask, Long> TASK_REFRESH = new IdentityHashMap<>();
    /** Clear orders are re-meshed now and then so mined blocks drop out of the red preview. */
    private static final long CLEAR_REFRESH_MS = 2500;
    private static final int CLEAR_REFRESH_MAX = 60_000;
    /** Build orders: how often placed blocks are taken out of the ghost. */
    private static final long BUILD_REFRESH_MS = 1000;

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
    public static void hologram(PoseStack matrices, Matrix4f projection, Vec3 cam) {
        PreviewMesh.pump();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            closeTaskMeshes(List.of());
            return;
        }
        drawTaskMeshes(client, matrices, projection, cam);
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

    /** Ghosts of queued build (green) and clear (red) orders; done blocks drop out every second or so. */
    private static void drawTaskMeshes(Minecraft client, PoseStack matrices, Matrix4f projection, Vec3 cam) {
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
            if (build.blocks().size() <= CLEAR_REFRESH_MAX) {
                long last = TASK_REFRESH.getOrDefault(build, 0L);
                if (now - last > (build.clearing() ? CLEAR_REFRESH_MS : BUILD_REFRESH_MS)) {
                    TASK_REFRESH.put(build, now);
                    if (build.clearing()) {
                        List<BlockPos> left = new ArrayList<>();
                        for (BlockPos p : build.blocks().keySet()) {
                            if (!client.level.getBlockState(p).isAir()) {
                                left.add(p);
                            }
                        }
                        mesh.request(List.of(build, left.size()),
                                () -> PreviewMesh.Blocks.of(left, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false), true);
                    } else {
                        // Only what's still to place: blocks placed by the builder or by hand drop out.
                        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> left = new java.util.LinkedHashMap<>();
                        for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> e : build.blocks().entrySet()) {
                            if (!com.testrts.rtsclient.task.MaterialPlanner.matches(client.level.getBlockState(e.getKey()), e.getValue())) {
                                left.put(e.getKey(), e.getValue());
                            }
                        }
                        mesh.request(List.of(build, left.size()), () -> new PreviewMesh.Blocks(left, false), true);
                    }
                }
            } else {
                mesh.request(build, () -> new PreviewMesh.Blocks(build.blocks(), false), true);
            }
            mesh.draw(matrices, projection, cam);
        }
    }

    public static void render(RenderContext context) {
        // Uploads must run even when RTS is off, so the preview worker never waits forever.
        PreviewMesh.pump();
        RtsController controller = RtsController.get();
        Minecraft client = Minecraft.getInstance();
        if (!controller.isActive() || client.level == null || client.player == null) {
            closeTaskMeshes(List.of());
            return;
        }
        PoseStack matrices = context.matrixStack();
        Matrix4f projection = context.projectionMatrix();
        Vec3 cam = context.camera().getPosition();
        BuildSession session = controller.session();
        BlockHitResult hover = controller.hover();

        // ---- meshes (GPU buffers) ----
        drawTaskMeshes(client, matrices, projection, cam);

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
            List<Object> key = List.of(bp, origin, session.pasteTurns, session.pasteMirror, session.arrayX, session.arrayZ, session.arrayGap);
            if (!PASTE.isRequested(key)) {
                // Copy the settings now; the mesh is built on the worker thread.
                com.testrts.rtsclient.build.BuildSession snap = new com.testrts.rtsclient.build.BuildSession();
                snap.pasteTurns = session.pasteTurns;
                snap.pasteMirror = session.pasteMirror;
                snap.arrayX = session.arrayX;
                snap.arrayZ = session.arrayZ;
                snap.arrayGap = session.arrayGap;
                PASTE.request(key, () -> new PreviewMesh.Blocks(
                        com.testrts.rtsclient.screen.WorldActions.pasteBlocks(snap, bp, origin), false), true);
            }
            PASTE.draw(matrices, projection, cam);
        } else {
            PASTE.clear();
        }

        // Terrain tool: what Flatten will mine (red) and fill, or where Light up puts torches.
        if (session.tool == ToolMode.TERRAIN && session.anchorA != null && (session.anchorB != null || hover != null)) {
            BlockPos a = session.anchorA;
            BlockPos b = session.anchorB != null ? session.anchorB : hover.getBlockPos();
            var world = client.level;
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
                        net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), false), true);
            } else {
                // Quarry, tree felling and harvesting only show the area box.
                TERRAIN_CLEAR.clear();
                TERRAIN_FILL.clear();
            }
            TERRAIN_FILL.draw(matrices, projection, cam);
        } else {
            TERRAIN_CLEAR.clear();
            TERRAIN_FILL.clear();
        }

        MultiBufferSource.BufferSource immediate = client.renderBuffers().bufferSource();
        renderCursorBlock(client, session, hover, matrices, cam, immediate, controller.camera().yaw());

        // ---- lines (immediate) ----
        VertexConsumer lines = immediate.getBuffer(RenderType.lines());
        matrices.pushPose();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        // Linked storage: gold, brighter in Link mode.
        float storageAlpha = session.tool == ToolMode.LINK ? 1.0F : 0.45F;
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            BlockPos p = c.pos();
            if (p.distToCenterSqr(cam.x, cam.y, cam.z) > 160 * 160) {
                continue;
            }
            AABB box = new AABB(p).inflate(0.02);
            for (BlockPos n : new BlockPos[]{p.north(), p.south(), p.east(), p.west()}) {
                if (client.level.hasChunkAt(n) && StorageManager.canonical(client.level, n).equals(p) && !n.equals(p)) {
                    box = box.minmax(new AABB(n).inflate(0.02));
                }
            }
            LevelRenderer.renderLineBox(matrices, lines, box, 1.0F, c.indexed() ? 0.75F : 0.5F, 0.1F, storageAlpha);
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
            AABB box = new AABB(area.minX(), y0, area.minZ(), area.maxX() + 1, y0 + 96, area.maxZ() + 1);
            LevelRenderer.renderLineBox(matrices, lines, box, 1.0F, 0.25F, 0.25F, protectAlpha);
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
                if (p.distToCenterSqr(cam.x, cam.y, cam.z) > 160 * 160) {
                    continue;
                }
                AABB plate = new AABB(p).inflate(0.03);
                if (plateOnFace) {
                    net.minecraft.core.Direction d = t.side();
                    double inset = 0.85;
                    plate = new AABB(p).deflate(0.1).inflate(0.02).contract(-d.getStepX() * inset, -d.getStepY() * inset, -d.getStepZ() * inset);
                }
                LevelRenderer.renderLineBox(matrices, lines, plate, r, g, bl, storageAlpha);
            }
        }

        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.blocks().isEmpty()) {
                if (build.clearing()) {
                    LevelRenderer.renderLineBox(matrices, lines, build.bounds().inflate(0.03), 1.0F, 0.3F, 0.3F, 0.5F);
                } else {
                    LevelRenderer.renderLineBox(matrices, lines, build.bounds().inflate(0.03), 0.3F, 1.0F, 0.4F, 0.5F);
                }
            } else if (task instanceof MoveTask move) {
                BlockPos t = move.target();
                LevelRenderer.renderLineBox(matrices, lines, new AABB(t.getX() + 0.2, t.getY(), t.getZ() + 0.2,
                        t.getX() + 0.8, t.getY() + 0.1, t.getZ() + 0.8), 0.4F, 0.8F, 1.0F, 1.0F);
            }
        }

        // Built-in engine path.
        List<BlockPos> path = NativeEngine.get().currentPath();
        if (path != null) {
            for (BlockPos p : path) {
                LevelRenderer.renderLineBox(matrices, lines, new AABB(p.getX() + 0.35, p.getY(), p.getZ() + 0.35,
                        p.getX() + 0.65, p.getY() + 0.08, p.getZ() + 0.65), 0.4F, 0.8F, 1.0F, 0.9F);
            }
        }

        // Shape outline and anchors.
        if (showShape) {
            PreviewMesh mesh = session.tool == ToolMode.DESTROY ? SHAPE_CLEAR : SHAPE_GHOST;
            if (mesh.truncated() && mesh.bounds() != null) {
                LevelRenderer.renderLineBox(matrices, lines, mesh.bounds(), 1.0F, 0.2F, 0.2F, 1.0F);
            }
            LevelRenderer.renderLineBox(matrices, lines, new AABB(session.anchorA).inflate(0.06), 1.0F, 1.0F, 0.2F, 1.0F);
            if (session.anchorB != null) {
                LevelRenderer.renderLineBox(matrices, lines, new AABB(session.anchorB).inflate(0.06), 1.0F, 0.85F, 0.2F, 1.0F);
            }
        }
        boolean regionTool = session.tool == ToolMode.BLUEPRINT && session.clipboard == null
                || session.tool == ToolMode.TERRAIN || session.tool == ToolMode.MEASURE;
        if (regionTool && session.anchorA != null) {
            BlockPos a = session.anchorA;
            BlockPos b = session.anchorB != null ? session.anchorB : hover != null ? hover.getBlockPos() : a;
            AABB sel = new AABB(a).minmax(new AABB(b)).inflate(0.02);
            float[] c = session.tool == ToolMode.MEASURE ? new float[]{0.85F, 0.55F, 1.0F}
                    : session.tool == ToolMode.TERRAIN ? new float[]{0.55F, 0.85F, 0.35F} : new float[]{1.0F, 0.84F, 0.3F};
            LevelRenderer.renderLineBox(matrices, lines, sel, c[0], c[1], c[2], 1.0F);
            LevelRenderer.renderLineBox(matrices, lines, new AABB(a).inflate(0.06), 1.0F, 1.0F, 0.2F, 1.0F);
        }
        if (session.moving()) {
            // The building being moved: it comes down once the copy is placed.
            AABB from = new AABB(session.moveFrom).minmax(new AABB(session.moveTo)).inflate(0.04);
            float movePulse = (float) (0.5 - 0.5 * Math.cos((System.currentTimeMillis() % 1000) / 1000.0 * Math.PI * 2.0));
            LevelRenderer.renderLineBox(matrices, lines, from, 1.0F, 0.35F, 0.3F, 0.5F + 0.5F * movePulse);
        }
        if (session.tool == ToolMode.BLUEPRINT && session.clipboard != null && PASTE.bounds() != null) {
            LevelRenderer.renderLineBox(matrices, lines, PASTE.bounds().inflate(0.03), 1.0F, 0.84F, 0.3F,
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
            LevelRenderer.renderLineBox(matrices, lines, new AABB(target).inflate(0.01), c[0], c[1], c[2], 0.65F + 0.35F * pulse);
            LevelRenderer.renderLineBox(matrices, lines, new AABB(target).inflate(0.04 + 0.03 * pulse), c[0], c[1], c[2], 0.25F * (1.0F - pulse));
        }

        if (session.airPlace && hover != null) {
            drawAirLayer(client, matrices, lines, targetFor(session, hover), session.airY);
        }

        // With the Link tool, label each linked container with what it mostly holds (drawn last:
        // text uses other buffers than the lines above).
        if (session.tool == ToolMode.LINK) {
            for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
                BlockPos p = c.pos();
                if (p.distToLowCornerSqr(cam.x, cam.y, cam.z) > 32 * 32) {
                    continue;
                }
                net.minecraft.world.item.Item item = StorageManager.get().labelFor(p);
                if (item == null) {
                    continue;
                }
                String text = item.getDescription().getString();
                matrices.pushPose();
                matrices.translate(p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5);
                matrices.mulPose(context.camera().rotation());
                // 1.21's camera rotation already includes the 180 degree turn, so x isn't flipped (like vanilla name tags).
                matrices.scale(0.025F, -0.025F, 0.025F);
                float x = -client.font.width(text) / 2.0F;
                client.font.drawInBatch(text, x, 0, 0xFFFFE08A, false, matrices.last().pose(), immediate,
                        net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0x80101820, LightTexture.FULL_BRIGHT);
                matrices.popPose();
            }
        }

        matrices.popPose();
        immediate.endBatch();
    }

    /** A fading grid on the air layer around the cursor, and a drop line to the ground below it. */
    private static void drawAirLayer(Minecraft client, PoseStack matrices, VertexConsumer lines, BlockPos target, int y) {
        if (target.getY() != y || client.level == null) {
            return;
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                float fade = 1.0F - Math.max(Math.abs(dx), Math.abs(dz)) / 4.0F;
                int x = target.getX() + dx;
                int z = target.getZ() + dz;
                LevelRenderer.renderLineBox(matrices, lines, new AABB(x, y, z, x + 1, y, z + 1), 1.0F, 1.0F, 1.0F, 0.35F * fade);
            }
        }
        BlockPos.MutableBlockPos p = target.mutable();
        int ground = y;
        for (int i = 0; i < 128 && p.getY() > client.level.getMinBuildHeight(); i++) {
            p.move(0, -1, 0);
            if (!client.level.getBlockState(p).isAir()) {
                ground = p.getY() + 1;
                break;
            }
        }
        if (ground < y) {
            double cx = target.getX() + 0.5;
            double cz = target.getZ() + 0.5;
            LevelRenderer.renderLineBox(matrices, lines, new AABB(cx - 0.04, ground, cz - 0.04, cx + 0.04, y, cz + 0.04), 1.0F, 1.0F, 1.0F, 0.6F);
            LevelRenderer.renderLineBox(matrices, lines, new AABB(cx - 0.3, ground, cz - 0.3, cx + 0.3, ground + 0.02, cz + 0.3), 1.0F, 1.0F, 1.0F, 0.6F);
        }
    }

    /**
     * The exact block the next click places, drawn under the cursor every frame (not baked), so
     * rotating it, changing a property or turning the camera shows up immediately.
     */
    private static void renderCursorBlock(Minecraft client, BuildSession session, BlockHitResult hover, PoseStack matrices,
                                          Vec3 cam, MultiBufferSource.BufferSource immediate, float yaw) {
        if (session.tool != ToolMode.BUILD || hover == null || client.level == null) {
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
        int light = LevelRenderer.getLightColor(client.level, pos);
        int block = Math.max(LightTexture.block(light), 10);
        int sky = Math.max(LightTexture.sky(light), 10);
        matrices.pushPose();
        matrices.translate(pos.getX() - cam.x + 0.5, pos.getY() - cam.y + 0.5, pos.getZ() - cam.z + 0.5);
        // A hair bigger than a block, so it shows over the block it would replace.
        matrices.scale(1.002F, 1.002F, 1.002F);
        matrices.translate(-0.5, -0.5, -0.5);
        try {
            client.getBlockRenderer().renderSingleBlock(state, matrices, immediate,
                    LightTexture.pack(block, sky), OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException ignored) {
            // Some modded blocks can't be drawn outside the world; the outline still shows.
        }
        matrices.popPose();
    }

    /** Forget all baked previews (after a setting that changes how they look). */
    public static void invalidate() {
        SHAPE_GHOST.clear();
        SHAPE_CLEAR.clear();
        PASTE.clear();
        TERRAIN_CLEAR.clear();
        TERRAIN_FILL.clear();
        closeTaskMeshes(List.of());
    }

    private static void requestShapePreview(BuildSession s, BlockPos a, BlockPos b, float yaw) {
        Minecraft client = Minecraft.getInstance();
        OrderPlanner.Request request = OrderPlanner.request(s, a, b, yaw, RtsConfig.get().maxShapeBlocks);
        PreviewMesh mesh = s.tool == ToolMode.DESTROY ? SHAPE_CLEAR : SHAPE_GHOST;
        var world = client.level;
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
                        request.height(), request.hollow(), request.limit() + 1, request.inverted());
                java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> glass = new java.util.LinkedHashMap<>();
                for (BlockPos p : shape.positions()) {
                    if (world.getBlockState(p).isAir()) {
                        glass.put(p, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
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

    /** Blocks to clear and to fill in the terrain preview; -1 while still being worked out. */
    public static int[] terrainCounts() {
        return new int[]{
                TERRAIN_CLEAR.isReady() ? TERRAIN_CLEAR.blocks() : -1,
                TERRAIN_FILL.isReady() ? TERRAIN_FILL.blocks() : -1
        };
    }

    private static void closeTaskMeshes(List<BuildTask> keep) {
        TASK_MESHES.entrySet().removeIf(e -> {
            if (!keep.contains(e.getKey())) {
                e.getValue().close();
                TASK_REFRESH.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /** Build mode targets the block in front of the clicked face; everything else the clicked block. */
    public static BlockPos targetFor(BuildSession session, BlockHitResult hit) {
        if (session.tool == ToolMode.BUILD || session.tool == ToolMode.BLUEPRINT && session.clipboard != null) {
            Minecraft client = Minecraft.getInstance();
            BlockPos clicked = hit.getBlockPos();
            if (client.level != null && client.level.getBlockState(clicked).canBeReplaced()) {
                return clicked;
            }
            return clicked.relative(hit.getDirection());
        }
        return hit.getBlockPos();
    }
}
