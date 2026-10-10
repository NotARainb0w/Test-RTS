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

    private WorldOverlay() {
    }

    /** The vanilla renderer skips the local player when the camera is elsewhere; draw them ourselves. */
    public static void renderPlayer(RenderContext context) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!RtsController.get().isActive() || player == null || client.getCameraEntity() == player
                || context.consumers() == null || player.isInvisible()) {
            return;
        }
        float tickDelta = context.tickDelta();
        Vec3 cam = context.camera().getPosition();
        double x = Mth.lerp(tickDelta, player.xo, player.getX()) - cam.x;
        double y = Mth.lerp(tickDelta, player.yo, player.getY()) - cam.y;
        double z = Mth.lerp(tickDelta, player.zo, player.getZ()) - cam.z;
        float yaw = Mth.lerp(tickDelta, player.yRotO, player.getYRot());
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        dispatcher.render(player, x, y, z, yaw, tickDelta, context.matrixStack(), context.consumers(),
                dispatcher.getPackedLightCoords(player, tickDelta));
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
            if (build.clearing() && build.blocks().size() <= CLEAR_REFRESH_MAX) {
                long last = TASK_REFRESH.getOrDefault(build, 0L);
                if (now - last > CLEAR_REFRESH_MS) {
                    TASK_REFRESH.put(build, now);
                    List<BlockPos> left = new ArrayList<>();
                    for (BlockPos p : build.blocks().keySet()) {
                        if (!client.level.getBlockState(p).isAir()) {
                            left.add(p);
                        }
                    }
                    mesh.request(List.of(build, left.size()),
                            () -> PreviewMesh.Blocks.of(left, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false), true);
                }
            } else {
                mesh.request(build, () -> new PreviewMesh.Blocks(build.blocks(), false), true);
            }
            mesh.draw(matrices, projection, cam);
        }

        boolean showShape = session.tool.usesShapes() && session.anchorA != null;
        if (showShape) {
            BlockPos b = session.anchorB != null ? session.anchorB : hover != null ? targetFor(session, hover) : session.anchorA;
            requestShapePreview(session, session.anchorA, b, controller.camera().yaw());
            PreviewMesh mesh = session.tool == ToolMode.DESTROY ? SHAPE_CLEAR : SHAPE_GHOST;
            (session.tool == ToolMode.DESTROY ? SHAPE_GHOST : SHAPE_CLEAR).clear();
            mesh.draw(matrices, projection, cam);
        } else {
            SHAPE_GHOST.clear();
            SHAPE_CLEAR.clear();
        }

        if (session.tool == ToolMode.BLUEPRINT && session.clipboard != null && (hover != null || session.pasteAnchor != null)) {
            BlockPos origin = session.pasteAnchor != null ? session.pasteAnchor
                    : session.clipboard.originFor(targetFor(session, hover), session.pasteTurns);
            var bp = session.clipboard;
            int turns = session.pasteTurns;
            boolean mirror = session.pasteMirror;
            PASTE.request(List.of(bp, origin, turns, mirror), () -> new PreviewMesh.Blocks(bp.placed(origin, turns, mirror), false), true);
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
            } else {
                TERRAIN_CLEAR.clear();
                TERRAIN_FILL.request(List.of("light", a, b), () -> PreviewMesh.Blocks.of(
                        com.testrts.rtsclient.build.TerrainPlanner.lightUp(world, a, b),
                        net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), false), true);
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
            };
            // Pulsing outline with a faint outer glow, so the cursor reads at a glance.
            float pulse = (float) (0.5 - 0.5 * Math.cos((System.currentTimeMillis() % 1200) / 1200.0 * Math.PI * 2.0));
            LevelRenderer.renderLineBox(matrices, lines, new AABB(target).inflate(0.01), c[0], c[1], c[2], 0.65F + 0.35F * pulse);
            LevelRenderer.renderLineBox(matrices, lines, new AABB(target).inflate(0.04 + 0.03 * pulse), c[0], c[1], c[2], 0.25F * (1.0F - pulse));
        }

        if (session.airPlace && hover != null) {
            drawAirLayer(client, matrices, lines, targetFor(session, hover), session.airY);
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
