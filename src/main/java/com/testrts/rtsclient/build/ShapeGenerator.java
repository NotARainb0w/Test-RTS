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

    /** Positions in the order they were generated; {@code truncated} means the limit was hit. */
    public record Result(Set<BlockPos> positions, boolean truncated) {
    }

    private ShapeGenerator() {
    }

    /**
     * @param height vertical extent for WALL/BOX/CYLINDER. Positive grows upward from A, negative grows
     *               downward (handy for digging). Never zero.
     */
    public static Result generate(BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow, int limit) {
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
            case SPHERE -> sphere(out, a, b, hollow);
            case VEIN -> vein(out, a);
            case HOLE -> hole(out, a);
        }
        return new Result(out.positions, out.truncated);
    }

    private static final class Collector {
        final Set<BlockPos> positions = new LinkedHashSet<>();
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
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            if (!out.add(
                    (int) Math.round(a.getX() + dx * t),
                    (int) Math.round(a.getY() + dy * t),
                    (int) Math.round(a.getZ() + dz * t))) {
                return;
            }
        }
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
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0 : (double) i / steps;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            for (int y = y0; y <= y1; y++) {
                boolean edge = i == 0 || i == steps || y == y0 || y == y1;
                if ((!hollow || edge) && !out.add(x, y, z)) {
                    return;
                }
            }
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

    private static void sphere(Collector out, BlockPos center, BlockPos edge, boolean hollow) {
        double radius = Math.sqrt(center.getSquaredDistance(edge));
        double limit = (radius + 0.5) * (radius + 0.5);
        int r = (int) Math.ceil(radius);
        for (int y = -r; y <= r; y++) {
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
