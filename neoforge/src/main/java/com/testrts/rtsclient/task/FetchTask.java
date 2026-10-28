package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.MeNetwork;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pulls items out of storage: first the storage networks (AE2, Refined Storage: wireless terminal, or
 * the nearest linked terminal / grid) when there are any, then linked containers, visiting the nearest ones that have what we need.
 */
public final class FetchTask implements RtsTask {
    private static final int CLICKS_PER_TICK = 6;

    private final Map<Item, Integer> remaining;
    private final Set<BlockPos> visited = new HashSet<>();
    /** The visit in progress: a {@link ContainerVisitTask} or a {@link MeFetchTask}. */
    private RtsTask visit;
    /** AE2 network: tried (or found nothing to try), and how many terminals we tried to open. */
    private boolean meDone;
    /** Per network: tries so far (wireless, then a terminal), and whether it's been dealt with. */
    private final Map<MeNetwork, Integer> meAttempts = new java.util.HashMap<>();
    private final java.util.Set<MeNetwork> meFinished = new java.util.HashSet<>();
    private Map<Item, Integer> before;
    /** Items the order still uses, never moved out of the inventory to make room. */
    private Set<Item> keep = Set.of();

    public FetchTask(Map<Item, Integer> wanted) {
        this.remaining = new LinkedHashMap<>(wanted);
    }

    public FetchTask keep(Set<Item> keep) {
        this.keep = keep;
        return this;
    }

    @Override
    public String label() {
        return "Fetch " + MaterialPlanner.describe(this.remaining, 2);
    }

    @Override
    public String detail() {
        return this.visit != null ? this.visit.detail() : "planning";
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        Player player = client.player;
        if (player == null) {
            return Status.FAILED;
        }
        if (this.visit != null) {
            Status s = this.visit.tick(client, queue);
            if (s == Status.RUNNING) {
                return Status.RUNNING;
            }
            if (this.visit instanceof MeFetchTask me && s == Status.DONE) {
                this.meFinished.add(me.network());
            }
            settle(player.getInventory());
            this.visit = null;
        }
        this.remaining.values().removeIf(v -> v <= 0);
        if (this.remaining.isEmpty()) {
            return Status.DONE;
        }
        // A full inventory can still take from a backpack: it makes room by putting things away in it.
        boolean full = player.getInventory().getFreeSlot() < 0;
        if (!this.meDone) {
            MeFetchTask me = meVisit(client, player.blockPosition(), full);
            if (me != null) {
                this.meAttempts.merge(me.network(), 1, Integer::sum);
                this.before = counts(player.getInventory());
                this.visit = me.keep(this.keep);
                return Status.RUNNING;
            }
            this.meDone = true;
        }
        if (full) {
            Messages.warn("Inventory full, stopped fetching");
            return Status.DONE;
        }
        BlockPos next = nextContainer(player.blockPosition());
        if (next == null) {
            Messages.warn("Storage doesn't have " + MaterialPlanner.describe(this.remaining, 3));
            return Status.DONE;
        }
        this.visited.add(next);
        this.before = counts(player.getInventory());
        this.visit = new ContainerVisitTask(next, "Fetch", this::take);
        return Status.RUNNING;
    }

    /**
     * Get what a storage network (AE2, then Refined Storage) may have: through its wireless terminal, or
     * if that can't be used (or just failed), at its nearest linked terminal. Null when there's nothing to try.
     * With a full inventory only a backpack is worth opening.
     */
    private MeFetchTask meVisit(Minecraft client, BlockPos near, boolean full) {
        for (MeNetwork me : MeNetwork.all()) {
            if (this.meFinished.contains(me) || this.meAttempts.getOrDefault(me, 0) >= 2 || !me.reachable(client)
                    || full && !me.mod().portable()) {
                continue;
            }
            Map<Item, Integer> fromMe = new LinkedHashMap<>();
            this.remaining.forEach((item, n) -> {
                if (me.mightHave(client, item)) {
                    fromMe.put(item, n);
                }
            });
            if (fromMe.isEmpty()) {
                continue;
            }
            if (me.wirelessUsable(client)) {
                return new MeFetchTask(me, fromMe, null);
            }
            MeNetwork.Terminal terminal = me.nearestTerminal(near);
            if (terminal != null) {
                return new MeFetchTask(me, fromMe, terminal);
            }
        }
        return null;
    }

    private BlockPos nextContainer(BlockPos near) {
        for (Item item : this.remaining.keySet()) {
            List<StorageManager.LinkedContainer> holders = StorageManager.get().containersWith(item, near);
            for (StorageManager.LinkedContainer c : holders) {
                if (!this.visited.contains(c.pos())) {
                    return c.pos();
                }
            }
        }
        return null;
    }

    private boolean take(Minecraft client, AbstractContainerMenu handler) {
        Player player = client.player;
        if (player == null || client.gameMode == null) {
            return true;
        }
        Map<Item, Integer> stillWanted = new LinkedHashMap<>(this.remaining);
        Map<Item, Integer> gained = diff(this.before, counts(player.getInventory()));
        gained.forEach((item, n) -> stillWanted.merge(item, -n, Integer::sum));

        int clicks = 0;
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || stillWanted.getOrDefault(stack.getItem(), 0) <= 0) {
                continue;
            }
            if (player.getInventory().getFreeSlot() < 0 && player.getInventory().getSlotWithRemainingSpace(stack) < 0) {
                return true;
            }
            stillWanted.merge(stack.getItem(), -stack.getCount(), Integer::sum);
            client.gameMode.handleInventoryMouseClick(handler.containerId, slot.index, 0, ClickType.QUICK_MOVE, player);
            if (++clicks >= CLICKS_PER_TICK) {
                return false;
            }
        }
        return true;
    }

    private void settle(Inventory inv) {
        if (this.before == null) {
            return;
        }
        diff(this.before, counts(inv)).forEach((item, n) -> this.remaining.merge(item, -n, Integer::sum));
        this.before = null;
    }

    private Map<Item, Integer> counts(Inventory inv) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Item item : this.remaining.keySet()) {
            out.put(item, inv.countItem(item));
        }
        return out;
    }

    private static Map<Item, Integer> diff(Map<Item, Integer> before, Map<Item, Integer> after) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (before == null) {
            return out;
        }
        after.forEach((item, n) -> {
            int d = n - before.getOrDefault(item, 0);
            if (d > 0) {
                out.put(item, d);
            }
        });
        return out;
    }

    @Override
    public void cancel(Minecraft client) {
        if (this.visit != null) {
            this.visit.cancel(client);
        }
    }
}
