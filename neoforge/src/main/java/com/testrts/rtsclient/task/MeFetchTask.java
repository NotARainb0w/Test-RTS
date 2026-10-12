package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.MeNetwork;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes items out of a storage network (AE2, Refined Storage): opens the wireless terminal from wherever
 * the player is, or walks to a linked terminal / grid and opens that, then asks for stacks of what's
 * needed straight into the inventory and closes the terminal again.
 */
public final class MeFetchTask implements RtsTask {
    private enum Phase { WALK, OPEN, WAIT_OPEN, SETTLE, TAKE, ARRIVE, DONE }

    /** Ticks to wait for a terminal to open after clicking it. */
    private static final int OPEN_TIMEOUT_TICKS = 30;
    /** Ticks to wait per wireless hotkey (each kind of wireless terminal has its own). */
    private static final int HOTKEY_TIMEOUT_TICKS = 15;
    /** Per storage mod, the way of opening its wireless terminal that worked last time; tried first. */
    private static final Map<String, String> workingHotkey = new java.util.HashMap<>();
    private static final int CLOSE_OTHER_TIMEOUT_TICKS = 40;
    /** The network's contents arrive in a few packets after the screen opens. */
    private static final int SETTLE_MIN_TICKS = 4;
    private static final int SETTLE_MAX_TICKS = 40;
    private static final int CLICKS_PER_TICK = 8;
    /** After the last request, wait until the inventory has been still this long. */
    private static final int ARRIVE_QUIET_TICKS = 8;
    private static final int ARRIVE_TIMEOUT_TICKS = 100;

    private final Map<Item, Integer> wanted;
    @Nullable
    private final MeNetwork.Terminal terminal;
    private final MeNetwork network;
    private final com.testrts.rtsclient.storage.GridMod mod;
    @Nullable
    private final WalkPhase walk;
    private List<String> hotkeys;
    private int hotkeyIndex;
    private Phase phase;
    private int timer;
    private int quiet;
    private int lastEntryCount = -1;
    private AbstractContainerMenu handler;
    /** Stacks still to ask for, per entry serial. */
    private final Map<Object, Integer> requests = new LinkedHashMap<>();
    private Map<Item, Integer> lastCounts;

    /** At a linked terminal of {@code network}, or through its wireless terminal when {@code terminal} is null. */
    public MeFetchTask(MeNetwork network, Map<Item, Integer> wanted, @Nullable MeNetwork.Terminal terminal) {
        this.network = network;
        this.mod = network.mod();
        this.wanted = new LinkedHashMap<>(wanted);
        this.terminal = terminal;
        this.walk = terminal == null ? null : new WalkPhase(terminal.pos());
        this.phase = terminal == null ? Phase.OPEN : Phase.WALK;
    }

    public MeNetwork network() {
        return this.network;
    }

    public boolean wireless() {
        return this.terminal == null;
    }

    @Override
    public String label() {
        return this.wanted.isEmpty() ? "Read " + this.mod.name() + " network"
                : this.mod.name() + " fetch " + MaterialPlanner.describe(this.wanted, 2);
    }

