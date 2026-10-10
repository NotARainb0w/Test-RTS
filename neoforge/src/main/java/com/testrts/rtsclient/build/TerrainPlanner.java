package com.testrts.rtsclient.build;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plans for the Terrain tool. Pure world reads, so previews can run them on the worker thread. */
public final class TerrainPlanner {
    /**
     * Saplings on a grid {@code spacing} apart over the area, on grass or dirt with room above for the
     * trunk. Spots close to existing trees or saplings are skipped. Dark oak is planted 2x2 (it only
     * grows that way).
     */
    public static Map<BlockPos, BlockState> plantTrees(ClientLevel world, BlockPos a, BlockPos b,
                                                       net.minecraft.world.level.block.Block sapling, int spacing) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int top = Math.max(a.getY(), b.getY()) + 8;
        int bottom = Math.min(a.getY(), b.getY()) - 8;
        boolean big = sapling == Blocks.DARK_OAK_SAPLING;
        int step = Math.max(2, spacing) + (big ? 1 : 0);
        // Centre the grid in the area.
        int ox = x0 + ((x1 - x0) % step) / 2;
        int oz = z0 + ((z1 - z0) % step) / 2;
        BlockState state = sapling.defaultBlockState();
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int x = ox; x <= x1; x += step) {
            for (int z = oz; z <= z1; z += step) {
                BlockPos ground = treeGround(world, x, z, top, bottom);
                if (ground == null) {
                    continue;
                }
                BlockPos spot = ground.above();
                if (nearTree(world, spot, Math.max(1, spacing / 2))) {
                    continue;
                }
                if (big) {
                    List<BlockPos> four = List.of(spot, spot.east(), spot.south(), spot.east().south());
                    boolean ok = true;
                    for (BlockPos q : four) {
                        ok &= plantable(world, q);
                    }
                    if (ok) {
                        for (BlockPos q : four) {
                            out.put(q, state);
                        }
                    }
                } else if (plantable(world, spot)) {
                    out.put(spot, state);
                }
            }
        }
        return out;
    }

    /** The top block of a column if it's grass or dirt (null under trees, on stone, in water...). */
    private static BlockPos treeGround(ClientLevel world, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = world.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) {
                continue; // air, tall grass, flowers, snow
            }
            if (s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS)) {
                return null;
            }
            return s.is(net.minecraft.tags.BlockTags.DIRT) ? p : null;
        }
        return null;
    }

    /** Grass or dirt below, and five free blocks above for the trunk. */
    private static boolean plantable(ClientLevel world, BlockPos spot) {
        if (!world.getBlockState(spot.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            BlockState s = world.getBlockState(spot.above(i));
            if (!(s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    private static boolean nearTree(ClientLevel world, BlockPos spot, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    BlockState s = world.getBlockState(spot.offset(dx, dy, dz));
                    if (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.SAPLINGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private TerrainPlanner() {
    }

    /** What Flatten mines above the level and fills below it. */
    public record Flatten(Map<BlockPos, BlockState> clear, Map<BlockPos, BlockState> fill, boolean truncated) {
    }

    /**
     * @param level       the surface height to level to (the first click's Y)
     * @param clearHeight how far above the level to mine
     */
    public static Flatten flatten(ClientLevel world, BlockPos a, BlockPos b, BlockState fillState, int clearHeight, int limit) {
        int level = a.getY();
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = level + 1; y <= level + clearHeight; y++) {
                    BlockState st = world.getBlockState(m.set(x, y, z));
                    if (!st.isAir() && !(st.getBlock() instanceof LiquidBlock) && st.getDestroySpeed(world, m) >= 0) {
                        clear.put(m.immutable(), air);
                    }
                }
                // Fill holes from the level downwards until we reach ground (at most 8 deep).
                for (int y = level; y > level - 8; y--) {
                    BlockState st = world.getBlockState(m.set(x, y, z));
                    if (st.isAir() || st.canBeReplaced()) {
                        fill.put(m.immutable(), fillState);
                    } else {
                        break;
                    }
                }
                if (clear.size() + fill.size() > limit) {
                    return new Flatten(clear, fill, true);
                }
            }
        }
        return new Flatten(clear, fill, false);
    }

    /** Spots that are dark enough for mobs and can hold a torch, spaced so each torch covers the gaps. */
    public static List<BlockPos> lightUp(ClientLevel world, BlockPos a, BlockPos b) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int y0 = Math.min(a.getY(), b.getY()) - 2;
        int y1 = Math.max(a.getY(), b.getY()) + 6;
        BlockState torch = Blocks.TORCH.defaultBlockState();
        List<BlockPos> chosen = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1 && chosen.size() < 500; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    m.set(x, y, z);
                    if (!world.getBlockState(m).isAir() || world.getBrightness(LightLayer.BLOCK, m) >= 8
                            || !torch.canSurvive(world, m)) {
                        continue;
                    }
                    boolean covered = false;
                    for (BlockPos c : chosen) {
                        // A torch gives light 14, fading 1 per block: within 6 steps it's still 8+.
                        if (c.distManhattan(m) <= 6) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) {
                        chosen.add(m.immutable());
                    }
                }
            }
        }
        return chosen;
    }
}
