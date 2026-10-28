package com.testrts.rtsclient.task;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.exec.Creative;
import com.testrts.rtsclient.exec.Inventories;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * FramedBlocks: after a build, walks to each framed block and right-clicks it with its camo block, the way a
 * player textures a frame. Each one is checked afterwards (the frame's block entity says what camo it has),
 * so frames that already have the right camo are left alone and a click that didn't take is tried again.
 */
public final class CamoTask implements RtsTask {
    /** Ticks to turn toward the frame, click, and hear back from the server. */
    private static final int SETTLE_TICKS = 12;
    private static final int MAX_TRIES = 3;

    private final String label;
    /** Wanted camo per framed block. */
    private final Map<BlockPos, BlockState> camo;
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    @Nullable
    private BlockPos target;
    @Nullable
    private WalkPhase walk;
    private int wait;
    private boolean fetched;
    private int applied;

    /** Camo of every queued camo pass, so the builder can put it on each frame right after placing it. */
    private static final Map<BlockPos, BlockState> WANTED = new java.util.concurrent.ConcurrentHashMap<>();

    public CamoTask(String label, Map<BlockPos, BlockState> camo) {
        this.label = label;
        this.camo = new LinkedHashMap<>(camo);
        WANTED.putAll(camo);
    }

    /** The camo a queued pass wants on the frame at {@code p}, or null. */
    @Nullable
    public static BlockState wantedAt(BlockPos p) {
        return WANTED.get(p);
    }

    private void forget() {
        this.camo.keySet().forEach(WANTED::remove);
    }

    @Override
    public void cancel(Minecraft client) {
        forget();
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        return this.target == null ? "looking for frames" : "camo on " + this.target.toShortString();
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        ClientLevel world = client.level;
        if (client.player == null || world == null) {
            return Status.FAILED;
        }
        if (this.wait > 0) {
            this.wait--;
            return Status.RUNNING;
        }
        if (this.target != null && (hasCamo(world, this.target, this.camo.get(this.target)) || this.skipped.contains(this.target))) {
            if (!this.skipped.contains(this.target)) {
                this.applied++;
            }
            this.target = null;
            this.walk = null;
        }
        if (this.target == null) {
            this.target = next(client);
            if (this.target == null) {
                forget();
                if (!this.skipped.isEmpty()) {
                    Messages.warn(this.label + ": " + this.skipped.size() + " frame(s) left without camo");
                } else if (this.applied > 0) {
                    Messages.info(this.label + ": camo on " + this.applied + " frame(s)");
                }
                return Status.DONE;
            }
            this.walk = new WalkPhase(this.target);
        }
        Item item = this.camo.get(this.target).getBlock().asItem();
        if (!Creative.supplies(client) && Inventories.count(client, item) <= 0) {
            if (!this.fetched) {
                // Once: fetch every camo block still needed from storage, then carry on.
                this.fetched = true;
                Map<Item, Integer> need = stillNeeded(client);
                if (!need.isEmpty()) {
                    queue.pushFront(new FetchTask(need));
                    return Status.RUNNING;
                }
            }
            skip("no " + item.getDescription().getString() + " for its camo");
            return Status.RUNNING;
        }
        WalkPhase.Result r = this.walk.tick(client);
        if (r == WalkPhase.Result.UNREACHABLE) {
            skip("can't get to it");
            return Status.RUNNING;
        }
        if (r == WalkPhase.Result.WALKING) {
            return Status.RUNNING;
        }
        if (this.tries.merge(this.target, 1, Integer::sum) > MAX_TRIES) {
            skip("the camo didn't take");
            return Status.RUNNING;
        }
        boolean holding = Creative.supplies(client) ? Creative.spawnInHand(client, item) : Inventories.hold(client, item);
        if (!holding) {
            skip("couldn't take " + item.getDescription().getString() + " in hand");
            return Status.RUNNING;
        }
        // A plain right-click (not sneaking) with the block puts it on the frame as its camo.
        Reach.interact(client, this.target, null);
        this.wait = SETTLE_TICKS;
        return Status.RUNNING;
    }

    private void skip(String why) {
        RtsClientMod.LOGGER.debug("Camo at {} skipped: {}", this.target, why);
        this.skipped.add(this.target);
        this.target = null;
        this.walk = null;
    }

    /** Nearest frame that still needs its camo. Spots that aren't a frame (not built, or broken) are skipped. */
    @Nullable
    private BlockPos next(Minecraft client) {
        ClientLevel world = client.level;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> e : this.camo.entrySet()) {
            BlockPos p = e.getKey();
            if (this.skipped.contains(p) || !world.hasChunkAt(p)) {
                continue;
            }
            if (!isFrame(world.getBlockEntity(p))) {
                this.skipped.add(p);
                continue;
            }
            if (hasCamo(world, p, e.getValue())) {
                continue;
            }
            double d = client.player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private Map<Item, Integer> stillNeeded(Minecraft client) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : this.camo.entrySet()) {
            if (!this.skipped.contains(e.getKey()) && !hasCamo(client.level, e.getKey(), e.getValue())) {
                need.merge(e.getValue().getBlock().asItem(), 1, Integer::sum);
            }
        }
        need.replaceAll((item, n) -> Math.max(0, n - Inventories.count(client, item)));
        need.values().removeIf(n -> n <= 0);
        return need;
    }

    // ------------------------------------------------------------------ FramedBlocks, by reflection

    private static boolean tried;
    @Nullable
    private static Class<?> frameClass;
    @Nullable
    private static Method getCamo;
    @Nullable
    private static Method getContent;

    private static boolean loaded() {
        if (!tried) {
            tried = true;
            try {
                frameClass = Class.forName("xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity");
                getCamo = frameClass.getMethod("getCamo");
                getContent = Class.forName("xfacthd.framedblocks.api.camo.CamoContainer").getMethod("getContent");
            } catch (ReflectiveOperationException | LinkageError e) {
                frameClass = null;
            }
        }
        return frameClass != null;
    }

    /** A framed block (its block entity), which takes camo. */
    public static boolean isFrame(@Nullable BlockEntity be) {
        return be != null && loaded() && frameClass.isInstance(be);
    }

    /** The frame at {@code p} has {@code wanted} as its camo (same block; the camo may be turned). */
    public static boolean hasCamo(ClientLevel world, BlockPos p, BlockState wanted) {
        BlockEntity be = world.getBlockEntity(p);
        if (!isFrame(be)) {
            return false;
        }
        try {
            Object content = getContent.invoke(getCamo.invoke(be));
            Method getState = content.getClass().getMethod("getState");
            Object state = getState.invoke(content);
            return state instanceof BlockState s && s.getBlock() == wanted.getBlock();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // empty camo has no getState
        }
    }
}
