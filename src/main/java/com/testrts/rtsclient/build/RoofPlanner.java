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
 * one-block overhang is laid on top, in the brush's material (stairs give a proper sloped roof). Styles:
 * gable, steep gable, barn (gambrel), lean-to, hip, mansard and flat.
 */
public final class RoofPlanner {
    public enum Style {
        GABLE("Gable", "Two slopes meeting at a ridge, closed triangle ends"),
        STEEP("Steep gable", "A tall A-frame: two slopes rising two blocks per step"),
        GAMBREL("Barn", "A barn roof: steep lower slopes, then a gentler top"),
        SHED("Lean-to", "One slope from one side up to the other"),
        HIP("Hip", "Slopes on all four sides, up to a ridge or a point"),
        MANSARD("Mansard", "Steep slopes on all four sides with a flat top"),
        FLAT("Flat", "A flat roof with a low parapet round the edge");

        private final String label;
        private final String description;

        Style(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return this.label;
        }

        public String description() {
            return this.description;
        }

        public Style next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** The roof's materials and details (any style). Brush: the selected block, as before. */
    public enum Look {
        BRUSH("Brush", "Your selected block (stairs work best)", null, null, null, false, null, null),
        COTTAGE("Cottage", "Spruce with a dark oak outline and ridge, cobblestone chimney",
                new String[]{"spruce_stairs", "spruce_slab", "spruce_planks"},
                new String[]{"dark_oak_stairs", "dark_oak_slab", "dark_oak_planks"}, "cobblestone", false, null, null),
        WITCH("Witch", "Teal copper with a blackstone outline, flared eaves and spikes on the ridge",
                new String[]{"waxed_oxidized_cut_copper_stairs", "waxed_oxidized_cut_copper_slab", "waxed_oxidized_cut_copper"},
                new String[]{"polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "polished_blackstone_bricks"},
                null, true, "lightning_rod", null),
        SNOWY("Snowy", "Mixed stone with white patches like snow, dark oak outline",
                new String[]{"cobblestone_stairs", "cobblestone_slab", "cobblestone"},
                new String[]{"dark_oak_stairs", "dark_oak_slab", "dark_oak_planks"}, null, false, null,
                new String[]{"cobblestone_stairs", "stone_stairs", "andesite_stairs", "diorite_stairs", "diorite_stairs", "polished_diorite_stairs"}),
        MEDIEVAL("Medieval", "Deepslate tiles with a spruce outline and a stone brick chimney",
                new String[]{"deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tiles"},
                new String[]{"spruce_stairs", "spruce_slab", "spruce_planks"}, "stone_bricks", false, null, null);

        private final String label;
        private final String description;
        /** stairs, slab, full block (minecraft ids without the namespace); null for Brush. */
        final String[] body;
        final String[] trim;
        @Nullable
        final String chimney;
        final boolean flare;
        @Nullable
        final String spikes;
        /** Stairs to mix into the slopes (by position), or null. */
        @Nullable
        final String[] mix;

        Look(String label, String description, String[] body, String[] trim, @Nullable String chimney, boolean flare,
             @Nullable String spikes, @Nullable String[] mix) {
            this.label = label;
            this.description = description;
            this.body = body;
            this.trim = trim;
            this.chimney = chimney;
            this.flare = flare;
            this.spikes = spikes;
            this.mix = mix;
        }

        public String label() {
            return this.label;
        }

        public String description() {
            return this.description;
        }

        public Look next() {
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
    public static Roof plan(ClientWorld world, BlockPos clicked, @Nullable BlockState brush, Style style, String[] error) {
        return plan(world, clicked, brush, style, Look.BRUSH, error);
    }

    /** @return the roof, or null with {@code error[0]} saying why */
    @Nullable
    public static Roof plan(ClientWorld world, BlockPos clicked, @Nullable BlockState brush, Style style, Look look, String[] error) {
        if (brush == null && (look == Look.BRUSH || look.body == null)) {
            error[0] = "Pick a roof block first (stairs work best, e.g. oak stairs), or choose a roof Look";
            return null;
        }
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
        Materials m = look == Look.BRUSH || look.body == null ? materials(brush) : named(look.body);
        Materials tm = look == Look.BRUSH || look.trim == null ? m : named(look.trim);
        Map<BlockPos, BlockState> out = new HashMap<>();
        // One block of overhang all round.
        int ax0 = x0 - 1, ax1 = x1 + 1, az0 = z0 - 1, az1 = z1 + 1;
        int base = y + 1;
        switch (style) {
            case GABLE, STEEP, GAMBREL, SHED -> sloped(out, m, tm, look, style, ax0, ax1, az0, az1, x0, x1, z0, z1, base);
            case HIP -> hip(out, m, tm, ax0, ax1, az0, az1, base);
            case MANSARD -> {
                if (ax1 - ax0 >= 6 && az1 - az0 >= 6) {
                    mansard(out, m, tm, ax0, ax1, az0, az1, base);
                } else {
                    hip(out, m, tm, ax0, ax1, az0, az1, base); // too small for two steep rings
                }
            }
            case FLAT -> flat(out, m, tm, ax0, ax1, az0, az1, base);
        }
        if (look.mix != null && m.stairs != null) {
            mixStairs(out, m.stairs.getBlock(), look.mix);
        }
        // Relative to the minimum corner (flared eaves reach past the overhang), for the blueprint.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : out.keySet()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        Map<BlockPos, BlockState> rel = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : out.entrySet()) {
            BlockPos p = e.getKey();
            rel.put(new BlockPos(p.getX() - minX, p.getY() - minY, p.getZ() - minZ), e.getValue());
        }
        String name = (look == Look.BRUSH ? "" : look.label() + " ") + style.label() + " roof";
        Blueprint bp = new Blueprint(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, rel);
        return new Roof(bp, new BlockPos(minX, minY, minZ), x1 - x0 + 1, z1 - z0 + 1);
    }

    /** Materials of a look: {stairs, slab, full} minecraft ids. */
    private static Materials named(String[] ids) {
        BlockState stairs = find("minecraft", ids[0]);
        BlockState slab = find("minecraft", ids[1]);
        BlockState full = find("minecraft", ids[2]);
        if (full == null) {
            full = stairs != null ? stairs : slab;
        }
        return new Materials(stairs, slab != null ? slab : full, full);
    }

    /** Swap the body's stairs for a mix of others (by position, so the preview matches), keeping their facing. */
    private static void mixStairs(Map<BlockPos, BlockState> out, Block body, String[] mix) {
        java.util.List<BlockState> variants = new java.util.ArrayList<>();
        for (String id : mix) {
            BlockState v = find("minecraft", id);
            if (v != null) {
                variants.add(v);
            }
        }
        if (variants.isEmpty()) {
            return;
        }
        for (Map.Entry<BlockPos, BlockState> e : out.entrySet()) {
            BlockState s = e.getValue();
            if (s.getBlock() != body) {
                continue;
            }
            BlockPos p = e.getKey();
            int h = p.getX() * 73856093 ^ p.getY() * 19349663 ^ p.getZ() * 83492791;
            h ^= h >>> 13;
            BlockState v = variants.get(Math.floorMod(h * 0x5bd1e995, variants.size()));
            e.setValue(v.with(Properties.HORIZONTAL_FACING, s.get(Properties.HORIZONTAL_FACING)).with(Properties.BLOCK_HALF, s.get(Properties.BLOCK_HALF)));
        }
    }

    /**
     * Roofs with a ridge along the longer side (or one slope, for the lean-to). Each row across the roof
     * sits at the height its style's profile gives it; where a row is more than one higher than the one
     * before (steep roofs), full blocks fill in under its stairs. The ends (and the lean-to's high side)
     * are closed with full blocks over the walls. The outline (end rows, eaves, ridge, gable ends) is in
     * the trim material.
     */
    private static void sloped(Map<BlockPos, BlockState> out, Materials m, Materials tm, Look look, Style style, int ax0, int ax1,
                               int az0, int az1, int x0, int x1, int z0, int z1, int y) {
        boolean alongX = ax1 - ax0 >= az1 - az0;
        int uMin = alongX ? az0 : ax0;
        int uMax = alongX ? az1 : ax1;
        int tMin = alongX ? ax0 : az0;
        int tMax = alongX ? ax1 : az1;
        int endA = alongX ? x0 : z0;
        int endB = alongX ? x1 : z1;
        int inLo = alongX ? z0 : x0;
        int inHi = alongX ? z1 : x1;
        Direction up = alongX ? Direction.SOUTH : Direction.EAST;
        Direction down = up.getOpposite();
        int rows = uMax - uMin + 1;
        int knee = Math.max(1, rows / 6);
        int[] heights = new int[rows];
        for (int u = uMin; u <= uMax; u++) {
            int i = u - uMin;
            int j = uMax - u;
            int h;
            int below;
            Direction face;
            boolean ridge = false;
            if (style == Style.SHED) {
                h = i;
                below = i - 1;
                face = up;
            } else {
                int k = Math.min(i, j);
                h = profile(style, k, knee);
                below = k == 0 ? -1 : profile(style, k - 1, knee);
                face = i < j ? up : down;
                ridge = i == j;
            }
            heights[i] = h;
            boolean eave = u == uMin || u == uMax;
            for (int t = tMin; t <= tMax; t++) {
                boolean edge = eave || t == tMin || t == tMax;
                Materials mat = edge || ridge ? tm : m;
                for (int f = below + 1; f < h; f++) {
                    put(out, alongX, t, y + f, u, mat.full);
                }
                put(out, alongX, t, y + h, u, ridge ? tm.slab : mat.stair(face));
            }
            // Close the ends under the roof (the gable triangles), over the end walls.
            if (u >= inLo && u <= inHi) {
                for (int f = 0; f < h; f++) {
                    put(out, alongX, endA, y + f, u, tm.full);
                    put(out, alongX, endB, y + f, u, tm.full);
                }
            }
        }
        if (style == Style.SHED) {
            // The lean-to's high side: wall up to the roof.
            int h = inHi - uMin;
            for (int t = endA; t <= endB; t++) {
                for (int f = 0; f < h; f++) {
                    put(out, alongX, t, y + f, inHi, tm.full);
                }
            }
        }
        if (look.flare) {
            // Flared eaves: one more row lower down outside the long sides (just the low side of a lean-to).
            for (int t = tMin; t <= tMax; t++) {
                put(out, alongX, t, y - 1, uMin - 1, tm.stair(up));
                if (style != Style.SHED) {
                    put(out, alongX, t, y - 1, uMax + 1, tm.stair(down));
                }
            }
        }
        if (look.spikes != null && style != Style.SHED) {
            BlockState spike = find("minecraft", look.spikes);
            int ridgeU = uMin + (rows - 1) / 2;
            if (spike != null) {
                for (int t = tMin + 1; t < tMax; t += 2) {
                    put(out, alongX, t, y + heights[ridgeU - uMin] + 1, ridgeU, spike);
                }
            }
        }
        if (look.chimney != null && endB - endA >= 3 && inHi - inLo >= 3) {
            // A chimney near one end, through the roof and two blocks above it, with a campfire for smoke.
            BlockState block = find("minecraft", look.chimney);
            BlockState fire = find("minecraft", "campfire");
            int t = endA + 1;
            int u = inLo + 1;
            int top = y + heights[u - uMin] + 2;
            if (block != null && fire != null) {
                for (int cy = y; cy < top; cy++) {
                    put(out, alongX, t, cy, u, block);
                }
                put(out, alongX, t, top, u, fire);
            }
        }
    }

    /** Height of the row {@code k} rows in from the eaves. */
    private static int profile(Style style, int k, int knee) {
        return switch (style) {
            case STEEP -> 2 * k;
            case GAMBREL -> k <= knee ? 2 * k : 2 * knee + (k - knee);
            default -> k;
        };
    }

    private static void put(Map<BlockPos, BlockState> out, boolean alongX, int t, int y, int u, BlockState s) {
        out.put(alongX ? new BlockPos(t, y, u) : new BlockPos(u, y, t), s);
    }

    /** Two steep rings on all four sides (full blocks under stairs; the first ring in trim), then a flat top. */
    private static void mansard(Map<BlockPos, BlockState> out, Materials m, Materials tm, int xlo, int xhi, int zlo, int zhi, int y) {
        for (int r = 0; r < 2 && xlo < xhi && zlo < zhi; r++) {
            Materials mat = r == 0 ? tm : m;
            for (int x = xlo; x <= xhi; x++) {
                out.put(new BlockPos(x, y, zlo), mat.full);
                out.put(new BlockPos(x, y + 1, zlo), mat.stair(Direction.SOUTH));
                out.put(new BlockPos(x, y, zhi), mat.full);
                out.put(new BlockPos(x, y + 1, zhi), mat.stair(Direction.NORTH));
            }
            for (int z = zlo + 1; z < zhi; z++) {
                out.put(new BlockPos(xlo, y, z), mat.full);
                out.put(new BlockPos(xlo, y + 1, z), mat.stair(Direction.EAST));
                out.put(new BlockPos(xhi, y, z), mat.full);
                out.put(new BlockPos(xhi, y + 1, z), mat.stair(Direction.WEST));
            }
            xlo++;
            xhi--;
            zlo++;
            zhi--;
            y += 2;
        }
        // The flat top, level with the top of the last ring's stairs.
        for (int x = xlo; x <= xhi; x++) {
            for (int z = zlo; z <= zhi; z++) {
                out.put(new BlockPos(x, y - 1, z), m.full);
            }
        }
    }

    /** A flat roof over everything (overhang included), with a low slab parapet (trim) round the edge. */
    private static void flat(Map<BlockPos, BlockState> out, Materials m, Materials tm, int xlo, int xhi, int zlo, int zhi, int y) {
        for (int x = xlo; x <= xhi; x++) {
            for (int z = zlo; z <= zhi; z++) {
                boolean edge = x == xlo || x == xhi || z == zlo || z == zhi;
                out.put(new BlockPos(x, y, z), edge ? tm.full : m.full);
                if (edge) {
                    out.put(new BlockPos(x, y + 1, z), tm.slab);
                }
            }
        }
    }

    /** Slopes on all four sides, rising to a ridge (or a point on square rooms); the eaves ring in trim. */
    private static void hip(Map<BlockPos, BlockState> out, Materials m, Materials tm, int xlo, int xhi, int zlo, int zhi, int y) {
        boolean first = true;
        while (xlo < xhi && zlo < zhi) {
            Materials mat = first ? tm : m;
            first = false;
            for (int x = xlo; x <= xhi; x++) {
                out.put(new BlockPos(x, y, zlo), mat.stair(Direction.SOUTH));
                out.put(new BlockPos(x, y, zhi), mat.stair(Direction.NORTH));
            }
            for (int z = zlo + 1; z < zhi; z++) {
                out.put(new BlockPos(xlo, y, z), mat.stair(Direction.EAST));
                out.put(new BlockPos(xhi, y, z), mat.stair(Direction.WEST));
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
                out.put(new BlockPos(x, y, z), tm.slab);
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
        if (s.isAir() || s.isReplaceable()) {
            // A queued build's hologram counts as built: a roof can go on a house that's still to be built.
            BlockState ghost = com.testrts.rtsclient.task.BuildTask.queuedAt(p);
            if (ghost != null) {
                s = ghost;
            }
        }
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
