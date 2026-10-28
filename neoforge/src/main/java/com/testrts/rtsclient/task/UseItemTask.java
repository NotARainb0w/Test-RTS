package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Creative;
import com.testrts.rtsclient.exec.Inventories;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Walk to each spot and right-click its top with an item: hoe the ground of a farm, sow seeds on farmland.
 * Spots already done are skipped, one that won't take is tried a few times and then left.
 */
public final class UseItemTask implements RtsTask {
    private static final int SETTLE_TICKS = 4;
    private static final int MAX_TRIES = 3;

    private final String label;
    private final List<BlockPos> spots;
    /** Any item matching this will do (any hoe); {@code fetch} is what to get from storage if none is carried. */
    private final Predicate<Item> usable;
    @Nullable
    private final Item fetch;
    /** The spot needs nothing more. */
    private final BiPredicate<ClientLevel, BlockPos> done;
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private int index;
    @Nullable
    private WalkPhase walk;
    private int wait;
    private boolean fetched;
    private int skipped;

    public UseItemTask(String label, List<BlockPos> spots, Predicate<Item> usable, @Nullable Item fetch,
                       BiPredicate<ClientLevel, BlockPos> done) {
        this.label = label;
        this.spots = new ArrayList<>(spots);
        this.usable = usable;
        this.fetch = fetch;
        this.done = done;
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        return this.index + " / " + this.spots.size();
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return Status.FAILED;
        }
        if (this.wait > 0) {
            this.wait--;
            return Status.RUNNING;
        }
        // Next spot that still needs it, nearest first along the list.
        while (this.index < this.spots.size() && this.done.test(client.level, this.spots.get(this.index))) {
            this.index++;
            this.walk = null;
        }
        if (this.index >= this.spots.size()) {
            if (this.skipped > 0) {
                Messages.warn(this.label + ": " + this.skipped + " spot(s) left");
            }
            return Status.DONE;
        }
        BlockPos spot = this.spots.get(this.index);
        Item item = held(client);
        if (item == null) {
            if (!this.fetched && this.fetch != null && !Creative.supplies(client)) {
                this.fetched = true;
                queue.pushFront(new FetchTask(Map.of(this.fetch, Math.min(64, this.spots.size() - this.index))));
                return Status.RUNNING;
            }
            Messages.warn(this.label + ": nothing to use (" + (this.fetch == null ? "item" : new ItemStack(this.fetch).getHoverName().getString()) + ")");
            return Status.FAILED;
        }
        if (this.walk == null) {
            this.walk = new WalkPhase(spot);
        }
        WalkPhase.Result r = this.walk.tick(client);
        if (r == WalkPhase.Result.WALKING) {
            return Status.RUNNING;
        }
        if (r == WalkPhase.Result.UNREACHABLE || this.tries.merge(spot, 1, Integer::sum) > MAX_TRIES) {
            this.skipped++;
            this.index++;
            this.walk = null;
            return Status.RUNNING;
        }
        if (!Inventories.hold(client, item)) {
            return Status.FAILED;
        }
        Vec3 top = new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, spot, false));
        client.player.swing(InteractionHand.MAIN_HAND);
        this.wait = SETTLE_TICKS;
        return Status.RUNNING;
    }

    /** An item in the inventory that will do, or null (in creative supply: the item to fetch, spawned in hand). */
    @Nullable
    private Item held(Minecraft client) {
        if (Creative.supplies(client) && this.fetch != null) {
            Creative.spawnInHand(client, this.fetch);
            return this.fetch;
        }
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && this.usable.test(s.getItem())) {
                return s.getItem();
            }
        }
        return null;
    }
}
