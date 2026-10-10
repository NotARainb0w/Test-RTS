package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Like {@link DepositTask}, but only for the stacks a job chooses: a filter picks the items,
 * and {@code keep} leaves some of them behind (whole stacks, hotbar first), e.g. a stack of seeds.
 */
public final class StoreItemsTask implements RtsTask {
    private static final int CLICKS_PER_TICK = 6;
    private static final int MAX_CONTAINERS = 8;

    private final String label;
    private final Predicate<ItemStack> filter;
    private final Map<Item, Integer> keep;
    private final boolean hotbar;
    private final Set<BlockPos> visited = new HashSet<>();
    /** Inventory indices to store during the current visit. */
    private final Set<Integer> toStore = new LinkedHashSet<>();
    private ContainerVisitTask visit;

    /**
     * @param filter which stacks to store
     * @param keep   per item, how many to leave in the inventory
     * @param hotbar also store matching hotbar stacks (picked-up drops land there first)
     */
    public StoreItemsTask(String label, Predicate<ItemStack> filter, Map<Item, Integer> keep, boolean hotbar) {
        this.label = label;
        this.filter = filter;
        this.keep = new HashMap<>(keep);
        this.hotbar = hotbar;
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        return this.visit != null ? this.visit.detail() : "";
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        Player player = client.player;
        if (player == null) {
            return Status.FAILED;
        }
        if (this.visit != null) {
            if (this.visit.tick(client, queue) == Status.RUNNING) {
                return Status.RUNNING;
            }
            this.visit = null;
        }
        List<Integer> slots = storable(player.getInventory());
        if (slots.isEmpty()) {
            return Status.DONE;
        }
        if (StorageManager.get().linked().isEmpty()) {
            Messages.warn(this.label + ": no linked storage - link a chest first");
            return Status.DONE;
        }
        if (this.visited.size() >= MAX_CONTAINERS) {
            Messages.warn(this.label + ": linked storage looks full");
            return Status.DONE;
        }
        Set<Item> items = new HashSet<>();
        for (int i : slots) {
            items.add(player.getInventory().getItem(i).getItem());
        }
        BlockPos target = pickContainer(items, player.blockPosition());
        if (target == null) {
            Messages.warn(this.label + ": linked storage looks full");
            return Status.DONE;
        }
        this.visited.add(target);
        this.toStore.clear();
        this.toStore.addAll(slots);
        this.visit = new ContainerVisitTask(target, this.label, this::store);
        return Status.RUNNING;
    }

    /** Inventory indices whose stacks should go into storage now. */
    private List<Integer> storable(Inventory inv) {
        Set<String> pinned = Jobs.pinned();
        Map<Item, Integer> keepLeft = new HashMap<>(this.keep);
        List<Integer> out = new ArrayList<>();
        // Hotbar first so the kept stacks are the ones in easy reach.
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !this.filter.test(stack) || Jobs.isPinned(pinned, stack)) {
                continue;
            }
            int k = keepLeft.getOrDefault(stack.getItem(), 0);
            if (k > 0) {
                keepLeft.put(stack.getItem(), k - stack.getCount());
                continue;
            }
            if (i < 9 && !this.hotbar) {
                continue;
            }
            out.add(i);
        }
        return out;
    }

    private BlockPos pickContainer(Set<Item> items, BlockPos near) {
        List<StorageManager.LinkedContainer> candidates = new ArrayList<>();
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            if (!this.visited.contains(c.pos())) {
                candidates.add(c);
            }
        }
        candidates.sort(Comparator
                .comparingInt((StorageManager.LinkedContainer c) -> -matches(c, items))
                .thenComparingDouble(c -> c.pos().distSqr(near)));
        return candidates.isEmpty() ? null : candidates.get(0).pos();
    }

    private static int matches(StorageManager.LinkedContainer c, Set<Item> items) {
        int n = 0;
        for (Item item : items) {
            if (c.count(item) > 0) {
                n++;
            }
        }
        return n;
    }

    private boolean store(Minecraft client, AbstractContainerMenu handler) {
        Player player = client.player;
        if (player == null || client.gameMode == null) {
            return true;
        }
        int clicks = 0;
        var it = this.toStore.iterator();
        while (it.hasNext()) {
            int index = it.next();
            it.remove();
            Slot slot = Jobs.playerSlot(handler, index);
            if (slot == null || slot.getItem().isEmpty()) {
                continue;
            }
            client.gameMode.handleInventoryMouseClick(handler.containerId, slot.index, 0, ClickType.QUICK_MOVE, player);
            if (++clicks >= CLICKS_PER_TICK) {
                return this.toStore.isEmpty();
            }
        }
        return true;
    }

    @Override
    public void suspend(Minecraft client) {
        if (this.visit != null) {
            this.visit.cancel(client);
            this.visited.remove(this.visit.pos());
            this.visit = null;
        }
    }

    @Override
    public void cancel(Minecraft client) {
        if (this.visit != null) {
            this.visit.cancel(client);
        }
    }
}