    @Override
    public String detail() {
        String where = this.terminal == null ? "wireless " + this.mod.terminalName() : this.mod.terminalName() + " " + this.terminal.pos().toShortString();
        return switch (this.phase) {
            case WALK -> "walking to " + where;
            case OPEN, WAIT_OPEN, SETTLE -> "opening " + where;
            case TAKE, ARRIVE -> "taking items";
            case DONE -> "done";
        };
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return Status.FAILED;
        }
        switch (this.phase) {
            case WALK -> {
                BlockPos at = this.terminal.pos();
                if (client.level.hasChunkAt(at) && !this.mod.isTerminalAt(client.level.getBlockEntity(at), this.terminal.side())) {
                    Messages.warn("No " + this.mod.terminalName() + " at " + at.toShortString() + " any more - unlinked");
                    this.network.unlink(at);
                    return Status.FAILED;
                }
                WalkPhase.Result r = this.walk.tick(client);
                if (r == WalkPhase.Result.UNREACHABLE) {
                    Messages.warn("Can't reach the " + this.mod.terminalName() + " at " + this.terminal.pos().toShortString());
                    return Status.FAILED;
                }
                if (r == WalkPhase.Result.IN_REACH) {
                    this.phase = Phase.OPEN;
                    this.timer = 0;
                }
                return Status.RUNNING;
            }
            case OPEN -> {
                if (player.containerMenu != player.inventoryMenu) {
                    if (this.mod.isTerminal(player.containerMenu) && this.terminal == null) {
                        // A terminal is already open (the player's own): use it.
                        this.handler = player.containerMenu;
                        this.phase = Phase.SETTLE;
                        this.timer = 0;
                        return Status.RUNNING;
                    }
                    if (++this.timer > CLOSE_OTHER_TIMEOUT_TICKS) {
                        Messages.warn("Couldn't close the open screen to use the " + this.mod.terminalName());
                        return Status.FAILED;
                    }
                    player.closeContainer();
                    return Status.RUNNING;
                }
                if (this.terminal == null) {
                    if (this.hotkeys == null) {
                        this.hotkeys = new java.util.ArrayList<>(this.mod.wirelessOpeners());
                        String worked = workingHotkey.get(this.mod.name());
                        if (worked != null && this.hotkeys.remove(worked)) {
                            this.hotkeys.add(0, worked);
                        }
                    }
                    if (this.hotkeyIndex >= this.hotkeys.size() || !this.mod.openWireless(this.hotkeys.get(this.hotkeyIndex))) {
                        this.network.wirelessFailed();
                        Messages.warn("The wireless " + this.mod.terminalName() + " didn't open (carry a linked one, in range of its network)");
                        return Status.FAILED;
                    }
                } else {
                    if (!Reach.inReach(client, this.terminal.pos())) {
                        this.phase = Phase.WALK;
                        return Status.RUNNING;
                    }
                    Reach.interact(client, this.terminal.pos(), faceHit(this.terminal));
                }
                this.phase = Phase.WAIT_OPEN;
                this.timer = 0;
                return Status.RUNNING;
            }
            case WAIT_OPEN -> {
                AbstractContainerMenu open = player.containerMenu;
                if (open != player.inventoryMenu && this.mod.isTerminal(open)) {
                    this.handler = open;
                    if (this.terminal == null) {
                        this.network.wirelessOpened();
                        if (this.hotkeys != null && this.hotkeyIndex < this.hotkeys.size()) {
                            workingHotkey.put(this.mod.name(), this.hotkeys.get(this.hotkeyIndex));
                        }
                    }
                    this.phase = Phase.SETTLE;
                    this.timer = 0;
                } else if (++this.timer > (this.terminal == null ? HOTKEY_TIMEOUT_TICKS : OPEN_TIMEOUT_TICKS)) {
                    if (this.terminal == null) {
                        com.testrts.rtsclient.RtsClientMod.LOGGER.debug("Wireless {} didn't open with '{}': open screen {}", this.mod.name(),
                                this.hotkeys.get(this.hotkeyIndex), player.containerMenu.getClass().getName());
                        // That hotkey wasn't for a terminal the player carries: try the next kind.
                        this.hotkeyIndex++;
                        this.phase = Phase.OPEN;
                        this.timer = 0;
                        return Status.RUNNING;
                    }
                    Messages.warn("The " + this.mod.terminalName() + " at " + this.terminal.pos().toShortString() + " didn't open");
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            case SETTLE -> {
                if (!stillOpen(player)) {
                    return Status.FAILED;
                }
                this.timer++;
                int n = this.mod.hasContents(this.handler) ? this.mod.entries(this.handler).size() : -1;
                if (n >= 0 && n == this.lastEntryCount) {
                    this.quiet++;
                } else {
                    this.quiet = 0;
                }
                this.lastEntryCount = n;
                boolean settled = this.timer >= SETTLE_MIN_TICKS && n > 0 && this.quiet >= 3;
                if (!settled && this.timer >= SETTLE_MAX_TICKS && n <= 0) {
                    // Nothing showed up: out of range, not linked, or the list never came. Not the same
                    // as an empty network, so this counts as a failed try (another way gets tried next).
                    Messages.warn("The " + (this.terminal == null ? "wireless " : "") + this.mod.terminalName() + " showed no items");
                    close(player);
                    return Status.FAILED;
                }
                if (settled || this.timer >= SETTLE_MAX_TICKS) {
                    planRequests(player.getInventory());
                    if (this.requests.isEmpty()) {
                        close(player);
                        return Status.DONE;
                    }
                    this.lastCounts = counts(player.getInventory());
                    this.phase = Phase.TAKE;
                    this.timer = 0;
                }
                return Status.RUNNING;
            }
            case TAKE -> {
                if (!stillOpen(player)) {
                    return Status.FAILED;
                }
                int clicks = 0;
                var it = this.requests.entrySet().iterator();
                while (it.hasNext() && clicks < CLICKS_PER_TICK) {
                    Map.Entry<Object, Integer> e = it.next();
                    if (!this.mod.takeStack(this.handler, e.getKey())) {
                        close(player);
                        return Status.FAILED;
                    }
                    clicks++;
                    if (e.getValue() <= 1) {
                        it.remove();
                    } else {
                        e.setValue(e.getValue() - 1);
                    }
                }
                if (this.requests.isEmpty()) {
                    this.phase = Phase.ARRIVE;
                    this.timer = 0;
                    this.quiet = 0;
                }
                return Status.RUNNING;
            }
            case ARRIVE -> {
                if (!stillOpen(player)) {
                    return Status.DONE; // whatever arrived is in the inventory
                }
                Map<Item, Integer> now = counts(player.getInventory());
                this.quiet = now.equals(this.lastCounts) ? this.quiet + 1 : 0;
                this.lastCounts = now;
                if (this.quiet >= ARRIVE_QUIET_TICKS || ++this.timer > ARRIVE_TIMEOUT_TICKS) {
                    close(player);
                    this.phase = Phase.DONE;
                    return Status.DONE;
                }
                return Status.RUNNING;
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    /** How many stacks of which entries to ask for, limited by free inventory space. */
    private void planRequests(Inventory inv) {
        this.requests.clear();
        int freeSlots = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                freeSlots++;
            }
        }
        List<com.testrts.rtsclient.storage.GridMod.Entry> entries = this.mod.entries(this.handler);
        for (Map.Entry<Item, Integer> w : this.wanted.entrySet()) {
            Item item = w.getKey();
            int need = w.getValue();
            int perStack = Math.max(1, item.getDefaultMaxStackSize());
            // Biggest entry first (an item can be split by NBT, e.g. damaged or named copies).
            List<com.testrts.rtsclient.storage.GridMod.Entry> holders = entries.stream().filter(e -> e.item() == item)
                    .sorted((a, b) -> Long.compare(b.amount(), a.amount())).toList();
            for (com.testrts.rtsclient.storage.GridMod.Entry e : holders) {
                if (need <= 0 || freeSlots <= 0) {
                    break;
                }
                int stacks = (int) Math.min((need + perStack - 1) / perStack, (e.amount() + perStack - 1) / perStack);
                stacks = Math.min(stacks, freeSlots);
                if (stacks > 0) {
                    this.requests.merge(e.handle(), stacks, Integer::sum);
                    freeSlots -= stacks;
                    need -= (int) Math.min((long) stacks * perStack, e.amount());
                }
            }
        }
    }

    private Map<Item, Integer> counts(Inventory inv) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Item item : this.wanted.keySet()) {
            out.put(item, inv.countItem(item));
        }
        return out;
    }

    /** Click the middle of the terminal's face, just inside the block where the part sits. */
    private static BlockHitResult faceHit(MeNetwork.Terminal t) {
        Vec3 c = Vec3.atCenterOf(t.pos());
        Vec3 hit = c.add(t.side().getStepX() * 0.45, t.side().getStepY() * 0.45, t.side().getStepZ() * 0.45);
        return new BlockHitResult(hit, t.side(), t.pos(), true);
    }

    private boolean stillOpen(LocalPlayer player) {
        if (player.containerMenu != this.handler) {
            // A wireless terminal closes by itself once the player is out of range of the network.
            Messages.warn(this.terminal == null ? "The wireless " + this.mod.terminalName() + " closed (out of range of the network?)"
                    : "The " + this.mod.terminalName() + " was closed early");
            return false;
        }
        return true;
    }

    private void close(LocalPlayer player) {
        if (player.containerMenu == this.handler && this.handler != null) {
            player.closeContainer();
        }
    }

    @Override
    public void cancel(Minecraft client) {
        if (client.player != null) {
            close(client.player);
        }
    }
}
