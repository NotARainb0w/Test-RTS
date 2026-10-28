package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.CompletedBuilds;
import com.testrts.rtsclient.task.MaterialPlanner;
import com.testrts.rtsclient.task.Reach;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Placing the hologram's blocks by hand, outside RTS mode, without turning the view:
 * <ul>
 *   <li>auto-place key held: every block of queued builds within range, lowest first (with "Hold-Z in
 *   layers" on, only blocks of each build's lowest unfinished layer, going up once it's finished);</li>
 *   <li>aimed-place key held: the hologram block the crosshair is on.</li>
 * </ul>
 * The hand switches to the block needed and back to the old slot when the key is let go. Only while
 * the queue isn't running, so it never fights the builder.
 * <p>
 * Server-safe placing (the default) only does what a player could do by hand, so anti-cheat plugins
 * have nothing to object to: no faked look direction, no mid-air placing, only faces that point toward
 * the player, at most the pace of holding right-click, and the aimed key is a plain right-click on
 * whatever the crosshair is on. Blocks that would come out pointing the wrong way from where the player
 * looks are left for the aimed key or the builder.
 */
public final class AutoPlace {
    /** Holding right-click places a block every 4 ticks; server-safe placing never goes faster. */
    private static final int VANILLA_DELAY_TICKS = 4;
    private static final long WARN_INTERVAL_MS = 3000;
    private static final int MAX_ATTEMPTS = 3;

    private static int cooldown;
    private static int restoreSlot = -1;
    private static int sinceCheck;
    private static long lastWarn;
    /** Spots that didn't take a block (protected, entity in the way...), skipped while the key stays down. */
    private static final Set<BlockPos> refused = new HashSet<>();
    /** Spots of layers passed over with the Skip layer key: hold-Z in layers (and the hologram) moves past them. */
    private static final Set<BlockPos> skippedLayers = new HashSet<>();
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    /** Hold-Z in layers: per queued build, the lowest Y with a spot still to fill (MAX_VALUE: none). */
    private static final Map<BuildTask, Integer> lowestLayer = new HashMap<>();
    private static int layersIn;
    /** The layer that held placing back on the last look around (Integer.MIN_VALUE: none), for the hint. */
    private static int heldAtLayer = Integer.MIN_VALUE;
    /** Ticks since our last click, and where it was. */
    private static int sinceClick = 100;
    private static BlockPos lastClicked;

    private AutoPlace() {
    }

    /**
     * @param all    the auto-place key is held (everything in range)
     * @param aimed  the aimed-place key is held (the block the crosshair is on)
     */
    public static void tick(Minecraft client, boolean all, boolean aimed) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null || client.gameMode == null) {
            return;
        }
        boolean held = all || aimed;
        sinceClick++;
        if (client.screen != null && sinceClick <= 5 && player.containerMenu != player.inventoryMenu) {
            // Our click opened a screen after all (a block that ignores sneaking): close it, skip that spot.
            player.closeContainer();
            if (lastClicked != null) {
                refused.add(lastClicked);
            }
            return;
        }
        if (!held || RtsController.get().isActive() || client.screen != null || player.isSpectator()) {
            if (!held) {
                release(player);
            }
            return;
        }
        if (TaskQueue.get().isStarted()) {
            warn("Pause the queue to place blocks by hand");
            return;
        }
        if (restoreSlot < 0) {
            restoreSlot = player.getInventory().selected;
        }
        if (++sinceCheck >= 20) {
            sinceCheck = 0;
            removeFinished(client);
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        List<BuildTask> builds = builds();
        if (builds.isEmpty()) {
            return;
        }
        boolean safe = RtsConfig.get().serverSafePlacing;
        if (aimed && !all) {
            aimedPlace(client, builds, safe);
            return;
        }
        Set<Item> missing = new HashSet<>();
        int wrongWay = 0;
        int budget = perTick(safe);
        int done = 0;
        for (Candidate c : inRange(client, builds, safe)) {
            Item item = c.wanted.getBlock().asItem();
            if (item == Items.AIR || missing.contains(item)) {
                continue;
            }
            if (!Inventories.hold(client, item)) {
                missing.add(item);
                continue;
            }
            Placed placed = place(client, c.pos, c.wanted, safe);
            if (placed == Placed.DONE) {
                // Above 20 a second, several blocks go down in the same tick.
                if (++done >= budget || client.screen != null) {
                    cooldown = delayTicks(safe);
                    return;
                }
                continue;
            }
            if (placed == Placed.WRONG_WAY) {
                wrongWay++;
            }
        }
        if (done > 0) {
            cooldown = delayTicks(safe);
            return;
        }
        if (!missing.isEmpty()) {
            warn("Nothing to place: no " + missing.iterator().next().getDescription().getString() + " in your inventory");
        } else if (wrongWay > 0) {
            warn(wrongWay + " block(s) would face the wrong way from here - place them with the aimed key (look the right way) or the builder");
        } else if (heldAtLayer != Integer.MIN_VALUE) {
            warn("Building in layers: the layer at y=" + heldAtLayer + " comes first - get where you can place it");
        }
    }

    /** Ticks between placements: from the speed setting, never faster than right-click when server-safe. */
    private static int delayTicks(boolean safe) {
        double perSecond = Math.max(0.5, RtsConfig.get().autoPlaceSpeed);
        int ticks = Math.max(1, (int) Math.round(20.0 / perSecond));
        return safe ? Math.max(VANILLA_DELAY_TICKS, ticks) : ticks;
    }

    /** Blocks placed per tick: more than one only above 20 a second, and never when server-safe. */
    private static int perTick(boolean safe) {
        return safe ? 1 : Math.max(1, (int) Math.round(RtsConfig.get().autoPlaceSpeed / 20.0));
    }

    /** Key let go: back to the slot that was selected before. */
    private static void release(LocalPlayer player) {
        if (restoreSlot >= 0) {
            player.getInventory().selected = restoreSlot;
            restoreSlot = -1;
        }
        refused.clear();
        attempts.clear();
        lowestLayer.clear();
        layersIn = 0;
        cooldown = 0;
    }

    private record Candidate(BlockPos pos, BlockState wanted, double distance) {
    }

    private enum Placed { DONE, WRONG_WAY, NO }

    private static List<BuildTask> builds() {
        List<BuildTask> builds = new ArrayList<>();
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing() && !TaskQueue.get().isPaused(task)) {
                builds.add(build);
            }
        }
        return builds;
    }

    /** What a queued build wants at {@code p}, if that spot is empty and still needs it. */
    private static BlockState pending(Minecraft client, List<BuildTask> builds, BlockPos p) {
        return pending(client, builds, p, Map.of());
    }

    /** Same, but only in the build's lowest unfinished layer when {@code layers} has it (see {@link #layers}). */
    private static BlockState pending(Minecraft client, List<BuildTask> builds, BlockPos p, Map<BuildTask, Integer> layers) {
        if (refused.contains(p)) {
            return null;
        }
        BlockState wanted = null;
        BuildTask owner = null;
        for (BuildTask b : builds) {
            wanted = b.blocks().get(p);
            if (wanted != null) {
                owner = b;
                break;
            }
        }
        if (wanted == null || wanted.isAir()) {
            return null;
        }
        ClientLevel world = client.level;
        BlockState now = world.getBlockState(p);
        // Only empty spots: never break anything to make room.
        if (MaterialPlanner.matches(now, wanted) || !(now.isAir() || now.canBeReplaced())) {
            return null;
        }
        if (client.player.getBoundingBox().intersects(new AABB(p)) || !world.getEntities((net.minecraft.world.entity.Entity) null, new AABB(p),
                e -> e.isAlive() && e.canBeCollidedWith()).isEmpty()) {
            return null;
        }
        Integer layer = layers.get(owner);
        if (layer != null && p.getY() > layer) {
            heldAtLayer = layer;
            return null;
        }
        return wanted;
    }

    /**
     * Hold-Z in layers on: per build, the lowest Y that still has an empty spot to fill, so the key works
     * bottom-up like the builder. Empty when the setting is off. Looked up again every few ticks.
     */
    private static Map<BuildTask, Integer> layers(Minecraft client, List<BuildTask> builds) {
        if (!RtsConfig.get().autoPlaceLayers) {
            lowestLayer.clear();
            return lowestLayer;
        }
        if (--layersIn > 0 && lowestLayer.keySet().containsAll(builds)) {
            return lowestLayer;
        }
        layersIn = 5;
        lowestLayer.clear();
        ClientLevel world = client.level;
        for (BuildTask b : builds) {
            lowestLayer.put(b, lowestUnfinished(world, b));
        }
        return lowestLayer;
    }

    /**
     * The lowest Y where {@code build} still has an empty spot to fill: the layer hold-Z works on (and the
     * hologram shows outside RTS mode). Integer.MAX_VALUE when there's none.
     */
    public static int lowestUnfinished(ClientLevel world, BuildTask build) {
        int lowest = Integer.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> e : build.blocks().entrySet()) {
            BlockPos p = e.getKey();
            // Out of view (unloaded) counts as not done: the layer must really be finished. Spots that
            // refused a block are let go until the key is pressed again, or one bad spot would block for good.
            if (p.getY() >= lowest || e.getValue().isAir() || refused.contains(p) || skippedLayers.contains(p)) {
                continue;
            }
            BlockState now = world.getBlockState(p);
            if (!MaterialPlanner.matches(now, e.getValue()) && (now.isAir() || now.canBeReplaced())) {
                lowest = p.getY();
            }
        }
        return lowest;
    }

    /**
     * The Skip layer key with no build running: hold-Z in layers leaves what's left of each queued build's
     * current layer and goes on with the one above. @return the layers skipped (empty: nothing to skip)
     */
    public static java.util.SortedSet<Integer> skipLayer(Minecraft client) {
        java.util.SortedSet<Integer> ys = new java.util.TreeSet<>();
        if (client.level == null || !RtsConfig.get().autoPlaceLayers) {
            return ys;
        }
        Set<BlockPos> queued = new HashSet<>();
        for (RtsTask t : TaskQueue.get().snapshot()) {
            if (t instanceof BuildTask b && !b.clearing()) {
                queued.addAll(b.blocks().keySet());
                int y = lowestUnfinished(client.level, b);
                if (y == Integer.MAX_VALUE) {
                    continue;
                }
                for (BlockPos p : b.blocks().keySet()) {
                    if (p.getY() == y) {
                        skippedLayers.add(p.immutable());
                    }
                }
                ys.add(y);
            }
        }
        // Forget spots of builds that are gone.
        skippedLayers.retainAll(queued);
        layersIn = 0;
        return ys;
    }

    /** Blocks of queued builds within range that still need placing: lowest first, then nearest. */
    private static List<Candidate> inRange(Minecraft client, List<BuildTask> builds, boolean safe) {
        List<Candidate> out = new ArrayList<>();
        Map<BuildTask, Integer> layers = layers(client, builds);
        heldAtLayer = Integer.MIN_VALUE;
        double reach = Reach.reach(client);
        double range = RtsConfig.get().autoPlaceRange;
        if (range > 0) {
            reach = Math.min(reach, range);
        }
        int r = Mth.ceil(reach);
        Vec3 eye = client.player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        boolean airPlace = !safe && RtsConfig.get().airPlace;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    double d = eye.distanceToSqr(Vec3.atCenterOf(p));
                    if (d > reach * reach) {
                        continue;
                    }
                    BlockState wanted = pending(client, builds, p, layers);
                    if (wanted == null) {
                        continue;
                    }
                    // Something to place it against (server-safe: on a side we can see), or mid-air placing.
                    boolean placeable = safe ? !visibleSupports(client, p).isEmpty() : NativeBuilder.support(client.level, p) != null || airPlace;
                    if (placeable) {
                        out.add(new Candidate(p.immutable(), wanted, d));
                    }
                }
            }
        }
        out.sort((a, b) -> a.pos.getY() != b.pos.getY() ? Integer.compare(a.pos.getY(), b.pos.getY())
                : Double.compare(a.distance, b.distance));
        return out;
    }

    /**
     * Clicks that would put a block into {@code pos} on a face we can see: a solid neighbour's face that
     * points toward the eyes (a player can't click the far side of a block). Several points per face,
     * since where the click lands decides slab and stair halves.
     */
    private static List<BlockHitResult> visibleSupports(Minecraft client, BlockPos pos) {
        List<BlockHitResult> out = new ArrayList<>();
        Vec3 eye = client.player.getEyePosition();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!NativeBuilder.clickable(client.level, n)) {
                continue;
            }
            Direction face = d.getOpposite(); // the neighbour's face toward pos
            Vec3 faceCenter = Vec3.atCenterOf(pos).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            Vec3 toEye = eye.subtract(faceCenter);
            if (toEye.x * face.getStepX() + toEye.y * face.getStepY() + toEye.z * face.getStepZ() <= 0.05) {
                continue;
            }
            out.add(new BlockHitResult(faceCenter, face, n, false));
            if (face.getAxis().isHorizontal()) {
                out.add(new BlockHitResult(faceCenter.add(0, 0.25, 0), face, n, false));
                out.add(new BlockHitResult(faceCenter.add(0, -0.25, 0), face, n, false));
            }
        }
        return out;
    }

    /**
     * Aimed key. Server-safe: a plain right-click on what the crosshair is on, when the cell it would
     * place into is a ghost block. Otherwise: the first ghost block along the view (through other ghost
     * cells, mid-air allowed), placed like the auto-place key does.
     */
    private static void aimedPlace(Minecraft client, List<BuildTask> builds, boolean safe) {
        LocalPlayer player = client.player;
        if (safe) {
            if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                return;
            }
            BlockPos clicked = hit.getBlockPos();
            BlockPos target = client.level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            BlockState wanted = pending(client, builds, target);
            if (wanted == null) {
                return;
            }
            Item item = wanted.getBlock().asItem();
            if (item == Items.AIR) {
                return;
            }
            if (!Inventories.hold(client, item)) {
                warn("Nothing to place: no " + item.getDescription().getString() + " in your inventory");
                return;
            }
            if (attempts.merge(target, 1, Integer::sum) > MAX_ATTEMPTS) {
                refused.add(target.immutable());
                return;
            }
            sinceClick = 0;
            lastClicked = target.immutable();
            Clicks.place(client, player, hit);
            cooldown = VANILLA_DELAY_TICKS;
            return;
        }
        Candidate c = aimedAt(client, builds);
        if (c == null) {
            return;
        }
        Item item = c.wanted.getBlock().asItem();
        if (item == Items.AIR) {
            return;
        }
        if (!Inventories.hold(client, item)) {
            warn("Nothing to place: no " + item.getDescription().getString() + " in your inventory");
            return;
        }
        if (place(client, c.pos, c.wanted, false) == Placed.DONE) {
            cooldown = delayTicks(false);
        }
    }

    /**
     * The hologram block the crosshair is on: the first spot along the view, within reach, that still
     * needs a block. Real blocks stop the look (as for a normal click), the empty ghost cells don't.
     */
    private static Candidate aimedAt(Minecraft client, List<BuildTask> builds) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        double reach = Reach.reach(client);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        Optional<BlockPos> hit = BlockGetter.traverseBlocks(eye, end, world, (w, p) -> {
            if (pending(client, builds, p) != null) {
                return Optional.of(p.immutable());
            }
            BlockState s = w.getBlockState(p);
            // A real block in the way: stop looking (an empty result ends the search).
            return s.getShape(w, p).isEmpty() ? null : Optional.<BlockPos>empty();
        }, w -> Optional.empty());
        if (hit.isEmpty()) {
            return null;
        }
        BlockPos p = hit.get();
        boolean placeable = NativeBuilder.support(world, p) != null || RtsConfig.get().airPlace;
        return placeable ? new Candidate(p, pending(client, builds, p), eye.distanceToSqr(Vec3.atCenterOf(p))) : null;
    }

    /**
     * Click to place {@code wanted} at {@code pos} without turning the view.
     * <p>
     * Server-safe: only on a face we can see, and only if the block comes out pointing the right way from
     * where we really look. Otherwise a block that needs a facing gets it by telling the server we look
     * that way for this one click, then the real direction right after (the view never moves), and
     * mid-air placing is allowed.
     */
    private static Placed place(Minecraft client, BlockPos pos, BlockState wanted, boolean safe) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (attempts.getOrDefault(pos, 0) >= MAX_ATTEMPTS) {
            refused.add(pos);
            return Placed.NO;
        }
        if (safe) {
            List<BlockHitResult> faces = visibleSupports(client, pos);
            for (BlockHitResult hit : faces) {
                if (NativeBuilder.comesOutAs(player, wanted, hit)) {
                    click(client, pos, hit);
                    return Placed.DONE;
                }
            }
            return faces.isEmpty() ? Placed.NO : Placed.WRONG_WAY;
        }
        // Every way of clicking into the spot: against each solid neighbour (low and high on side faces, which
        // decides a stair's or slab's half), then mid-air. The first that puts the block down pointing the way
        // the order wants is used; if none does, it's left alone rather than placed the wrong way round.
        List<BlockHitResult> hits = placementHits(world, pos, RtsConfig.get().airPlace);
        BlockHitResult hit = null;
        float[] rotation = null;
        for (BlockHitResult h : hits) {
            float[] r = NativeBuilder.rotationFor(player, wanted, h);
            if (r != null) {
                hit = h;
                rotation = r;
                break;
            }
        }
        if (hit == null) {
            if (hits.isEmpty() || NativeBuilder.oriented(wanted)) {
                return hits.isEmpty() ? Placed.NO : Placed.WRONG_WAY;
            }
            hit = hits.get(0); // no facing to get right (and the block won't say what it would become)
        }
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        boolean turn = rotation != null && (Math.abs(Mth.wrapDegrees(rotation[0] - yaw)) > 1.0F || Math.abs(rotation[1] - pitch) > 1.0F);
        if (turn) {
            // The server decides the facing from the rotation it last heard of; the client predicts it
            // from the player's, so set both for the click only.
            player.connection.send(new ServerboundMovePlayerPacket.Rot(rotation[0], rotation[1], player.onGround()));
            player.setYRot(rotation[0]);
            player.setXRot(rotation[1]);
        }
        try {
            click(client, pos, hit);
        } finally {
            if (turn) {
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
            }
        }
        return Placed.DONE;
    }

    /** Clicks that would put a block into {@code pos}: on each solid neighbour (side faces also low and high), then mid-air. */
    private static List<BlockHitResult> placementHits(ClientLevel world, BlockPos pos, boolean airPlace) {
        List<BlockHitResult> out = new ArrayList<>();
        Vec3 center = Vec3.atCenterOf(pos);
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!NativeBuilder.clickable(world, n)) {
                continue;
            }
            Vec3 face = center.add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            for (double dy : d.getAxis().isHorizontal() ? new double[]{0, 0.25, -0.25} : new double[]{0}) {
                out.add(new BlockHitResult(face.add(0, dy, 0), d.getOpposite(), n, false));
            }
        }
        if (airPlace) {
            // Mid-air: click the empty cell itself.
            for (Direction f : Direction.values()) {
                Vec3 face = center.add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5);
                for (double dy : f.getAxis().isHorizontal() ? new double[]{0, 0.25, -0.25} : new double[]{0}) {
                    out.add(new BlockHitResult(face.add(0, dy, 0), f, pos, false));
                }
            }
        }
        return out;
    }

    private static void click(Minecraft client, BlockPos pos, BlockHitResult hit) {
        attempts.merge(pos, 1, Integer::sum);
        sinceClick = 0;
        lastClicked = pos;
        Clicks.place(client, client.player, hit);
    }

    /** Orders whose every block is in place come off the queue (and count as finished builds). */
    private static void removeFinished(Minecraft client) {
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing() && MaterialPlanner.remaining(client.level, build.blocks()) == 0) {
                TaskQueue.get().remove(client, build);
                CompletedBuilds.record(build.label(), build.blocks());
                Messages.success(build.label() + " finished");
            }
        }
    }

    private static void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarn >= WARN_INTERVAL_MS) {
            lastWarn = now;
            Messages.warn(message);
        }
    }
}
