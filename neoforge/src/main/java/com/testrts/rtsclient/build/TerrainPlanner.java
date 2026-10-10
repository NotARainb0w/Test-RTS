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
