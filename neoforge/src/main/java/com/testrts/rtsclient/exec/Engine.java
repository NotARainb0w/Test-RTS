package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.baritone.BaritoneBridge;
import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;

/**
 * Picks who does the walking, placing and mining: Baritone, or the built-in engine.
 * Every task goes through here, so switching engines is one setting.
 */
public final class Engine {
    public enum Kind {
        AUTO("Auto"),
        BUILTIN("Built-in"),
        BARITONE("Baritone");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public Kind next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Ticks Baritone may claim to be busy without anything happening before we take over. */
    private static final int WATCHDOG_TICKS = 200;

    /** Set when Baritone stopped responding this session; Auto then uses the built-in engine. */
    private static boolean baritoneUnresponsive;
    /** The same request, for the built-in engine, in case Baritone has to be replaced mid-order. */
    private static Runnable fallback;
    private static long lastSignature;
    private static int idleTicks;
    /** Ticks Baritone may walk without getting a block closer to the goal (it's going in circles). */
    private static final int GOAL_WATCHDOG_TICKS = 20 * 45;
    /** Where the current Baritone walk is headed (null for builds), and how close it has got. */
    private static BlockPos walkGoal;
    private static double bestGoalDistance;
    private static int noCloserTicks;

    private Engine() {
    }

    /**
     * Watches Baritone: if it says it's working but the player neither moves nor gains/loses
     * items for {@link #WATCHDOG_TICKS}, Baritone can't drive the player in this setup (a mod
     * conflict, an old version...). Switch to the built-in engine and redo the same request.
     */
    public static void tick(Minecraft client) {
        if (client.player == null || fallback == null || !BaritoneBridge.isAvailable()) {
            idleTicks = 0;
            return;
        }
        boolean busy = BaritoneBridge.isBusy() && !BaritoneBridge.isBuilderPaused();
        // Mining a hard block can legitimately take a while with nothing else changing.
        boolean mining = client.gameMode != null && client.gameMode.isDestroying();
        if (!busy || mining) {
            idleTicks = 0;
            return;
        }
        if (walkGoal != null && BaritoneBridge.isMoving() && checkGoalProgress(client)) {
            return;
        }
        long sig = client.player.blockPosition().asLong() * 31 + client.player.getInventory().items.hashCode();
        int items = 0;
        for (var stack : client.player.getInventory().items) {
            items += stack.getCount();
        }
        sig = sig * 31 + items;
        if (sig != lastSignature) {
            lastSignature = sig;
            idleTicks = 0;
            return;
        }
        if (++idleTicks >= WATCHDOG_TICKS) {
            idleTicks = 0;
            BaritoneBridge.stop();
            if (configured() == Kind.AUTO) {
                baritoneUnresponsive = true;
                com.testrts.rtsclient.util.Messages.warn("Baritone isn't moving the player - switched to the built-in engine");
                Runnable redo = fallback;
                fallback = null;
                redo.run();
            } else {
                com.testrts.rtsclient.util.Messages.warn("Baritone seems stuck - try Engine: Built-in");
            }
        }
    }

    /**
     * Baritone is moving, but never gets closer to where it's going (oscillating between two
     * paths, walking into the same wall over and over). Stop it; in Auto mode hand this one
     * walk to the built-in engine.
     *
     * @return true if it stepped in
     */
    private static boolean checkGoalProgress(Minecraft client) {
        double d = client.player.position().distanceTo(Vec3.atCenterOf(walkGoal));
        if (d < bestGoalDistance - 1.0) {
            bestGoalDistance = d;
            noCloserTicks = 0;
            return false;
        }
        if (++noCloserTicks < GOAL_WATCHDOG_TICKS) {
            return false;
        }
        BlockPos goal = walkGoal;
        walkGoal = null;
        noCloserTicks = 0;
        idleTicks = 0;
        BaritoneBridge.stop();
        Runnable redo = fallback;
        fallback = null;
        if (configured() == Kind.AUTO && redo != null) {
            com.testrts.rtsclient.util.Messages.warn("Baritone isn't getting closer to " + goal.toShortString()
                    + " - trying the built-in engine");
            redo.run();
        } else {
            com.testrts.rtsclient.util.Messages.warn("Baritone isn't getting closer to " + goal.toShortString() + " - stopped");
        }
        return true;
    }

