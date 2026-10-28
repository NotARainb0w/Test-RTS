package com.testrts.rtsclient.build;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Blend terrain brush: columns the player painted are smoothed toward their unpainted surroundings,
 * then mined down or filled up to the smoothed height. Only natural ground (dirt, stone, sand...) is
 * changed; buildings, trees and water are left alone and don't pull on the result. Pure world reads, so
 * previews can run it on the worker thread.
 */
public final class TerrainBlend {
    /** How far above and below the painted height to look for the ground. */
    private static final int SCAN = 24;
    /** Smoothing kernel radius; also how wide the unpainted ring is that the blend leans on. */
    private static final int KERNEL = 2;
    /** Most columns one painting can hold. */
    public static final int MAX_COLUMNS = 16384;

    private static final Set<Block> NATURAL = Set.of(Blocks.GRAVEL, Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.DIRT_PATH,
            Blocks.FARMLAND, Blocks.MUD, Blocks.PACKED_MUD, Blocks.MOSS_BLOCK, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.PACKED_ICE, Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.END_STONE, Blocks.BASALT,
            Blocks.BLACKSTONE, Blocks.OBSIDIAN, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE);
    /** Surface blocks that only grow on top: the blocks under them are dirt. */
    private static final Set<Block> COVERS = Set.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM);

    private TerrainBlend() {
    }

    /** Column key for the paint map. */
    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /**
     * Paint (or with {@code erase}, unpaint) a round brush of {@code radius} around {@code center} into
     * {@code paint} (column to the height the brush touched). Returns whether anything changed.
     */
    public static boolean paint(Map<Long, Integer> paint, BlockPos center, int radius, boolean erase) {
        boolean changed = false;
        double r2 = (radius + 0.5) * (radius + 0.5);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                long k = key(center.getX() + dx, center.getZ() + dz);
                if (erase) {
                    changed |= paint.remove(k) != null;
                } else if (!paint.containsKey(k) && paint.size() < MAX_COLUMNS) {
                    paint.put(k, center.getY());
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** What Blend mines and fills. */
    public record Plan(Map<BlockPos, BlockState> clear, Map<BlockPos, BlockState> fill, boolean truncated) {
    }

    private record Column(int ground, BlockState top, boolean painted) {
    }

    /** The top ground block of every painted column that can be blended (for the paint overlay). */
    public static Map<BlockPos, BlockState> surface(ClientWorld world, Map<Long, Integer> paint) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : paint.entrySet()) {
            int x = keyX(e.getKey());
            int z = keyZ(e.getKey());
            Column c = column(world, x, z, e.getValue(), true);
            if (c != null) {
                out.put(new BlockPos(x, c.ground(), z), c.top());
            }
        }
        return out;
    }

    /**
     * @param strength smoothing passes (1-10): higher flattens bumps further and spreads slopes wider
     * @param keepTop  top raised columns with the surface block (grass...) instead of dirt
     */
    public static Plan plan(ClientWorld world, Map<Long, Integer> paint, int strength, boolean keepTop, int limit) {
        // The painted columns and a ring of unpainted ones around them, which stay put and anchor the blend.
        Map<Long, Column> cols = new HashMap<>();
        for (Map.Entry<Long, Integer> e : paint.entrySet()) {
            Column c = column(world, keyX(e.getKey()), keyZ(e.getKey()), e.getValue(), true);
            if (c != null) {
                cols.put(e.getKey(), c);
            }
        }
        if (cols.isEmpty()) {
            return new Plan(Map.of(), Map.of(), false);
        }
        for (Map.Entry<Long, Integer> e : paint.entrySet()) {
            int x = keyX(e.getKey());
            int z = keyZ(e.getKey());
            for (int dx = -KERNEL; dx <= KERNEL; dx++) {
                for (int dz = -KERNEL; dz <= KERNEL; dz++) {
                    long k = key(x + dx, z + dz);
                    if (!paint.containsKey(k) && !cols.containsKey(k)) {
                        Column c = column(world, x + dx, z + dz, e.getValue(), false);
                        if (c != null) {
                            cols.put(k, c);
                        }
                    }
                }
            }
        }

        // Smooth the painted heights; the ring keeps its own. Gaussian weights over a 5x5 kernel.
        double[][] w = new double[KERNEL * 2 + 1][KERNEL * 2 + 1];
        for (int dx = -KERNEL; dx <= KERNEL; dx++) {
            for (int dz = -KERNEL; dz <= KERNEL; dz++) {
                w[dx + KERNEL][dz + KERNEL] = Math.exp(-(dx * dx + dz * dz) / 2.5);
            }
        }
        // Plain arrays for the passes: each painted column's neighbours (and their weights) are looked up once.
        long[] keys = new long[cols.size()];
        Map<Long, Integer> index = new HashMap<>();
        double[] h = new double[keys.length];
        int n = 0;
        for (Map.Entry<Long, Column> e : cols.entrySet()) {
            keys[n] = e.getKey();
            index.put(e.getKey(), n);
            h[n] = e.getValue().ground();
            n++;
        }
        int[] painted = new int[n];
        int[][] nbr = new int[n][];
        double[][] nbrW = new double[n][];
        double[] totalW = new double[n];
        int count = 0;
        int[] found = new int[w.length * w.length];
        double[] foundW = new double[found.length];
        for (int i = 0; i < n; i++) {
            if (!cols.get(keys[i]).painted()) {
                continue;
            }
            int x = keyX(keys[i]);
            int z = keyZ(keys[i]);
            int m = 0;
            for (int dx = -KERNEL; dx <= KERNEL; dx++) {
                for (int dz = -KERNEL; dz <= KERNEL; dz++) {
                    Integer j = index.get(key(x + dx, z + dz));
                    if (j != null) {
                        found[m] = j;
                        foundW[m] = w[dx + KERNEL][dz + KERNEL];
                        totalW[i] += foundW[m];
                        m++;
                    }
                }
            }
            nbr[i] = java.util.Arrays.copyOf(found, m);
            nbrW[i] = java.util.Arrays.copyOf(foundW, m);
            painted[count++] = i;
        }
        int passes = Math.max(1, Math.min(10, strength)) * 2;
        double[] next = h.clone();
        for (int pass = 0; pass < passes; pass++) {
            for (int c = 0; c < count; c++) {
                int i = painted[c];
                double sum = 0;
                int[] js = nbr[i];
                double[] ws = nbrW[i];
                for (int k = 0; k < js.length; k++) {
                    sum += h[js[k]] * ws[k];
                }
                next[i] = sum / totalW[i];
            }
            double[] t = h;
            h = next;
            next = t;
            System.arraycopy(h, 0, next, 0, n);
        }

        Map<Long, Integer> targets = new HashMap<>();
        for (int c = 0; c < count; c++) {
            int i = painted[c];
            targets.put(keys[i], (int) Math.round(h[i]));
        }
        return reshape(world, cols, targets, keepTop, limit);
    }

    /**
     * The Level brush: painted columns are mined or filled to {@code level}; unpainted ground within
     * {@code ramp} blocks of the painting slopes from the level back to its own height, so there's no cliff
     * at the edge.
     *
     * @param level the ground height to level to (the top block's Y)
     * @param ramp  how wide the sloped edge is (0: a sharp edge)
     */
    public static Plan level(ClientWorld world, Map<Long, Integer> paint, int level, int ramp, boolean keepTop, int limit) {
        Map<Long, Column> cols = new HashMap<>();
        Map<Long, Integer> targets = new HashMap<>();
        for (Map.Entry<Long, Integer> e : paint.entrySet()) {
            Column c = column(world, keyX(e.getKey()), keyZ(e.getKey()), e.getValue(), true);
            if (c != null) {
                cols.put(e.getKey(), c);
                targets.put(e.getKey(), level);
            }
        }
        if (ramp > 0) {
            // Distance from each nearby unpainted column to the painting, measured from the painting's edge.
            Map<Long, Double> dist = new HashMap<>();
            double reach = ramp + 0.5;
            for (Map.Entry<Long, Integer> e : paint.entrySet()) {
                int x = keyX(e.getKey());
                int z = keyZ(e.getKey());
                if (paint.containsKey(key(x + 1, z)) && paint.containsKey(key(x - 1, z))
                        && paint.containsKey(key(x, z + 1)) && paint.containsKey(key(x, z - 1))) {
                    continue; // inside the painting: its neighbours are painted too
                }
                for (int dx = -ramp; dx <= ramp; dx++) {
                    for (int dz = -ramp; dz <= ramp; dz++) {
                        long k = key(x + dx, z + dz);
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d <= reach && !paint.containsKey(k)) {
                            dist.merge(k, d, Math::min);
                        }
                    }
                }
            }
            for (Map.Entry<Long, Double> e : dist.entrySet()) {
                int x = keyX(e.getKey());
                int z = keyZ(e.getKey());
                Column c = column(world, x, z, level, false);
                if (c == null) {
                    continue;
                }
                // Smoothstep from the level (next to the painting) to the column's own height (at the far edge).
                double f = Math.min(1.0, e.getValue() / (ramp + 1.0));
                f = f * f * (3 - 2 * f);
                cols.put(e.getKey(), c);
                targets.put(e.getKey(), (int) Math.round(level + (c.ground() - level) * f));
            }
        }
        return reshape(world, cols, targets, keepTop, limit);
    }

    /** Mine each column down, or fill it up, to its target height. */
    private static Plan reshape(ClientWorld world, Map<Long, Column> cols, Map<Long, Integer> targets, boolean keepTop, int limit) {
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (Map.Entry<Long, Integer> e : targets.entrySet()) {
            Column c = cols.get(e.getKey());
            int x = keyX(e.getKey());
            int z = keyZ(e.getKey());
            int g = c.ground();
            int t = e.getValue();
            BlockState top = c.top().getBlock().getDefaultState();
            boolean cover = COVERS.contains(top.getBlock());
            BlockState under = cover ? Blocks.DIRT.getDefaultState() : top;
            if (t < g) {
                for (int y = t + 1; y <= g; y++) {
                    BlockState st = world.getBlockState(m.set(x, y, z));
                    if (!st.isAir() && !(st.getBlock() instanceof FluidBlock) && st.getHardness(world, m) >= 0) {
                        clear.put(m.toImmutable(), air);
                    }
                }
                // The dirt left on top isn't re-grassed: the builder counts dirt and grass as the same block
                // (so grass turning to dirt isn't dug up forever). Grass spreads back over it by itself.
            } else if (t > g) {
                for (int y = g + 1; y <= t; y++) {
                    BlockState st = world.getBlockState(m.set(x, y, z));
                    if (!open(world, m, st)) {
                        break; // a trunk or a wall: don't build through it
                    }
                    if (!st.isAir() && !st.isReplaceable() && st.getFluidState().isEmpty()) {
                        clear.put(m.toImmutable(), air); // a flower or mushroom in the way
                    }
                    fill.put(m.toImmutable(), y == t && keepTop ? top : under);
                }
            }
            if (clear.size() + fill.size() > limit) {
                return new Plan(clear, fill, true);
            }
        }
        return new Plan(clear, fill, false);
    }

    /** Air, water, snow, tall grass, flowers: things a raised column may fill. */
    private static boolean open(ClientWorld world, BlockPos p, BlockState s) {
        return s.isAir() || s.isReplaceable()
                || s.getCollisionShape(world, p).isEmpty() && s.getHardness(world, p) >= 0 && !s.isIn(BlockTags.SAPLINGS);
    }

    /**
     * The ground of a column near {@code hintY}: the highest natural block, looking through trees and plants.
     * Null over water, buildings (a column topped by anything else) or when nothing is found.
     */
    private static Column column(ClientWorld world, int x, int z, int hintY, boolean painted) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        int bottom = Math.max(world.getBottomY(), hintY - SCAN);
        for (int y = hintY + SCAN; y >= bottom; y--) {
            BlockState s = world.getBlockState(m.set(x, y, z));
            if (s.isAir() || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)
                    || s.getFluidState().isEmpty() && (s.isReplaceable() || s.getCollisionShape(world, m).isEmpty())) {
                continue;
            }
            if (!s.getFluidState().isEmpty() || !natural(s)) {
                return null;
            }
            return new Column(y, s, painted);
        }
        return null;
    }

    private static boolean natural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.SAND)
                || s.isIn(BlockTags.TERRACOTTA) || s.isIn(BlockTags.NYLIUM) || NATURAL.contains(s.getBlock())
                || COVERS.contains(s.getBlock());
    }
}
