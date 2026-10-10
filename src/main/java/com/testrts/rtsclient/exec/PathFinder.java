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
 * and drop down up to three. With a {@link Building} context (the builder) it can also build its
 * own way: pillar up (jump and place a block underneath), bridge (place the block to step onto)
 * and dig back down through its own scaffolding, so blocks high up or over a drop are reachable.
 * Steps are checked against the real floor heights (slabs, carpets, soul sand...), so a path
 * never asks for a jump higher than the player can make.
 */
public final class PathFinder {
    private static final int MAX_NODES = 40_000;
    private static final int MAX_DROP = 3;
    /** Rise the player walks up without jumping (vanilla step height). */
    private static final double STEP_HEIGHT = 0.6;
    /** Highest rise a jump clears (a jump reaches about 1.25). */
    private static final double MAX_RISE = 1.2;
    /** How long a step the player got stuck on stays more expensive. */
    private static final long STRIKE_MS = 60_000L;
    /** Strikes after which a step is left out of searches altogether (until it expires). */
    private static final int STRIKE_BLOCK = 3;
    private static final double STRIKE_EDGE_COST = 30.0;
    private static final double STRIKE_NODE_COST = 4.0;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    /** Costs of the building moves, in "blocks walked": slower than walking, and they use up blocks. */
    private static final double PILLAR_COST = 5.0;
    private static final double BRIDGE_COST = 4.0;
    private static final double DIG_COST = 3.0;

    /** How a step of the path is taken. */
    public enum Action {
        WALK,
        /** Jump and place a block under ourselves: the step is straight up. */
        PILLAR,
        /** Place the block under the next step first, then walk onto it. */
        BRIDGE,
        /** Mine the block we stand on (our own scaffolding) and drop onto the one below. */
        DIG
    }

    /** What the search may build or take down to get somewhere. */
    public interface Building {
        /** A temporary block may be put in this (empty) cell. */
        boolean canPlace(BlockPos pos);

        /** This block is ours to mine on the way down. */
        boolean canDig(BlockPos pos);

        /** How many blocks one route may place. */
        int budget();
    }

    /** A step from one node to another; {@code from == Long.MIN_VALUE} stands for "any step into {@code to}". */
    private record Edge(long from, long to) {
    }

    private static final class Strike {
        int count;
        long until;
    }

    /** Steps the player got stuck on recently. Client thread only. */
    private static final Map<Edge, Strike> STRIKES = new HashMap<>();

    private PathFinder() {
    }

    /**
     * Remember that the player couldn't get from {@code from} to {@code to}, so the next searches
     * go around it for a while (a mob in the way, a block the rules got wrong...).
     */
    public static void reportStuck(@Nullable BlockPos from, @Nullable BlockPos to) {
        if (from == null || to == null) {
            return;
        }
        long now = System.currentTimeMillis();
        STRIKES.values().removeIf(s -> s.until < now);
        strike(new Edge(from.asLong(), to.asLong()), now);
        strike(new Edge(Long.MIN_VALUE, to.asLong()), now);
    }

    private static void strike(Edge edge, long now) {
        Strike s = STRIKES.computeIfAbsent(edge, e -> new Strike());
        s.count++;
        s.until = now + STRIKE_MS;
    }

