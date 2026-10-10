package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.baritone.BaritoneBridge;
import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

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
            return BaritoneBridge.goNextTo(pos);
        }
        return NativeEngine.get().goNextTo(Minecraft.getInstance(), pos);
    }

    public static boolean goTo(BlockPos feet) {
        if (baritone()) {
            fallback = () -> NativeEngine.get().goTo(Minecraft.getInstance(), feet);
            idleTicks = 0;
            return BaritoneBridge.goTo(feet);
        }
        return NativeEngine.get().goTo(Minecraft.getInstance(), feet);
    }

    public static void build(String name, Map<BlockPos, BlockState> blocks, boolean strict, boolean manageSettings) {
        boolean layered = RtsConfig.get().buildInLayers;
        boolean clearing = !blocks.isEmpty() && blocks.values().iterator().next().isAir();
        if (baritone()) {
            if (manageSettings) {
                BaritoneBridge.applySettings(strict, layered, clearing);
            }
            fallback = () -> NativeEngine.get().build(blocks, strict, layered);
            idleTicks = 0;
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
        fallback = null;
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
