package com.testrts.rtsclient.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

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
        return generate(shape, a, b, height, hollow, limit, inverted, bridgeStyle, IslandStyle.GRASSY, IslandBottom.POINTED);
    }

    /**
     * @param islandStyle  the look of the Island shape (see {@link IslandStyle})
     * @param islandBottom the shape of its underside (see {@link IslandBottom})
     */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted,
                                  BridgeStyle bridgeStyle, IslandStyle islandStyle, IslandBottom islandBottom) {
        return generate(shape, a, b, height, hollow, limit, inverted, bridgeStyle, islandStyle, islandBottom, 0L);
    }

    /** @param islandSeed which design the Island shape gets (see {@link BuildSession#islandSeed}) */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted,
                                  BridgeStyle bridgeStyle, IslandStyle islandStyle, IslandBottom islandBottom, long islandSeed) {
        return generate(shape, a, b, height, hollow, limit, inverted, bridgeStyle, islandStyle, islandBottom, islandSeed, MountainForm.PEAKED);
    }

    /** @param mountainForm the kind of mountain the Mountain shape makes (see {@link MountainForm}) */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted,
                                  BridgeStyle bridgeStyle, IslandStyle islandStyle, IslandBottom islandBottom, long islandSeed,
                                  MountainForm mountainForm) {
        return generate(shape, a, b, height, hollow, limit, inverted, bridgeStyle, islandStyle, islandBottom, islandSeed, mountainForm,
                TreeKind.OAK, RockForm.BOULDERS, RockLook.STONE);
    }

    /** @param treeKind the Giant tree's kind; {@code rockForm} / {@code rockLook} what the Rocks shape makes and of what */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted,
                                  BridgeStyle bridgeStyle, IslandStyle islandStyle, IslandBottom islandBottom, long islandSeed,
                                  MountainForm mountainForm, TreeKind treeKind, RockForm rockForm, RockLook rockLook) {
        return generate(shape, a, b, height, hollow, limit, inverted, bridgeStyle, islandStyle, islandBottom, islandSeed, mountainForm,
                treeKind, rockForm, rockLook, MazeStyle.HEDGE, MazeLayout.CLASSIC, BackroomsLevel.LEVEL_0);
    }

    /** @param mazeStyle / {@code mazeLayout} the Maze shape's walls and paths; {@code backroomsLevel} the Backrooms' look */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit, boolean inverted,
                                  BridgeStyle bridgeStyle, IslandStyle islandStyle, IslandBottom islandBottom, long islandSeed,
                                  MountainForm mountainForm, TreeKind treeKind, RockForm rockForm, RockLook rockLook,
                                  MazeStyle mazeStyle, MazeLayout mazeLayout, BackroomsLevel backroomsLevel) {
        Collector out = new Collector(limit);
        // The Circle has no height: it carries the size of the hole in its middle (see BuildSession#circleHole).
        int circleHole = hollow ? 0 : Math.max(0, height);
        if (height == 0) {
            height = 1;
        }
        switch (shape) {
            case BLOCK -> out.add(a.getX(), a.getY(), a.getZ());
            case LINE -> line3d(out, a, b);
            case FLOOR -> floor(out, a, b, hollow);
            case WALL -> wall(out, a, b, height, hollow);
            case BOX -> box(out, a, b, height, hollow);
            case CIRCLE -> cylinder(out, a, b, 1, hollow, circleHole);
            case CYLINDER -> cylinder(out, a, b, height, hollow);
            case SPHERE -> sphere(out, a, b, hollow, false);
            case DOME -> sphere(out, a, b, hollow, true);
            case PYRAMID -> pyramid(out, a, b, hollow);
            case CONE -> cone(out, a, b, height, hollow);
            case TORUS -> torus(out, a, b, height, hollow);
            case ARCH -> arch(out, a, b, height, hollow);
            case BRIDGE -> {
                // Inverted bridges sag down in the middle instead of humping up.
                if (bridgeStyle == null || bridgeStyle == BridgeStyle.CLASSIC) {
                    bridge(out, a, b, height, hollow, inverted);
                } else {
                    styledBridge(out, a, b, height, bridgeStyle, inverted);
                }
            }
            case STAIRS -> stairs(out, a, b, height, hollow);
            case SPIRAL -> spiral(out, a, b, height, hollow);
            case VEIN -> vein(out, a);
            case HOLE -> hole(out, a);
            case ISLAND -> island(out, a, b, height, hollow, islandStyle == null ? IslandStyle.GRASSY : islandStyle,
                    islandBottom == null ? IslandBottom.POINTED : islandBottom, islandSeed);
            case MOUNTAIN -> mountain(out, a, b, height, hollow, islandStyle == null ? IslandStyle.GRASSY : islandStyle, islandSeed,
                    mountainForm);
            case GIANT_TREE -> giantTree(out, a, b, height, treeKind, islandSeed);
            case ROCKS -> rocks(out, a, b, height, rockForm, rockLook, islandSeed);
            case MAZE -> maze(out, a, b, height, mazeStyle, mazeLayout, islandSeed);
            case BACKROOMS -> backrooms(out, a, b, height, backroomsLevel, islandSeed);
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

    /**
     * Deck heights along the run, with a hump in the middle of {@code hump} blocks at most; or, when
     * {@code sag}, hanging down in the middle like a rope bridge (deeper the longer it is).
     */
    private static int[] deckHeights(BlockPos a, BlockPos b, int steps, double hump, boolean sag) {
        if (sag) {
            double depth = Math.min(10.0, Math.max(1.0, steps / 7.0));
            int[] ys = new int[steps + 1];
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                // A parabola is close to how a hanging rope curves.
                ys[i] = a.getY() + (int) Math.round((b.getY() - a.getY()) * t - 4 * t * (1 - t) * depth);
            }
            return ys;
        }
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

    private static void styledBridge(Collector out, BlockPos a, BlockPos b, int height, BridgeStyle style, boolean sag) {
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
            case ROPE -> Math.min(3.0, steps / 7.0);
            case MODERN, SUSPENSION, TRESTLE, TRUSS, DWARVEN -> 0.0;
            case JAPANESE -> Math.min(4.0, steps / 3.0);
            case BAMBOO -> Math.min(1.5, steps / 8.0);
            default -> Math.min(2.0, steps / 10.0);
        };
        // Rope bridges always hang down in the middle.
        int[] ys = deckHeights(a, b, steps, hump, sag || style == BridgeStyle.ROPE);
        // Stone bridges: arches between piers, about every 10 blocks (Viaduct and Dwarven: every 7, deeper).
        boolean deepArches = style == BridgeStyle.VIADUCT || style == BridgeStyle.DWARVEN;
        int arches = Math.max(1, (int) Math.round(steps / (deepArches ? 7.0 : 10.0)));
        double archLen = (double) steps / arches;
        int archDepth = deepArches ? 5 : 3;
        int mid = steps / 2;
        // Suspension: two towers a fifth in from the ends (at the ends on short bridges), cables between them.
        int towerA = steps < 10 ? 0 : (int) Math.round(steps * 0.2);
        int towerB = steps - towerA;
        int towerH = 8 + Math.min(12, steps / 5);
        int[] cable = style == BridgeStyle.SUSPENSION ? cableHeights(ys, towerA, towerB, towerH) : null;
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
                case VIADUCT, STONE, OVERGROWN, NETHER, SANDSTONE, DWARVEN, OCEAN, END -> {
                    boolean mossy = style == BridgeStyle.OVERGROWN;
                    boolean viaduct = deepArches;
                    // Lights on every other pillar cap, except the plain stone ones.
                    boolean capLights = style != BridgeStyle.STONE && !mossy;
                    String[] m = stoneMaterials(style);
                    String plain = m[0];
                    String chiseled = m[1];
                    String wallBlock = m[2];
                    String slabBlock = m[3];
                    String light = m[6];
                    int seg = (int) Math.floor(i / archLen);
                    int segStart = (int) Math.round(seg * archLen);
                    int segEnd = (int) Math.round((seg + 1) * archLen);
                    double t = (i - seg * archLen) / archLen;
                    boolean pier = end || i == segStart || i == segEnd;
                    int depth = pier ? 0 : (int) Math.round(archDepth * (1.0 - Math.sin(Math.PI * t)));
                    String towardPier = t < 0.5 ? br.back : br.forward;
                    boolean capStep = end || i % 4 == 0;
                    String stairsBlock = m[4];
                    String path = m[5];
                    for (int w = railLo; w <= railHi; w++) {
                        boolean edge = w == railLo || w == railHi;
                        String brick = mossy ? stoneMix(c, w, y) : plain;
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
                                    br.put(c, w, y + 1, chiseled);
                                    br.put(c, w, y + 2, wallBlock);
                                    br.put(c, w, y + 3, light);
                                } else if (i % 2 == 0) {
                                    br.put(c, w, y + 1, plain);
                                    br.put(c, w, y + 2, slabBlock);
                                } else {
                                    br.put(c, w, y + 1, wallBlock);
                                }
                            } else if (capStep) {
                                br.put(c, w, y + 1, brick);
                                br.put(c, w, y + 2, mossy && hash(c[0], y, c[1], 3) == 0 ? "minecraft:azalea_leaves"
                                        : capLights && (end || i % 8 == 0) ? light : slabBlock);
                            } else {
                                br.put(c, w, y + 1, mossy ? "minecraft:mossy_stone_brick_wall" : wallBlock);
                                if (mossy && hash(c[0], y + 2, c[1], 4) == 0) {
                                    br.put(c, w, y + 2, "minecraft:azalea_leaves");
                                }
                            }
                        }
                    }
                    // Under the deck: the arch (curved with upside-down stairs, keystone at the crown), or a pier.
                    for (int w = railLo - 1; w <= railHi + 1; w++) {
                        boolean outerCol = w < railLo || w > railHi;
                        String brick = mossy ? stoneMix(c, w, y - 1) : plain;
                        if (pier) {
                            if (!outerCol || viaduct) {
                                // Viaduct piers get buttresses past the railing.
                                br.put(c, w, y - 1, viaduct ? chiseled : brick);
                                br.down(c, w, y - 2, brick);
                            }
                        } else if (!outerCol) {
                            if (depth == 0) {
                                br.put(c, w, y - 1, chiseled);
                            }
                            for (int d = 1; d <= depth; d++) {
                                String block = mossy ? stoneMix(c, w, y - d) : plain;
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
                            br.put(c, w, y + 6, "minecraft:stripped_oak_log[axis=ACROSS]");
                        }
                    }
                    if (post && lo <= 0 && 0 <= hi) {
                        br.put(c, 0, y + 5, "minecraft:lantern[hanging=true]");
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
                            for (int k = 1; k <= 5; k++) {
                                br.put(c, side, y + k, "minecraft:oak_log[axis=y]");
                            }
                        } else {
                            br.put(c, side, y + 1, "minecraft:oak_fence");
                        }
                        br.put(c, side, y + 6, "minecraft:stripped_oak_log[axis=RUN]");
                    }
                    // Gable roof of cherry stairs over the whole width, ridge of planks.
                    for (int k = 0; ; k++) {
                        int left = railLo - 1 + k;
                        int right = railHi + 1 - k;
                        int ry = y + 6 + k;
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
                case ROPE -> {
                    // Planks on rope: a sagging deck between two log towers, rope (chain) handrails on fence posts.
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, i % 3 == 0 ? "minecraft:dark_oak_slab[type=top]" : "minecraft:spruce_slab[type=top]");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:spruce_stairs[facing=" + stairDir + "]");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        if (end) {
                            for (int k = 1; k <= 4; k++) {
                                br.put(c, side, y + k, "minecraft:spruce_log[axis=y]");
                            }
                            br.put(c, side, y + 5, "minecraft:lantern");
                            br.put(c, side, y, "minecraft:spruce_log[axis=y]");
                            br.down(c, side, y - 1, "minecraft:spruce_log[axis=y]");
                        } else {
                            br.put(c, side, y, "minecraft:spruce_slab[type=top]");
                            br.put(c, side, y + 1, i % 3 == 0 ? "minecraft:spruce_fence" : "minecraft:chain[axis=RUN]");
                            if (i % 3 == 0) {
                                // Ropes hanging under the deck.
                                br.put(c, side, y - 1, "minecraft:chain[axis=y]");
                            }
                        }
                    }
                    if (end) {
                        // A beam across the top of the towers, with a lantern hanging over the way in.
                        for (int w = railLo; w <= railHi; w++) {
                            br.put(c, w, y + 4, "minecraft:stripped_spruce_log[axis=ACROSS]");
                        }
                        br.put(c, 0, y + 3, "minecraft:lantern[hanging=true]");
                    }
                }
                case MODERN -> {
                    // Smooth quartz deck with a grey centre line, glass railings, sea lantern posts, slim pylons.
                    boolean post = end || i % 6 == 0;
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, w == 0 && width >= 3 ? "minecraft:light_gray_concrete" : "minecraft:smooth_quartz");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:smooth_quartz_stairs[facing=" + stairDir + "]");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        br.put(c, side, y, "minecraft:smooth_quartz");
                        br.put(c, outer, y, "minecraft:smooth_quartz_slab[type=top]");
                        if (post) {
                            br.put(c, side, y + 1, "minecraft:quartz_pillar[axis=y]");
                            br.put(c, side, y + 2, "minecraft:sea_lantern");
                        } else {
                            br.put(c, side, y + 1, "minecraft:white_stained_glass_pane");
                        }
                    }
                    if (post) {
                        for (int w = railLo; w <= railHi; w++) {
                            br.put(c, w, y - 1, "minecraft:smooth_stone_slab[type=top]");
                        }
                    }
                    if (end || i % 12 == 0) {
                        br.down(c, 0, y - 1, "minecraft:quartz_pillar[axis=y]");
                    }
                }
                case JAPANESE -> {
                    // A steep drum bridge: dark deck, red railing with a handrail on top, capped posts.
                    boolean post = end || i % 3 == 0;
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, "minecraft:dark_oak_planks");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:dark_oak_stairs[facing=" + stairDir + "]");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        String in = side == railLo ? br.pos : br.neg;
                        br.put(c, side, y, "minecraft:stripped_mangrove_log[axis=RUN]");
                        br.put(c, outer, y, "minecraft:mangrove_stairs[facing=" + in + ",half=top]");
                        if (post) {
                            br.put(c, side, y + 1, "minecraft:stripped_mangrove_log[axis=y]");
                            br.put(c, side, y + 2, "minecraft:stripped_mangrove_log[axis=y]");
                            br.put(c, side, y + 3, end ? "minecraft:lantern" : "minecraft:dark_oak_slab");
                        } else {
                            br.put(c, side, y + 1, "minecraft:mangrove_fence");
                            br.put(c, side, y + 2, "minecraft:mangrove_slab");
                        }
                        if (end) {
                            br.down(c, side, y - 1, "minecraft:mossy_cobblestone");
                        } else {
                            // Red stringers along the curved underside.
                            br.put(c, side, y - 1, "minecraft:stripped_mangrove_log[axis=RUN]");
                        }
                    }
                    if (end) {
                        for (int w = lo; w <= hi; w++) {
                            br.down(c, w, y - 1, "minecraft:mossy_cobblestone");
                        }
                    }
                }
                case BAMBOO -> {
                    boolean post = end || i % 4 == 0;
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, "minecraft:bamboo_mosaic");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:bamboo_mosaic_stairs[facing=" + stairDir + "]");
                        }
                    }
                    if (post) {
                        // Cross beam under the deck, from railing to railing.
                        for (int w = railLo; w <= railHi; w++) {
                            br.put(c, w, y - 1, "minecraft:bamboo_block[axis=ACROSS]");
                        }
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        String in = side == railLo ? br.pos : br.neg;
                        br.put(c, side, y, "minecraft:bamboo_block[axis=RUN]");
                        br.put(c, outer, y, "minecraft:bamboo_stairs[facing=" + in + ",half=top]");
                        if (post) {
                            br.put(c, side, y + 1, "minecraft:stripped_bamboo_block[axis=y]");
                            br.put(c, side, y + 2, "minecraft:bamboo_fence");
                            br.put(c, side, y + 3, "minecraft:lantern");
                            br.down(c, side, y - 2, "minecraft:bamboo_block[axis=y]");
                        } else {
                            br.put(c, side, y + 1, "minecraft:bamboo_fence");
                        }
                    }
                }
                case SUSPENSION -> {
                    // Road deck with a dashed centre line, iron railing; cables from tower to tower with chain hangers.
                    boolean tower = i == towerA || i == towerB;
                    for (int w = lo; w <= hi; w++) {
                        boolean line = w == 0 && width >= 3 && i % 2 == 0;
                        br.put(c, w, y, line ? "minecraft:yellow_concrete" : "minecraft:gray_concrete");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:stone_stairs[facing=" + stairDir + "]");
                        }
                    }
                    int top = Math.max(cable[i], Math.max(i > 0 ? cable[i - 1] : cable[i], i < steps ? cable[i + 1] : cable[i]) - 1);
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        br.put(c, side, y, "minecraft:red_nether_bricks");
                        br.put(c, outer, y, "minecraft:red_nether_brick_slab[type=top]");
                        if (tower) {
                            for (int k = 1; k <= towerH; k++) {
                                br.put(c, side, y + k, "minecraft:red_nether_bricks");
                            }
                            br.put(c, side, y + towerH + 1, "minecraft:redstone_lamp");
                            br.down(c, side, y - 1, "minecraft:red_nether_bricks");
                            br.down(c, outer, y - 1, "minecraft:red_nether_bricks");
                            continue;
                        }
                        br.put(c, side, y + 1, "minecraft:iron_bars");
                        // The cable, filled up to its neighbours so it has no gaps where it climbs.
                        for (int k = cable[i]; k <= top; k++) {
                            br.put(c, side, k, "minecraft:red_nether_brick_wall");
                        }
                        if (i % 2 == 0) {
                            for (int k = y + 2; k < cable[i]; k++) {
                                br.put(c, side, k, "minecraft:chain[axis=y]");
                            }
                        }
                    }
                    if (tower) {
                        // Portal beams between the towers, high enough to drive under.
                        for (int w = lo; w <= hi; w++) {
                            br.put(c, w, y + towerH, "minecraft:red_nether_bricks");
                            br.put(c, w, y + towerH - 4, "minecraft:red_nether_brick_slab[type=top]");
                        }
                    }
                    if (end) {
                        // Anchorages at both ends.
                        for (int w = railLo; w <= railHi; w++) {
                            br.down(c, w, y - 1, "minecraft:stone_bricks");
                        }
                    }
                }
                case TRESTLE -> {
                    // A track down the middle (rails climb by themselves where the deck steps up), timber bents below.
                    boolean bent = end || i % 3 == 0;
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, "minecraft:spruce_planks");
                        if (w == 0) {
                            br.put(c, w, y + 1, "minecraft:rail");
                        } else if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:spruce_stairs[facing=" + stairDir + "]");
                        }
                    }
                    if (bent) {
                        for (int w = railLo - 1; w <= railHi + 1; w++) {
                            br.put(c, w, y - 1, "minecraft:spruce_log[axis=ACROSS]");
                        }
                        br.down(c, 0, y - 2, "minecraft:spruce_fence");
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        int outer = side == railLo ? railLo - 1 : railHi + 1;
                        br.put(c, side, y, "minecraft:stripped_spruce_log[axis=RUN]");
                        br.put(c, side, y + 1, "minecraft:spruce_fence");
                        if (i % 6 == 0) {
                            br.put(c, side, y + 2, "minecraft:lantern");
                        }
                        if (bent) {
                            br.down(c, side, y - 2, "minecraft:spruce_log[axis=y]");
                            br.down(c, outer, y - 2, "minecraft:spruce_fence");
                        } else {
                            br.put(c, side, y - 1, "minecraft:stripped_spruce_log[axis=RUN]");
                        }
                    }
                }
                case TRUSS -> {
                    // Warren truss on both sides: chords at deck level and 4 up, diagonals zig-zagging between them.
                    String steel = "minecraft:waxed_oxidized_cut_copper";
                    int t = i % 6;
                    int h = t <= 3 ? t : 6 - t;
                    boolean vertical = end || t == 0 || t == 3;
                    for (int w = lo; w <= hi; w++) {
                        br.put(c, w, y, "minecraft:polished_andesite");
                        if (stairDir != null) {
                            br.put(c, w, y + 1, "minecraft:polished_andesite_stairs[facing=" + stairDir + "]");
                        }
                        if (vertical) {
                            // Overhead bracing, 3 blocks clear above the deck.
                            br.put(c, w, y + 4, "minecraft:waxed_oxidized_cut_copper_slab[type=top]");
                        }
                    }
                    if (t == 3 && lo <= 0 && 0 <= hi) {
                        br.put(c, 0, y + 3, "minecraft:lantern[hanging=true]");
                    }
                    for (int side : new int[]{railLo, railHi}) {
                        br.put(c, side, y, steel);
                        br.put(c, side, y + 4, steel);
                        for (int k = 1; k <= 3; k++) {
                            if (vertical || k == h + 1 && h < 3) {
                                br.put(c, side, y + k, steel);
                            } else if (k == 1) {
                                br.put(c, side, y + k, "minecraft:iron_bars");
                            }
                        }
                    }
                    if (end || i % 12 == 0) {
                        for (int w = railLo; w <= railHi; w++) {
                            br.down(c, w, y - 1, "minecraft:stone_bricks");
                        }
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

    /** Arch bridge materials: {bricks, chiseled (pier tops), wall, slab, stairs, path (deck middle), light}. */
    private static String[] stoneMaterials(BridgeStyle style) {
        return switch (style) {
            case NETHER -> new String[]{"minecraft:polished_blackstone_bricks", "minecraft:chiseled_polished_blackstone",
                    "minecraft:polished_blackstone_brick_wall", "minecraft:polished_blackstone_brick_slab",
                    "minecraft:polished_blackstone_brick_stairs", "minecraft:polished_blackstone", "minecraft:soul_lantern"};
            case SANDSTONE -> new String[]{"minecraft:smooth_sandstone", "minecraft:chiseled_sandstone", "minecraft:sandstone_wall",
                    "minecraft:smooth_sandstone_slab", "minecraft:smooth_sandstone_stairs", "minecraft:cut_red_sandstone", "minecraft:lantern"};
            case DWARVEN -> new String[]{"minecraft:deepslate_bricks", "minecraft:chiseled_deepslate", "minecraft:deepslate_brick_wall",
                    "minecraft:deepslate_brick_slab", "minecraft:deepslate_brick_stairs", "minecraft:deepslate_tiles", "minecraft:lantern"};
            case OCEAN -> new String[]{"minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:prismarine_wall",
                    "minecraft:prismarine_brick_slab", "minecraft:prismarine_brick_stairs", "minecraft:dark_prismarine", "minecraft:sea_lantern"};
            case END -> new String[]{"minecraft:end_stone_bricks", "minecraft:purpur_pillar[axis=y]", "minecraft:end_stone_brick_wall",
                    "minecraft:end_stone_brick_slab", "minecraft:end_stone_brick_stairs", "minecraft:purpur_block", "minecraft:end_rod[facing=up]"};
            case OVERGROWN -> new String[]{"minecraft:stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:stone_brick_wall",
                    "minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_stairs", "minecraft:mossy_cobblestone", "minecraft:lantern"};
            default -> new String[]{"minecraft:stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:stone_brick_wall",
                    "minecraft:stone_brick_slab", "minecraft:stone_brick_stairs", "minecraft:polished_andesite", "minecraft:lantern"};
        };
    }

    /**
     * Suspension cable height (absolute y) over each deck cell: from the deck at the ends up to the tower tops,
     * and between the towers hanging down to just above the deck in the middle.
     */
    private static int[] cableHeights(int[] ys, int towerA, int towerB, int towerH) {
        int steps = ys.length - 1;
        int topA = ys[towerA] + towerH;
        int topB = ys[towerB] + towerH;
        int mid = (towerA + towerB) / 2;
        int[] cable = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double y;
            if (i < towerA) {
                y = ys[0] + 1 + (topA - ys[0] - 1) * (double) i / towerA;
            } else if (i > towerB) {
                y = ys[steps] + 1 + (topB - ys[steps] - 1) * (double) (steps - i) / (steps - towerB);
            } else {
                double s = towerB == towerA ? 0 : (double) (i - towerA) / (towerB - towerA);
                double straight = topA + (topB - topA) * s;
                double sagDepth = topA + (topB - topA) * 0.5 - (ys[mid] + 2);
                // A parabola, like a loaded cable.
                y = straight - 4 * s * (1 - s) * sagDepth;
            }
            cable[i] = Math.max(ys[i] + 2, (int) Math.round(y));
        }
        return cable;
    }

    // ------------------------------------------------------------------ floating islands

    /**
     * A floating island. A is the middle of its top, B how far it reaches (the radius: drag B further
     * out to scale it up). The top is the style's surface with gentle hills, trees and plants; the
     * underside follows {@code bottom}: a point with drips, a round bowl, terraces, or torn out of the
     * ground with loose chunks below. Height is how deep it hangs (1-3: about the radius). Each column is
     * solid from top to bottom, so there are no ledges underneath for mobs to spawn on (the loose chunks
     * of Torn out excepted). Hollow: only a 2 block shell. {@code islandSeed} picks the design: outline,
     * lobes, bulges, depth, hills, where the deepest point is, how many trees and plants.
     */
    private static void island(Collector out, BlockPos a, BlockPos b, int height, boolean hollow, IslandStyle style,
                               IslandBottom bottom, long islandSeed) {
        double bx = b.getX() - a.getX();
        double bz = b.getZ() - a.getZ();
        double radius = Math.max(3.0, Math.sqrt(bx * bx + bz * bz));
        int cx = a.getX();
        int cy = a.getY();
        int cz = a.getZ();
        // Every island is its own design, from the seed the session holds (a new one each time an island is
        // queued, or with [N]). Preview and build share the seed, so what you see is what gets built.
        java.util.Random rng = new java.util.Random(islandSeed);
        int seed = rng.nextInt(1 << 20);
        // Shifts the block-by-block noise (ores, drips, trees, plants), so no two islands share those either.
        int[] n = {rng.nextInt(1 << 16) - (1 << 15), rng.nextInt(1 << 12), rng.nextInt(1 << 16) - (1 << 15)};
        double rimPhase = rng.nextDouble() * Math.PI * 2;
        double hillPhase = rng.nextDouble() * Math.PI * 2;
        double deepPhase = rng.nextDouble() * Math.PI * 2;
        // Outline: stretched along a random direction, a rim with its own number and size of lobes, and up to
        // three bulges sticking out, each hanging down a little on its own.
        double turn = rng.nextDouble() * Math.PI * 2;
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        double stretch = 0.65 + 0.35 * rng.nextDouble();
        int[] lobes = {2 + rng.nextInt(3), 4 + rng.nextInt(3), 7 + rng.nextInt(4)};
        double rimAmp = 0.12 + 0.2 * rng.nextDouble();
        double[][] bulges = new double[rng.nextInt(4)][];
        for (int i = 0; i < bulges.length; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = radius * (0.45 + 0.3 * rng.nextDouble());
            bulges[i] = new double[]{Math.cos(angle) * dist, Math.sin(angle) * dist, radius * (0.28 + 0.22 * rng.nextDouble()),
                    rng.nextDouble() * Math.PI * 2};
        }
        // The deepest point sits off the middle, toward a random side.
        double tiltX = Math.cos(rng.nextDouble() * Math.PI * 2);
        double tiltZ = Math.sqrt(1 - tiltX * tiltX) * (rng.nextBoolean() ? 1 : -1);
        double tilt = 0.35 * rng.nextDouble();
        double depthMax = Math.abs(height) > 3 ? Math.abs(height)
                : Math.max(2.0, radius * bottom.depthScale * (0.8 + 0.45 * rng.nextDouble()));
        double step = Math.max(2, Math.round(depthMax / 5));
        double hills = radius * (0.05 + 0.15 * rng.nextDouble());
        int dripOdds = 15 + rng.nextInt(30);
        double treeFactor = 0.6 + rng.nextDouble();
        double coverFactor = 0.6 + 0.8 * rng.nextDouble();
        int soilDepth = 2 + (int) (radius / 12);
        int reach = (int) Math.ceil(radius * 1.3) + 1;

        // Columns: {x offset, z offset, top, bottom} (top and bottom relative to A), and how far in (1: middle).
        java.util.Map<Long, int[]> columns = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Double> inward = new java.util.HashMap<>();
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                // Along and across the stretch.
                double lx = x * cos + z * sin;
                double lz = (-x * sin + z * cos) / stretch;
                double d = Math.sqrt(lx * lx + lz * lz);
                double edge = radius * (1 - rimAmp + rimAmp * wave(Math.atan2(lz, lx), rimPhase, lobes));
                double tMain = 1 - d / edge;
                double t = tMain;
                // Rounded: one smooth bowl under the body, and a smaller, shallower one under each bulge.
                double bowl = tMain > 0 ? Math.sqrt(1 - (1 - tMain) * (1 - tMain)) : 0;
                for (double[] g : bulges) {
                    double bx2 = x - g[0];
                    double bz2 = z - g[1];
                    double bEdge = g[2] * (0.8 + 0.2 * wave(Math.atan2(bz2, bx2), g[3], lobes));
                    double tb = 1 - Math.sqrt(bx2 * bx2 + bz2 * bz2) / bEdge;
                    t = Math.max(t, tb * 0.75);
                    if (tb > 0) {
                        bowl = Math.max(bowl, Math.sqrt(1 - (1 - tb) * (1 - tb)) * Math.min(0.8, g[2] / radius * 1.3));
                    }
                }
                if (t <= 0) {
                    continue;
                }
                int wx = cx + x;
                int wz = cz + z;
                int top = (int) Math.round(hills * t * t * field(wx, wz, hillPhase, radius));
                double wobble = 0.75 + 0.5 * field(wx, wz, deepPhase, radius * 0.6);
                double depth = switch (bottom) {
                    case POINTED -> 1 + (depthMax - 1) * Math.pow(t, 1.3) * wobble + ih(n, wx, 0, wz, 3) * 0.5;
                    // A bowl: deep right up to near the rim, then curving in.
                    case ROUNDED -> 1 + (depthMax - 1) * bowl * (0.85 + 0.2 * wobble)
                            + ih(n, wx, 0, wz, 2) * 0.5;
                    // Whole steps, so each ring is a flat terrace (seen from below).
                    case STEPPED -> Math.max(1, Math.ceil(depthMax * Math.pow(t, 0.9) * (0.9 + 0.1 * wobble) / step) * step);
                    // Ragged: lumpy in 2x2 and 4x4 clumps, like earth that came up with it.
                    case TORN -> 1 + (depthMax - 1) * Math.pow(t, 0.7) * (0.55 + 0.6 * field(wx, wz, deepPhase, radius * 0.4))
                            + ih(n, wx >> 1, 11, wz >> 1, 4) + ih(n, wx, 12, wz, 2)
                            + (ih(n, wx >> 2, 13, wz >> 2, 6) == 0 ? 3 : 0) * t;
                };
                // Not for flat-bottomed ones: a tilted bowl or terrace just looks lopsided.
                if (bottom == IslandBottom.POINTED || bottom == IslandBottom.TORN) {
                    depth = Math.max(1, depth * (1 + tilt * (x * tiltX + z * tiltZ) / radius));
                }
                if (bottom == IslandBottom.POINTED && t > 0.25 && ih(n, wx, 7, wz, dripOdds) == 0) {
                    // A drip: a short spike under the island.
                    depth += 2 + ih(n, wx, 9, wz, Math.max(1, (int) (radius / 4)));
                }
                long key = columnKey(x, z);
                columns.put(key, new int[]{x, z, top, -(int) Math.round(depth)});
                inward.put(key, t);
            }
        }

        java.util.Map<BlockPos, String> blocks = new java.util.HashMap<>();
        for (int[] c : columns.values()) {
            int wx = cx + c[0];
            int wz = cz + c[1];
            for (int y = c[2]; y >= c[3]; y--) {
                int fromTop = c[2] - y;
                if (hollow && fromTop > soilDepth && y - c[3] >= 2 && enclosed(columns, c[0], c[1], y)) {
                    continue;
                }
                int wy = cy + y;
                String spec;
                if (fromTop == 0) {
                    spec = style.top;
                } else if (fromTop <= soilDepth
                        || bottom == IslandBottom.TORN && y - c[3] < 2 && ih(n, wx, wy, wz, 2) == 0) {
                    // Torn out: clods of soil on the underside too.
                    spec = style.soil[ih(n, wx, wy, wz, style.soil.length)];
                } else if (ih(n, wx, wy, wz, 40) == 0) {
                    spec = style.ores[ih(n, wz, wy, wx, style.ores.length)];
                } else if (style == IslandStyle.DESERT) {
                    // Terracotta in bands, like a badlands cliff.
                    spec = style.stone[Math.floorMod(Math.floorDiv(wy, 2) + seed, style.stone.length)];
                } else {
                    spec = style.stone[ih(n, wx, wy, wz, style.stone.length)];
                }
                blocks.put(new BlockPos(wx, wy, wz), spec);
            }
        }

        // Trees, spread out, not too close to the rim.
        int spacing = switch (style.tree) {
            case CACTUS -> 3;
            case SPRUCE -> 4;
            case OAK, CRIMSON -> 5;
            case CHERRY, MUSHROOM -> 7;
        };
        int odds = switch (style.tree) {
            case SPRUCE -> 10;
            case OAK -> 14;
            case CRIMSON -> 16;
            case CACTUS -> 18;
            case CHERRY, MUSHROOM -> 22;
        };
        java.util.List<int[]> trees = new java.util.ArrayList<>();
        for (int[] c : columns.values()) {
            int wx = cx + c[0];
            int wz = cz + c[1];
            if (inward.get(columnKey(c[0], c[1])) < 0.22 || ih(n, wx, 1, wz, Math.max(3, (int) Math.round(odds / treeFactor))) != 0) {
                continue;
            }
            boolean crowded = false;
            for (int[] o : trees) {
                if (Math.abs(o[0] - wx) < spacing && Math.abs(o[2] - wz) < spacing) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                int[] t = {wx, cy + c[2] + 1, wz};
                trees.add(t);
                islandTree(blocks, style.tree, t[0], t[1], t[2], ih(n, wx, 2, wz, 1 << 10));
            }
        }

        // Plants on top and things hanging underneath.
        for (int[] c : columns.values()) {
            int wx = cx + c[0];
            int wz = cz + c[1];
            BlockPos above = new BlockPos(wx, cy + c[2] + 1, wz);
            if (!blocks.containsKey(above) && ih(n, wx, 3, wz, 1000) < style.coverChance * coverFactor * 1000) {
                blocks.put(above, style.cover[ih(n, wx, 4, wz, style.cover.length)]);
            }
            boolean torn = bottom == IslandBottom.TORN;
            if (style.hanging != null && (torn || inward.get(columnKey(c[0], c[1])) < 0.85) && ih(n, wx, 5, wz, torn ? 4 : 10) == 0) {
                int under = cy + c[3] - 1;
                if (style == IslandStyle.CRIMSON) {
                    int length = 1 + ih(n, wx, 6, wz, 3);
                    for (int i = 0; i < length; i++) {
                        blocks.put(new BlockPos(wx, under - i, wz),
                                i == length - 1 ? "minecraft:weeping_vines[age=25]" : "minecraft:weeping_vines_plant");
                    }
                } else {
                    blocks.put(new BlockPos(wx, under, wz), style.hanging);
                }
            }
        }

        if (bottom == IslandBottom.TORN) {
            fallingChunks(blocks, columns, cx, cy, cz, depthMax, style, n);
        }

        // Bottom up, the way it gets built.
        java.util.List<BlockPos> order = new java.util.ArrayList<>(blocks.keySet());
        order.sort(java.util.Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        for (BlockPos p : order) {
            if (!out.addRole(p.getX(), p.getY(), p.getZ(), BLOCK + blocks.get(p))) {
                return;
            }
        }
    }

    /**
     * Torn out: loose clumps of the island's own blocks hanging in the air under it, as if they were
     * falling away when it was pulled out of the ground. None of them touch the island or each other.
     */
    private static void fallingChunks(java.util.Map<BlockPos, String> blocks, java.util.Map<Long, int[]> columns,
                                      int cx, int cy, int cz, double depthMax, IslandStyle style, int[] n) {
        Set<BlockPos> taken = new HashSet<>(blocks.keySet());
        int fall = Math.max(4, (int) (depthMax * 0.6));
        // Sand would drop out of the air; then the chunks are only rock.
        boolean soilFalls = java.util.Arrays.asList(style.soil).contains("minecraft:sand");
        for (int[] c : columns.values()) {
            int wx = cx + c[0];
            int wz = cz + c[1];
            if (ih(n, wx, 14, wz, 9) != 0) {
                continue;
            }
            int y0 = cy + c[3] - 2 - ih(n, wx, 15, wz, fall);
            int size = ih(n, wx, 16, wz, 3);
            java.util.List<BlockPos> chunk = new java.util.ArrayList<>();
            chunk.add(new BlockPos(wx, y0, wz));
            if (size >= 1) {
                chunk.add(new BlockPos(wx + 1, y0, wz));
            }
            if (size >= 2) {
                chunk.add(new BlockPos(wx, y0, wz + 1));
                chunk.add(new BlockPos(wx, y0 + 1, wz));
            }
            boolean clear = true;
            for (BlockPos p : chunk) {
                for (BlockPos near : new BlockPos[]{p, p.above(), p.below(), p.north(), p.south(), p.east(), p.west()}) {
                    if (taken.contains(near)) {
                        clear = false;
                    }
                }
            }
            if (!clear) {
                continue;
            }
            for (BlockPos p : chunk) {
                String spec;
                if (size >= 2 && p.getY() > y0 && !soilFalls) {
                    spec = style.top; // a clod with its grass still on
                } else if (!soilFalls && ih(n, p.getX(), p.getY(), p.getZ(), 2) == 0) {
                    spec = style.soil[ih(n, p.getX(), 17, p.getZ(), style.soil.length)];
                } else {
                    spec = style.stone[ih(n, p.getX(), p.getY(), p.getZ(), style.stone.length)];
                }
                blocks.put(p, spec);
                taken.add(p);
            }
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Hollow islands: a block with island on every side two blocks deep is inside the shell. */
    private static boolean enclosed(java.util.Map<Long, int[]> columns, int x, int z, int y) {
        for (int k = 1; k <= 2; k++) {
            for (long key : new long[]{columnKey(x + k, z), columnKey(x - k, z), columnKey(x, z + k), columnKey(x, z - k)}) {
                int[] c = columns.get(key);
                if (c == null || c[2] < y || c[3] > y) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ mountains

    /**
     * A mountain on the ground: A is the ground block at the middle of its foot, B how far it spreads (drag further to
     * scale it up), Height how high it rises (1-3: about the radius, depending on the form). Every design (seed) is its
     * own; the form sets the kind (peaked, rounded, mesa, volcano, jagged, a ridge), the style picks the blocks. Cliffs
     * show bare stone, high tops are snow. Hollow: only a 2-block shell.
     */
    private static void mountain(Collector out, BlockPos a, BlockPos b, int height, boolean hollow, IslandStyle style, long seed,
                                 MountainForm form) {
        MountainForm f = form == null ? MountainForm.PEAKED : form;
        java.util.Random rng = new java.util.Random(seed ^ 0x9E3779B97F4A7C15L);
        double bx = b.getX() - a.getX();
        double bz = b.getZ() - a.getZ();
        double radius = Math.max(4.0, Math.sqrt(bx * bx + bz * bz));
        int cx = a.getX();
        int cy = a.getY();
        int cz = a.getZ();
        double peak = Math.abs(height) > 3 ? Math.abs(height) : radius * f.heightScale * (0.8 + 0.4 * rng.nextDouble());
        int[] n = {rng.nextInt(1 << 16) - (1 << 15), rng.nextInt(1 << 12), rng.nextInt(1 << 16) - (1 << 15)};
        double rimPhase = rng.nextDouble() * Math.PI * 2;
        int[] lobes = {2 + rng.nextInt(3), 4 + rng.nextInt(3), 7 + rng.nextInt(4)};
        double rimAmp = (f == MountainForm.ROUNDED || f == MountainForm.VOLCANO ? 0.08 : 0.15) + 0.15 * rng.nextDouble();
        double ridgePhase = rng.nextDouble() * Math.PI * 2;
        int ridgeCount = 3 + rng.nextInt(4);
        boolean grooves = f == MountainForm.PEAKED || f == MountainForm.JAGGED || f == MountainForm.RIDGE;
        double ridgeDepth = grooves ? 0.08 + 0.14 * rng.nextDouble() : 0;
        double lumps = switch (f) {
            case ROUNDED -> 0.04;
            case MESA -> 0.0;
            case VOLCANO -> 0.05;
            default -> 0.12;
        };
        double steep = switch (f) {
            case JAGGED -> 2.2 + 0.8 * rng.nextDouble();
            case VOLCANO -> 1.1 + 0.3 * rng.nextDouble();
            default -> 1.1 + 0.9 * rng.nextDouble();
        };
        // A ridge runs along the line from A to B: footprint long and narrow that way, peaks in a row along it.
        double along = Math.atan2(bz, bx);
        double ca = Math.cos(along);
        double sa = Math.sin(along);
        double narrow = f == MountainForm.RIDGE ? 0.4 + 0.15 * rng.nextDouble() : 1.0;
        // Peaks: {x, z, height, spread}. The first is the main one.
        double[][] peaks;
        switch (f) {
            case JAGGED -> {
                peaks = new double[5 + rng.nextInt(5)][];
                for (int i = 0; i < peaks.length; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double dist = i == 0 ? 0 : radius * (0.15 + 0.45 * rng.nextDouble());
                    peaks[i] = new double[]{Math.cos(ang) * dist, Math.sin(ang) * dist,
                            peak * (i == 0 ? 1.0 : 0.55 + 0.4 * rng.nextDouble()), radius * (0.25 + 0.2 * rng.nextDouble())};
                }
            }
            case RIDGE -> {
                peaks = new double[5 + rng.nextInt(3)][];
                for (int i = 0; i < peaks.length; i++) {
                    double t = -0.8 + 1.6 * i / (peaks.length - 1) + (rng.nextDouble() - 0.5) * 0.15;
                    double off = (rng.nextDouble() - 0.5) * radius * narrow * 0.4;
                    peaks[i] = new double[]{ca * t * radius - sa * off, sa * t * radius + ca * off,
                            peak * (0.6 + 0.4 * rng.nextDouble()), radius * (0.3 + 0.15 * rng.nextDouble())};
                }
            }
            case ROUNDED -> {
                peaks = new double[1 + rng.nextInt(3)][];
                for (int i = 0; i < peaks.length; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double dist = i == 0 ? radius * 0.1 * rng.nextDouble() : radius * (0.35 + 0.3 * rng.nextDouble());
                    peaks[i] = new double[]{Math.cos(ang) * dist, Math.sin(ang) * dist,
                            i == 0 ? peak : peak * (0.5 + 0.3 * rng.nextDouble()), i == 0 ? radius : radius * (0.55 + 0.25 * rng.nextDouble())};
                }
            }
            case MESA, VOLCANO -> peaks = new double[][]{{0, 0, peak, radius}};
            default -> {
                peaks = new double[2 + rng.nextInt(3)][];
                for (int i = 0; i < peaks.length; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double dist = i == 0 ? radius * 0.15 * rng.nextDouble() : radius * (0.3 + 0.35 * rng.nextDouble());
                    peaks[i] = new double[]{Math.cos(ang) * dist, Math.sin(ang) * dist,
                            i == 0 ? peak : peak * (0.4 + 0.35 * rng.nextDouble()), i == 0 ? radius : radius * (0.4 + 0.3 * rng.nextDouble())};
                }
            }
        }
        double plateau = 0.55 + 0.2 * rng.nextDouble();
        double crater = 0.15 + 0.1 * rng.nextDouble();
        double snowLine = switch (f) {
            case PEAKED, JAGGED, RIDGE -> peak * (0.6 + 0.25 * rng.nextDouble());
            default -> Double.MAX_VALUE;
        };
        double treeFactor = 0.5 + rng.nextDouble();
        int reach = (int) Math.ceil(radius * 1.3) + 1;

        // Columns: {x, z, top, crater floor 1/0}.
        java.util.Map<Long, int[]> columns = new java.util.LinkedHashMap<>();
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                // Along / across the ridge line (narrow across for a ridge).
                double u = x * ca + z * sa;
                double v = (-x * sa + z * ca) / narrow;
                double d = Math.sqrt(u * u + v * v);
                double edge = radius * (1 - rimAmp + rimAmp * wave(Math.atan2(v, u), rimPhase, lobes));
                double edgeT = 1 - d / edge;
                if (edgeT <= 0) {
                    continue;
                }
                double h = 0;
                boolean craterFloor = false;
                for (double[] p : peaks) {
                    double px = x - p[0];
                    double pz = z - p[1];
                    double pu = px * ca + pz * sa;
                    double pv = (-px * sa + pz * ca) / (f == MountainForm.RIDGE ? Math.max(0.6, narrow * 1.4) : 1.0);
                    double dd = Math.sqrt(pu * pu + pv * pv) / p[3];
                    if (dd >= 1) {
                        continue;
                    }
                    double ph = switch (f) {
                        case ROUNDED -> p[2] * Math.pow(1 - dd * dd, 2);
                        case MESA -> dd < plateau ? p[2] : p[2] * Math.pow(1 - (dd - plateau) / (1 - plateau), 0.35);
                        case VOLCANO -> {
                            double rim = p[2] * Math.pow(1 - crater, steep);
                            if (dd < crater) {
                                craterFloor = dd < crater * 0.55;
                                yield rim - p[2] * 0.35 * (1 - dd / crater);
                            }
                            yield p[2] * Math.pow(1 - dd, steep); // the cone, meeting the crater's rim
                        }
                        default -> p[2] * Math.pow(1 - dd, steep);
                    };
                    h = Math.max(h, ph);
                }
                // Fade into the ground at the outline (a mesa keeps its cliff), grooves down the sides, lumps.
                h *= f == MountainForm.MESA ? Math.min(1.0, edgeT * 8) : Math.min(1.0, edgeT * 3);
                h *= 1 - ridgeDepth * Math.abs(Math.sin(Math.atan2(z, x) * ridgeCount / 2.0 + ridgePhase));
                h += (field(cx + x, cz + z, ridgePhase, radius * 0.5) - 0.5) * peak * lumps;
                int top = (int) Math.round(h);
                if (top >= 1) {
                    columns.put(columnKey(x, z), new int[]{x, z, top, craterFloor ? 1 : 0});
                }
            }
        }

        boolean dark = f == MountainForm.VOLCANO;
        String[] volcanic = {"minecraft:blackstone", "minecraft:basalt[axis=y]", "minecraft:tuff", "minecraft:blackstone"};
        java.util.Map<BlockPos, String> blocks = new java.util.HashMap<>();
        java.util.List<int[]> trees = new java.util.ArrayList<>();
        for (int[] c : columns.values()) {
            int x = c[0];
            int z = c[1];
            int top = c[2];
            int wx = cx + x;
            int wz = cz + z;
            int steepest = 0;
            for (long k : new long[]{columnKey(x + 1, z), columnKey(x - 1, z), columnKey(x, z + 1), columnKey(x, z - 1)}) {
                int[] o = columns.get(k);
                steepest = Math.max(steepest, Math.abs(top - (o == null ? 0 : o[2])));
            }
            boolean cliff = steepest >= (f == MountainForm.ROUNDED ? 5 : 3);
            boolean snow = top >= snowLine;
            boolean scorched = dark && top >= peak * 0.55;
            for (int y = 1; y <= top; y++) {
                int fromTop = top - y;
                if (hollow && fromTop > 2 && y > 1 && insideMountain(columns, x, z, y)) {
                    continue;
                }
                int wy = cy + y;
                String rock = f == MountainForm.MESA ? style.stone[Math.floorMod(Math.floorDiv(wy, 2) + n[1], style.stone.length)]
                        : scorched ? volcanic[ih(n, wx, wy, wz, volcanic.length)]
                        : style.stone[ih(n, wx, wy, wz, style.stone.length)];
                String spec;
                if (fromTop == 0) {
                    spec = c[3] == 1 ? "minecraft:magma_block" : snow ? "minecraft:snow_block" : scorched || cliff ? rock : style.top;
                } else if (fromTop <= 2 && !cliff && !snow && !scorched) {
                    spec = style.soil[ih(n, wx, wy, wz, style.soil.length)];
                } else if (!scorched && ih(n, wx, wy, wz, 45) == 0) {
                    spec = style.ores[ih(n, wz, wy, wx, style.ores.length)];
                } else {
                    spec = rock;
                }
                blocks.put(new BlockPos(wx, wy, wz), spec);
            }
            // Gentle grassy slopes below the snow (and off the volcano's dark top): trees spread out, plants.
            if (!cliff && !snow && !scorched && c[3] == 0 && top < Math.min(snowLine, peak) * 0.85) {
                BlockPos above = new BlockPos(wx, cy + top + 1, wz);
                if (ih(n, wx, 1, wz, Math.max(4, (int) Math.round(18 / treeFactor))) == 0) {
                    boolean crowded = false;
                    for (int[] t : trees) {
                        if (Math.abs(t[0] - wx) < 5 && Math.abs(t[2] - wz) < 5) {
                            crowded = true;
                            break;
                        }
                    }
                    if (!crowded) {
                        trees.add(new int[]{wx, cy + top + 1, wz});
                        islandTree(blocks, style.tree, wx, cy + top + 1, wz, ih(n, wx, 2, wz, 1 << 10));
                        continue;
                    }
                }
                if (!blocks.containsKey(above) && ih(n, wx, 3, wz, 1000) < style.coverChance * 600) {
                    blocks.put(above, style.cover[ih(n, wx, 4, wz, style.cover.length)]);
                }
            }
        }
        java.util.List<BlockPos> order = new java.util.ArrayList<>(blocks.keySet());
        order.sort(java.util.Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        for (BlockPos p : order) {
            if (!out.addRole(p.getX(), p.getY(), p.getZ(), BLOCK + blocks.get(p))) {
                return;
            }
        }
    }

    /** Hollow mountains: a block with mountain on every side two blocks deep (and above it) is inside the shell. */
    private static boolean insideMountain(java.util.Map<Long, int[]> columns, int x, int z, int y) {
        for (int k = 1; k <= 2; k++) {
            for (long key : new long[]{columnKey(x + k, z), columnKey(x - k, z), columnKey(x, z + k), columnKey(x, z - k)}) {
                int[] c = columns.get(key);
                if (c == null || c[2] < y + 2) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ giant trees

    /**
     * A giant tree: A is the ground block at the foot of the trunk, B how far the crown spreads, Height the trunk's
     * height (1-3: from the crown's size). Every design (seed) is its own: a stocky trunk that bends (sometimes in an
     * S), buttress roots sloping into the ground, and a crown grown branch by branch (see {@link #colonize}). The kind
     * sets the shape (a cone for spruce, flat umbrellas for acacia, a curtain for the willow, bare for dead...).
     * Leaves never decay.
     */
    private static void giantTree(Collector out, BlockPos a, BlockPos b, int height, TreeKind kind, long seed) {
        java.util.Random rng = new java.util.Random(seed ^ 0x5DEECE66DL);
        TreeKind k = kind == null ? TreeKind.OAK : kind;
        if (k == TreeKind.RANDOM) {
            k = TreeKind.values()[rng.nextInt(TreeKind.values().length - 1)];
        }
        double bx = b.getX() - a.getX();
        double bz = b.getZ() - a.getZ();
        double crown = Math.max(3.0, Math.sqrt(bx * bx + bz * bz) * (0.85 + 0.25 * rng.nextDouble()));
        int cx = a.getX();
        int cy = a.getY();
        int cz = a.getZ();
        int[] n = {rng.nextInt(1 << 16) - (1 << 15), rng.nextInt(1 << 12), rng.nextInt(1 << 16) - (1 << 15)};
        // Big builds have short, stocky trunks under a crown far wider than they are tall.
        double tall = switch (k) {
            case SPRUCE, JUNGLE -> 2.2;
            case BIRCH -> 1.5;
            case DEAD -> 1.2;
            case ACACIA, OAK -> 0.9;
            case CHERRY, WILLOW, DARK_OAK -> 0.85;
            default -> 1.0;
        };
        int trunkH = Math.abs(height) > 3 ? Math.abs(height) : (int) Math.round(crown * tall * (0.85 + 0.3 * rng.nextDouble()));
        trunkH = Math.max(trunkH, 5);
        double trunkR = switch (k) {
            case DARK_OAK -> Math.max(2.0, crown * 0.28);
            case BIRCH -> Math.max(1.0, crown * 0.15);
            case SPRUCE, ACACIA, JUNGLE -> Math.max(1.2, crown * 0.18);
            case DEAD -> Math.max(1.2, crown * 0.2);
            case WILLOW -> Math.max(1.5, crown * 0.25);
            default -> Math.max(1.5, crown * 0.25);
        };
        java.util.Map<BlockPos, String> blocks = new java.util.LinkedHashMap<>();
        // Bark all round (wood, not logs: no rings showing), with a darker bark mixed into the big oaks.
        String wood = k.log.replace("_log", "_wood");
        String bark = k == TreeKind.OAK || k == TreeKind.JUNGLE ? "minecraft:spruce_wood" : null;
        String woodY = wood + "[axis=y]";
        // Trunk: a solid disc per layer, tapering; it bends as it climbs, now and then back the other way (an S), never
        // so fast that a layer stops sitting on the one below.
        boolean bendy = k == TreeKind.ACACIA || k == TreeKind.DEAD;
        double leanX = (rng.nextDouble() - 0.5) * (bendy ? 0.4 : 0.2);
        double leanZ = (rng.nextDouble() - 0.5) * (bendy ? 0.4 : 0.2);
        double bendX = (rng.nextDouble() - 0.5) * (bendy ? 0.03 : 0.016);
        double bendZ = (rng.nextDouble() - 0.5) * (bendy ? 0.03 : 0.016);
        int flip = rng.nextDouble() < 0.4 ? (int) (trunkH * (0.4 + 0.3 * rng.nextDouble())) : -1;
        double taper = 0.35 + 0.2 * rng.nextDouble();
        double tx = 0;
        double tz = 0;
        for (int y = 1; y <= trunkH; y++) {
            double t = (y - 1) / (double) trunkH;
            double r = Math.max(0.5, trunkR * (1.0 - taper * t)) + (y == 1 ? 0.6 : y == 2 ? 0.3 : 0);
            if (y == flip) {
                bendX = -bendX * 1.5;
                bendZ = -bendZ * 1.5;
            }
            leanX = Math.max(-0.35, Math.min(0.35, leanX + bendX));
            leanZ = Math.max(-0.35, Math.min(0.35, leanZ + bendZ));
            if (y > 3) {
                tx += leanX;
                tz += leanZ;
            }
            treeDisc(blocks, cx + tx, cy + y, cz + tz, r, woodY, bark, n, false);
        }
        int topX = (int) Math.round(cx + tx);
        int topZ = (int) Math.round(cz + tz);
        int topY = cy + trunkH;
        // Buttress roots: ridges from high on the trunk sloping down into the ground, a star round the foot.
        int roots = 4 + rng.nextInt(4);
        double rootH = trunkR * 0.9 + 1.5 + rng.nextDouble() * trunkR * 0.5;
        for (int i = 0; i < roots; i++) {
            double ang = Math.PI * 2 * i / roots + (rng.nextDouble() - 0.5) * 0.6;
            double curve = (rng.nextDouble() - 0.5) * 0.6;
            double s0 = trunkR * 0.6;
            double len = trunkR * 1.3 + 2 + crown * (0.15 + 0.15 * rng.nextDouble());
            double h0 = rootH * (0.7 + 0.5 * rng.nextDouble());
            for (double s = s0; s <= len; s += 0.5) {
                double f = (s - s0) / (len - s0);
                double ra = ang + curve * f;
                int hgt = Math.max(1, (int) Math.round(h0 * Math.pow(1 - f, 1.6)));
                double w = Math.max(0.5, trunkR * 0.45 * (1 - f));
                for (int y = 1; y <= hgt; y++) {
                    treeDisc(blocks, cx + Math.cos(ra) * s, cy + y, cz + Math.sin(ra) * s, w, woodY, bark, n, true);
                }
            }
        }
        String[] leaves = treeLeaves(k);
        switch (k) {
            case SPRUCE -> {
                // Rings of needles round the trunk, widest low down, to a point above the top.
                int start = (int) (trunkH * (0.15 + 0.2 * rng.nextDouble()));
                int tier = 2 + rng.nextInt(3);
                for (int y = start; y <= trunkH + 2; y++) {
                    double t = (y - start) / (double) (trunkH + 2 - start);
                    double f = Math.min(1.0, y / (double) trunkH);
                    double r = crown * (1 - t) * (0.85 + 0.25 * ((y - start) % tier == 0 ? 1 : 0)) * (0.9 + 0.2 * rng.nextDouble()) + 0.6;
                    treeDisc(blocks, cx + tx * f, cy + y, cz + tz * f, r, leaves[0], null, n, true);
                }
            }
            case ACACIA -> {
                // Two to four limbs bending out, each ending in a flat crown.
                int count = 2 + rng.nextInt(3);
                for (int i = 0; i < count; i++) {
                    double ang = Math.PI * 2 * i / count + rng.nextDouble() * 0.8;
                    double dist = crown * (0.45 + 0.25 * rng.nextDouble());
                    double ex = topX + Math.cos(ang) * dist;
                    double ez = topZ + Math.sin(ang) * dist;
                    double ey = topY + dist * 0.5;
                    logLine(blocks, wood, topX, topY - 2, topZ, ex, ey, ez, trunkR >= 1.5 ? 1 : 0);
                    double r = crown * (0.45 + 0.2 * rng.nextDouble());
                    treeDisc(blocks, ex, (int) Math.round(ey) + 1, ez, r, leaves[0], null, n, true);
                    treeDisc(blocks, ex, (int) Math.round(ey) + 2, ez, r * 0.65, leaves[0], null, n, true);
                }
            }
            default -> colonize(blocks, k, wood, rng, n, cx, cy, cz, tx, tz, trunkH, trunkR, crown, leaves);
        }
        emitSorted(out, blocks);
    }

    /** The leaves of each kind: {main, accent (mixed in on top), underside (darker), the odd flower}; null: bare. */
    private static String[] treeLeaves(TreeKind k) {
        String[] l = switch (k) {
            case OAK -> new String[]{"oak_leaves", "azalea_leaves", "dark_oak_leaves", "flowering_azalea_leaves"};
            case BIRCH -> new String[]{"birch_leaves", null, "oak_leaves", null};
            case DARK_OAK -> new String[]{"dark_oak_leaves", "oak_leaves", "spruce_leaves", null};
            case JUNGLE -> new String[]{"jungle_leaves", "oak_leaves", "dark_oak_leaves", null};
            case WILLOW -> new String[]{"mangrove_leaves", "azalea_leaves", "jungle_leaves", null};
            case CHERRY -> new String[]{"cherry_leaves", null, null, null};
            case SPRUCE -> new String[]{"spruce_leaves", null, null, null};
            case ACACIA -> new String[]{"acacia_leaves", null, null, null};
            default -> null;
        };
        if (l == null) {
            return null;
        }
        String[] out = new String[l.length];
        for (int i = 0; i < l.length; i++) {
            out[i] = l[i] == null ? null : "minecraft:" + l[i] + "[persistent=true]";
        }
        return out;
    }

    /**
     * Grows a broadleaf crown. Two to four thick main limbs split from the top of the trunk and arch out under the
     * canopy; from them the rest grows by space colonization: random points fill the crown's shape (a wide dome with
     * a flat underside for oak, cherry and dark oak, a tall oval for birch, a cap for jungle, sparse for dead) and
     * branches grow a block at a time towards the points near them, dropping each once reached. Limbs thicken towards
     * the trunk with the twigs they carry; leaves clump round the twig ends, darker underneath, and hang in strands
     * below (a curtain for the willow).
     */
    private static void colonize(java.util.Map<BlockPos, String> blocks, TreeKind k, String wood, java.util.Random rng, int[] n,
                                 int cx, int cy, int cz, double tx, double tz, int trunkH, double trunkR, double crown, String[] leaves) {
        double rx;
        double ry;
        double lift;
        double floor;
        switch (k) {
            case BIRCH -> { rx = crown * 0.6; ry = crown * 1.0; lift = 0.1; floor = -1.0; }
            case DARK_OAK -> { rx = crown * 1.05; ry = crown * 0.55; lift = 0.15; floor = -0.45; }
            case CHERRY -> { rx = crown * 1.1; ry = crown * 0.65; lift = 0.2; floor = -0.45; }
            case JUNGLE -> { rx = crown * 0.8; ry = crown * 0.6; lift = 0.2; floor = -0.4; }
            case WILLOW -> { rx = crown * 1.0; ry = crown * 0.7; lift = 0.2; floor = -0.3; }
            case DEAD -> { rx = crown * 0.9; ry = crown * 0.8; lift = 0.0; floor = -1.0; }
            default -> { rx = crown * 1.0; ry = crown * 0.75; lift = 0.2; floor = -0.5; }
        }
        // Every design a slightly different envelope.
        rx *= 0.85 + 0.3 * rng.nextDouble();
        ry *= 0.85 + 0.3 * rng.nextDouble();
        double rz = rx * (0.8 + 0.4 * rng.nextDouble());
        double topX = cx + tx;
        double topZ = cz + tz;
        int topY = cy + trunkH;
        double ccx = topX + (rng.nextDouble() - 0.5) * crown * 0.3;
        double ccz = topZ + (rng.nextDouble() - 0.5) * crown * 0.3;
        double ccy = topY + ry * lift;
        int want = k == TreeKind.DEAD ? 25 + rng.nextInt(20) : (int) Math.min(700, Math.max(50, rx * ry * rz * 0.3));
        java.util.List<double[]> points = new java.util.ArrayList<>();
        for (int tries = 0; points.size() < want && tries < want * 20; tries++) {
            double u = rng.nextDouble() * 2 - 1;
            double v = rng.nextDouble() * 2 - 1;
            double w = rng.nextDouble() * 2 - 1;
            if (u * u + v * v + w * w > 1 || v < floor) {
                continue;
            }
            points.add(new double[]{ccx + u * rx, ccy + v * ry, ccz + w * rz});
        }
        // Branch nodes: the top of the trunk and the main limbs to start, then everything that grows.
        java.util.List<double[]> nodes = new java.util.ArrayList<>();
        java.util.List<Integer> parent = new java.util.ArrayList<>();
        for (int y = (int) (trunkH * 0.8); y <= trunkH; y++) {
            double f = y / (double) trunkH;
            nodes.add(new double[]{cx + tx * f, cy + y, cz + tz * f});
            parent.add(-1);
        }
        int limbs = k == TreeKind.DEAD ? 3 + rng.nextInt(3) : k == TreeKind.BIRCH || k == TreeKind.JUNGLE ? 2 + rng.nextInt(2) : 2 + rng.nextInt(3);
        double flat = k == TreeKind.DARK_OAK || k == TreeKind.CHERRY ? 0.12 : 0.25;
        int limbR = trunkR >= 2.5 ? 2 : trunkR >= 1.2 ? 1 : 0;
        double turn = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < limbs; i++) {
            double t = 0.6 + 0.25 * rng.nextDouble();
            double sx = cx + tx * t;
            double sy = cy + trunkH * t;
            double sz = cz + tz * t;
            double ang = turn + Math.PI * 2 * i / limbs + (rng.nextDouble() - 0.5) * 0.7;
            double dist = rx * (0.35 + 0.25 * rng.nextDouble());
            double ex = topX + Math.cos(ang) * dist;
            double ez = topZ + Math.sin(ang) * dist;
            double ey = topY + crown * flat * (0.3 + rng.nextDouble());
            // Arched: out and up to a bend, then on to the end.
            double mx = sx + (ex - sx) * 0.5;
            double mz = sz + (ez - sz) * 0.5;
            double my = sy + (ey - sy) * 0.75 + 1;
            logLine(blocks, wood, sx, sy, sz, mx, my, mz, limbR);
            logLine(blocks, wood, mx, my, mz, ex, ey, ez, Math.max(0, limbR - (dist > 6 ? 0 : 1)));
            int along = Math.max(2, (int) Math.round(dist));
            for (int s = along / 2; s <= along; s++) {
                double f = s / (double) along;
                nodes.add(new double[]{mx + (ex - mx) * (f * 2 - 1 < 0 ? 0 : f * 2 - 1), my + (ey - my) * Math.max(0, f * 2 - 1),
                        mz + (ez - mz) * Math.max(0, f * 2 - 1)});
                parent.add(-1);
            }
        }
        int fixed = nodes.size();
        double reach = Math.max(rx, ry) * 1.3;
        double kill = k == TreeKind.DEAD ? 3.0 : 2.5;
        for (int iter = 0; iter < 120 && !points.isEmpty(); iter++) {
            java.util.Map<Integer, double[]> pull = new java.util.HashMap<>();
            java.util.Iterator<double[]> it = points.iterator();
            while (it.hasNext()) {
                double[] p = it.next();
                int best = -1;
                double bestD = reach * reach;
                for (int i = 0; i < nodes.size(); i++) {
                    double[] q = nodes.get(i);
                    double d = (p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]);
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                if (best < 0) {
                    continue;
                }
                if (bestD < kill * kill) {
                    it.remove();
                    continue;
                }
                double[] q = nodes.get(best);
                double len = Math.sqrt(bestD);
                double[] acc = pull.computeIfAbsent(best, x -> new double[3]);
                acc[0] += (p[0] - q[0]) / len;
                acc[1] += (p[1] - q[1]) / len;
                acc[2] += (p[2] - q[2]) / len;
            }
            if (pull.isEmpty()) {
                break;
            }
            for (java.util.Map.Entry<Integer, double[]> e : pull.entrySet()) {
                double[] q = nodes.get(e.getKey());
                double[] d = e.getValue();
                // A slight upward pull, and a little wobble so limbs don't run dead straight.
                d[1] += 0.15;
                double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                if (len < 1e-6) {
                    continue;
                }
                nodes.add(new double[]{q[0] + d[0] / len + (rng.nextDouble() - 0.5) * 0.3,
                        q[1] + d[1] / len + (rng.nextDouble() - 0.5) * 0.3,
                        q[2] + d[2] / len + (rng.nextDouble() - 0.5) * 0.3});
                parent.add(e.getKey());
            }
        }
        // How many twig ends each node carries: the thicker the branch.
        int[] tips = new int[nodes.size()];
        boolean[] hasChild = new boolean[nodes.size()];
        for (int i = fixed; i < nodes.size(); i++) {
            hasChild[parent.get(i)] = true;
        }
        for (int i = nodes.size() - 1; i >= fixed; i--) {
            if (!hasChild[i]) {
                tips[i]++;
            }
            tips[parent.get(i)] += tips[i];
        }
        for (int i = fixed; i < nodes.size(); i++) {
            double[] q = nodes.get(i);
            double[] p = nodes.get(parent.get(i));
            double r = Math.min(trunkR * 0.5, 0.3 * Math.sqrt(tips[i]));
            logLine(blocks, wood, p[0], p[1], p[2], q[0], q[1], q[2], r >= 1.6 ? 2 : r >= 0.8 ? 1 : 0);
        }
        if (leaves == null) {
            return;
        }
        double squash = k == TreeKind.DARK_OAK ? 0.6 : k == TreeKind.CHERRY ? 0.7 : 0.85;
        double leafR = Math.max(2.0, Math.min(4.2, crown * 0.24));
        for (int i = fixed; i < nodes.size(); i++) {
            if (hasChild[i] && tips[i] > 2) {
                continue;
            }
            double[] q = nodes.get(i);
            blob(blocks, (int) Math.round(q[0]), (int) Math.round(q[1]), (int) Math.round(q[2]),
                    leafR * (hasChild[i] ? 0.8 : 1.0) * (0.85 + 0.3 * rng.nextDouble()), squash, leaves, n);
        }
        // Strands hanging from the underside: a long curtain for the willow, a few short drapes on the rest.
        double chance = switch (k) {
            case WILLOW -> 0.4;
            case JUNGLE -> 0.08;
            case OAK, CHERRY -> 0.05;
            case DARK_OAK -> 0.04;
            default -> 0.0;
        };
        if (chance <= 0) {
            return;
        }
        java.util.List<BlockPos> under = new java.util.ArrayList<>();
        for (java.util.Map.Entry<BlockPos, String> e : blocks.entrySet()) {
            if (e.getValue().contains("leaves") && !blocks.containsKey(e.getKey().below())) {
                under.add(e.getKey());
            }
        }
        for (BlockPos p : under) {
            if (ih(n, p.getX(), p.getY() * 7 + 5, p.getZ(), 1000) >= chance * 1000) {
                continue;
            }
            int room = p.getY() - cy - 2;
            int len = k == TreeKind.WILLOW ? (int) Math.round(room * (0.4 + 0.5 * ih(n, p.getX(), 3, p.getZ(), 100) / 100.0))
                    : 1 + ih(n, p.getX(), 4, p.getZ(), k == TreeKind.JUNGLE ? 5 : 3);
            for (int d = 1; d <= Math.min(len, room); d++) {
                BlockPos q = p.below(d);
                if (blocks.containsKey(q)) {
                    break;
                }
                blocks.put(q, leaves[0]);
            }
        }
    }

    /** A straight limb of wood from one point to another, {@code radius} 1 or 2 for a thick one; never over what's there. */
    private static void logLine(java.util.Map<BlockPos, String> blocks, String wood, double x0, double y0, double z0, double x1, double y1,
                                double z1, int radius) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        String axis = Math.abs(dy) >= Math.max(Math.abs(dx), Math.abs(dz)) ? "y" : Math.abs(dx) >= Math.abs(dz) ? "x" : "z";
        String spec = wood + "[axis=" + axis + "]";
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2.5) + 1;
        for (int s = 0; s <= steps; s++) {
            double f = s / (double) steps;
            int px = (int) Math.round(x0 + dx * f);
            int py = (int) Math.round(y0 + dy * f);
            int pz = (int) Math.round(z0 + dz * f);
            for (int ox = -radius; ox <= radius; ox++) {
                for (int oy = -radius; oy <= radius; oy++) {
                    for (int oz = -radius; oz <= radius; oz++) {
                        if (Math.abs(ox) + Math.abs(oy) + Math.abs(oz) <= radius) {
                            blocks.putIfAbsent(new BlockPos(px + ox, py + oy, pz + oz), spec);
                        }
                    }
                }
            }
        }
    }

    /**
     * A horizontal disc of radius {@code r} round (x, z), {@code alt} (if any) mixed in here and there; {@code soft}: only
     * where nothing is yet.
     */
    private static void treeDisc(java.util.Map<BlockPos, String> blocks, double x, int y, double z, double r, String spec, String alt,
                                 int[] n, boolean soft) {
        if (spec == null) {
            return;
        }
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                int px = (int) Math.round(x) + dx;
                int pz = (int) Math.round(z) + dz;
                double ddx = px - x;
                double ddz = pz - z;
                if (ddx * ddx + ddz * ddz <= r * r + 0.3) {
                    BlockPos p = new BlockPos(px, y, pz);
                    String s = alt != null && ih(n, px, y, pz, 5) == 0 ? alt + "[axis=y]" : spec;
                    if (soft) {
                        blocks.putIfAbsent(p, s);
                    } else {
                        blocks.put(p, s);
                    }
                }
            }
        }
    }

    /** A round, lumpy clump of leaves, {@code squash} flattening it; darker underneath, an accent on top; never over wood. */
    private static void blob(java.util.Map<BlockPos, String> blocks, int x, int y, int z, double r, double squash, String[] leaves, int[] n) {
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    double rough = r * (0.8 + 0.25 * ih(n, x + dx, y + dy, z + dz, 100) / 100.0);
                    double ey = dy / squash;
                    if (dx * dx + ey * ey + dz * dz <= rough * rough) {
                        int h = ih(n, x + dx, (y + dy) * 3 + 11, z + dz, 100);
                        String spec = leaves[3] != null && h < 3 ? leaves[3]
                                : dy < 0 && leaves[2] != null && h < 55 ? leaves[2]
                                : leaves[1] != null && h >= 78 ? leaves[1]
                                : leaves[0];
                        blocks.putIfAbsent(new BlockPos(x + dx, y + dy, z + dz), spec);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ rocks

    /**
     * Rocks: A is the middle on the ground, B how far they spread, Height how tall the tallest gets (1-3: from the
     * spread). Every design (seed) is its own; the form sets what (boulders, an arch, sea stacks, an outcrop), the look
     * the stone (plain, mossy, sandy, dark).
     */
    private static void rocks(Collector out, BlockPos a, BlockPos b, int height, RockForm form, RockLook look, long seed) {
        RockForm f = form == null ? RockForm.BOULDERS : form;
        RockLook l = look == null ? RockLook.STONE : look;
        java.util.Random rng = new java.util.Random(seed ^ 0x2545F4914F6CDD1DL);
        double bx = b.getX() - a.getX();
        double bz = b.getZ() - a.getZ();
        double spread = Math.max(2.0, Math.sqrt(bx * bx + bz * bz));
        int cx = a.getX();
        int cy = a.getY();
        int cz = a.getZ();
        int[] n = {rng.nextInt(1 << 16) - (1 << 15), rng.nextInt(1 << 12), rng.nextInt(1 << 16) - (1 << 15)};
        double tallest = Math.abs(height) > 3 ? Math.abs(height) : spread * (f == RockForm.STACKS ? 1.6 : f == RockForm.ARCH ? 0.9 : 0.6);
        java.util.Set<BlockPos> solid = new java.util.LinkedHashSet<>();
        switch (f) {
            case BOULDERS -> {
                int count = 3 + rng.nextInt(6);
                for (int i = 0; i < count; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double dist = i == 0 ? 0 : spread * Math.sqrt(rng.nextDouble()) * 0.8;
                    double r = Math.max(1.0, (i == 0 ? 0.35 : 0.15 + 0.15 * rng.nextDouble()) * spread);
                    r = Math.min(r, tallest);
                    rock(solid, cx + Math.cos(ang) * dist, cy + r * (0.2 + 0.3 * rng.nextDouble()), cz + Math.sin(ang) * dist,
                            r, r * (0.7 + 0.3 * rng.nextDouble()), n);
                }
            }
            case ARCH -> {
                double ang = rng.nextDouble() * Math.PI;
                double half = spread * 0.75;
                double thick = Math.max(1.2, spread * 0.18);
                int steps = (int) Math.ceil(Math.PI * half * 2);
                for (int i = 0; i <= steps; i++) {
                    double t = Math.PI * i / steps;
                    double along = -Math.cos(t) * half;
                    double up = Math.sin(t) * tallest;
                    double th = thick * (1.25 - 0.45 * Math.sin(t));
                    rock(solid, cx + Math.cos(ang) * along, cy + 1 + up, cz + Math.sin(ang) * along, th, th, n);
                }
                // Legs sunk into the ground.
                for (int side : new int[]{-1, 1}) {
                    rock(solid, cx + Math.cos(ang) * half * side, cy + 1, cz + Math.sin(ang) * half * side, thick * 1.4, thick, n);
                }
            }
            case STACKS -> {
                int count = 3 + rng.nextInt(4);
                for (int i = 0; i < count; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double dist = i == 0 ? 0 : spread * (0.35 + 0.55 * rng.nextDouble());
                    double h = tallest * (i == 0 ? 1.0 : 0.4 + 0.5 * rng.nextDouble());
                    double r = Math.max(1.0, spread * (0.12 + 0.1 * rng.nextDouble()));
                    double x0 = cx + Math.cos(ang) * dist;
                    double z0 = cz + Math.sin(ang) * dist;
                    for (int y = 1; y <= h; y++) {
                        double t = y / h;
                        double rr = r * (1.15 - 0.55 * t) * (0.85 + 0.3 * ih(n, (int) x0, cy + y, (int) z0, 100) / 100.0);
                        rock(solid, x0 + (rng.nextDouble() - 0.5) * 0.4, cy + y, z0 + (rng.nextDouble() - 0.5) * 0.4, rr, 0.6, n);
                    }
                }
            }
            case OUTCROP -> {
                rock(solid, cx, cy + tallest * 0.3, cz, spread * 0.55, tallest * 0.75, n);
                int lumps = 2 + rng.nextInt(4);
                for (int i = 0; i < lumps; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double dist = spread * (0.2 + 0.4 * rng.nextDouble());
                    double r = spread * (0.2 + 0.15 * rng.nextDouble());
                    rock(solid, cx + Math.cos(ang) * dist, cy + tallest * (0.2 + 0.5 * rng.nextDouble()), cz + Math.sin(ang) * dist, r, r, n);
                }
            }
        }
        java.util.Map<BlockPos, String> blocks = new java.util.HashMap<>();
        for (BlockPos p : solid) {
            if (p.getY() <= cy) {
                continue; // nothing in the ground itself
            }
            boolean top = !solid.contains(new BlockPos(p.getX(), p.getY() + 1, p.getZ()));
            String spec = top && l.top != null ? l.top : l.rock[ih(n, p.getX(), p.getY(), p.getZ(), l.rock.length)];
            blocks.put(p, spec);
            if (top && l.cover != null && ih(n, p.getX(), 7, p.getZ(), 3) == 0) {
                blocks.putIfAbsent(new BlockPos(p.getX(), p.getY() + 1, p.getZ()), l.cover);
            }
        }
        java.util.List<BlockPos> order = new java.util.ArrayList<>(blocks.keySet());
        order.sort(java.util.Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        for (BlockPos p : order) {
            if (!out.addRole(p.getX(), p.getY(), p.getZ(), BLOCK + blocks.get(p))) {
                return;
            }
        }
    }

    /** A lumpy ellipsoid of rock round (x, y, z): {@code r} across, {@code ry} up and down. */
    private static void rock(java.util.Set<BlockPos> solid, double x, double y, double z, double r, double ry, int[] n) {
        int ri = (int) Math.ceil(r + 1);
        int ryi = (int) Math.ceil(ry + 1);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ryi; dy <= ryi; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    int px = (int) Math.round(x) + dx;
                    int py = (int) Math.round(y) + dy;
                    int pz = (int) Math.round(z) + dz;
                    double ex = (px - x) / r;
                    double ey = (py - y) / Math.max(0.5, ry);
                    double ez = (pz - z) / r;
                    double rough = 0.82 + 0.3 * ih(n, px >> 1, py >> 1, pz >> 1, 100) / 100.0;
                    if (ex * ex + ey * ey + ez * ez <= rough * rough) {
                        solid.add(new BlockPos(px, py, pz));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ mazes

    /**
     * A maze between corners A and B, its walls Height tall (1: 3) on the ground at A. Every design (seed) is its own
     * maze; the style sets the walls, the layout how the paths run. An entrance on one side, an exit on the other.
     */
    private static void maze(Collector out, BlockPos a, BlockPos b, int height, MazeStyle style, MazeLayout layout, long seed) {
        java.util.Random rng = new java.util.Random(seed ^ 0x9E3779B97F4A7C15L);
        MazeStyle st = style == null ? MazeStyle.HEDGE : style;
        if (st == MazeStyle.RANDOM) {
            st = MazeStyle.values()[rng.nextInt(MazeStyle.values().length - 1)];
        }
        MazeLayout lay = layout == null ? MazeLayout.CLASSIC : layout;
        if (lay == MazeLayout.RANDOM) {
            lay = MazeLayout.values()[rng.nextInt(MazeLayout.values().length - 1)];
        }
        int[] n = {rng.nextInt(1 << 16) - (1 << 15), rng.nextInt(1 << 12), rng.nextInt(1 << 16) - (1 << 15)};
        int x0 = Math.min(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int width = Math.max(a.getX(), b.getX()) - x0 + 1;
        int depth = Math.max(a.getZ(), b.getZ()) - z0 + 1;
        int cy = a.getY();
        // Paths 1 wide, or now and then 2 in a big maze; a cell is a path plus one wall.
        int pw = width >= 25 && depth >= 25 && rng.nextDouble() < 0.35 ? 2 : 1;
        int p = pw + 1;
        int nx = (width - 1) / p;
        int nz = (depth - 1) / p;
        if (nx < 2 || nz < 2) {
            return;
        }
        int gw = nx * p + 1;
        int gd = nz * p + 1;
        int ox = x0 + (width - gw) / 2;
        int oz = z0 + (depth - gd) / 2;
        boolean[][] valid = new boolean[nx][nz];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                double u = (i + 0.5) / nx * 2 - 1;
                double v = (j + 0.5) / nz * 2 - 1;
                valid[i][j] = lay != MazeLayout.ROUND || u * u + v * v <= 1.0;
            }
        }
        boolean[][] open = new boolean[gw][gd];
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // Carve: a random walk that backs up from dead ends (one way between any two cells).
        boolean[][] seen = new boolean[nx][nz];
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        seen[nx / 2][nz / 2] = true;
        mazeCell(open, nx / 2, nz / 2, p, pw);
        stack.push(new int[]{nx / 2, nz / 2});
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            java.util.List<int[]> next = new java.util.ArrayList<>();
            for (int[] d : dirs) {
                int i = c[0] + d[0];
                int j = c[1] + d[1];
                if (i >= 0 && j >= 0 && i < nx && j < nz && valid[i][j] && !seen[i][j]) {
                    next.add(d);
                }
            }
            if (next.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] d = next.get(rng.nextInt(next.size()));
            int i = c[0] + d[0];
            int j = c[1] + d[1];
            seen[i][j] = true;
            mazeCell(open, i, j, p, pw);
            mazePassage(open, c[0], c[1], d, p, pw);
            stack.push(new int[]{i, j});
        }
        if (lay == MazeLayout.LOOPS) {
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < nz; j++) {
                    if (!valid[i][j]) {
                        continue;
                    }
                    java.util.List<int[]> closed = new java.util.ArrayList<>();
                    int ways = 0;
                    for (int[] d : dirs) {
                        int ni = i + d[0];
                        int nj = j + d[1];
                        if (ni < 0 || nj < 0 || ni >= nx || nj >= nz || !valid[ni][nj]) {
                            continue;
                        }
                        if (mazeOpen(open, i, j, d, p)) {
                            ways++;
                        } else {
                            closed.add(d);
                        }
                    }
                    if (!closed.isEmpty() && (ways == 1 ? rng.nextDouble() < 0.7 : rng.nextDouble() < 0.06)) {
                        mazePassage(open, i, j, closed.get(rng.nextInt(closed.size())), p, pw);
                    }
                }
            }
        }
        if (lay == MazeLayout.CHAMBERS) {
            int rooms = 2 + rng.nextInt(3) + nx * nz / 80;
            for (int r = 0; r < rooms; r++) {
                int rw = 2 + rng.nextInt(2);
                int rd = 2 + rng.nextInt(2);
                mazeRoom(open, valid, rng.nextInt(Math.max(1, nx - rw + 1)), rng.nextInt(Math.max(1, nz - rd + 1)), rw, rd, p, nx, nz);
            }
            if (nx >= 7 && nz >= 7) {
                mazeRoom(open, valid, nx / 2 - 1, nz / 2 - 1, 3, 3, p, nx, nz);
            }
        }
        // The way in on the west side, the way out on the east.
        for (int side : new int[]{-1, 1}) {
            for (int tries = 0; tries < 40; tries++) {
                int j = rng.nextInt(nz);
                int i = -1;
                for (int t = 0; t < nx; t++) {
                    int c = side < 0 ? t : nx - 1 - t;
                    if (valid[c][j]) {
                        i = c;
                        break;
                    }
                }
                if (i >= 0) {
                    mazePassage(open, i, j, new int[]{side, 0}, p, pw);
                    break;
                }
            }
        }
        int h = Math.abs(height) <= 1 ? 3 : Math.abs(height);
        java.util.Map<BlockPos, String> blocks = new java.util.HashMap<>();
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gd; gz++) {
                if (open[gx][gz] || lay == MazeLayout.ROUND && !mazeNear(valid, gx, gz, p, nx, nz)) {
                    continue;
                }
                int x = ox + gx;
                int z = oz + gz;
                for (int y = 1; y <= h; y++) {
                    String spec = y == h && st.cap != null && h > 2 ? st.cap : st.walls[ih(n, x, cy + y, z, st.walls.length)];
                    blocks.put(new BlockPos(x, cy + y, z), spec);
                }
                if (st.light != null && gx % p == 0 && gz % p == 0 && ih(n, x, 99, z, 14) == 0) {
                    blocks.put(new BlockPos(x, cy + h + 1, z), st.light);
                }
            }
        }
        emitSorted(out, blocks);
    }

    private static void mazeCell(boolean[][] open, int i, int j, int p, int pw) {
        for (int dx = 1; dx <= pw; dx++) {
            for (int dz = 1; dz <= pw; dz++) {
                open[i * p + dx][j * p + dz] = true;
            }
        }
    }

    /** Knocks through the wall from cell (i, j) towards {@code d}. */
    private static void mazePassage(boolean[][] open, int i, int j, int[] d, int p, int pw) {
        for (int k = 1; k <= pw; k++) {
            if (d[0] != 0) {
                open[d[0] > 0 ? (i + 1) * p : i * p][j * p + k] = true;
            } else {
                open[i * p + k][d[1] > 0 ? (j + 1) * p : j * p] = true;
            }
        }
    }

    private static boolean mazeOpen(boolean[][] open, int i, int j, int[] d, int p) {
        return d[0] != 0 ? open[d[0] > 0 ? (i + 1) * p : i * p][j * p + 1] : open[i * p + 1][d[1] > 0 ? (j + 1) * p : j * p];
    }

    /** Opens a room of rw x rd cells from cell (i0, j0), walls and posts inside it too; only where every cell is in the maze. */
    private static void mazeRoom(boolean[][] open, boolean[][] valid, int i0, int j0, int rw, int rd, int p, int nx, int nz) {
        int i1 = Math.min(nx, i0 + rw);
        int j1 = Math.min(nz, j0 + rd);
        for (int i = i0; i < i1; i++) {
            for (int j = j0; j < j1; j++) {
                if (!valid[i][j]) {
                    return;
                }
            }
        }
        for (int gx = i0 * p + 1; gx < i1 * p; gx++) {
            for (int gz = j0 * p + 1; gz < j1 * p; gz++) {
                open[gx][gz] = true;
            }
        }
    }

    /** Whether grid spot (gx, gz) borders a cell of a round maze (outside it nothing is built). */
    private static boolean mazeNear(boolean[][] valid, int gx, int gz, int p, int nx, int nz) {
        for (int i = gx / p - 1; i <= gx / p; i++) {
            for (int j = gz / p - 1; j <= gz / p; j++) {
                if (i >= 0 && j >= 0 && i < nx && j < nz && valid[i][j] && i * p <= gx && gx <= i * p + p && j * p <= gz && gz <= j * p + p) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ backrooms

    /**
     * The Backrooms: a closed level between corners A and B on the ground at A, Height of air inside (1-2: 4), with a
     * floor, a ceiling of lights and walls round it. Inside it splits into rooms at random, each with its own doorways
     * and its own kind (empty, pillars, bits of wall, a low ceiling, a row of columns, one left dark).
     */
    private static void backrooms(Collector out, BlockPos a, BlockPos b, int height, BackroomsLevel level, long seed) {
        java.util.Random rng = new java.util.Random(seed ^ 0xC2B2AE3D27D4EB4FL);
        BackroomsLevel lv = level == null ? BackroomsLevel.LEVEL_0 : level;
        if (lv == BackroomsLevel.RANDOM) {
            lv = BackroomsLevel.values()[rng.nextInt(BackroomsLevel.values().length - 1)];
        }
        int[] n = {rng.nextInt(1 << 16) - (1 << 15), rng.nextInt(1 << 12), rng.nextInt(1 << 16) - (1 << 15)};
        int x0 = Math.min(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int x1 = Math.max(a.getX(), b.getX());
        int z1 = Math.max(a.getZ(), b.getZ());
        if (x1 - x0 < 4 || z1 - z0 < 4) {
            return;
        }
        int h = Math.abs(height) <= 2 ? 4 : Math.abs(height);
        int fy = a.getY() + 1;
        int top = fy + h + 1;
        java.util.Map<BlockPos, String> blocks = new java.util.HashMap<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                blocks.put(new BlockPos(x, fy, z), lv.floor[ih(n, x, 1, z, lv.floor.length)]);
                blocks.put(new BlockPos(x, top, z), lv.ceiling[ih(n, x, 2, z, lv.ceiling.length)]);
                if (x == x0 || x == x1 || z == z0 || z == z1) {
                    for (int y = fy + 1; y < top; y++) {
                        blocks.put(new BlockPos(x, y, z), lv.walls[ih(n, x, y, z, lv.walls.length)]);
                    }
                }
            }
        }
        java.util.List<int[]> rooms = new java.util.ArrayList<>();
        java.util.List<int[]> doors = new java.util.ArrayList<>();
        backroomsSplit(blocks, rng, n, lv, x0 + 1, z0 + 1, x1 - 1, z1 - 1, fy, h, 0, rooms, doors);
        for (int[] r : rooms) {
            backroomsRoom(blocks, rng, n, lv, r[0], r[1], r[2], r[3], fy, h, top);
        }
        // Doorways stay clear in front and behind, whatever was built next to them.
        for (int[] d : doors) {
            for (int k = -1; k <= 1; k++) {
                int x = d[0] + (d[2] == 0 ? k : 0);
                int z = d[1] + (d[2] == 0 ? 0 : k);
                if (x <= x0 || x >= x1 || z <= z0 || z >= z1) {
                    continue;
                }
                for (int y = fy + 1; y <= fy + d[3]; y++) {
                    blocks.remove(new BlockPos(x, y, z));
                }
            }
        }
        // A way in: a doorway in a random outside wall.
        int side = rng.nextInt(4);
        boolean alongX = side < 2;
        int span = alongX ? x1 - x0 : z1 - z0;
        int at = (alongX ? x0 : z0) + 1 + rng.nextInt(Math.max(1, span - 2));
        int fixed = side == 0 ? z0 : side == 1 ? z1 : side == 2 ? x0 : x1;
        for (int k = 0; k < 2; k++) {
            for (int y = fy + 1; y <= fy + Math.min(3, h); y++) {
                int c = Math.min(at + k, (alongX ? x1 : z1) - 1);
                blocks.remove(alongX ? new BlockPos(c, y, fixed) : new BlockPos(fixed, y, c));
            }
        }
        emitSorted(out, blocks);
    }

    /** Splits the air (ax, az)-(bx, bz) with a wall and doorways, again and again, until it's down to rooms. */
    private static void backroomsSplit(java.util.Map<BlockPos, String> blocks, java.util.Random rng, int[] n, BackroomsLevel lv,
                                       int ax, int az, int bx, int bz, int fy, int h, int depth,
                                       java.util.List<int[]> rooms, java.util.List<int[]> doors) {
        int w = bx - ax + 1;
        int d = bz - az + 1;
        boolean canX = w >= 7;
        boolean canZ = d >= 7;
        if (!canX && !canZ || depth > 0 && w < 16 && d < 16 && rng.nextDouble() < 0.2 || depth > 12) {
            rooms.add(new int[]{ax, az, bx, bz});
            return;
        }
        // A wall across the longer way (or either, now and then).
        boolean wallAtX = canX && (!canZ || (w > d ? rng.nextDouble() < 0.8 : rng.nextDouble() < 0.2));
        int lo = wallAtX ? ax : az;
        int hi = wallAtX ? bx : bz;
        int s = lo + 3 + rng.nextInt(hi - lo - 5);
        int from = wallAtX ? az : ax;
        int to = wallAtX ? bz : bx;
        for (int c = from; c <= to; c++) {
            for (int y = fy + 1; y <= fy + h; y++) {
                int x = wallAtX ? s : c;
                int z = wallAtX ? c : s;
                blocks.put(new BlockPos(x, y, z), lv.walls[ih(n, x, y, z, lv.walls.length)]);
            }
        }
        int length = to - from + 1;
        int count = 1 + rng.nextInt(length > 12 ? 3 : 2);
        for (int i = 0; i < count; i++) {
            int dw = rng.nextDouble() < 0.12 ? Math.max(2, length / 2) : 1 + rng.nextInt(3);
            dw = Math.min(dw, length);
            int start = from + rng.nextInt(length - dw + 1);
            int dh = rng.nextDouble() < 0.6 ? h : Math.min(h, 3);
            for (int c = start; c < start + dw; c++) {
                int x = wallAtX ? s : c;
                int z = wallAtX ? c : s;
                for (int y = fy + 1; y <= fy + dh; y++) {
                    blocks.remove(new BlockPos(x, y, z));
                }
                doors.add(new int[]{x, z, wallAtX ? 0 : 1, dh});
            }
        }
        if (wallAtX) {
            backroomsSplit(blocks, rng, n, lv, ax, az, s - 1, bz, fy, h, depth + 1, rooms, doors);
            backroomsSplit(blocks, rng, n, lv, s + 1, az, bx, bz, fy, h, depth + 1, rooms, doors);
        } else {
            backroomsSplit(blocks, rng, n, lv, ax, az, bx, s - 1, fy, h, depth + 1, rooms, doors);
            backroomsSplit(blocks, rng, n, lv, ax, s + 1, bx, bz, fy, h, depth + 1, rooms, doors);
        }
    }

    /** Fills one room with its kind and its lights. */
    private static void backroomsRoom(java.util.Map<BlockPos, String> blocks, java.util.Random rng, int[] n, BackroomsLevel lv,
                                      int ax, int az, int bx, int bz, int fy, int h, int top) {
        int w = bx - ax + 1;
        int d = bz - az + 1;
        int lightY = top;
        double kind = rng.nextDouble();
        if (kind < 0.35) {
            // empty
        } else if (kind < 0.55 && w >= 5 && d >= 5) {
            // pillars on a grid
            int pitch = 3 + rng.nextInt(2);
            int size = w >= 9 && d >= 9 && rng.nextBoolean() ? 2 : 1;
            for (int x = ax + 1 + rng.nextInt(2); x + size - 1 < bx; x += pitch + size - 1) {
                for (int z = az + 1 + rng.nextInt(2); z + size - 1 < bz; z += pitch + size - 1) {
                    backroomsColumn(blocks, n, lv, x, z, size, fy, h);
                }
            }
        } else if (kind < 0.75 && w >= 5 && d >= 5) {
            // bits of wall standing on their own
            int bits = 1 + rng.nextInt(3);
            for (int i = 0; i < bits; i++) {
                boolean alongX = rng.nextBoolean();
                int len = 2 + rng.nextInt(3);
                int x = ax + 1 + rng.nextInt(Math.max(1, w - 2));
                int z = az + 1 + rng.nextInt(Math.max(1, d - 2));
                for (int k = 0; k < len; k++) {
                    int px = alongX ? x + k : x;
                    int pz = alongX ? z : z + k;
                    if (px < bx && pz < bz) {
                        backroomsColumn(blocks, n, lv, px, pz, 1, fy, h);
                    }
                }
            }
        } else if (kind < 0.87 && h >= 4) {
            // a lower ceiling
            lightY = fy + h;
            for (int x = ax; x <= bx; x++) {
                for (int z = az; z <= bz; z++) {
                    blocks.put(new BlockPos(x, lightY, z), lv.ceiling[ih(n, x, 3, z, lv.ceiling.length)]);
                }
            }
        } else if (w >= 5 || d >= 5) {
            // a row of columns down the middle
            boolean alongX = w >= d;
            for (int k = 1 + rng.nextInt(2); k < (alongX ? w : d) - 1; k += 2 + rng.nextInt(2)) {
                backroomsColumn(blocks, n, lv, alongX ? ax + k : ax + w / 2, alongX ? az + d / 2 : az + k, 1, fy, h);
            }
        }
        if (rng.nextDouble() < 0.08) {
            return; // the lights are out in this one
        }
        int pitch = 3 + rng.nextInt(3);
        int offX = rng.nextInt(Math.min(pitch, w));
        int offZ = rng.nextInt(Math.min(pitch, d));
        for (int x = ax + offX; x <= bx; x += pitch) {
            for (int z = az + offZ; z <= bz; z += pitch) {
                if (rng.nextDouble() >= 0.15) {
                    blocks.put(new BlockPos(x, lightY, z), lv.light);
                }
            }
        }
    }

    private static void backroomsColumn(java.util.Map<BlockPos, String> blocks, int[] n, BackroomsLevel lv, int x, int z, int size, int fy,
                                        int h) {
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int y = fy + 1; y <= fy + h; y++) {
                    blocks.put(new BlockPos(x + dx, y, z + dz), lv.walls[ih(n, x + dx, y, z + dz, lv.walls.length)]);
                }
            }
        }
    }

    /** Adds the blocks bottom layer first, row by row. */
    private static void emitSorted(Collector out, java.util.Map<BlockPos, String> blocks) {
        java.util.List<BlockPos> order = new java.util.ArrayList<>(blocks.keySet());
        order.sort(java.util.Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        for (BlockPos p : order) {
            if (!out.addRole(p.getX(), p.getY(), p.getZ(), BLOCK + blocks.get(p))) {
                return;
            }
        }
    }

    /** {@link #hash} for islands, shifted by the island's own offset {@code n}. */
    private static int ih(int[] n, int x, int y, int z, int m) {
        return hash(x + n[0], y + n[1], z + n[2], m);
    }

    /** 0..1 around the rim with {@code lobes} lobes of each size. */
    private static double wave(double angle, double phase, int[] lobes) {
        double v = Math.sin(lobes[0] * angle + phase) * 0.5 + Math.sin(lobes[1] * angle + phase * 1.7) * 0.3
                + Math.sin(lobes[2] * angle + phase * 2.3) * 0.2;
        return (v + 1) / 2;
    }

    /** Smooth 0..1 bumps over the ground, their size following {@code scale}. */
    private static double field(int x, int z, double phase, double scale) {
        double s = Math.max(4.0, scale) / 2.0;
        double v = Math.sin(x / s + phase) * Math.cos(z / s * 1.3 - phase) * 0.6
                + Math.sin((x + z) / (s * 0.7) + phase * 2) * 0.4;
        return (v + 1) / 2;
    }

    /** One tree (or cactus, huge mushroom, fungus) standing at x,y,z; {@code k} varies its size. */
    private static void islandTree(java.util.Map<BlockPos, String> blocks, IslandStyle.Tree tree, int x, int y, int z, int k) {
        switch (tree) {
            case OAK -> {
                String wood = k % 3 == 0 ? "birch" : "oak";
                int h = 4 + k % 3;
                trunk(blocks, x, y, z, h, "minecraft:" + wood + "_log[axis=y]");
                String leaves = "minecraft:" + wood + "_leaves[persistent=true]";
                for (int ly = y + h - 3; ly <= y + h; ly++) {
                    int r = ly < y + h - 1 ? 2 : 1;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                            if (corner && (r == 1 && ly == y + h || hash(x + dx, ly, z + dz, 2) == 0)) {
                                continue;
                            }
                            blocks.putIfAbsent(new BlockPos(x + dx, ly, z + dz), leaves);
                        }
                    }
                }
            }
            case SPRUCE -> {
                int h = 6 + k % 4;
                trunk(blocks, x, y, z, h, "minecraft:spruce_log[axis=y]");
                String leaves = "minecraft:spruce_leaves[persistent=true]";
                blocks.putIfAbsent(new BlockPos(x, y + h, z), leaves);
                for (int ly = y + h - 1; ly >= y + 2; ly--) {
                    int j = y + h - ly;
                    int wide = Math.min(3, 1 + j / 3);
                    int r = j <= 2 || j % 2 == 1 ? Math.max(1, wide - 1) : wide;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.abs(dx) + Math.abs(dz) <= r + (r >= 2 ? 1 : 0)) {
                                blocks.putIfAbsent(new BlockPos(x + dx, ly, z + dz), leaves);
                            }
                        }
                    }
                }
            }
            case CACTUS -> trunk(blocks, x, y, z, 1 + k % 3, "minecraft:cactus[age=0]");
            case CHERRY -> {
                int h = 4 + k % 2;
                trunk(blocks, x, y, z, h, "minecraft:cherry_log[axis=y]");
                String leaves = "minecraft:cherry_leaves[persistent=true]";
                for (int ly = y + h - 1; ly <= y + h + 1; ly++) {
                    double r = ly == y + h - 1 ? 3.2 : ly == y + h ? 2.8 : 1.8;
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            if (Math.sqrt(dx * dx + dz * dz) <= r) {
                                blocks.putIfAbsent(new BlockPos(x + dx, ly, z + dz), leaves);
                            }
                        }
                    }
                }
                // Blossom hanging off the edge of the canopy.
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d > 2.2 && d <= 3.2 && hash(x + dx, y, z + dz, 3) == 0) {
                            blocks.putIfAbsent(new BlockPos(x + dx, y + h - 2, z + dz), leaves);
                        }
                    }
                }
            }
            case MUSHROOM -> {
                boolean red = k % 2 == 0;
                int h = 5 + k % 2;
                trunk(blocks, x, y, z, h, "minecraft:mushroom_stem");
                if (red) {
                    String cap = "minecraft:red_mushroom_block";
                    for (int ly = y + h - 3; ly < y + h; ly++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && !(Math.abs(dx) == 2 && Math.abs(dz) == 2)) {
                                    blocks.put(new BlockPos(x + dx, ly, z + dz), cap);
                                }
                            }
                        }
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            blocks.put(new BlockPos(x + dx, y + h, z + dz), cap);
                        }
                    }
                } else {
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            if (!(Math.abs(dx) == 3 && Math.abs(dz) == 3)) {
                                blocks.put(new BlockPos(x + dx, y + h, z + dz), "minecraft:brown_mushroom_block");
                            }
                        }
                    }
                }
            }
            case CRIMSON -> {
                int h = 4 + k % 4;
                trunk(blocks, x, y, z, h, "minecraft:crimson_stem[axis=y]");
                for (int ly = y + h - 2; ly <= y + h + 1; ly++) {
                    int r = ly <= y + h - 1 ? 2 : ly == y + h ? 1 : 0;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && hash(x + dx, ly, z + dz, 2) == 0) {
                                continue;
                            }
                            String wart = hash(x + dx, ly, z + dz, 8) == 0 ? "minecraft:shroomlight" : "minecraft:nether_wart_block";
                            blocks.putIfAbsent(new BlockPos(x + dx, ly, z + dz), wart);
                        }
                    }
                }
                // Weeping vines under the cap's edge.
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && hash(x + dx, y, z + dz, 4) == 0) {
                            int length = 1 + hash(x + dx, y + 1, z + dz, Math.max(1, h - 3));
                            for (int i = 0; i < length; i++) {
                                blocks.putIfAbsent(new BlockPos(x + dx, y + h - 3 - i, z + dz),
                                        i == length - 1 ? "minecraft:weeping_vines[age=25]" : "minecraft:weeping_vines_plant");
                            }
                        }
                    }
                }
            }
        }
    }

    private static void trunk(java.util.Map<BlockPos, String> blocks, int x, int y, int z, int h, String spec) {
        for (int i = 0; i < h; i++) {
            blocks.put(new BlockPos(x, y + i, z), spec);
        }
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
        cylinder(out, center, edge, height, hollow, 0);
    }

    /** @param hole radius of a hole cut from the middle (1: just the centre block); the outer ring always stays */
    private static void cylinder(Collector out, BlockPos center, BlockPos edge, int height, boolean hollow, int hole) {
        double holeLimit = hole <= 0 ? -1 : (hole - 0.5) * (hole - 0.5);
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
                    if ((hollow || x * x + z * z <= holeLimit) && interiorOfDisc(x, z, limit)) {
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
        double radius = Math.sqrt(center.distSqr(edge));
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
     * Deck from A to B (sloping with B's height) with a gentle hump, or sagging down in the middle.
     * Unless hollow: fence railings on both sides (a wall or fence matching the deck block) with
     * lantern posts every few blocks and at both ends.
     */
    private static void bridge(Collector out, BlockPos a, BlockPos b, int height, boolean hollow, boolean sag) {
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
        int[] ys = deckHeights(a, b, steps, hump, sag);
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
        ClientLevel world = Minecraft.getInstance().level;
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
                        BlockPos n = p.offset(dx, dy, dz);
                        if (seen.add(n) && world.hasChunkAt(n) && world.getBlockState(n).getBlock() == block) {
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
        ClientLevel world = Minecraft.getInstance().level;
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
                    || p.getY() < world.getMinBuildHeight()) {
                out.positions.clear();
                out.truncated = false;
                return;
            }
            if (!out.add(p.getX(), p.getY(), p.getZ())) {
                return;
            }
            for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[]{
                    net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST,
                    net.minecraft.core.Direction.DOWN}) {
                BlockPos n = p.relative(d);
                if (seen.add(n) && world.hasChunkAt(n) && fillable(world.getBlockState(n))) {
                    queue.add(n);
                }
            }
        }
    }

    private static boolean fillable(net.minecraft.world.level.block.state.BlockState s) {
        // Water, lava, grass and snow layers are all "replaceable".
        return s.isAir() || s.canBeReplaced();
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