    /** Extra cost of a step the player got stuck on; infinite when it should be avoided entirely. */
    private static double strikeCost(BlockPos from, BlockPos to, long now) {
        double cost = 0.0;
        Strike edge = STRIKES.get(new Edge(from.asLong(), to.asLong()));
        if (edge != null && edge.until >= now) {
            if (edge.count >= STRIKE_BLOCK) {
                return Double.POSITIVE_INFINITY;
            }
            cost += STRIKE_EDGE_COST * edge.count;
        }
        Strike node = STRIKES.get(new Edge(Long.MIN_VALUE, to.asLong()));
        if (node != null && node.until >= now) {
            cost += STRIKE_NODE_COST * node.count;
        }
        return cost;
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g;
        final double f;
        final Node parent;
        final Action action;
        /** Blocks placed along the route so far. */
        final int placed;

        Node(BlockPos pos, double g, double h, Node parent) {
            this(pos, g, h, parent, Action.WALK, 0);
        }

        Node(BlockPos pos, double g, double h, Node parent, Action action, int placed) {
            this.pos = pos;
            this.g = g;
            this.f = g + h;
            this.parent = parent;
            this.action = action;
            this.placed = placed;
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
        private final long now = System.currentTimeMillis();
        private final boolean strikes = !STRIKES.isEmpty();
        @Nullable
        private final Building building;
        private int expanded;
        private State state = State.RUNNING;
        private List<BlockPos> result;
        private List<Action> actions;

        Search(ClientWorld world, BlockPos start, Predicate<BlockPos> goal, Vec3d toward, @Nullable Building building) {
            this.world = world;
            this.building = building;
            this.goal = goal;
            this.toward = toward;
            if (goal.test(start)) {
                this.state = State.FOUND;
                this.result = new ArrayList<>();
                this.actions = new ArrayList<>();
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

        /** How each step of {@link #result()} is taken. */
        public List<Action> actions() {
            return this.actions;
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
                    unwind(cur);
                    this.state = State.FOUND;
                    break;
                }
                List<Move> moves = moves(this.world, cur.pos);
                if (this.building != null) {
                    buildingMoves(this.world, this.building, cur, moves);
                }
                for (Move m : moves) {
                    double extra = this.strikes ? strikeCost(cur.pos, m.to, this.now) : 0.0;
                    if (extra == Double.POSITIVE_INFINITY) {
                        continue;
                    }
                    double g = cur.g + m.cost + extra;
                    long key = m.to.asLong();
                    if (g < this.best.getOrDefault(key, Double.MAX_VALUE)) {
                        this.best.put(key, g);
                        int placed = cur.placed + (m.action == Action.PILLAR || m.action == Action.BRIDGE ? 1 : 0);
                        this.open.add(new Node(m.to, g, h(m.to, this.toward), cur, m.action, placed));
                    }
                }
            }
            return this.state;
        }

        private void unwind(Node end) {
            List<BlockPos> path = new ArrayList<>();
            List<Action> acts = new ArrayList<>();
            for (Node n = end; n.parent != null; n = n.parent) {
                path.add(n.pos);
                acts.add(n.action);
            }
            Collections.reverse(path);
            Collections.reverse(acts);
            this.result = path;
            this.actions = acts;
        }
    }

    public static Search start(ClientWorld world, BlockPos start, Predicate<BlockPos> goal, Vec3d toward) {
        return new Search(world, start, goal, toward, null);
    }

    /** A search that may also pillar, bridge and dig through scaffolding (see {@link Building}). */
    public static Search start(ClientWorld world, BlockPos start, Predicate<BlockPos> goal, Vec3d toward,
                               @Nullable Building building) {
        return new Search(world, start, goal, toward, building);
    }

    private record Move(BlockPos to, double cost, Action action) {
        Move(BlockPos to, double cost) {
            this(to, cost, Action.WALK);
        }
    }

    /**
     * Moves that change the world. The search doesn't track what an earlier step of the same route
     * placed; each step only looks at the cells it needs (like Baritone), and if the world turns out
     * different the follower gets stuck and the route is planned again from there.
     */
    private static void buildingMoves(ClientWorld world, Building b, Node cur, List<Move> out) {
        BlockPos from = cur.pos;
        boolean canPlace = cur.placed < b.budget();
        boolean inFluid = !world.getBlockState(from).getFluidState().isEmpty();
        // Pillar: our feet cell becomes a block and we stand on it.
        if (canPlace && !inFluid && Walkability.passable(world, from.up(2)) && b.canPlace(from)) {
            out.add(new Move(from.up(), PILLAR_COST, Action.PILLAR));
        }
        // Bridge: put a block under the next cell and walk onto it.
        if (canPlace) {
            for (Direction d : HORIZONTAL) {
                BlockPos n = from.offset(d);
                if (clear(world, n) && !Walkability.standable(world, n) && b.canPlace(n.down())) {
                    out.add(new Move(n, BRIDGE_COST, Action.BRIDGE));
                }
            }
        }
        // Dig down through our own scaffolding onto the block below it.
        BlockPos floor = from.down();
        if (b.canDig(floor) && (Walkability.floor(world, floor.down()) || b.canDig(floor.down()))) {
            out.add(new Move(floor, DIG_COST, Action.DIG));
        }
    }

    private static List<Move> moves(ClientWorld world, BlockPos from) {
        List<Move> out = new ArrayList<>(12);
        boolean headroom = Walkability.passable(world, from.up(2));
        double fromY = Walkability.standY(world, from);
        for (Direction d : HORIZONTAL) {
            BlockPos n = from.offset(d);
            if (Walkability.standable(world, n)) {
                // Same node level, but e.g. carpet -> full block still needs a jump (and room for it).
                double rise = Walkability.standY(world, n) - fromY;
                if (rise <= STEP_HEIGHT) {
                    out.add(new Move(n, 1.0));
                } else if (rise <= MAX_RISE && headroom && Walkability.passable(world, n.up(2))) {
                    out.add(new Move(n, 1.5));
                }
                continue;
            }
            // Step up onto a block: needs room to jump from here, and the jump must be high enough
            // (a full block from a slab is 1.5 up, which nobody can jump).
            BlockPos up = n.up();
            if (headroom && Walkability.passable(world, n.up(2)) && Walkability.standable(world, up)
                    && Walkability.standY(world, up) - fromY <= MAX_RISE) {
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
                // No jumping on diagonals: the corner would catch the player.
                if (Walkability.standable(world, diag)
                        && clear(world, from.offset(a)) && clear(world, from.offset(b))
                        && Walkability.standY(world, diag) - fromY <= STEP_HEIGHT) {
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

}
