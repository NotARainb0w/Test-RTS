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
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

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
 *   <li>auto-place key held: every block of queued builds within range, lowest first;</li>
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
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    /** Ticks since our last click, and where it was. */
    private static int sinceClick = 100;
    private static BlockPos lastClicked;

    private AutoPlace() {
    }

    /**
     * @param all    the auto-place key is held (everything in range)
     * @param aimed  the aimed-place key is held (the block the crosshair is on)
     */
    public static void tick(MinecraftClient client, boolean all, boolean aimed) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            return;
        }
        boolean held = all || aimed;
        sinceClick++;
        if (client.currentScreen != null && sinceClick <= 5 && player.currentScreenHandler != player.playerScreenHandler) {
            // Our click opened a screen after all (a block that ignores sneaking): close it, skip that spot.
            player.closeHandledScreen();
            if (lastClicked != null) {
                refused.add(lastClicked);
            }
            return;
        }
        if (!held || RtsController.get().isActive() || client.currentScreen != null || player.isSpectator()) {
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
            restoreSlot = player.getInventory().selectedSlot;
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
                cooldown = delayTicks(safe);
                return;
            }
            if (placed == Placed.WRONG_WAY) {
                wrongWay++;
            }
        }
        if (!missing.isEmpty()) {
            warn("Nothing to place: no " + missing.iterator().next().getName().getString() + " in your inventory");
        } else if (wrongWay > 0) {
            warn(wrongWay + " block(s) would face the wrong way from here - place them with the aimed key (look the right way) or the builder");
        }
    }

    /** Ticks between placements: from the speed setting, never faster than right-click when server-safe. */
    private static int delayTicks(boolean safe) {
        double perSecond = Math.max(0.5, RtsConfig.get().autoPlaceSpeed);
        int ticks = Math.max(1, (int) Math.round(20.0 / perSecond));
        return safe ? Math.max(VANILLA_DELAY_TICKS, ticks) : ticks;
    }

    /** Key let go: back to the slot that was selected before. */
    private static void release(ClientPlayerEntity player) {
        if (restoreSlot >= 0) {
            player.getInventory().selectedSlot = restoreSlot;
            restoreSlot = -1;
        }
        refused.clear();
        attempts.clear();
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
    private static BlockState pending(MinecraftClient client, List<BuildTask> builds, BlockPos p) {
        if (refused.contains(p)) {
            return null;
        }
        BlockState wanted = null;
        for (BuildTask b : builds) {
            wanted = b.blocks().get(p);
            if (wanted != null) {
                break;
            }
        }
        if (wanted == null || wanted.isAir()) {
            return null;
        }
        ClientWorld world = client.world;
        BlockState now = world.getBlockState(p);
        // Only empty spots: never break anything to make room.
        if (MaterialPlanner.matches(now, wanted) || !(now.isAir() || now.isReplaceable())) {
            return null;
        }
        if (client.player.getBoundingBox().intersects(new Box(p)) || !world.getOtherEntities(null, new Box(p),
                e -> e.isAlive() && e.isCollidable()).isEmpty()) {
            return null;
        }
        return wanted;
    }

    /** Blocks of queued builds within range that still need placing: lowest first, then nearest. */
    private static List<Candidate> inRange(MinecraftClient client, List<BuildTask> builds, boolean safe) {
        List<Candidate> out = new ArrayList<>();
        double reach = Reach.reach(client);
        double range = RtsConfig.get().autoPlaceRange;
        if (range > 0) {
            reach = Math.min(reach, range);
        }
        int r = MathHelper.ceil(reach);
        Vec3d eye = client.player.getEyePos();
        BlockPos center = BlockPos.ofFloored(eye);
        boolean airPlace = !safe && RtsConfig.get().airPlace;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = center.add(dx, dy, dz);
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d > reach * reach) {
                        continue;
                    }
                    BlockState wanted = pending(client, builds, p);
                    if (wanted == null) {
                        continue;
                    }
                    // Something to place it against (server-safe: on a side we can see), or mid-air placing.
                    boolean placeable = safe ? !visibleSupports(client, p).isEmpty() : NativeBuilder.support(client.world, p) != null || airPlace;
                    if (placeable) {
                        out.add(new Candidate(p.toImmutable(), wanted, d));
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
    private static List<BlockHitResult> visibleSupports(MinecraftClient client, BlockPos pos) {
        List<BlockHitResult> out = new ArrayList<>();
        Vec3d eye = client.player.getEyePos();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (!NativeBuilder.clickable(client.world, n)) {
                continue;
            }
            Direction face = d.getOpposite(); // the neighbour's face toward pos
            Vec3d faceCenter = Vec3d.ofCenter(pos).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
            Vec3d toEye = eye.subtract(faceCenter);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.05) {
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
    private static void aimedPlace(MinecraftClient client, List<BuildTask> builds, boolean safe) {
        ClientPlayerEntity player = client.player;
        if (safe) {
            if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                return;
            }
            BlockPos clicked = hit.getBlockPos();
            BlockPos target = client.world.getBlockState(clicked).isReplaceable() ? clicked : clicked.offset(hit.getSide());
            BlockState wanted = pending(client, builds, target);
            if (wanted == null) {
                return;
            }
            Item item = wanted.getBlock().asItem();
            if (item == Items.AIR) {
                return;
            }
            if (!Inventories.hold(client, item)) {
                warn("Nothing to place: no " + item.getName().getString() + " in your inventory");
                return;
            }
            if (attempts.merge(target, 1, Integer::sum) > MAX_ATTEMPTS) {
                refused.add(target.toImmutable());
                return;
            }
            sinceClick = 0;
            lastClicked = target.toImmutable();
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
            warn("Nothing to place: no " + item.getName().getString() + " in your inventory");
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
    private static Candidate aimedAt(MinecraftClient client, List<BuildTask> builds) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        double reach = Reach.reach(client);
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(reach));
        Optional<BlockPos> hit = BlockView.raycast(eye, end, world, (w, p) -> {
            if (pending(client, builds, p) != null) {
                return Optional.of(p.toImmutable());
            }
            BlockState s = w.getBlockState(p);
            // A real block in the way: stop looking (an empty result ends the search).
            return s.getOutlineShape(w, p).isEmpty() ? null : Optional.<BlockPos>empty();
        }, w -> Optional.empty());
        if (hit.isEmpty()) {
            return null;
        }
        BlockPos p = hit.get();
        boolean placeable = NativeBuilder.support(world, p) != null || RtsConfig.get().airPlace;
        return placeable ? new Candidate(p, pending(client, builds, p), eye.squaredDistanceTo(Vec3d.ofCenter(p))) : null;
    }

    /**
     * Click to place {@code wanted} at {@code pos} without turning the view.
     * <p>
     * Server-safe: only on a face we can see, and only if the block comes out pointing the right way from
     * where we really look. Otherwise a block that needs a facing gets it by telling the server we look
     * that way for this one click, then the real direction right after (the view never moves), and
     * mid-air placing is allowed.
     */
    private static Placed place(MinecraftClient client, BlockPos pos, BlockState wanted, boolean safe) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
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
        Direction toSupport = NativeBuilder.support(world, pos);
        BlockHitResult hit;
        if (toSupport != null) {
            Vec3d v = Vec3d.ofCenter(pos).add(toSupport.getOffsetX() * 0.5, toSupport.getOffsetY() * 0.5, toSupport.getOffsetZ() * 0.5);
            hit = new BlockHitResult(v, toSupport.getOpposite(), pos.offset(toSupport), false);
        } else {
            // Mid-air: click the empty cell itself, on the face toward us.
            Vec3d eye = player.getEyePos();
            Direction f = Direction.getFacing(eye.x - (pos.getX() + 0.5), eye.y - (pos.getY() + 0.5), eye.z - (pos.getZ() + 0.5));
            hit = new BlockHitResult(Vec3d.ofCenter(pos).add(f.getOffsetX() * 0.5, f.getOffsetY() * 0.5, f.getOffsetZ() * 0.5), f, pos, false);
        }
        float[] rotation = NativeBuilder.rotationFor(player, wanted, hit);
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        boolean turn = rotation != null && (Math.abs(MathHelper.wrapDegrees(rotation[0] - yaw)) > 1.0F || Math.abs(rotation[1] - pitch) > 1.0F);
        if (turn) {
            // The server decides the facing from the rotation it last heard of; the client predicts it
            // from the player's, so set both for the click only.
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(rotation[0], rotation[1], player.isOnGround()));
            player.setYaw(rotation[0]);
            player.setPitch(rotation[1]);
        }
        try {
            click(client, pos, hit);
        } finally {
            if (turn) {
                player.setYaw(yaw);
                player.setPitch(pitch);
                player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround()));
            }
        }
        return Placed.DONE;
    }

    private static void click(MinecraftClient client, BlockPos pos, BlockHitResult hit) {
        attempts.merge(pos, 1, Integer::sum);
        sinceClick = 0;
        lastClicked = pos;
        Clicks.place(client, client.player, hit);
    }

    /** Orders whose every block is in place come off the queue (and count as finished builds). */
    private static void removeFinished(MinecraftClient client) {
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing() && MaterialPlanner.remaining(client.world, build.blocks()) == 0) {
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
