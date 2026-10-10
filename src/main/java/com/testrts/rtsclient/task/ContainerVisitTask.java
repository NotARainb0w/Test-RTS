package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.ContainerTracker;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/**
 * Walk to a container, open it, let an {@link Action} work on its slots, then close it.
 * The contents seen while it was open are saved to the storage index.
 */
public class ContainerVisitTask implements RtsTask {
    /** Works on an open container. Return true when finished; it is called again next tick otherwise. */
    @FunctionalInterface
    public interface Action {
        boolean run(MinecraftClient client, ScreenHandler handler);
    }

    private enum Phase { WALK, OPEN, WAIT_OPEN, SETTLE, ACT, DONE }

    private static final int OPEN_TIMEOUT_TICKS = 60;
    private static final int SETTLE_TICKS = 3;
    /** Ticks we try to close some other open screen before giving up. */
    private static final int CLOSE_OTHER_TIMEOUT_TICKS = 40;
    /** Right-clicks on the container before "didn't open" (the first can miss, e.g. while still moving). */
    private static final int MAX_OPEN_TRIES = 2;
    /** An action that never says it's finished is cut off after this long. */
    private static final int ACT_TIMEOUT_TICKS = 20 * 30;

    private final BlockPos pos;
    private final String label;
    private final Action action;
    private final WalkPhase walk;
    private Phase phase = Phase.WALK;
    private int timer;
    private int openTries;
    private ScreenHandler handler;

    public ContainerVisitTask(BlockPos pos, String label, Action action) {
        this.pos = pos.toImmutable();
        this.label = label;
        this.action = action;
        this.walk = new WalkPhase(this.pos);
    }

    public BlockPos pos() {
        return this.pos;
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        return switch (this.phase) {
            case WALK -> "walking to " + this.pos.toShortString();
            case OPEN, WAIT_OPEN, SETTLE -> "opening " + this.pos.toShortString();
            case ACT -> "moving items";
            case DONE -> "done";
        };
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Status.FAILED;
        }
        switch (this.phase) {
            case WALK -> {
                if (!StorageManager.isContainer(client.world, this.pos) && client.world.isChunkLoaded(this.pos)) {
                    Messages.warn("No container at " + this.pos.toShortString() + " any more");
                    return Status.FAILED;
                }
                WalkPhase.Result r = this.walk.tick(client);
                if (r == WalkPhase.Result.UNREACHABLE) {
                    Messages.warn("Can't reach container at " + this.pos.toShortString());
                    return Status.FAILED;
                }
                if (r == WalkPhase.Result.IN_REACH) {
                    this.phase = Phase.OPEN;
                    this.timer = 0;
                }
                return Status.RUNNING;
            }
            case OPEN -> {
                if (player.currentScreenHandler != player.playerScreenHandler) {
                    // Something else is open (chat, another chest). Close it first.
                    if (++this.timer > CLOSE_OTHER_TIMEOUT_TICKS) {
                        Messages.warn("Couldn't close the open screen to use the container at " + this.pos.toShortString());
                        return Status.FAILED;
                    }
                    player.closeHandledScreen();
                    return Status.RUNNING;
                }
                if (!Reach.inReach(client, this.pos)) {
                    // Drifted out of reach (pushed, still sliding): walk back first.
                    this.phase = Phase.WALK;
                    return Status.RUNNING;
                }
                Reach.interact(client, this.pos, null);
                this.openTries++;
                this.phase = Phase.WAIT_OPEN;
                this.timer = 0;
                return Status.RUNNING;
            }
            case WAIT_OPEN -> {
                ScreenHandler open = ContainerTracker.get().openHandler();
                BlockPos openPos = ContainerTracker.get().openContainerPos();
                if (open != null && this.pos.equals(openPos)) {
                    this.handler = open;
                    this.phase = Phase.SETTLE;
                    this.timer = 0;
                } else if (++this.timer > OPEN_TIMEOUT_TICKS) {
                    if (this.openTries < MAX_OPEN_TRIES) {
                        this.phase = Phase.OPEN;
                        this.timer = 0;
                        return Status.RUNNING;
                    }
                    Messages.warn("Container at " + this.pos.toShortString() + " didn't open");
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            case SETTLE -> {
                // Wait for the server to send the slot contents.
                if (!stillOpen(player)) {
                    return Status.FAILED;
                }
                if (++this.timer >= SETTLE_TICKS) {
                    this.phase = Phase.ACT;
                    this.timer = 0;
                }
                return Status.RUNNING;
            }
            case ACT -> {
                if (!stillOpen(player)) {
                    return Status.FAILED;
                }
                boolean finished = this.action.run(client, this.handler);
                // Clicks are predicted client-side, so the handler already shows the result.
                BlockPos key = StorageManager.canonical(client.world, this.pos);
                if (StorageManager.get().container(key) != null) {
                    StorageManager.get().record(key, ContainerTracker.snapshot(this.handler));
                }
                if (finished) {
                    player.closeHandledScreen();
                    this.phase = Phase.DONE;
                    return Status.DONE;
                }
                if (++this.timer > ACT_TIMEOUT_TICKS) {
                    Messages.warn("Gave up moving items at " + this.pos.toShortString() + " (took too long)");
                    player.closeHandledScreen();
                    this.phase = Phase.DONE;
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    private boolean stillOpen(ClientPlayerEntity player) {
        if (player.currentScreenHandler != this.handler) {
            Messages.warn("Container at " + this.pos.toShortString() + " was closed early");
            return false;
        }
        return true;
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (client.player != null && this.handler != null && client.player.currentScreenHandler == this.handler) {
            client.player.closeHandledScreen();
        }
    }
}
