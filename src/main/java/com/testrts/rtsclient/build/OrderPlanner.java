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
                          java.util.List<BlockState> mix, boolean clearInTheWay, MixPattern pattern) {
    }

    public static Request request(BuildSession s, BlockPos a, BlockPos b, float cameraYaw, int limit) {
        java.util.List<BlockState> mix = s.tool == ToolMode.BUILD ? s.mixStates(cameraYaw) : java.util.List.of();
        BlockState brush = s.brushState(cameraYaw);
        if (brush == null && !mix.isEmpty()) {
            brush = mix.get(0);
        }
        return new Request(s.tool, s.shape, s.mode, a, b, s.height, s.hollow, brush, s.replaceTarget, limit, mix,
                com.testrts.rtsclient.config.RtsConfig.get().clearInTheWay, s.pattern);
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
        // Pattern brushes: each block once (in the order added) and the shape's height, for layered patterns.
        java.util.List<BlockState> distinct = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(r.mix()));
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : shape.positions()) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        for (BlockPos p : shape.positions()) {
            BlockState want = pick(r, p, distinct, minY, maxY);
            String role = shape.roles().get(p);
            if (ShapeGenerator.RAIL.equals(role)) {
                want = railingFor(want);
            } else if (ShapeGenerator.LAMP.equals(role)) {
                want = LANTERN;
            } else if (ShapeGenerator.STAIR.equals(role)) {
                want = stairsFor(want);
            }
            String facing = shape.facing().get(p);
            if (facing != null) {
                // Stair-like shapes: stairs (and anything else with a facing) point up the staircase.
                want = BuildSession.withProperty(want, "facing", facing);
            }
            BlockState current = world.getBlockState(p);
            if (com.testrts.rtsclient.task.MaterialPlanner.matches(current, want)) {
                continue;
            }
            boolean empty = current.isAir() || current.isReplaceable();
            boolean take = switch (r.mode()) {
                case FILL -> empty || r.clearInTheWay() && !(current.getBlock() instanceof FluidBlock)
                        && current.getHardness(world, p) >= 0;
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

    /** The brush, or with a mix the pattern's pick for this position (stable, so preview = build). */
    private static BlockState pick(Request r, BlockPos p, java.util.List<BlockState> distinct, int minY, int maxY) {
        java.util.List<BlockState> mix = r.mix();
        if (mix.isEmpty()) {
            return r.brush();
        }
        MixPattern pattern = r.pattern() == null ? MixPattern.RANDOM : r.pattern();
        int i = pattern.pick(p.getX(), p.getY(), p.getZ(), mix.size(), distinct.size(), minY, maxY);
        return pattern.usesWeights() ? mix.get(i) : distinct.get(i);
    }

    private static boolean isSolidTop(ClientWorld world, BlockPos below) {
        BlockState s = world.getBlockState(below);
        return !s.isAir() && !s.isReplaceable() && !s.getCollisionShape(world, below).isEmpty();
    }

    private static final BlockState LANTERN = Blocks.LANTERN.getDefaultState();

    /**
     * A fence or wall that goes with {@code deck}: oak planks or logs get oak fence, stone bricks a
     * stone brick wall, cobblestone a cobblestone wall... oak fence when nothing matches.
     */
    public static BlockState railingFor(BlockState deck) {
        net.minecraft.util.Identifier id = net.minecraft.registry.Registries.BLOCK.getId(deck.getBlock());
        String path = id.getPath();
        if (path.endsWith("_fence") || path.endsWith("_wall")) {
            return deck.getBlock().getDefaultState();
        }
        String base = path.replace("stripped_", "");
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String suffix : new String[]{"_planks", "_log", "_wood", "_stem", "_hyphae"}) {
            if (base.endsWith(suffix)) {
                names.add(base.substring(0, base.length() - suffix.length()) + "_fence");
            }
        }
        if (base.endsWith("_bricks") || base.endsWith("_tiles")) {
            names.add(base.substring(0, base.length() - 1) + "_wall");
        }
        names.add(base + "_wall");
        names.add(base + "_fence");
        // Stone has no wall of its own, but its bricks do (stone, deepslate, mud...).
        names.add(base + "_brick_wall");
        for (String name : names) {
            net.minecraft.util.Identifier c = new net.minecraft.util.Identifier(id.getNamespace(), name);
            if (net.minecraft.registry.Registries.BLOCK.containsId(c)) {
                return net.minecraft.registry.Registries.BLOCK.get(c).getDefaultState();
            }
        }
        return Blocks.OAK_FENCE.getDefaultState();
    }

    /** The stairs of {@code block}'s material (oak planks: oak stairs, stone bricks: stone brick stairs), or the block itself. */
    public static BlockState stairsFor(BlockState block) {
        net.minecraft.util.Identifier id = net.minecraft.registry.Registries.BLOCK.getId(block.getBlock());
        String path = id.getPath();
        if (path.endsWith("_stairs")) {
            return block;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        if (path.endsWith("_planks")) {
            names.add(path.substring(0, path.length() - "_planks".length()) + "_stairs");
        }
        if (path.endsWith("_bricks") || path.endsWith("_tiles")) {
            names.add(path.substring(0, path.length() - 1) + "_stairs");
        }
        names.add(path + "_stairs");
        for (String name : names) {
            net.minecraft.util.Identifier c = new net.minecraft.util.Identifier(id.getNamespace(), name);
            if (net.minecraft.registry.Registries.BLOCK.containsId(c)) {
                return net.minecraft.registry.Registries.BLOCK.get(c).getDefaultState();
            }
        }
        return block;
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
