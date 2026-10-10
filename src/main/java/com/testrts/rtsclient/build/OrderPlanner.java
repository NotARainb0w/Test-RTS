package com.testrts.rtsclient.build;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the player's selection (tool, shape, build mode, brush) into the exact set of block
 * changes an order will make. The preview and the order both come from here, so what you see
 * is what gets built.
 */
public final class OrderPlanner {
    /** A planned order. {@code truncated} means the shape exceeded the size limit. */
    public record Plan(Map<BlockPos, BlockState> changes, int shapeSize, boolean truncated,
                       int sizeX, int sizeY, int sizeZ, Map<Item, Integer> materials) {
        /** "5 x 5 x 1" (width x length x height). */
        public String dimensions() {
            return this.sizeX + " x " + this.sizeZ + " x " + this.sizeY;
        }
    }

    /** Item counts needed to place {@code changes} (air entries ignored). */
    public static Map<Item, Integer> materials(Map<BlockPos, BlockState> changes) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (BlockState s : changes.values()) {
            if (!s.isAir()) {
                Item item = s.getBlock().asItem();
                if (item != Items.AIR) {
                    out.merge(item, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    private static Plan plan(Map<BlockPos, BlockState> changes, java.util.Set<BlockPos> shape, boolean truncated) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : shape) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (shape.isEmpty()) {
            return new Plan(changes, 0, truncated, 0, 0, 0, Map.of());
        }
        return new Plan(changes, shape.size(), truncated, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, materials(changes));
    }

    private OrderPlanner() {
    }

    /**
     * Settings captured on the client thread, so planning can also run on a worker thread.
     *
     * @param replaceTarget block to replace in {@link BuildMode#REPLACE_TYPE}, or null
     */
    public record Request(ToolMode tool, BuildShape shape, BuildMode mode, BlockPos a, BlockPos b, int height,
                          boolean hollow, @Nullable BlockState brush, @Nullable Block replaceTarget, int limit,
                          java.util.List<BlockState> mix) {
    }

    public static Request request(BuildSession s, BlockPos a, BlockPos b, float cameraYaw, int limit) {
        java.util.List<BlockState> mix = s.tool == ToolMode.BUILD ? s.mixStates(cameraYaw) : java.util.List.of();
        BlockState brush = s.brushState(cameraYaw);
        if (brush == null && !mix.isEmpty()) {
            brush = mix.get(0);
        }
        return new Request(s.tool, s.shape, s.mode, a, b, s.height, s.hollow, brush, s.replaceTarget, limit, mix);
    }

    public static Plan plan(ClientWorld world, Request r) {
        ShapeGenerator.Result shape = ShapeGenerator.generate(r.shape(), r.a(), r.b(), r.height(), r.hollow(), r.limit() + 1);
        boolean truncated = shape.truncated() || shape.positions().size() > r.limit();
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        if (truncated) {
            return plan(changes, shape.positions(), true);
        }
        if (r.tool() == ToolMode.DESTROY) {
            BlockState air = Blocks.AIR.getDefaultState();
            for (BlockPos p : shape.positions()) {
                BlockState current = world.getBlockState(p);
                // Skip air, water/lava and unbreakable blocks like bedrock.
                if (!current.isAir() && !(current.getBlock() instanceof FluidBlock) && current.getHardness(world, p) >= 0) {
                    changes.put(p, air);
                }
            }
            return plan(changes, shape.positions(), false);
        }
        BlockState brush = r.brush();
        if (brush == null) {
            return plan(changes, shape.positions(), false);
        }
        for (BlockPos p : shape.positions()) {
            BlockState want = pick(r, p);
            BlockState current = world.getBlockState(p);
            if (current.getBlock() == want.getBlock()) {
                continue;
            }
            boolean empty = current.isAir() || current.isReplaceable();
            boolean take = switch (r.mode()) {
                case FILL -> empty;
                case REPLACE -> current.getHardness(world, p) >= 0;
                case REPLACE_TYPE -> r.replaceTarget() != null && current.getBlock() == r.replaceTarget();
                case COVER -> empty && isSolidTop(world, p.down());
            };
            if (take) {
                changes.put(p, want);
            }
        }
        return plan(changes, shape.positions(), false);
    }

    /** The brush, or with a pattern mix a pseudo-random pick that's stable per position (preview = build). */
    private static BlockState pick(Request r, BlockPos p) {
        java.util.List<BlockState> mix = r.mix();
        if (mix.isEmpty()) {
            return r.brush();
        }
        long h = p.asLong() * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return mix.get((int) Math.floorMod(h, (long) mix.size()));
    }

    private static boolean isSolidTop(ClientWorld world, BlockPos below) {
        BlockState s = world.getBlockState(below);
        return !s.isAir() && !s.isReplaceable() && !s.getCollisionShape(world, below).isEmpty();
    }

    public static String label(Request r, int count) {
        String shape = r.shape() == BuildShape.BLOCK ? "" : " (" + r.shape().label() + ")";
        if (r.tool() == ToolMode.DESTROY) {
            return "Clear " + count + shape;
        }
        String verb = switch (r.mode()) {
            case FILL, COVER -> "Build ";
            case REPLACE, REPLACE_TYPE -> "Replace ";
        };
        String name = !r.mix().isEmpty() ? "mixed" : r.brush() == null ? "?" : r.brush().getBlock().getName().getString();
        return verb + count + "x " + name + shape;
    }
}
