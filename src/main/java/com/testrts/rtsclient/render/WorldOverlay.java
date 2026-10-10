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
    private static final Map<BuildTask, PreviewMesh> TASK_MESHES = new IdentityHashMap<>();
    private static final Map<BuildTask, Long> TASK_REFRESH = new IdentityHashMap<>();
    /** Clear orders are re-meshed now and then so mined blocks drop out of the red preview. */
    private static final long CLEAR_REFRESH_MS = 2500;
    private static final int CLEAR_REFRESH_MAX = 60_000;

    private WorldOverlay() {
    }

    /** The vanilla renderer skips the local player when the camera is elsewhere; draw them ourselves. */
    public static void renderPlayer(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (!RtsController.get().isActive() || player == null || client.getCameraEntity() == player
                || context.consumers() == null || player.isInvisible()) {
            return;
        }
        float tickDelta = context.tickDelta();
        Vec3d cam = context.camera().getPos();
        double x = MathHelper.lerp(tickDelta, player.prevX, player.getX()) - cam.x;
        double y = MathHelper.lerp(tickDelta, player.prevY, player.getY()) - cam.y;
        double z = MathHelper.lerp(tickDelta, player.prevZ, player.getZ()) - cam.z;
        float yaw = MathHelper.lerp(tickDelta, player.prevYaw, player.getYaw());
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        dispatcher.render(player, x, y, z, yaw, tickDelta, context.matrixStack(), context.consumers(),
                dispatcher.getLight(player, tickDelta));
    }

    public static void render(WorldRenderContext context) {
        // Uploads must run even when RTS is off, so the preview worker never waits forever.
        PreviewMesh.pump();
        RtsController controller = RtsController.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!controller.isActive() || client.world == null || client.player == null) {
            closeTaskMeshes(List.of());
            return;
        }
        MatrixStack matrices = context.matrixStack();
        Matrix4f projection = context.projectionMatrix();
        Vec3d cam = context.camera().getPos();
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
                        if (!client.world.getBlockState(p).isAir()) {
                            left.add(p);
                        }
                    }
                    mesh.request(List.of(build, left.size()),
                            () -> PreviewMesh.Blocks.of(left, net.minecraft.block.Blocks.AIR.getDefaultState(), false), true);
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
            PASTE.request(List.of(bp, origin, turns), () -> new PreviewMesh.Blocks(bp.placed(origin, turns), false), true);
            PASTE.draw(matrices, projection, cam);
        } else {
            PASTE.clear();
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
        if (session.tool == ToolMode.BLUEPRINT && session.clipboard == null && session.anchorA != null) {
            BlockPos a = session.anchorA;
            BlockPos b = session.anchorB != null ? session.anchorB : hover != null ? hover.getBlockPos() : a;
            Box sel = new Box(a).union(new Box(b)).expand(0.02);
            WorldRenderer.drawBox(matrices, lines, sel, 1.0F, 0.84F, 0.3F, 1.0F);
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
            };
            WorldRenderer.drawBox(matrices, lines, new Box(target).expand(0.01), c[0], c[1], c[2], 1.0F);
        }

        matrices.pop();
        immediate.draw();
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
