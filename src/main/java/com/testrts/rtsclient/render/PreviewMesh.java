package com.testrts.rtsclient.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.config.RtsConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A preview of a set of planned blocks, built on a background thread and drawn from GPU buffers.
 * <ul>
 *   <li>{@link Style#GHOST}: the real block models, see-through (up to {@code ghostBlockLimit} blocks);</li>
 *   <li>otherwise, or when larger: the shape's outer surface, tinted, with a block grid.</li>
 * </ul>
 * Only the newest request matters: while one is being built, newer ones replace the queued one,
 * and the last finished mesh stays on screen, so even huge shapes never stall a frame.
 */
public final class PreviewMesh implements AutoCloseable {
    /** Opacity from the config, clamped to something visible. */
    static final class PreviewOpacity {
        private PreviewOpacity() {
        }

        static float opacity() {
            return Math.max(0.2F, Math.min(1.0F, RtsConfig.get().previewOpacity));
        }
    }

    public enum Style {
        /** Real blocks, see-through. Falls back to a cyan surface when too large. */
        GHOST(0x5533CCFF, 0xCC66DDFF),
        /** Blocks to be mined. */
        CLEAR(0x55FF4040, 0xCCFF6060),
        /** A region being selected (copy). */
        SELECT(0x40FFD54F, 0xCCFFD54F);

        final int fill;
        final int edge;

        Style(int fill, int edge) {
            this.fill = fill;
            this.edge = edge;
        }
    }

    /** What a supplier returns: the blocks, and whether the shape was cut off at the size limit. */
    public record Blocks(Map<BlockPos, BlockState> states, boolean truncated) {
        public static Blocks of(Iterable<BlockPos> positions, BlockState state, boolean truncated) {
            Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
            for (BlockPos p : positions) {
                m.put(p, state);
            }
            return new Blocks(m, truncated);
        }
    }

    /** Slightly smaller than a block, so the real block hides the preview once it's placed. */
    private static final float GHOST_SCALE = 0.985F;
    private static final int MAX_EDGE_FACES = 120_000;

    // ------------------------------------------------------------------ shared worker

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rtsclient-preview");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** Three builders shared by every preview; the worker waits for each upload before reusing them. */
    private static final BufferBuilder[] BUILDERS = new BufferBuilder[3];
    private static final ConcurrentLinkedQueue<Finished> FINISHED = new ConcurrentLinkedQueue<>();

    private record Finished(PreviewMesh target, Object key, Parts parts, CountDownLatch uploaded) {
    }

    private static final class Parts {
        BufferBuilder.BuiltBuffer ghost;
        BufferBuilder.BuiltBuffer fill;
        BufferBuilder.BuiltBuffer edges;
        BlockPos origin = BlockPos.ORIGIN;
        int blocks;
        boolean truncated;
        @Nullable
        Box bounds;
        boolean anyGhost;
        boolean anyFill;
        boolean anyEdges;

        void release() {
            for (BufferBuilder.BuiltBuffer b : new BufferBuilder.BuiltBuffer[]{this.ghost, this.fill, this.edges}) {
                if (b != null) {
                    b.release();
                }
            }
            this.ghost = this.fill = this.edges = null;
        }
    }

    /** Called every frame on the render thread: upload whatever the worker finished. */
    public static void pump() {
        Finished f;
        while ((f = FINISHED.poll()) != null) {
            try {
                f.target().accept(f.key(), f.parts());
            } catch (RuntimeException e) {
                RtsClientMod.LOGGER.warn("Preview upload failed", e);
            } finally {
                f.parts().release();
                f.uploaded().countDown();
            }
        }
    }

    // ------------------------------------------------------------------ instance

    private final Style style;
    private VertexBuffer ghostBuffer;
    private VertexBuffer fillBuffer;
    private VertexBuffer edgeBuffer;
    private boolean hasGhost;
    private boolean hasFill;
    private boolean hasEdges;
    private BlockPos origin = BlockPos.ORIGIN;

    private Object requestedKey;
    private Object shownKey;
    private boolean inFlight;
    private Object queuedKey;
    private Supplier<Blocks> queuedSupplier;
    private boolean queuedOffThread;
    private boolean closed;

    private int blocks;
    private boolean truncated;
    @Nullable
    private Box bounds;

    public PreviewMesh(Style style) {
        this.style = style;
    }

    /**
     * Ask for the preview of a set of blocks. No-op if {@code key} is already requested.
     *
     * @param offThread false when the supplier reads the world (it then runs right away on this thread)
     */
    public void request(Object key, Supplier<Blocks> supplier, boolean offThread) {
        if (this.closed || key.equals(this.requestedKey)) {
            return;
        }
        this.requestedKey = key;
        if (this.inFlight) {
            this.queuedKey = key;
            this.queuedSupplier = supplier;
            this.queuedOffThread = offThread;
            return;
        }
        submit(key, supplier, offThread);
    }

    private void submit(Object key, Supplier<Blocks> supplier, boolean offThread) {
        this.inFlight = true;
        Blocks ready = offThread ? null : supplier.get();
        ClientWorld world = MinecraftClient.getInstance().world;
        int ghostLimit = RtsConfig.get().ghostBlockLimit;
        WORKER.execute(() -> {
            Parts parts = new Parts();
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Blocks b = ready != null ? ready : supplier.get();
                bake(parts, b, world, this.style, ghostLimit);
            } catch (Throwable t) {
                RtsClientMod.LOGGER.warn("Building preview failed", t);
                parts.release();
                parts = new Parts();
            }
            FINISHED.add(new Finished(this, key, parts, latch));
            try {
                // Don't touch the builders again until the render thread has uploaded them.
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void accept(Object key, Parts parts) {
        this.inFlight = false;
        if (!this.closed && key.equals(this.requestedKey)) {
            upload(parts);
            this.shownKey = key;
        }
        if (!this.closed && this.queuedKey != null) {
            Object k = this.queuedKey;
            Supplier<Blocks> s = this.queuedSupplier;
            boolean off = this.queuedOffThread;
            this.queuedKey = null;
            this.queuedSupplier = null;
            if (k.equals(this.requestedKey)) {
                submit(k, s, off);
            }
        }
    }

    private void upload(Parts parts) {
        RenderSystem.assertOnRenderThread();
        if (this.ghostBuffer == null) {
            this.ghostBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.fillBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.edgeBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        this.origin = parts.origin;
        this.blocks = parts.blocks;
        this.truncated = parts.truncated;
        this.bounds = parts.bounds;
        this.hasGhost = parts.anyGhost && parts.ghost != null;
        this.hasFill = parts.anyFill && parts.fill != null;
        this.hasEdges = parts.anyEdges && parts.edges != null;
        if (this.hasGhost) {
            this.ghostBuffer.bind();
            this.ghostBuffer.upload(parts.ghost);
            parts.ghost = null;
        }
        if (this.hasFill) {
            this.fillBuffer.bind();
            this.fillBuffer.upload(parts.fill);
            parts.fill = null;
        }
        if (this.hasEdges) {
            this.edgeBuffer.bind();
            this.edgeBuffer.upload(parts.edges);
            parts.edges = null;
        }
        VertexBuffer.unbind();
    }

    public boolean isReady() {
        return this.requestedKey != null && this.requestedKey.equals(this.shownKey);
    }

    public int blocks() {
        return this.blocks;
    }

    public boolean truncated() {
        return this.truncated;
    }

    @Nullable
    public Box bounds() {
        return this.bounds;
    }

    /** Stop showing anything (keeps GPU buffers for reuse). */
    public void clear() {
        this.requestedKey = null;
        this.shownKey = null;
        this.queuedKey = null;
        this.queuedSupplier = null;
        this.hasGhost = this.hasFill = this.hasEdges = false;
        this.bounds = null;
        this.blocks = 0;
        this.truncated = false;
    }

    @Override
    public void close() {
        clear();
        this.closed = true;
        if (this.ghostBuffer != null) {
            this.ghostBuffer.close();
            this.fillBuffer.close();
            this.edgeBuffer.close();
            this.ghostBuffer = this.fillBuffer = this.edgeBuffer = null;
        }
    }

    // ------------------------------------------------------------------ drawing

    public void draw(MatrixStack matrices, Matrix4f projection, Vec3d cam) {
        if (this.closed || this.shownKey == null || !(this.hasGhost || this.hasFill || this.hasEdges)) {
            return;
        }
        matrices.push();
        matrices.translate(this.origin.getX() - cam.x, this.origin.getY() - cam.y, this.origin.getZ() - cam.z);
        Matrix4f model = matrices.peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        // Nearly opaque previews behave like real blocks: they hide what's behind them.
        RenderSystem.depthMask(this.hasGhost && PreviewOpacity.opacity() >= 0.85F);

        if (this.hasGhost) {
            MinecraftClient client = MinecraftClient.getInstance();
            ShaderProgram shader = GameRenderer.getRenderTypeTranslucentProgram();
            if (shader != null) {
                RenderSystem.enableCull();
                RenderSystem.setShaderTexture(0, SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
                client.gameRenderer.getLightmapTextureManager().enable();
                if (shader.chunkOffset != null) {
                    shader.chunkOffset.set(0.0F, 0.0F, 0.0F);
                }
                this.ghostBuffer.bind();
                this.ghostBuffer.draw(model, projection, shader);
                client.gameRenderer.getLightmapTextureManager().disable();
            }
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        if (this.hasFill) {
            this.fillBuffer.bind();
            this.fillBuffer.draw(model, projection, GameRenderer.getPositionColorProgram());
        }
        if (this.hasEdges) {
            this.edgeBuffer.bind();
            this.edgeBuffer.draw(model, projection, GameRenderer.getPositionColorProgram());
        }
        VertexBuffer.unbind();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        matrices.pop();
    }

    // ------------------------------------------------------------------ baking (worker thread)

    private static BufferBuilder builder(int i) {
        if (BUILDERS[i] == null) {
            BUILDERS[i] = new BufferBuilder(1 << 16);
        }
        return BUILDERS[i];
    }

    private static void bake(Parts parts, Blocks b, @Nullable ClientWorld world, Style style, int ghostLimit) {
        Map<BlockPos, BlockState> states = b.states();
        parts.blocks = states.size();
        parts.truncated = b.truncated();
        if (states.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : states.keySet()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        parts.origin = new BlockPos(minX, minY, minZ);
        parts.bounds = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        if (b.truncated()) {
            // Too big to build anyway: the caller draws just the bounds.
            return;
        }

        List<BlockPos> surfaceOnly = new ArrayList<>();
        if (style == Style.GHOST && states.size() <= ghostLimit && world != null) {
            bakeGhosts(parts, states, world, surfaceOnly);
        } else {
            surfaceOnly.addAll(states.keySet());
        }
        if (!surfaceOnly.isEmpty()) {
            bakeSurface(parts, surfaceOnly, style);
        }
    }

    private static void bakeGhosts(Parts parts, Map<BlockPos, BlockState> states, ClientWorld world, List<BlockPos> surfaceOnly) {
        Map<Long, BlockState> byLong = new HashMap<>(states.size() * 2);
        for (Map.Entry<BlockPos, BlockState> e : states.entrySet()) {
            byLong.put(e.getKey().asLong(), e.getValue());
        }
        GhostView view = new GhostView(byLong, world);
        BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();
        BufferBuilder bb = builder(0);
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
        GhostConsumer consumer = new GhostConsumer(bb, PreviewOpacity.opacity());
        MatrixStack matrices = new MatrixStack();
        Random random = Random.create();
        BlockPos o = parts.origin;
        for (Map.Entry<BlockPos, BlockState> e : states.entrySet()) {
            BlockState state = e.getValue();
            BlockPos p = e.getKey();
            if (state.isAir()) {
                continue;
            }
            if (state.getRenderType() != BlockRenderType.MODEL) {
                // Chests and other block-entity-drawn blocks have no plain model: show a box.
                surfaceOnly.add(p);
                continue;
            }
            matrices.push();
            matrices.translate(p.getX() - o.getX() + 0.5, p.getY() - o.getY() + 0.5, p.getZ() - o.getZ() + 0.5);
            // Slightly smaller than a block, so once the real block is placed it hides the ghost.
            matrices.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
            matrices.translate(-0.5, -0.5, -0.5);
            try {
                brm.renderBlock(state, p, view, matrices, consumer, true, random);
                parts.anyGhost = true;
            } catch (RuntimeException ignored) {
                // A block that can't be drawn this way; leave it out.
            }
            matrices.pop();
        }
        parts.ghost = bb.end();
    }

    private static void bakeSurface(Parts parts, List<BlockPos> positions, Style style) {
        LongOpenHashSet set = new LongOpenHashSet(positions.size() * 2);
        for (BlockPos p : positions) {
            set.add(p.asLong());
        }
        BlockPos o = parts.origin;
        BufferBuilder fill = builder(1);
        BufferBuilder edges = builder(2);
        fill.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        edges.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        int faces = 0;
        float[] c = new float[12];
        for (BlockPos p : positions) {
            for (Direction d : Direction.values()) {
                if (set.contains(BlockPos.asLong(p.getX() + d.getOffsetX(), p.getY() + d.getOffsetY(), p.getZ() + d.getOffsetZ()))) {
                    continue;
                }
                corners(c, p.getX() - o.getX(), p.getY() - o.getY(), p.getZ() - o.getZ(), d);
                float shade = switch (d) {
                    case DOWN -> 0.55F;
                    case UP -> 1.0F;
                    case NORTH, SOUTH -> 0.8F;
                    default -> 0.68F;
                };
                int f = style.fill;
                int r = (int) ((f >> 16 & 255) * shade);
                int g = (int) ((f >> 8 & 255) * shade);
                int bl = (int) ((f & 255) * shade);
                int a = f >>> 24;
                for (int k = 0; k < 4; k++) {
                    fill.vertex(c[k * 3], c[k * 3 + 1], c[k * 3 + 2]).color(r, g, bl, a).next();
                }
                if (faces < MAX_EDGE_FACES) {
                    int e = style.edge;
                    for (int k = 0; k < 4; k++) {
                        int n = (k + 1) % 4;
                        edges.vertex(c[k * 3], c[k * 3 + 1], c[k * 3 + 2]).color(e >> 16 & 255, e >> 8 & 255, e & 255, e >>> 24).next();
                        edges.vertex(c[n * 3], c[n * 3 + 1], c[n * 3 + 2]).color(e >> 16 & 255, e >> 8 & 255, e & 255, e >>> 24).next();
                    }
                    parts.anyEdges = true;
                }
                faces++;
                parts.anyFill = true;
            }
        }
        parts.fill = fill.end();
        parts.edges = edges.end();
    }

    private static void corners(float[] out, int x, int y, int z, Direction d) {
        float e = 0.004F;
        float x0 = x - e, y0 = y - e, z0 = z - e;
        float x1 = x + 1 + e, y1 = y + 1 + e, z1 = z + 1 + e;
        float[] v = switch (d) {
            case DOWN -> new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1};
            case UP -> new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0};
            case NORTH -> new float[]{x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0};
            case SOUTH -> new float[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
            case WEST -> new float[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0};
            case EAST -> new float[]{x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1};
        };
        System.arraycopy(v, 0, out, 0, 12);
    }
}
