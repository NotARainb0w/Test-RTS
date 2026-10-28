package com.testrts.rtsclient.exec;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
 * <p>
 * Several ideas come from mineflayer-pathfinder (MIT): partial routes to the closest spot found when
 * a search runs out ({@link Search#partial()}), long drops into water, diagonal step-downs, extra
 * cost for swimming and for cells mobs stand in, and tunnelling that won't let water or lava in and
 * prefers soft blocks.
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
    /** A block up or down a ladder, vine or scaffolding column. */
    private static final double CLIMB_COST = 1.2;
    /**
     * Per block mined to tunnel through: high, so a route only digs when walking (or a long way round)
     * can't get there. The builder puts the blocks back afterwards.
     */
    private static final double TUNNEL_COST = 8.0;
    /** Added per point of hardness (capped at 10) of a block tunnelled through: dirt over stone over obsidian. */
    private static final double HARDNESS_COST = 2.0;
    /** Added for tunnelling under sand or gravel, which falls in and has to be mined again. */
    private static final double FALLING_COST = 12.0;
    /** Longest drop into water: landing in water breaks any fall. */
    private static final int MAX_WATER_DROP = 24;
    /** Added to every step taken from water (swimming is slow). */
    private static final double SWIM_COST = 1.0;
    /** Added for stepping into a cell a mob, player or vehicle is in; far more when it's hostile. */
    private static final double ENTITY_COST = 3.0;
    private static final double HOSTILE_COST = 20.0;
    /** Entities further than this from the start are left out (they'll have moved by the time we get there). */
    private static final double ENTITY_RANGE = 48.0;
    /** A partial route must end at least this much closer to the target than where it starts. */
    private static final double MIN_PARTIAL_GAIN = 4.0;
    /** Longest parkour jump, in blocks from take-off to landing: a three-block gap, sprinting. */
    private static final int MAX_PARKOUR = 4;
    /** Added to a parkour jump on top of the blocks it covers: a jump can be missed. */
    private static final double PARKOUR_COST = 1.5;
    /** Added for a jump over a gap deeper than a safe fall (and not into water). */
    private static final double DEEP_GAP_COST = 10.0;
    /** Longest jump at an angle (centre to centre), sprinting; and without sprinting (too hungry). */
    private static final double MAX_ANGLED_JUMP = 4.0;
    private static final double MAX_WALK_JUMP = 2.3;

    /** How a step of the path is taken. */
    public enum Action {
        WALK,
        /** Jump and place a block under ourselves: the step is straight up. */
        PILLAR,
        /** Place the block under the next step first, then walk onto it. */
        BRIDGE,
        /** Mine the block we stand on (our own scaffolding) and drop onto the one below. */
        DIG,
        /** Up or down a ladder, vine or scaffolding column, like a player climbs one (the step is straight up or down). */
        CLIMB,
        /** Mine what's in the way of the next step (see {@link #tunnelCells}), then take it. */
        TUNNEL,
        /** Jump across a gap to the next step (straight along one axis; level, one up or one down). */
        PARKOUR
    }

    /** What the search may build or take down to get somewhere. */
    public interface Building {
        /** A temporary block may be put in this (empty) cell. */
        boolean canPlace(BlockPos pos);

        /** May route steps pillar up (jump and place a block under us)? Not with scaffolding alone. */
        default boolean canPillar() {
            return true;
        }

        /** This block is ours to mine on the way down. */
        boolean canDig(BlockPos pos);

        /** How many blocks one route may place. */
        int budget();

        /** This block may be mined to get through (it is put back once the work is done). */
        default boolean canTunnel(BlockPos pos) {
            return false;
        }
    }

    /**
     * The cells a step from {@code from} to {@code to} (one block across, level or one up or down) passes
     * through: feet and head at the target, plus the head room for the jump up or the step down.
     * Mined top first, so nothing is left hanging over the player.
     */
    public static List<BlockPos> tunnelCells(BlockPos from, BlockPos to) {
        List<BlockPos> out = new ArrayList<>(3);
        if (to.getY() > from.getY()) {
            out.add(from.above(2));
        } else if (to.getY() < from.getY()) {
            out.add(to.above(2));
        }
        out.add(to.above());
        out.add(to);
        return out;
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
    /** Longest one {@link Search#step} call may take (6 ms of a 50 ms tick). */
    private static final long MAX_STEP_NANOS = 6_000_000L;
    /** Nodes a search may expand: a base, and more the further away the target is (at most {@link #MAX_NODES}). */
    private static final int BASE_NODES = 3000;
    private static final int NODES_PER_BLOCK = 400;
    /**
     * How much further from the target than the start a route may wander. A hopeless search (the target
     * can't be walked to) then fails in a fraction of a second instead of exploring the whole area.
     */
    private static final double WANDER = 24.0;

    /**
     * An A* search that can be advanced a little each tick, so a long or hopeless search never
     * freezes the game.
     */
    public static final class Search {
        public enum State { RUNNING, FOUND, FAILED }

        private final ClientLevel world;
        private final Predicate<BlockPos> goal;
        private final Vec3 toward;
        private final PriorityQueue<Node> open = new PriorityQueue<>();
        private final Map<Long, Double> best = new HashMap<>();
        private final long now = System.currentTimeMillis();
        private final boolean strikes = !STRIKES.isEmpty();
        @Nullable
        private final Building building;
        private int expanded;
        /** This search's node limit and how far from the target it may go (see {@link #WANDER}). */
        private final int maxNodes;
        private final double maxH;
        private State state = State.RUNNING;
        private List<BlockPos> result;
        private List<Action> actions;
        /** Extra cost of cells entities are in (see {@link #ENTITY_COST}). */
        private final Map<Long, Double> entityCells;
        /** The node closest to the target so far, for {@link #partial()}. */
        private Node closest;
        private final double startH;
        private List<Action> partialActions;
        /** Longest parkour jump this search may use (see {@link #parkourReach()}). */
        private final int parkour;

        Search(ClientLevel world, BlockPos start, Predicate<BlockPos> goal, Vec3 toward, @Nullable Building building) {
            this.world = world;
            this.building = building;
            this.goal = goal;
            this.toward = toward;
            double startH = h(start, toward);
            this.startH = startH;
            this.maxNodes = (int) Math.min(MAX_NODES, BASE_NODES + NODES_PER_BLOCK * startH);
            this.maxH = startH + WANDER;
            this.entityCells = entityCells(world, start);
            this.parkour = parkourReach();
            if (goal.test(start)) {
                this.state = State.FOUND;
                this.result = new ArrayList<>();
                this.actions = new ArrayList<>();
                return;
            }
            this.closest = new Node(start, 0.0, startH, null);
            this.open.add(this.closest);
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

        /**
         * Once {@link State#FAILED}: a route to the spot closest to the target the search reached, if that is
         * worth walking to (at least {@link #MIN_PARTIAL_GAIN} closer than the start), else null. Walking it and
         * planning again from there gets to targets too far or too twisty for one search.
         */
        @Nullable
        public List<BlockPos> partial() {
            if (this.state != State.FAILED || this.closest == null || this.closest.parent == null
                    || this.startH - (this.closest.f - this.closest.g) < MIN_PARTIAL_GAIN) {
                return null;
            }
            List<BlockPos> path = new ArrayList<>();
            List<Action> acts = new ArrayList<>();
            for (Node n = this.closest; n.parent != null; n = n.parent) {
                path.add(n.pos);
                acts.add(n.action);
            }
            Collections.reverse(path);
            Collections.reverse(acts);
            this.partialActions = acts;
            return path;
        }

        /** How each step of the last {@link #partial()} route is taken. */
        public List<Action> partialActions() {
            return this.partialActions;
        }

        public State step(int budget) {
            int done = 0;
            // Also capped by time: tunnelling makes searches that fail explore a lot more, and a node
            // count alone let those cost whole frames.
            long deadline = System.nanoTime() + MAX_STEP_NANOS;
            while (this.state == State.RUNNING && done < budget
                    && ((done & 63) != 0 || System.nanoTime() < deadline)) {
                if (this.open.isEmpty() || this.expanded >= this.maxNodes) {
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
                List<Move> moves = moves(this.world, cur.pos, this.parkour);
                if (this.building != null) {
                    buildingMoves(this.world, this.building, cur, moves);
                }
                double swim = this.world.getBlockState(cur.pos).getFluidState().isEmpty() ? 0.0 : SWIM_COST;
                for (Move m : moves) {
                    double extra = this.strikes ? strikeCost(cur.pos, m.to, this.now) : 0.0;
                    if (extra == Double.POSITIVE_INFINITY) {
                        continue;
                    }
                    extra += swim;
                    if (!this.entityCells.isEmpty()) {
                        extra += Math.max(this.entityCells.getOrDefault(m.to.asLong(), 0.0),
                                this.entityCells.getOrDefault(m.to.above().asLong(), 0.0));
                    }
                    double mh = h(m.to, this.toward);
                    if (mh > this.maxH) {
                        continue; // wandered too far from the target
                    }
                    double g = cur.g + m.cost + extra;
                    long key = m.to.asLong();
                    if (g < this.best.getOrDefault(key, Double.MAX_VALUE)) {
                        this.best.put(key, g);
                        int placed = cur.placed + (m.action == Action.PILLAR || m.action == Action.BRIDGE ? 1 : 0);
                        Node next = new Node(m.to, g, mh, cur, m.action, placed);
                        this.open.add(next);
                        if (mh < this.closest.f - this.closest.g) {
                            this.closest = next;
                        }
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

    /**
     * The cells mobs, other players and vehicles near {@code start} are in, with the extra cost of stepping
     * into them. A snapshot: they move, but a route through a crowd or past a creeper is still worse.
     */
    private static Map<Long, Double> entityCells(ClientLevel world, BlockPos start) {
        Map<Long, Double> out = new HashMap<>();
        Vec3 centre = Vec3.atCenterOf(start);
        for (Entity e : world.entitiesForRendering()) {
            if (e instanceof LocalPlayer || !(e instanceof LivingEntity || e.canBeCollidedWith())
                    || e.getPassengers().stream().anyMatch(p -> p instanceof LocalPlayer)
                    || e.position().distanceToSqr(centre) > ENTITY_RANGE * ENTITY_RANGE) {
                continue;
            }
            double cost = e instanceof Enemy ? HOSTILE_COST : ENTITY_COST;
            AABB box = e.getBoundingBox();
            for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0E-4); x++) {
                for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY - 1.0E-4); y++) {
                    for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0E-4); z++) {
                        out.merge(BlockPos.asLong(x, y, z), cost, Math::max);
                    }
                }
            }
        }
        return out;
    }

    /** How far a parkour jump may go: 0 when turned off, 2 (one-block gaps, no sprint) when too hungry to sprint. */
    private static int parkourReach() {
        if (!com.testrts.rtsclient.config.RtsConfig.get().parkour) {
            return 0;
        }
        LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        return p == null || p.getFoodData().getFoodLevel() > 6 || p.mayFly() ? MAX_PARKOUR : 2;
    }

    public static Search start(ClientLevel world, BlockPos start, Predicate<BlockPos> goal, Vec3 toward) {
        return new Search(world, start, goal, toward, null);
    }

    /** A search that may also pillar, bridge and dig through scaffolding (see {@link Building}). */
    public static Search start(ClientLevel world, BlockPos start, Predicate<BlockPos> goal, Vec3 toward,
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
    private static void buildingMoves(ClientLevel world, Building b, Node cur, List<Move> out) {
        BlockPos from = cur.pos;
        boolean canPlace = cur.placed < b.budget();
        boolean inFluid = !world.getBlockState(from).getFluidState().isEmpty();
        // Pillar: our feet cell becomes a block and we stand on it.
        if (canPlace && b.canPillar() && !inFluid && Walkability.passable(world, from.above(2)) && b.canPlace(from)) {
            out.add(new Move(from.above(), PILLAR_COST, Action.PILLAR));
        }
        // Bridge: put a block under the next cell and walk onto it.
        if (canPlace) {
            for (Direction d : HORIZONTAL) {
                BlockPos n = from.relative(d);
                if (clear(world, n) && !Walkability.standable(world, n) && b.canPlace(n.below())) {
                    out.add(new Move(n, BRIDGE_COST, Action.BRIDGE));
                }
            }
        }
        // Dig down through our own scaffolding onto the block below it.
        BlockPos floor = from.below();
        if (b.canDig(floor) && (Walkability.floor(world, floor.below()) || b.canDig(floor.below()))) {
            // Our own scaffolding: break down through it (instant) rather than try to climb down it.
            out.removeIf(m -> m.to.equals(floor) && m.action == Action.CLIMB);
            out.add(new Move(floor, DIG_COST, Action.DIG));
        }
        // Tunnel: mine through to the next cell (level, one up or one down) when there's ground to stand on.
        if (!inFluid) {
            for (Direction d : HORIZONTAL) {
                BlockPos n = from.relative(d);
                tunnelMove(world, b, from, n, out);
                tunnelMove(world, b, from, n.above(), out);
                tunnelMove(world, b, from, n.below(), out);
            }
        }
    }

    private static void tunnelMove(ClientLevel world, Building b, BlockPos from, BlockPos to, List<Move> out) {
        if (!Walkability.floor(world, to.below())) {
            return;
        }
        int dig = 0;
        double cost = 1.5;
        for (BlockPos c : tunnelCells(from, to)) {
            if (!Walkability.passable(world, c)) {
                if (!b.canTunnel(c) || nextToFluid(world, c)) {
                    return; // mining next to water or lava lets it flow into the tunnel
                }
                dig++;
                BlockState s = world.getBlockState(c);
                cost += TUNNEL_COST + HARDNESS_COST * Math.min(10.0, Math.max(0.0, s.getDestroySpeed(world, c)));
                if (world.getBlockState(c.above()).getBlock() instanceof FallingBlock) {
                    cost += FALLING_COST;
                }
            }
        }
        if (dig > 0) {
            // Nothing to mine is a plain walk, which the walking moves already cover.
            out.add(new Move(to, cost, Action.TUNNEL));
        }
    }

    /** Water or lava above or beside {@code pos}, which would flow in once it's mined. */
    private static boolean nextToFluid(ClientLevel world, BlockPos pos) {
        if (!world.getBlockState(pos.above()).getFluidState().isEmpty()) {
            return true;
        }
        for (Direction d : HORIZONTAL) {
            if (!world.getBlockState(pos.relative(d)).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<Move> moves(ClientLevel world, BlockPos from, int parkour) {
        List<Move> out = new ArrayList<>(12);
        boolean headroom = Walkability.passable(world, from.above(2));
        double fromY = Walkability.standY(world, from);
        for (Direction d : HORIZONTAL) {
            BlockPos n = from.relative(d);
            if (Walkability.standable(world, n)) {
                // Same node level, but e.g. carpet -> full block still needs a jump (and room for it).
                double rise = Walkability.standY(world, n) - fromY;
                if (rise <= STEP_HEIGHT) {
                    out.add(new Move(n, 1.0));
                } else if (rise <= MAX_RISE && headroom && Walkability.passable(world, n.above(2))) {
                    out.add(new Move(n, 1.5));
                }
                continue;
            }
            // Step up onto a block: needs room to jump from here, and the jump must be high enough
            // (a full block from a slab is 1.5 up, which nobody can jump).
            BlockPos up = n.above();
            if (headroom && Walkability.passable(world, n.above(2)) && Walkability.standable(world, up)
                    && Walkability.standY(world, up) - fromY <= MAX_RISE) {
                out.add(new Move(up, 2.0));
                continue;
            }
            // Walk off an edge and fall a few blocks, or any height into water.
            if (Walkability.passable(world, n) && Walkability.passable(world, n.above())) {
                BlockPos down = n;
                for (int k = 1; k <= MAX_WATER_DROP; k++) {
                    down = down.below();
                    if (Walkability.standable(world, down)) {
                        if (k <= MAX_DROP || isWater(world, down.below())) {
                            out.add(new Move(down, 1.0 + k * 0.6));
                        }
                        break;
                    }
                    if (!Walkability.passable(world, down)) {
                        break;
                    }
                }
            }
            if (parkour >= 2) {
                parkourMoves(world, from, d, fromY, headroom, parkour, out);
            }
        }
        if (parkour >= 2) {
            angledParkour(world, from, fromY, headroom, parkour, out);
        }
        // Up and down a ladder, vine or scaffolding column.
        BlockPos above = from.above();
        if (Walkability.climbable(world, from) && Walkability.standable(world, above)) {
            out.add(new Move(above, CLIMB_COST, Action.CLIMB));
        }
        BlockPos under = from.below();
        if (Walkability.climbable(world, under) && Walkability.standable(world, under)) {
            out.add(new Move(under, CLIMB_COST, Action.CLIMB));
        }
        // Diagonals only when both side cells are clear, so we never clip a corner.
        for (int i = 0; i < 2; i++) {
            for (int j = 2; j < 4; j++) {
                Direction a = HORIZONTAL[i];
                Direction b = HORIZONTAL[j];
                BlockPos diag = from.relative(a).relative(b);
                // No jumping on diagonals: the corner would catch the player.
                BlockPos sideA = from.relative(a);
                BlockPos sideB = from.relative(b);
                if (!clear(world, sideA) || !clear(world, sideB)) {
                    continue;
                }
                if (Walkability.standable(world, diag)) {
                    if (Walkability.standY(world, diag) - fromY <= STEP_HEIGHT) {
                        out.add(new Move(diag, 1.414));
                    }
                } else if (clear(world, diag) && Walkability.standable(world, diag.below())
                        && droppable(world, sideA) && droppable(world, sideB)) {
                    // Diagonal step down one block. The sides may be level or one lower, but not a deeper
                    // drop the player could slip into on the way across.
                    out.add(new Move(diag.below(), 2.0));
                }
            }
        }
        return out;
    }

    /**
     * Jumps across a gap that starts right in front of {@code from} (direction {@code d}), up to {@code reach} blocks:
     * landing level, one block down, or up on the block that ends the gap. As in mineflayer-pathfinder, every column
     * passed over needs room for the arc.
     */
    private static void parkourMoves(ClientLevel world, BlockPos from, Direction d, double fromY, boolean headroom,
                                     int reach, List<Move> out) {
        BlockPos gap = from.relative(d);
        if (!headroom || !clear(world, gap) || !Walkability.passable(world, gap.above(2))
                || !Walkability.passable(world, gap.below()) || Walkability.climbable(world, from)
                || !world.getBlockState(from).getFluidState().isEmpty()) {
            return;
        }
        double risk = gapRisk(world, gap.below());
        if (risk == Double.POSITIVE_INFINITY) {
            return;
        }
        for (int k = 2; k <= reach; k++) {
            BlockPos c = from.relative(d, k);
            if (!Walkability.passable(world, c.above(2))) {
                return; // no room for the arc
            }
            double cost = k + PARKOUR_COST + risk;
            if (clear(world, c)) {
                if (Walkability.standable(world, c)) {
                    double rise = Walkability.standY(world, c) - fromY;
                    if (rise <= STEP_HEIGHT && rise >= -0.6) {
                        out.add(new Move(c, cost, Action.PARKOUR));
                    }
                    return;
                }
                BlockPos low = c.below();
                if (!Walkability.passable(world, low)) {
                    return; // too tall to land on (a fence or wall)
                }
                if (Walkability.standable(world, low) && fromY - Walkability.standY(world, low) <= 1.6) {
                    out.add(new Move(low, cost, Action.PARKOUR));
                }
                // Still a gap at our level: a longer jump may clear it too.
            } else {
                // The gap ends at a block: jump up onto it (not from four away, which mostly falls short).
                BlockPos up = c.above();
                if (k < MAX_PARKOUR && Walkability.standable(world, up) && Walkability.standY(world, up) - fromY <= MAX_RISE) {
                    out.add(new Move(up, cost + 0.5, Action.PARKOUR));
                }
                return;
            }
        }
    }

    /**
     * Parkour jumps at an angle (not straight along x or z), level or one block down, to landings up to
     * {@link #MAX_ANGLED_JUMP} away ({@link #MAX_WALK_JUMP} when too hungry to sprint). Only tried next to a gap and
     * toward it, so open ground doesn't pay for the checks.
     */
    private static void angledParkour(ClientLevel world, BlockPos from, double fromY, boolean headroom, int reach,
                                      List<Move> out) {
        if (!headroom || Walkability.climbable(world, from) || !world.getBlockState(from).getFluidState().isEmpty()) {
            return;
        }
        // Gaps next to us, in HORIZONTAL order: north (-z), south (+z), east (+x), west (-x).
        boolean[] gap = new boolean[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            BlockPos n = from.relative(HORIZONTAL[i]);
            gap[i] = clear(world, n) && Walkability.passable(world, n.below());
            any |= gap[i];
        }
        if (!any) {
            return;
        }
        double maxDist = reach >= MAX_PARKOUR ? MAX_ANGLED_JUMP : MAX_WALK_JUMP;
        int r = (int) Math.floor(maxDist);
        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                if (ox == 0 || oz == 0 || Math.abs(ox) == 1 && Math.abs(oz) == 1) {
                    continue; // straight jumps and diagonal steps are covered elsewhere
                }
                double dist = Math.sqrt(ox * ox + oz * oz);
                if (dist > maxDist
                        || !(ox > 0 && gap[2] || ox < 0 && gap[3] || oz > 0 && gap[1] || oz < 0 && gap[0])) {
                    continue;
                }
                BlockPos c = from.offset(ox, 0, oz);
                BlockPos landing = null;
                if (Walkability.standable(world, c)) {
                    double rise = Walkability.standY(world, c) - fromY;
                    if (rise <= STEP_HEIGHT && rise >= -0.6) {
                        landing = c;
                    }
                } else if (clear(world, c) && Walkability.standable(world, c.below())
                        && fromY - Walkability.standY(world, c.below()) <= 1.6) {
                    landing = c.below();
                }
                if (landing == null) {
                    continue;
                }
                double risk = angledRisk(world, from, ox, oz);
                if (risk != Double.POSITIVE_INFINITY) {
                    out.add(new Move(landing, dist + PARKOUR_COST + risk, Action.PARKOUR));
                }
            }
        }
    }

    /**
     * Checks the player fits along the straight jump from the middle of {@code from} to the cell {@code ox, oz} away:
     * every cell its body (0.6 wide) sweeps on the way is free at feet, head and jump height. Returns the extra cost of
     * missing (see {@link #gapRisk}), or infinity when something is in the way or there's lava under it.
     */
    private static double angledRisk(ClientLevel world, BlockPos from, int ox, int oz) {
        double len = Math.sqrt(ox * ox + oz * oz);
        double ux = ox / len;
        double uz = oz / len;
        double risk = 0.0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (double t = 0.25; t <= len - 0.25; t += 0.25) {
            for (double side = -0.3; side <= 0.31; side += 0.3) {
                double x = from.getX() + 0.5 + ux * t - uz * side;
                double z = from.getZ() + 0.5 + uz * t + ux * side;
                BlockPos col = BlockPos.containing(x, from.getY(), z);
                boolean ends = col.getX() == from.getX() && col.getZ() == from.getZ()
                        || col.getX() == from.getX() + ox && col.getZ() == from.getZ() + oz;
                if (ends || !seen.add(col.asLong())) {
                    continue;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    if (!Walkability.passable(world, col.above(dy))) {
                        return Double.POSITIVE_INFINITY;
                    }
                }
                if (Walkability.passable(world, col.below())) {
                    risk = Math.max(risk, gapRisk(world, col.below()));
                    if (risk == Double.POSITIVE_INFINITY) {
                        return risk;
                    }
                }
            }
        }
        return risk;
    }

    /** Extra cost of missing a jump into the gap whose top cell is {@code below}: a long fall costs more, lava rules it out. */
    private static double gapRisk(ClientLevel world, BlockPos below) {
        BlockPos p = below;
        for (int k = 1; k <= MAX_WATER_DROP; k++, p = p.below()) {
            if (world.getBlockState(p).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return Double.POSITIVE_INFINITY;
            }
            if (isWater(world, p)) {
                return 0.0;
            }
            if (!Walkability.passable(world, p)) {
                return k - 1 <= MAX_DROP ? 0.0 : DEEP_GAP_COST;
            }
        }
        return Double.POSITIVE_INFINITY; // the void, or a drop nobody survives
    }

    /** Crossing over the cell {@code side} falls at most one block. */
    private static boolean droppable(ClientLevel world, BlockPos side) {
        return !Walkability.passable(world, side.below()) || Walkability.standable(world, side.below());
    }

    private static boolean isWater(ClientLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    private static boolean clear(ClientLevel world, BlockPos feet) {
        return Walkability.passable(world, feet) && Walkability.passable(world, feet.above());
    }

    private static double h(BlockPos p, Vec3 toward) {
        double dx = p.getX() + 0.5 - toward.x;
        double dy = p.getY() - toward.y;
        double dz = p.getZ() + 0.5 - toward.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

}
