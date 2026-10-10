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

    /** Nodes expanded per {@link Search#step} call; keeps each tick's cost to a few milliseconds. */
    public static final int NODES_PER_TICK = 2500;

    /**
     * An A* search that can be advanced a little each tick, so a long or hopeless search never
     * freezes the game.
     */
    public static final class Search {
        public enum State { RUNNING, FOUND, FAILED }

        private final ClientWorld world;
        private final Predicate<BlockPos> goal;
        private final Vec3d toward;
        private final PriorityQueue<Node> open = new PriorityQueue<>();
        private final Map<Long, Double> best = new HashMap<>();
        private int expanded;
        private State state = State.RUNNING;
        private List<BlockPos> result;

        Search(ClientWorld world, BlockPos start, Predicate<BlockPos> goal, Vec3d toward) {
            this.world = world;
            this.goal = goal;
            this.toward = toward;
            if (goal.test(start)) {
                this.state = State.FOUND;
                this.result = new ArrayList<>();
                return;
            }
            this.open.add(new Node(start, 0.0, h(start, toward), null));
            this.best.put(start.asLong(), 0.0);
        }

        public State state() {
            return this.state;
        }

        /** The path once {@link State#FOUND}: feet positions, start excluded. */
        public List<BlockPos> result() {
            return this.result;
        }

        public State step(int budget) {
            int done = 0;
            while (this.state == State.RUNNING && done < budget) {
                if (this.open.isEmpty() || this.expanded >= MAX_NODES) {
                    this.state = State.FAILED;
                    break;
                }
                Node cur = this.open.poll();
                if (cur.g > this.best.getOrDefault(cur.pos.asLong(), Double.MAX_VALUE)) {
                    continue;
                }
                this.expanded++;
                done++;
                if (this.goal.test(cur.pos)) {
                    this.result = unwind(cur);
                    this.state = State.FOUND;
                    break;
                }
                for (Move m : moves(this.world, cur.pos)) {
                    double g = cur.g + m.cost;
                    long key = m.to.asLong();
                    if (g < this.best.getOrDefault(key, Double.MAX_VALUE)) {
                        this.best.put(key, g);
                        this.open.add(new Node(m.to, g, h(m.to, this.toward), cur));
                    }
                }
            }
            return this.state;
        }
    }

    public static Search start(ClientWorld world, BlockPos start, Predicate<BlockPos> goal, Vec3d toward) {
        return new Search(world, start, goal, toward);
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
