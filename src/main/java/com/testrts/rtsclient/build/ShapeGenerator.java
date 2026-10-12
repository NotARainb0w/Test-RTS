package com.testrts.rtsclient.build;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Turns a shape and its control points into the set of block positions it covers. */
public final class ShapeGenerator {

    /**
     * Positions in the order they were generated; {@code truncated} means the limit was hit.
     * {@code facing} gives stair-like shapes the way each block should face ("north"...), for
     * blocks that have a facing.
     */
    public record Result(Set<BlockPos> positions, boolean truncated, java.util.Map<BlockPos, String> facing,
                         java.util.Map<BlockPos, String> roles) {
    }

    /** {@link Result#roles()}: a railing (fence or wall matching the brush) and a lantern on top of a post. */
    public static final String RAIL = "rail";
    public static final String LAMP = "lamp";
    /** A stair of the brush's material (facing given in {@link Result#facing()}), for walkable slopes. */
    public static final String STAIR = "stair";

    private ShapeGenerator() {
    }

    /**
     * @param height vertical extent for WALL/BOX/CYLINDER. Positive grows upward from A, negative grows
     *               downward (handy for digging). Never zero.
     */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit) {
        return generate(shape, a, b, height, hollow, limit, false);
    }

    /**
     * @param inverted dome, pyramid or cone upside down: the clicked level is the bottom (the tip), the
     *                 shape widens upward from there (see {@link BuildShape#invertible()})
     */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted) {
        return generate(shape, a, b, height, hollow, limit, inverted, BridgeStyle.CLASSIC);
    }

    /** @param bridgeStyle the look of the Bridge shape (see {@link BridgeStyle}) */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted,
                                  BridgeStyle bridgeStyle) {
        Collector out = new Collector(limit);
        if (height == 0) {
            height = 1;
        }
        switch (shape) {
            case BLOCK -> out.add(a.getX(), a.getY(), a.getZ());
            case LINE -> line3d(out, a, b);
            case FLOOR -> floor(out, a, b, hollow);
            case WALL -> wall(out, a, b, height, hollow);
            case BOX -> box(out, a, b, height, hollow);
            case CIRCLE -> cylinder(out, a, b, 1, hollow);
            case CYLINDER -> cylinder(out, a, b, height, hollow);
            case SPHERE -> sphere(out, a, b, hollow, false);
            case DOME -> sphere(out, a, b, hollow, true);
            case PYRAMID -> pyramid(out, a, b, hollow);
            case CONE -> cone(out, a, b, height, hollow);
            case TORUS -> torus(out, a, b, height, hollow);
            case ARCH -> arch(out, a, b, height, hollow);
            case BRIDGE -> {
                if (bridgeStyle == null || bridgeStyle == BridgeStyle.CLASSIC) {
                    bridge(out, a, b, height, hollow);
                } else {
                    styledBridge(out, a, b, height, bridgeStyle);
                }
            }
            case STAIRS -> stairs(out, a, b, height, hollow);
            case SPIRAL -> spiral(out, a, b, height, hollow);
            case VEIN -> vein(out, a);
            case HOLE -> hole(out, a);
        }
        if (inverted && shape.invertible()) {
            return flipped(out);
        }
        return new Result(out.positions, out.truncated, out.facing, out.roles);
    }

    /**
     * The shape upside down within its own height: the tip of a pyramid or cone ends up at the clicked
     * level and the base on top (a funnel); a dome becomes a bowl.
     */
    private static Result flipped(Collector out) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : out.positions) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        // Mirror within the shape's own height: the bottom stays at the clicked level.
        int sum = minY + maxY;
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (BlockPos p : out.positions) {
            positions.add(new BlockPos(p.getX(), sum - p.getY(), p.getZ()));
        }
        java.util.Map<BlockPos, String> facing = new java.util.HashMap<>();
        out.facing.forEach((p, f) -> facing.put(new BlockPos(p.getX(), sum - p.getY(), p.getZ()), f));
        java.util.Map<BlockPos, String> roles = new java.util.HashMap<>();
        out.roles.forEach((p, r) -> roles.put(new BlockPos(p.getX(), sum - p.getY(), p.getZ()), r));
        return new Result(positions, out.truncated, facing, roles);
    }

    // ------------------------------------------------------------------ styled bridges

    /** Role prefix: this exact block ("block:minecraft:oak_stairs[facing=east]"), whatever the brush. */
    public static final String BLOCK = "block:";
    /** Role prefix: this block, repeated downward through air and water until it reaches ground (pillars). */
    public static final String DOWN = "down:";

    /** Places the blocks of one styled bridge; the deck goes from A to B, Height is its width. */
    private static final class Bridge {
        final Collector out;
        final boolean alongX;
        final String run;
        final String across;
        final String forward;
        final String back;
        /** Lateral directions: toward lower and higher {@code w}. */
        final String neg;
        final String pos;
        boolean full = true;

        Bridge(Collector out, BlockPos a, BlockPos b) {
            this.out = out;
            this.alongX = Math.abs(b.getX() - a.getX()) >= Math.abs(b.getZ() - a.getZ());
            this.run = this.alongX ? "x" : "z";
            this.across = this.alongX ? "z" : "x";
            this.forward = heading(a, b);
            this.back = switch (this.forward) {
                case "east" -> "west";
                case "west" -> "east";
                case "north" -> "south";
                default -> "north";
            };
            this.neg = this.alongX ? "north" : "west";
            this.pos = this.alongX ? "south" : "east";
        }

        private int x(int[] c, int w) {
            return this.alongX ? c[0] : c[0] + w;
        }

        private int z(int[] c, int w) {
            return this.alongX ? c[1] + w : c[1];
        }

        /** Exact block at lateral offset {@code w} of cell {@code c}. RUN / ACROSS in the spec become log axes. */
        void put(int[] c, int w, int y, String spec) {
            if (this.full && !this.out.addRole(x(c, w), y, z(c, w), BLOCK + axes(spec))) {
                this.full = false;
            }
        }

        /** A pillar: this block here and down to the ground. */
        void down(int[] c, int w, int y, String spec) {
            if (this.full && !this.out.addRole(x(c, w), y, z(c, w), DOWN + axes(spec))) {
                this.full = false;
            }
        }

        private String axes(String spec) {
            return spec.replace("RUN", this.run).replace("ACROSS", this.across);
        }
    }

    /** Deck heights along the run, with a hump in the middle of {@code hump} blocks at most. */
    private static int[] deckHeights(BlockPos a, BlockPos b, int steps, double hump) {
        int[] ys = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            ys[i] = a.getY() + (int) Math.round((b.getY() - a.getY()) * t + Math.sin(Math.PI * t) * hump);
        }
        return ys;
    }

    /** A stable "random" number 0..n-1 for a position (so the preview matches the build). */
    private static int hash(int x, int y, int z, int n) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return Math.floorMod(h ^ h >>> 15, n);
    }

    private static void styledBridge(Collector out, BlockPos a, BlockPos b, int height, BridgeStyle style) {
        java.util.List<int[]> cells = run(a, b);
        if (cells.isEmpty()) {
            return;
        }
        int steps = Math.max(1, cells.get(cells.size() - 1)[2]);
        int width = Math.max(1, Math.abs(height));
        int lo = -(width - 1) / 2;
        int hi = lo + width - 1;
        int railLo = lo - 1;
        int railHi = hi + 1;
        Bridge br = new Bridge(out, a, b);
        double hump = switch (style) {
            case GARDEN -> Math.min(2.5, steps / 4.0);
            case VIADUCT -> 0.0;
            case COVERED -> Math.min(1.0, steps / 12.0);
            default -> Math.min(2.0, steps / 10.0);
        };
        int[] ys = deckHeights(a, b, steps, hump);
        // Stone bridges: arches between piers, about every 10 blocks (Viaduct: every 7, deeper).
        int arches = Math.max(1, (int) Math.round(steps / (style == BridgeStyle.VIADUCT ? 7.0 : 10.0)));
        double archLen = (double) steps / arches;
        int archDepth = style == BridgeStyle.VIADUCT ? 5 : 3;
        int mid = steps / 2;
        for (int[] c : cells) {
            int i = c[2];
            int y = ys[i];
            String stairDir = i < steps && ys[i + 1] > y ? br.forward : i > 0 && ys[i - 1] > y ? br.back : null;
            boolean end = i == 0 || i == steps;
            switch (style) {
                case MEDIEVAL -> {
                    boolean post = end || i % 4 == 0;
                    for (int w = lo; w <= hi; w++) {
                        String deck = w == lo || w == hi ? "minecraft:stripped_spruce_log[axis=RUN]"
                                : i % 4 == 2 ? "minecraft:dark_oak_planks" : "minecraft:spruce_planks";
                        br.put(c, w, y, deck);
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:spruce_stairs[facing=" + stairDir + "]");
                        }
                    }
                    if (post) {
                        // Cross beam under the deck, from railing to railing.
                        for (int w = railLo; w <= railHi; w++) {
                            br.put(c, w, y - 1, "minecraft:stripped_spruce_log[axis=ACROSS]");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        String in = side == railLo ? br.pos : br.neg;
                        br.put(c, side, y, "minecraft:spruce_log[axis=RUN]");
                        // A lip along the outside (an upside-down stair), so the edge isn't a flat wall.
                        br.put(c, outer, y, "minecraft:spruce_stairs[facing=" + in + ",half=top]");
                        if (post) {
                            br.put(c, side, y + 1, "minecraft:stripped_spruce_log[axis=y]");
                            br.put(c, side, y + 2, "minecraft:spruce_fence");
                            br.put(c, side, y + 3, "minecraft:lantern");
                            br.down(c, side, y - 2, "minecraft:spruce_log[axis=y]");
                        } else {
                            br.put(c, side, y + 1, "minecraft:spruce_fence");
                            // Knee braces from the pillar up to the deck, next to each post.
                            if (i % 4 == 1) {
                                br.put(c, side, y - 1, "minecraft:spruce_stairs[facing=" + br.back + ",half=top]");
                            } else if (i % 4 == 3) {
                                br.put(c, side, y - 1, "minecraft:spruce_stairs[facing=" + br.forward + ",half=top]");
                            }
                        }
                    }
                }
                case VIADUCT, STONE, OVERGROWN -> {
                    boolean mossy = style == BridgeStyle.OVERGROWN;
                    boolean viaduct = style == BridgeStyle.VIADUCT;
                    int seg = (int) Math.floor(i / archLen);
                    int segStart = (int) Math.round(seg * archLen);
                    int segEnd = (int) Math.round((seg + 1) * archLen);
                    double t = (i - seg * archLen) / archLen;
                    boolean pier = end || i == segStart || i == segEnd;
                    int depth = pier ? 0 : (int) Math.round(archDepth * (1.0 - Math.sin(Math.PI * t)));
                    String towardPier = t < 0.5 ? br.back : br.forward;
                    boolean capStep = end || i % 4 == 0;
                    String stairsBlock = mossy ? "minecraft:mossy_stone_brick_stairs" : "minecraft:stone_brick_stairs";
                    String path = viaduct ? "minecraft:polished_andesite" : mossy ? "minecraft:mossy_cobblestone" : "minecraft:polished_andesite";
                    for (int w = railLo; w <= railHi; w++) {
                        boolean edge = w == railLo || w == railHi;
                        String brick = mossy ? stoneMix(c, w, y) : "minecraft:stone_bricks";
                        if (!edge) {
                            String deck = w == 0 && width >= 3 ? path : brick;
                            br.put(c, w, y, deck);
                            if (stairDir != null) {
                                br.put(c, w, y + 1, stairsBlock + "[facing=" + stairDir + "]");
                            }
                            if (mossy && stairDir == null && hash(c[0] + w, y + 1, c[1], 6) == 0) {
                                br.put(c, w, y + 1, "minecraft:moss_carpet");
                            }
                        } else {
                            String in = w == railLo ? br.pos : br.neg;
                            int outer = w == railLo ? railLo - 1 : railHi + 1;
                            br.put(c, w, y, brick);
                            // Cornice: an upside-down stair along the outside of the deck.
                            br.put(c, outer, y, stairsBlock + "[facing=" + in + ",half=top]");
                            if (viaduct) {
                                if (pier) {
                                    br.put(c, w, y + 1, "minecraft:chiseled_stone_bricks");
                                    br.put(c, w, y + 2, "minecraft:stone_brick_wall");
                                    br.put(c, w, y + 3, "minecraft:lantern");
                                } else if (i % 2 == 0) {
                                    br.put(c, w, y + 1, "minecraft:stone_bricks");
                                    br.put(c, w, y + 2, "minecraft:stone_brick_slab");
                                } else {
                                    br.put(c, w, y + 1, "minecraft:stone_brick_wall");
                                }
                            } else if (capStep) {
                                br.put(c, w, y + 1, brick);
                                br.put(c, w, y + 2, mossy && hash(c[0], y, c[1], 3) == 0 ? "minecraft:azalea_leaves" : "minecraft:stone_brick_slab");
                            } else {
                                br.put(c, w, y + 1, mossy ? "minecraft:mossy_stone_brick_wall" : "minecraft:stone_brick_wall");
                                if (mossy && hash(c[0], y + 2, c[1], 4) == 0) {
                                    br.put(c, w, y + 2, "minecraft:azalea_leaves");
                                }
                            }
                        }
                    }
                    // Under the deck: the arch (curved with upside-down stairs, keystone at the crown), or a pier.
                    for (int w = railLo - 1; w <= railHi + 1; w++) {
                        boolean outerCol = w < railLo || w > railHi;
                        String brick = mossy ? stoneMix(c, w, y - 1) : "minecraft:stone_bricks";
                        if (pier) {
                            if (!outerCol || viaduct) {
                                // Viaduct piers get buttresses past the railing.
                                br.put(c, w, y - 1, viaduct ? "minecraft:chiseled_stone_bricks" : brick);
                                br.down(c, w, y - 2, brick);
                            }
                        } else if (!outerCol) {
                            if (depth == 0) {
                                br.put(c, w, y - 1, "minecraft:chiseled_stone_bricks");
                            }
                            for (int d = 1; d <= depth; d++) {
                                String block = mossy ? stoneMix(c, w, y - d) : "minecraft:stone_bricks";
                                br.put(c, w, y - d, d == depth ? stairsBlock + "[facing=" + towardPier + ",half=top]" : block);
                            }
                        }
                    }
                }
                case GARDEN -> {
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, "minecraft:spruce_planks");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:spruce_stairs[facing=" + stairDir + "]");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        String in = side == railLo ? br.pos : br.neg;
                        br.put(c, side, y, "minecraft:stripped_spruce_log[axis=RUN]");
                        br.put(c, outer, y, "minecraft:spruce_stairs[facing=" + in + ",half=top]");
                        if (end) {
                            // End posts: a lantern on one, a leafy top on the other side.
                            br.put(c, side, y + 1, "minecraft:spruce_log[axis=y]");
                            br.put(c, side, y + 2, side == railLo ? "minecraft:lantern" : "minecraft:flowering_azalea_leaves");
                            br.down(c, side, y - 1, "minecraft:spruce_log[axis=y]");
                        } else if (i == mid) {
                            // The gateway: tall posts, a stepped double beam, chains with lanterns hanging inside.
                            for (int k = 1; k <= 4; k++) {
                                br.put(c, side, y + k, "minecraft:spruce_log[axis=y]");
                            }
                            br.down(c, side, y - 1, "minecraft:spruce_log[axis=y]");
                        } else {
                            br.put(c, side, y + 1, "minecraft:spruce_fence");
                        }
                    }
                    if (i == mid) {
                        for (int w = railLo - 1; w <= railHi + 1; w++) {
                            boolean tip = w == railLo - 1 || w == railHi + 1;
                            br.put(c, w, y + 5, tip ? "minecraft:dark_oak_stairs[facing=" + (w < 0 ? br.pos : br.neg) + ",half=top]"
                                    : "minecraft:dark_oak_planks");
                            if (w >= railLo && w <= railHi) {
                                br.put(c, w, y + 6, "minecraft:dark_oak_slab");
                            }
                        }
                        for (int w : new int[]{lo, hi}) {
                            br.put(c, w, y + 4, "minecraft:chain[axis=y]");
                            br.put(c, w, y + 3, "minecraft:lantern[hanging=true]");
                        }
                    }
                }
                case COVERED -> {
                    boolean post = end || i % 3 == 0;
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, "minecraft:oak_planks");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:oak_stairs[facing=" + stairDir + "]");
                        }
                        if (post) {
                            // Tie beams across under the roof; a lantern hangs from the middle one.
                            br.put(c, w, y + 4, "minecraft:stripped_oak_log[axis=ACROSS]");
                        }
                    }
                    if (post && lo <= 0 && 0 <= hi) {
                        br.put(c, 0, y + 3, "minecraft:lantern[hanging=true]");
                    }
                    if (end || i % 6 == 0) {
                        // Solid stone pier under the whole deck.
                        for (int w = railLo; w <= railHi; w++) {
                            br.down(c, w, y - 1, "minecraft:stone_bricks");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        br.put(c, side, y, "minecraft:stripped_oak_log[axis=RUN]");
                        if (post) {
                            for (int k = 1; k <= 3; k++) {
                                br.put(c, side, y + k, "minecraft:oak_log[axis=y]");
                            }
                        } else {
                            br.put(c, side, y + 1, "minecraft:oak_fence");
                        }
                        br.put(c, side, y + 4, "minecraft:stripped_oak_log[axis=RUN]");
                    }
                    // Gable roof of cherry stairs over the whole width, ridge of planks.
                    for (int k = 0; ; k++) {
                        int left = railLo - 1 + k;
                        int right = railHi + 1 - k;
                        int ry = y + 4 + k;
                        if (left > right) {
                            break;
                        }
                        if (left == right) {
                            br.put(c, left, ry, "minecraft:cherry_planks");
                            break;
                        }
                        br.put(c, left, ry, "minecraft:cherry_stairs[facing=" + br.pos + "]");
                        br.put(c, right, ry, "minecraft:cherry_stairs[facing=" + br.neg + "]");
                    }
                }
                default -> {
                }
            }
            if (!br.full) {
                return;
            }
        }
    }

    /** Overgrown bricks: mostly mossy, some cracked, some plain. */
    private static String stoneMix(int[] c, int w, int y) {
        return switch (hash(c[0] + w, y, c[1] - w, 5)) {
            case 0, 1 -> "minecraft:mossy_stone_bricks";
            case 2 -> "minecraft:cracked_stone_bricks";
            default -> "minecraft:stone_bricks";
        };
    }

    private static final class Collector {
        final Set<BlockPos> positions = new LinkedHashSet<>();
        final java.util.Map<BlockPos, String> facing = new java.util.HashMap<>();
        /** Blocks that aren't the brush: railings, lanterns. */
        final java.util.Map<BlockPos, String> roles = new java.util.HashMap<>();
        final int limit;
        boolean truncated;

        Collector(int limit) {
            this.limit = limit;
        }

        /** Returns false once full, so loops can bail out early. */
        boolean add(int x, int y, int z) {
            if (this.positions.size() >= this.limit) {
                this.truncated = true;
                return false;
            }
            this.positions.add(new BlockPos(x, y, z));
            return true;
        }

        /** Add a block that isn't the brush but plays {@code role} (see {@link #RAIL}). */
        boolean addRole(int x, int y, int z, String role) {
            if (!add(x, y, z)) {
                return false;
            }
            this.roles.put(new BlockPos(x, y, z), role);
            return true;
        }

        /** Give an already added block a facing (see {@link #add(int, int, int, String)}). */
        boolean addFacing(int x, int y, int z, String dir) {
            this.facing.put(new BlockPos(x, y, z), dir);
            return true;
        }

        /** Add a block that faces {@code dir} (a horizontal direction name) if its block has a facing. */
        boolean add(int x, int y, int z, String dir) {
            if (!add(x, y, z)) {
                return false;
            }
            this.facing.put(new BlockPos(x, y, z), dir);
            return true;
        }
    }

    private static int minY(int baseY, int height) {
        return height > 0 ? baseY : baseY + height + 1;
    }

    private static int maxY(int baseY, int height) {
        return height > 0 ? baseY + height - 1 : baseY;
    }

    private static void line3d(Collector out, BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps == 0) {
            out.add(a.getX(), a.getY(), a.getZ());
            return;
        }
        boolean connect = com.testrts.rtsclient.config.RtsConfig.get().buildableShells;
        int px = a.getX();
        int py = a.getY();
        int pz = a.getZ();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(a.getX() + dx * t);
            int y = (int) Math.round(a.getY() + dy * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            if (connect && i > 0 && !connectStep(out, px, py, pz, x, y, z)) {
                return;
            }
            if (!out.add(x, y, z)) {
                return;
            }
            px = x;
            py = y;
            pz = z;
        }
    }

    /**
     * Diagonal steps leave blocks touching only at an edge or corner, so a block can't be placed
     * against the previous one (over water or air there is nothing else to click). Fill in the step:
     * sideways first, then up, so a rising line becomes a walkable staircase.
     */
    private static boolean connectStep(Collector out, int px, int py, int pz, int x, int y, int z) {
        int cx = px;
        int cy = py;
        int cz = pz;
        int changed = (x != px ? 1 : 0) + (y != py ? 1 : 0) + (z != pz ? 1 : 0);
        if (changed <= 1) {
            return true;
        }
        if (x != cx) {
            cx = x;
            if (--changed > 0 && !out.add(cx, cy, cz)) {
                return false;
            }
        }
        if (z != cz) {
            cz = z;
            if (--changed > 0 && !out.add(cx, cy, cz)) {
                return false;
            }
        }
        return true;
    }

    private static void floor(Collector out, BlockPos a, BlockPos b, boolean hollow) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int y = a.getY();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if ((!hollow || edge) && !out.add(x, y, z)) {
                    return;
                }
            }
        }
    }

    private static void wall(Collector out, BlockPos a, BlockPos b, int height, boolean hollow) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int y0 = minY(a.getY(), height);
        int y1 = maxY(a.getY(), height);
        boolean connect = com.testrts.rtsclient.config.RtsConfig.get().buildableShells;
        int px = a.getX();
        int pz = a.getZ();
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0 : (double) i / steps;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            // A diagonal wall's columns only touch at their edges; add the corner column so it's solid.
            boolean corner = connect && i > 0 && x != px && z != pz;
            for (int y = y0; y <= y1; y++) {
                boolean edge = i == 0 || i == steps || y == y0 || y == y1;
                if (!hollow || edge) {
                    if (corner && !out.add(x, y, pz)) {
                        return;
                    }
                    if (!out.add(x, y, z)) {
                        return;
                    }
                }
            }
            px = x;
            pz = z;
        }
    }

    private static void box(Collector out, BlockPos a, BlockPos b, int height, boolean hollow) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int y0;
        int y1;
        if (a.getY() != b.getY()) {
            y0 = Math.min(a.getY(), b.getY());
            y1 = Math.max(a.getY(), b.getY());
        } else {
            y0 = minY(a.getY(), height);
            y1 = maxY(a.getY(), height);
        }
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1;
                    if ((!hollow || edge) && !out.add(x, y, z)) {
                        return;
                    }
                }
            }
        }
    }

    private static void cylinder(Collector out, BlockPos center, BlockPos edge, int height, boolean hollow) {
        double dx = edge.getX() - center.getX();
        double dz = edge.getZ() - center.getZ();
        double radius = Math.sqrt(dx * dx + dz * dz);
        double limit = (radius + 0.5) * (radius + 0.5);
        int r = (int) Math.ceil(radius);
        int y0 = minY(center.getY(), height);
        int y1 = maxY(center.getY(), height);
        for (int y = y0; y <= y1; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + z * z > limit) {
                        continue;
                    }
                    if (hollow && interiorOfDisc(x, z, limit)) {
                        continue;
                    }
                    if (!out.add(center.getX() + x, y, center.getZ() + z)) {
                        return;
                    }
                }
            }
        }
    }

    /** Like {@link #interiorOfBall}: buildable rings check diagonals too, so the ring is edge-to-edge connected. */
    private static boolean interiorOfDisc(int x, int z, double limit) {
        boolean buildable = com.testrts.rtsclient.config.RtsConfig.get().buildableShells;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || !buildable && dx != 0 && dz != 0) {
                    continue;
                }
                if (!insideDisc(x + dx, z + dz, limit)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean insideDisc(int x, int z, double limit) {
        return x * x + z * z <= limit;
    }

    /** @param upperHalf a dome: only the top half, standing on the centre's level */
    private static void sphere(Collector out, BlockPos center, BlockPos edge, boolean hollow, boolean upperHalf) {
        double radius = Math.sqrt(center.getSquaredDistance(edge));
        double limit = (radius + 0.5) * (radius + 0.5);
        int r = (int) Math.ceil(radius);
        for (int y = upperHalf ? 0 : -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (!insideBall(x, y, z, limit)) {
                        continue;
                    }
                    if (hollow && interiorOfBall(x, y, z, limit)) {
                        continue;
                    }
                    if (!out.add(center.getX() + x, center.getY() + y, center.getZ() + z)) {
                        return;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ pyramid, cone, torus

    private static void pyramid(Collector out, BlockPos center, BlockPos edge, boolean hollow) {
        int w = Math.max(Math.abs(edge.getX() - center.getX()), Math.abs(edge.getZ() - center.getZ()));
        // Buildable shells make each ring two wide, so every layer rests on the one below.
        int thick = com.testrts.rtsclient.config.RtsConfig.get().buildableShells ? 2 : 1;
        for (int i = 0; i <= w; i++) {
            int half = w - i;
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    boolean ring = Math.max(Math.abs(x), Math.abs(z)) > half - thick;
                    if ((!hollow || ring || half == 0) && !out.add(center.getX() + x, center.getY() + i, center.getZ() + z)) {
                        return;
                    }
                }
            }
        }
    }

    private static void cone(Collector out, BlockPos center, BlockPos edge, int height, boolean hollow) {
        double dx = edge.getX() - center.getX();
        double dz = edge.getZ() - center.getZ();
        double radius = Math.sqrt(dx * dx + dz * dz);
        int h = Math.max(1, Math.abs(height) <= 1 ? (int) Math.round(radius * 2) : Math.abs(height));
        int r = (int) Math.ceil(radius);
        for (int i = 0; i < h; i++) {
            double ri = radius * (1.0 - (double) i / h);
            double limit = (ri + 0.5) * (ri + 0.5);
            // A layer is "inside" where the next one up still covers it, so the outside steps stay closed.
            double above = radius * (1.0 - (double) (i + 1) / h);
            double inner = above - 0.5;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double d2 = x * x + z * z;
                    if (d2 > limit) {
                        continue;
                    }
                    boolean shell = i == h - 1 || inner <= 0 || d2 > inner * inner || interiorOfDiscEdge(x, z, limit);
                    if ((!hollow || shell) && !out.add(center.getX() + x, center.getY() + i, center.getZ() + z)) {
                        return;
                    }
                }
            }
        }
    }

    /** On the rim of a disc (some neighbour falls outside it). */
    private static boolean interiorOfDiscEdge(int x, int z, double limit) {
        return !interiorOfDisc(x, z, limit);
    }

    /** Height is the tube's radius. */
    private static void torus(Collector out, BlockPos center, BlockPos edge, int height, boolean hollow) {
        double dx = edge.getX() - center.getX();
        double dz = edge.getZ() - center.getZ();
        double major = Math.sqrt(dx * dx + dz * dz);
        double minor = Math.max(1, Math.min(Math.abs(height), Math.max(1, (int) major)));
        int rx = (int) Math.ceil(major + minor);
        int ry = (int) Math.ceil(minor);
        for (int y = -ry; y <= ry; y++) {
            for (int x = -rx; x <= rx; x++) {
                for (int z = -rx; z <= rx; z++) {
                    if (!inTorus(x, y, z, major, minor)) {
                        continue;
                    }
                    if (hollow && torusInterior(x, y, z, major, minor)) {
                        continue;
                    }
                    if (!out.add(center.getX() + x, center.getY() + y, center.getZ() + z)) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean inTorus(int x, int y, int z, double major, double minor) {
        double q = Math.sqrt(x * x + z * z) - major;
        return q * q + y * y <= (minor + 0.5) * (minor + 0.5);
    }

    /** Like {@link #interiorOfBall}: every face (and, for buildable shells, edge) neighbour is inside. */
    private static boolean torusInterior(int x, int y, int z, double major, double minor) {
        boolean buildable = com.testrts.rtsclient.config.RtsConfig.get().buildableShells;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (n == 0 || n == 3 || n == 2 && !buildable) {
                        continue;
                    }
                    if (!inTorus(x + dx, y + dy, z + dz, major, minor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ arch, bridge, stairs

    /**
     * Columns along the ground from A toward B (the same cells a one-block wall would use), with
     * the step index; diagonal runs get a corner cell so they stay connected.
     */
    private static java.util.List<int[]> run(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        int px = a.getX();
        int pz = a.getZ();
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0 : (double) i / steps;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            if (i > 0 && x != px && z != pz) {
                cells.add(new int[]{x, pz, i});
            }
            cells.add(new int[]{x, z, i});
            px = x;
            pz = z;
        }
        return cells;
    }

    /** The horizontal direction from A toward B ("north"...), by the longer axis. */
    private static String heading(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    /**
     * Arch between A and B: the outline of a half ellipse as wide as A-B and {@code height} high (a
     * half circle when 0-1). Solid: everything around the opening up to the top, like a gateway.
     */
    private static void arch(Collector out, BlockPos a, BlockPos b, int height, boolean hollow) {
        java.util.List<int[]> cells = run(a, b);
        int steps = cells.isEmpty() ? 0 : cells.get(cells.size() - 1)[2];
        if (steps < 2) {
            line3d(out, a, b);
            return;
        }
        double half = steps / 2.0;
        int rise = Math.abs(height) <= 1 ? (int) Math.ceil(half) : Math.abs(height);
        boolean thick = com.testrts.rtsclient.config.RtsConfig.get().buildableShells;
        for (int[] c : cells) {
            for (int y = 0; y <= rise; y++) {
                boolean inside = inArch(c[2], y, half, rise);
                boolean edge = inside && archEdge(c[2], y, half, rise, thick);
                if ((edge || !hollow && !inside) && !out.add(c[0], a.getY() + y, c[1])) {
                    return;
                }
            }
        }
    }

    private static boolean inArch(int col, int y, double half, int rise) {
        if (y < 0) {
            return true; // the ground closes the bottom
        }
        double u = (col - half) / (half + 0.5);
        double v = y / (rise + 0.5);
        return u * u + v * v <= 1.0;
    }

    /** On the outline: a neighbour (side, or with buildable shells also diagonal) is outside. */
    private static boolean archEdge(int col, int y, double half, int rise, boolean thick) {
        for (int dc = -1; dc <= 1; dc++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dc == 0 && dy == 0 || !thick && dc != 0 && dy != 0) {
                    continue;
                }
                if (!inArch(col + dc, y + dy, half, rise)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Deck from A to B (sloping with B's height) with a gentle hump. Unless hollow: fence railings on
     * both sides (a wall or fence matching the deck block) with lantern posts every few blocks and at
     * both ends.
     */
    private static void bridge(Collector out, BlockPos a, BlockPos b, int height, boolean hollow) {
        java.util.List<int[]> cells = run(a, b);
        int steps = Math.max(1, cells.isEmpty() ? 1 : cells.get(cells.size() - 1)[2]);
        int width = Math.max(1, Math.abs(height));
        boolean alongX = Math.abs(b.getX() - a.getX()) >= Math.abs(b.getZ() - a.getZ());
        int lo = -(width - 1) / 2;
        int hi = lo + width - 1;
        double hump = Math.min(3.0, steps / 8.0);
        // Railings stand just outside the deck, so the whole width stays walkable.
        int railLo = lo - 1;
        int railHi = hi + 1;
        int[] ys = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            ys[i] = a.getY() + (int) Math.round((b.getY() - a.getY()) * t + Math.sin(Math.PI * t) * hump);
        }
        String forward = heading(a, b);
        String back = switch (forward) {
            case "east" -> "west";
            case "west" -> "east";
            case "north" -> "south";
            default -> "north";
        };
        for (int[] c : cells) {
            int y = ys[c[2]];
            // Where the deck steps up or down a block, a stair makes it walkable without jumping.
            String stair = c[2] < steps && ys[c[2] + 1] > y ? forward : c[2] > 0 && ys[c[2] - 1] > y ? back : null;
            boolean post = c[2] == 0 || c[2] == steps || c[2] % 5 == 0;
            for (int w = hollow ? lo : railLo; w <= (hollow ? hi : railHi); w++) {
                int x = alongX ? c[0] : c[0] + w;
                int z = alongX ? c[1] + w : c[1];
                boolean deck = w >= lo && w <= hi;
                if (!out.add(x, y, z)) {
                    return;
                }
                // A step up: the stair sits on top of the lower deck block (walk deck, stair, next deck).
                if (deck && stair != null && (!out.addRole(x, y + 1, z, STAIR) || !out.addFacing(x, y + 1, z, stair))) {
                    return;
                }
                if (w == railLo || w == railHi) {
                    if (!out.addRole(x, y + 1, z, RAIL)) {
                        return;
                    }
                    if (post && (!out.addRole(x, y + 2, z, RAIL) || !out.addRole(x, y + 3, z, LAMP))) {
                        return;
                    }
                }
            }
        }
    }

    /** One block up per block forward from A toward B; stops at B's height when B is higher. */
    private static void stairs(Collector out, BlockPos a, BlockPos b, int height, boolean hollow) {
        BlockPos low = a.getY() <= b.getY() ? a : b;
        BlockPos high = low == a ? b : a;
        String dir = heading(low, high);
        int width = Math.max(1, Math.abs(height));
        boolean alongX = dir.equals("east") || dir.equals("west");
        int fx = alongX ? (dir.equals("east") ? 1 : -1) : 0;
        int fz = alongX ? 0 : (dir.equals("south") ? 1 : -1);
        int run = Math.max(Math.abs(high.getX() - low.getX()), Math.abs(high.getZ() - low.getZ()));
        int rise = high.getY() - low.getY();
        int steps = rise > 0 ? rise : run;
        int lo = -(width - 1) / 2;
        int hi = lo + width - 1;
        for (int i = 0; i <= steps; i++) {
            for (int w = lo; w <= hi; w++) {
                int x = low.getX() + fx * i + (alongX ? 0 : w);
                int z = low.getZ() + fz * i + (alongX ? w : 0);
                int y = low.getY() + i;
                if (!hollow) {
                    for (int below = low.getY(); below < y; below++) {
                        if (!out.add(x, below, z)) {
                            return;
                        }
                    }
                }
                // The step itself is the stair version of the brush (oak planks: oak stairs), facing up.
                if (!out.addRole(x, y, z, STAIR) || !out.addFacing(x, y, z, dir)) {
                    return;
                }
            }
        }
    }

    /** Spiral staircase: one step up per step (8-12 a turn), two wide, around a pole when solid. */
    private static void spiral(Collector out, BlockPos center, BlockPos edge, int height, boolean hollow) {
        double dx = edge.getX() - center.getX();
        double dz = edge.getZ() - center.getZ();
        double radius = Math.max(1.5, Math.sqrt(dx * dx + dz * dz));
        int h = Math.max(2, Math.abs(height));
        // About 8-12 steps per full turn (each step ~2 blocks along the outer edge): reads as a spiral
        // staircase, and a turn rises 8+ blocks so there's always head room.
        double perStep = 2.0 * Math.PI / Math.max(8, Math.round(Math.PI * radius));
        double inner = Math.max(0.5, radius - 1.5);
        for (int i = 0; i < h; i++) {
            int y = center.getY() + i;
            // The step covers a little more than its own angle so it overlaps the next one.
            for (double ang = i * perStep; ang <= (i + 1.4) * perStep; ang += perStep / 3.0) {
                double cs = Math.cos(ang);
                double sn = Math.sin(ang);
                // Walking up means moving along the turn (-sin, cos), so a stair block faces that way.
                double tx = -sn;
                double tz = cs;
                String dir = Math.abs(tx) >= Math.abs(tz) ? (tx > 0 ? "east" : "west") : (tz > 0 ? "south" : "north");
                for (double r = inner; r <= radius + 0.01; r += 0.5) {
                    int x = center.getX() + (int) Math.round(cs * r);
                    int z = center.getZ() + (int) Math.round(sn * r);
                    if (!out.positions.contains(new BlockPos(x, y, z)) && !out.add(x, y, z, dir)) {
                        return;
                    }
                }
            }
            if (!hollow && !out.add(center.getX(), y, center.getZ())) {
                return;
            }
        }
    }

    /** Flood fill through blocks of the same type as {@code start} (26-connected, like ore veins). */
    private static void vein(Collector out, BlockPos start) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        Block block = world.getBlockState(start).getBlock();
        if (world.getBlockState(start).isAir()) {
            out.add(start.getX(), start.getY(), start.getZ());
            return;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            if (!out.add(p.getX(), p.getY(), p.getZ())) {
                return;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.add(dx, dy, dz);
                        if (seen.add(n) && world.isChunkLoaded(n) && world.getBlockState(n).getBlock() == block) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
    }

    /**
     * Flood fill through air/fluid/replaceable blocks, sideways and down but never above the start,
     * so clicking into a pit fills it up to that level. Gives up (empty result) if it leaks out of
     * a 48-block radius, i.e. the "hole" isn't enclosed.
     */
    private static void hole(Collector out, BlockPos start) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || !fillable(world.getBlockState(start))) {
            return;
        }
        final int radius = 48;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            if (Math.abs(p.getX() - start.getX()) > radius || Math.abs(p.getZ() - start.getZ()) > radius
                    || p.getY() < world.getBottomY()) {
                out.positions.clear();
                out.truncated = false;
                return;
            }
            if (!out.add(p.getX(), p.getY(), p.getZ())) {
                return;
            }
            for (net.minecraft.util.math.Direction d : new net.minecraft.util.math.Direction[]{
                    net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH,
                    net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST,
                    net.minecraft.util.math.Direction.DOWN}) {
                BlockPos n = p.offset(d);
                if (seen.add(n) && world.isChunkLoaded(n) && fillable(world.getBlockState(n))) {
                    queue.add(n);
                }
            }
        }
    }

    private static boolean fillable(net.minecraft.block.BlockState s) {
        // Water, lava, grass and snow layers are all "replaceable".
        return s.isAir() || s.isReplaceable();
    }

    /**
     * Inside the shell? Plain shells only look at the 6 face neighbours, which leaves the flat parts of
     * a dome touching the layer below only along edges, so nothing can be placed against them.
     * Buildable shells also look at the 12 edge neighbours: the outside looks identical, the inside
     * is thicker where needed, and every block has a face to attach to.
     */
    private static boolean interiorOfBall(int x, int y, int z, double limit) {
        boolean buildable = com.testrts.rtsclient.config.RtsConfig.get().buildableShells;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (n == 0 || n == 3 || n == 2 && !buildable) {
                        continue;
                    }
                    if (!insideBall(x + dx, y + dy, z + dz, limit)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean insideBall(int x, int y, int z, double limit) {
        return x * x + y * y + z * z <= limit;
    }
}
