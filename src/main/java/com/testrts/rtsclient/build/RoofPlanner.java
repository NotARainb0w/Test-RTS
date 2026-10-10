package com.testrts.rtsclient.build;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Smart roof": click the top of a room's wall and get a roof sized to the room. The walls' top
 * course is followed all the way round to find the footprint, then a gable or hip roof with a
 * one-block overhang is laid on top, in the brush's material (stairs give a proper sloped roof).
 */
public final class RoofPlanner {
    public enum Style {
        GABLE("Gable"),
        HIP("Hip");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public Style next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** A roof ready to paste: the blueprint and where its minimum corner goes. */
    public record Roof(Blueprint blueprint, BlockPos origin, int width, int depth) {
    }

    private static final int MAX_SPAN = 64;
    private static final int MAX_CELLS = 4096;

    private RoofPlanner() {
    }

    /** @return the roof, or null with {@code error[0]} saying why */
    @Nullable
    public static Roof plan(ClientWorld world, BlockPos clicked, BlockState brush, Style style, String[] error) {
        BlockPos top = topOfWall(world, clicked);
        int y = top.getY();
        // Follow the wall's top course (solid, open above) round the room.
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(top);
        seen.add(top.asLong());
        int x0 = top.getX(), x1 = top.getX(), z0 = top.getZ(), z1 = top.getZ();
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
            if (seen.size() > MAX_CELLS || x1 - x0 > MAX_SPAN || z1 - z0 > MAX_SPAN) {
                error[0] = "That wall goes on too far - is it part of a bigger structure?";
                return null;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos n = p.add(dx, 0, dz);
                    if ((dx != 0 || dz != 0) && !seen.contains(n.asLong()) && isWallTop(world, n)) {
                        seen.add(n.asLong());
                        queue.add(n);
                    }
                }
            }
        }
        if (x1 - x0 < 2 || z1 - z0 < 2) {
            error[0] = "Click the top of a room's wall (the room must be at least 3x3)";
            return null;
        }
        Materials m = materials(brush);
        Map<BlockPos, BlockState> out = new HashMap<>();
        // One block of overhang all round.
        int ax0 = x0 - 1, ax1 = x1 + 1, az0 = z0 - 1, az1 = z1 + 1;
        int base = y + 1;
        if (style == Style.GABLE) {
            gable(out, m, ax0, ax1, az0, az1, x0, x1, z0, z1, base);
        } else {
            hip(out, m, ax0, ax1, az0, az1, base);
        }
        // Relative to the minimum corner, for the blueprint.
        int minY = base;
        int maxY = base;
        for (BlockPos p : out.keySet()) {
            maxY = Math.max(maxY, p.getY());
        }
        Map<BlockPos, BlockState> rel = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : out.entrySet()) {
            BlockPos p = e.getKey();
            rel.put(new BlockPos(p.getX() - ax0, p.getY() - minY, p.getZ() - az0), e.getValue());
        }
        Blueprint bp = new Blueprint(style.label() + " roof", ax1 - ax0 + 1, maxY - minY + 1, az1 - az0 + 1, rel);
        return new Roof(bp, new BlockPos(ax0, minY, az0), x1 - x0 + 1, z1 - z0 + 1);
    }

    /** Ridge along the longer side; the two short ends are closed with full blocks over the walls. */
    private static void gable(Map<BlockPos, BlockState> out, Materials m, int ax0, int ax1, int az0, int az1,
                              int x0, int x1, int z0, int z1, int y) {
        boolean alongX = ax1 - ax0 >= az1 - az0;
        int lo = alongX ? az0 : ax0;
        int hi = alongX ? az1 : ax1;
        while (lo < hi) {
            if (alongX) {
                for (int x = ax0; x <= ax1; x++) {
                    out.put(new BlockPos(x, y, lo), m.stair(Direction.SOUTH));
                    out.put(new BlockPos(x, y, hi), m.stair(Direction.NORTH));
                }
                for (int z = lo + 1; z < hi; z++) {
                    out.put(new BlockPos(x0, y, z), m.full);
                    out.put(new BlockPos(x1, y, z), m.full);
                }
            } else {
                for (int z = az0; z <= az1; z++) {
                    out.put(new BlockPos(lo, y, z), m.stair(Direction.EAST));
                    out.put(new BlockPos(hi, y, z), m.stair(Direction.WEST));
                }
                for (int x = lo + 1; x < hi; x++) {
                    out.put(new BlockPos(x, y, z0), m.full);
                    out.put(new BlockPos(x, y, z1), m.full);
                }
            }
            lo++;
            hi--;
            y++;
        }
        if (lo == hi) {
            if (alongX) {
                for (int x = ax0; x <= ax1; x++) {
                    out.put(new BlockPos(x, y, lo), m.slab);
                }
            } else {
                for (int z = az0; z <= az1; z++) {
                    out.put(new BlockPos(lo, y, z), m.slab);
                }
            }
        }
    }

    /** Slopes on all four sides, rising to a ridge (or a point on square rooms). */
    private static void hip(Map<BlockPos, BlockState> out, Materials m, int xlo, int xhi, int zlo, int zhi, int y) {
        while (xlo < xhi && zlo < zhi) {
            for (int x = xlo; x <= xhi; x++) {
                out.put(new BlockPos(x, y, zlo), m.stair(Direction.SOUTH));
                out.put(new BlockPos(x, y, zhi), m.stair(Direction.NORTH));
            }
            for (int z = zlo + 1; z < zhi; z++) {
                out.put(new BlockPos(xlo, y, z), m.stair(Direction.EAST));
                out.put(new BlockPos(xhi, y, z), m.stair(Direction.WEST));
            }
            xlo++;
            xhi--;
            zlo++;
            zhi--;
            y++;
        }
        // Whatever is left is a single row (or block): cap it with slabs.
        for (int x = xlo; x <= xhi; x++) {
            for (int z = zlo; z <= zhi; z++) {
                out.put(new BlockPos(x, y, z), m.slab);
            }
        }
    }

    private static BlockPos topOfWall(ClientWorld world, BlockPos p) {
        BlockPos.Mutable m = p.mutableCopy();
        for (int i = 0; i < 32 && isSolid(world, m.up()); i++) {
            m.move(Direction.UP);
        }
        return m.toImmutable();
    }

    private static boolean isWallTop(ClientWorld world, BlockPos p) {
        return isSolid(world, p) && !isSolid(world, p.up());
    }

    private static boolean isSolid(ClientWorld world, BlockPos p) {
        if (!world.isChunkLoaded(p)) {
            return false;
        }
        BlockState s = world.getBlockState(p);
        return !s.isAir() && !s.isReplaceable() && !s.getCollisionShape(world, p).isEmpty();
    }

    /** Stairs for the slopes, a slab for the ridge and a full block for the gable ends. */
    private record Materials(@Nullable BlockState stairs, BlockState slab, BlockState full) {
        BlockState stair(Direction facing) {
            if (this.stairs == null) {
                return this.full;
            }
            return this.stairs.with(Properties.HORIZONTAL_FACING, facing).with(Properties.BLOCK_HALF, BlockHalf.BOTTOM);
        }
    }

    private static Materials materials(BlockState brush) {
        Block block = brush.getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        if (block instanceof StairsBlock) {
            String base = id.getPath().substring(0, id.getPath().length() - "_stairs".length());
            BlockState slab = find(id.getNamespace(), base + "_slab");
            BlockState full = first(id.getNamespace(), base + "_planks", base, base + "s", base + "_block");
            BlockState stairs = block.getDefaultState();
            return new Materials(stairs, slab != null ? slab.with(Properties.SLAB_TYPE, SlabType.BOTTOM) : stairs,
                    full != null ? full : stairs);
        }
        if (block instanceof SlabBlock) {
            BlockState slab = block.getDefaultState().with(Properties.SLAB_TYPE, SlabType.BOTTOM);
            return new Materials(null, slab, block.getDefaultState().with(Properties.SLAB_TYPE, SlabType.DOUBLE));
        }
        return new Materials(null, block.getDefaultState(), block.getDefaultState());
    }

    @Nullable
    private static BlockState first(String namespace, String... paths) {
        for (String path : paths) {
            BlockState s = find(namespace, path);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    @Nullable
    private static BlockState find(String namespace, String path) {
        Identifier id = Identifier.tryParse(namespace + ":" + path);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        return Registries.BLOCK.get(id).getDefaultState();
    }
}
