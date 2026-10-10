package com.testrts.rtsclient.render;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.ShapeGenerator;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.MoveTask;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Set;

/** Outlines drawn in the world while RTS mode is on: cursor, shape preview, storage, orders. */
public final class WorldOverlay {
    private static final int PREVIEW_RENDER_LIMIT = 4096;

    private static Object previewKey;
    private static Set<BlockPos> previewCache = Set.of();

    private WorldOverlay() {
    }

    public static void render(WorldRenderContext context) {
        RtsController controller = RtsController.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!controller.isActive() || client.world == null || client.player == null) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        Vec3d cam = context.camera().getPos();
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        BuildSession session = controller.session();

        // Linked storage: gold, brighter in Link mode.
        float storageAlpha = session.tool == ToolMode.LINK ? 1.0F : 0.45F;
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            BlockPos p = c.pos();
            Box box = new Box(p).expand(0.02);
            if (client.world.isChunkLoaded(p)) {
                // Include the other half of a double chest.
                for (BlockPos n : new BlockPos[]{p.north(), p.south(), p.east(), p.west()}) {
                    if (StorageManager.canonical(client.world, n).equals(p) && !n.equals(p)) {
                        box = box.union(new Box(n).expand(0.02));
                    }
                }
            }
            float g = c.indexed() ? 0.75F : 0.5F;
            WorldRenderer.drawBox(matrices, lines, box, 1.0F, g, 0.1F, storageAlpha);
        }

        // Queued orders: bounding boxes, and move targets.
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.blocks().isEmpty()) {
                Box bounds = bounds(build.blocks().keySet());
                if (build.clearing()) {
                    WorldRenderer.drawBox(matrices, lines, bounds.expand(0.03), 1.0F, 0.3F, 0.3F, 0.6F);
                } else {
                    WorldRenderer.drawBox(matrices, lines, bounds.expand(0.03), 0.3F, 1.0F, 0.4F, 0.6F);
                }
            } else if (task instanceof MoveTask move) {
                BlockPos t = move.target();
                WorldRenderer.drawBox(matrices, lines, new Box(t.getX() + 0.2, t.getY(), t.getZ() + 0.2,
                        t.getX() + 0.8, t.getY() + 0.1, t.getZ() + 0.8), 0.4F, 0.8F, 1.0F, 1.0F);
            }
        }

        // Built-in engine path.
        java.util.List<BlockPos> path = com.testrts.rtsclient.exec.NativeEngine.get().currentPath();
        if (path != null) {
            for (BlockPos p : path) {
                WorldRenderer.drawBox(matrices, lines, new Box(p.getX() + 0.35, p.getY(), p.getZ() + 0.35,
                        p.getX() + 0.65, p.getY() + 0.08, p.getZ() + 0.65), 0.4F, 0.8F, 1.0F, 0.9F);
            }
        }

        // The player's body, so it's easy to find from above.
        WorldRenderer.drawBox(matrices, lines, client.player.getBoundingBox().expand(0.05), 0.35F, 0.75F, 1.0F, 0.9F);

        // Shape preview.
        BlockHitResult hover = controller.hover();
        if (session.tool.usesShapes() && session.anchorA != null) {
            BlockPos b = session.anchorB != null ? session.anchorB
                    : hover != null ? targetFor(session, hover) : session.anchorA;
            Set<BlockPos> preview = preview(session, session.anchorA, b);
            boolean clear = session.tool == ToolMode.DESTROY;
            float r = clear ? 1.0F : 0.3F;
            float gr = clear ? 0.3F : 0.85F;
            float bl = clear ? 0.3F : 1.0F;
            float a = session.anchorB != null ? 1.0F : 0.6F;
            int drawn = 0;
            for (BlockPos p : preview) {
                if (drawn++ >= PREVIEW_RENDER_LIMIT) {
                    break;
                }
                WorldRenderer.drawBox(matrices, lines, new Box(p).contract(0.04), r, gr, bl, a);
            }
            WorldRenderer.drawBox(matrices, lines, new Box(session.anchorA).expand(0.06), 1.0F, 1.0F, 0.2F, 1.0F);
        } else if (hover != null) {
            BlockPos target = targetFor(session, hover);
            float[] c = switch (session.tool) {
                case BUILD -> new float[]{0.3F, 0.85F, 1.0F};
                case DESTROY -> new float[]{1.0F, 0.3F, 0.3F};
                case INTERACT -> new float[]{1.0F, 1.0F, 1.0F};
                case LINK -> new float[]{1.0F, 0.75F, 0.1F};
            };
            WorldRenderer.drawBox(matrices, lines, new Box(target).expand(0.01), c[0], c[1], c[2], 1.0F);
        }

        matrices.pop();
        immediate.draw(RenderLayer.getLines());
    }

    /** Build mode targets the block in front of the clicked face; everything else the clicked block. */
    public static BlockPos targetFor(BuildSession session, BlockHitResult hit) {
        if (session.tool == ToolMode.BUILD) {
            MinecraftClient client = MinecraftClient.getInstance();
            BlockPos clicked = hit.getBlockPos();
            if (client.world != null && client.world.getBlockState(clicked).isReplaceable()) {
                return clicked;
            }
            return clicked.offset(hit.getSide());
        }
        return hit.getBlockPos();
    }

    private static Set<BlockPos> preview(BuildSession s, BlockPos a, BlockPos b) {
        Object key = Objects.hash(s.shape, a, b, s.height, s.hollow);
        if (!key.equals(previewKey)) {
            previewKey = key;
            previewCache = ShapeGenerator.generate(s.shape, a, b, s.height, s.hollow, PREVIEW_RENDER_LIMIT).positions();
        }
        return previewCache;
    }

    private static Box bounds(Set<BlockPos> positions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }
}