    private static void watchWalk(BlockPos goal) {
        walkGoal = goal.immutable();
        bestGoalDistance = Double.MAX_VALUE;
        noCloserTicks = 0;
    }

    public static Kind configured() {
        try {
            return Kind.valueOf(RtsConfig.get().engine.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Kind.AUTO;
        }
    }

    public static void cycle() {
        RtsConfig.get().engine = configured().next().name().toLowerCase(Locale.ROOT);
        baritoneUnresponsive = false;
        RtsConfig.save();
        stop();
    }

    /** True when Baritone should be used for the next order. */
    public static boolean baritone() {
        Kind kind = configured();
        if (kind == Kind.BUILTIN || !BaritoneBridge.isAvailable()) {
            return false;
        }
        return kind == Kind.BARITONE || !baritoneUnresponsive;
    }

    /** Short name of the engine that is actually in use. */
    public static String activeName() {
        return baritone() ? "Baritone" : "Built-in";
    }

    public static boolean goNextTo(BlockPos pos) {
        if (baritone()) {
            fallback = () -> NativeEngine.get().goNextTo(Minecraft.getInstance(), pos);
            idleTicks = 0;
            watchWalk(pos);
            return BaritoneBridge.goNextTo(pos);
        }
        return NativeEngine.get().goNextTo(Minecraft.getInstance(), pos);
    }

    public static boolean goTo(BlockPos feet) {
        if (baritone()) {
            fallback = () -> NativeEngine.get().goTo(Minecraft.getInstance(), feet);
            idleTicks = 0;
            watchWalk(feet);
            return BaritoneBridge.goTo(feet);
        }
        return NativeEngine.get().goTo(Minecraft.getInstance(), feet);
    }

    public static void build(String name, Map<BlockPos, BlockState> blocks, boolean strict, boolean manageSettings) {
        boolean layered = RtsConfig.get().buildInLayers;
        boolean clearing = !blocks.isEmpty() && blocks.values().iterator().next().isAir();
        // Baritone can't place blocks in mid-air (domes, spheres, floating builds stall or loop), so
        // builds go to the built-in engine while that's on, whichever engine is selected.
        boolean airBuild = !clearing && RtsConfig.get().airPlace;
        if (baritone() && !airBuild) {
            if (manageSettings) {
                BaritoneBridge.applySettings(strict, layered, clearing);
            }
            fallback = () -> NativeEngine.get().build(blocks, strict, layered);
            idleTicks = 0;
            walkGoal = null;
            BaritoneBridge.build(name, blocks, strict);
        } else {
            NativeEngine.get().build(blocks, strict, layered);
        }
    }

    public static boolean isBuilding() {
        return NativeEngine.get().isBuilding() || BaritoneBridge.isBuilding();
    }

    public static boolean isBuilderPaused() {
        return NativeEngine.get().isBuilderPaused() || BaritoneBridge.isBuilderPaused();
    }

    /** Item the built-in builder is paused for, e.g. scaffolding blocks. Null for Baritone. */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.world.item.Item missingItem() {
        return NativeEngine.get().missingItem();
    }

    public static void resumeBuilder() {
        NativeEngine.get().resumeBuilder();
        BaritoneBridge.resumeBuilder();
    }

    public static boolean isMoving() {
        return NativeEngine.get().isMoving() || BaritoneBridge.isMoving();
    }

    public static boolean isBusy() {
        return NativeEngine.get().isBusy() || BaritoneBridge.isBusy();
    }

    public static void stop() {
        Look.cancel();
        fallback = null;
        walkGoal = null;
        NativeEngine.get().stop(Minecraft.getInstance());
        BaritoneBridge.stop();
    }

    public static String status() {
        if (NativeEngine.get().isBusy() || !baritone()) {
            return NativeEngine.get().status();
        }
        return BaritoneBridge.status();
    }
}
