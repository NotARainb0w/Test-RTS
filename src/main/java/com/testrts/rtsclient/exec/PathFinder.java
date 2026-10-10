package com.testrts.rtsclient.exec;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* over standing positions (feet blocks). Moves: walk, diagonal walk, step up one block,
 * and drop down up to three. No bridging or pillaring; that's what Baritone is for.
 */
public final class PathFinder {
    private static final int MAX_NODES = 40_000;
    private static final int MAX_DROP = 3;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private PathFinder() {
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g;
        final double f;
        final Node parent;

        Node(BlockPos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.f = g + h;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }

    /**
     * @param goal    accepts a feet position as "arrived"
     * @param toward  point the search is drawn to (the heuristic)
     * @return feet positions from start (exclusive) to goal (inclusive), or null if none found
     */
    @Nullable
    public static List<BlockPos> find(ClientWorld world, BlockPos start, Predicate<BlockPos> goal, Vec3d toward) {
        if (goal.test(start)) {
            return new ArrayList<>();
        }
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        open.add(new Node(start, 0.0, h(start, toward), null));
        best.put(start.asLong(), 0.0);
        Node closest = null;
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll();
            if (cur.g > best.getOrDefault(cur.pos.asLong(), Double.MAX_VALUE)) {
                continue;
            }
            expanded++;
            if (goal.test(cur.pos)) {
                return unwind(cur);
            }
            if (closest == null || h(cur.pos, toward) < h(closest.pos, toward)) {
                closest = cur;
            }
            for (Move m : moves(world, cur.pos)) {
                double g = cur.g + m.cost;
                long key = m.to.asLong();
                if (g < best.getOrDefault(key, Double.MAX_VALUE)) {
                    best.put(key, g);
                    open.add(new Node(m.to, g, h(m.to, toward), cur));
                }
            }
        }
        return null;
    }

    private record Move(BlockPos to, double cost) {
    }

    private static List<Move> moves(ClientWorld world, BlockPos from) {
        List<Move> out = new ArrayList<>(12);
        boolean headroom = Walkability.passable(world, from.up(2));
        for (Direction d : HORIZONTAL) {
            BlockPos n = from.offset(d);
            if (Walkability.standable(world, n)) {
                out.add(new Move(n, 1.0));
                continue;
            }
            // Step up onto a block: needs room to jump from here.
            BlockPos up = n.up();
            if (headroom && Walkability.passable(world, n.up(2)) && Walkability.standable(world, up)) {
                out.add(new Move(up, 2.0));
                continue;
            }
            // Walk off an edge and fall a few blocks.
            if (Walkability.passable(world, n) && Walkability.passable(world, n.up())) {
                BlockPos down = n;
                for (int k = 1; k <= MAX_DROP; k++) {
                    down = down.down();
                    if (Walkability.standable(world, down)) {
                        out.add(new Move(down, 1.0 + k * 0.6));
                        break;
                    }
                    if (!Walkability.passable(world, down)) {
                        break;
                    }
                }
            }
        }
        // Diagonals only when both side cells are clear, so we never clip a corner.
        for (int i = 0; i < 2; i++) {
            for (int j = 2; j < 4; j++) {
                Direction a = HORIZONTAL[i];
                Direction b = HORIZONTAL[j];
                BlockPos diag = from.offset(a).offset(b);
                if (Walkability.standable(world, diag)
                        && clear(world, from.offset(a)) && clear(world, from.offset(b))) {
                    out.add(new Move(diag, 1.414));
                }
            }
        }
        return out;
    }

    private static boolean clear(ClientWorld world, BlockPos feet) {
        return Walkability.passable(world, feet) && Walkability.passable(world, feet.up());
    }

    private static double h(BlockPos p, Vec3d toward) {
        double dx = p.getX() + 0.5 - toward.x;
        double dy = p.getY() - toward.y;
        double dz = p.getZ() + 0.5 - toward.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<BlockPos> unwind(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            path.add(n.pos);
        }
        Collections.reverse(path);
        return path;
    }
}
