package com.testrts.rtsclient.task;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Empties the main inventory (not the hotbar, not pinned items) into linked containers,
 * preferring containers that already hold the same item.
 */
public final class DepositTask implements RtsTask {
    private static final int CLICKS_PER_TICK = 6;
    private static final int MAX_CONTAINERS = 8;

    private final Set<BlockPos> visited = new HashSet<>();
    /** Slots already shift-clicked during the current visit; a full container leaves them in place. */
    private final Set<Integer> clicked = new HashSet<>();
    private ContainerVisitTask visit;

    @Override
    public String label() {
        return "Store inventory";
    }

    @Override
    public String detail() {
        return this.visit != null ? this.visit.detail() : "";
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        PlayerEntity player = client.player;
        if (player == null) {
            return Status.FAILED;
        }
        if (this.visit != null) {
            if (this.visit.tick(client, queue) == Status.RUNNING) {
                return Status.RUNNING;
            }
            this.visit = null;
        }
        List<Item> toStore = storable(player.getInventory());
        if (toStore.isEmpty()) {
            Messages.success("Inventory stored");
            return Status.DONE;
        }
        if (this.visited.size() >= MAX_CONTAINERS) {
            Messages.warn("Linked storage looks full");
            return Status.DONE;
        }
        BlockPos target = pickContainer(toStore, player.getBlockPos());
        if (target == null) {
            Messages.warn(this.visited.isEmpty() ? "No linked storage - link a chest first" : "Linked storage looks full");
            return Status.DONE;
        }
        this.visited.add(target);
        this.clicked.clear();
        this.visit = new ContainerVisitTask(target, "Store", this::store);
        return Status.RUNNING;
    }

    private BlockPos pickContainer(List<Item> items, BlockPos near) {
        List<StorageManager.LinkedContainer> candidates = new ArrayList<>();
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            if (!this.visited.contains(c.pos())) {
                candidates.add(c);
            }
        }
        // Prefer a container that already holds what we carry, then the nearest.
        candidates.sort(Comparator
                .comparingInt((StorageManager.LinkedContainer c) -> -matches(c, items))
                .thenComparingDouble(c -> c.pos().getSquaredDistance(near)));
        return candidates.isEmpty() ? null : candidates.get(0).pos();
    }

    private static int matches(StorageManager.LinkedContainer c, List<Item> items) {
        int n = 0;
        for (Item item : items) {
            if (c.count(item) > 0) {
                n++;
            }
        }
        return n;
    }

    private static List<Item> storable(PlayerInventory inv) {
        Set<String> pinned = new HashSet<>(RtsConfig.get().pinned);
        List<Item> out = new ArrayList<>();
        // Main inventory only: indices 9..35. The hotbar (0..8) is the player's working set.
        for (int i = 9; i < 36; i++) {
            var stack = inv.getStack(i);
            if (!stack.isEmpty() && !pinned.contains(Registries.ITEM.getId(stack.getItem()).toString())) {
                out.add(stack.getItem());
            }
        }
        return out;
    }

    private boolean store(MinecraftClient client, ScreenHandler handler) {
        PlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return true;
        }
        Set<String> pinned = new HashSet<>(RtsConfig.get().pinned);
        int clicks = 0;
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || slot.getIndex() < 9 || slot.getIndex() >= 36) {
                continue;
            }
            var stack = slot.getStack();
            if (stack.isEmpty() || pinned.contains(Registries.ITEM.getId(stack.getItem()).toString())
                    || !this.clicked.add(slot.id)) {
                continue;
            }
            client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
            if (++clicks >= CLICKS_PER_TICK) {
                return false;
            }
        }
        return true;
    }
}
