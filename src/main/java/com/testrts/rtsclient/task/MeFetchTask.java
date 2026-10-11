package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.Ae2;
import com.testrts.rtsclient.storage.MeNetwork;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes items out of the Applied Energistics 2 network: opens the wireless terminal from wherever
 * the player is, or walks to a linked ME terminal and opens that, then shift-clicks stacks of what's
 * needed straight into the inventory and closes the terminal again.
 */
public final class MeFetchTask implements RtsTask {
    private enum Phase { WALK, OPEN, WAIT_OPEN, SETTLE, TAKE, ARRIVE, DONE }

    /** Ticks to wait for a terminal to open after clicking it. */
    private static final int OPEN_TIMEOUT_TICKS = 30;
    /** Ticks to wait per wireless hotkey (each kind of wireless terminal has its own). */
    private static final int HOTKEY_TIMEOUT_TICKS = 15;
    /** The hotkey that opened the wireless terminal last time; tried first. */
    @Nullable
    private static String workingHotkey;
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
    @Nullable
    private final WalkPhase walk;
    private List<String> hotkeys;
    private int hotkeyIndex;
    private Phase phase;
    private int timer;
    private int quiet;
    private int lastEntryCount = -1;
    private ScreenHandler handler;
    /** Stacks still to ask for, per entry serial. */
    private final Map<Long, Integer> requests = new LinkedHashMap<>();
    private Map<Item, Integer> lastCounts;

    /** Through the wireless terminal. */
    public MeFetchTask(Map<Item, Integer> wanted) {
        this(wanted, null);
    }

    /** At a linked ME terminal, or wireless when {@code terminal} is null. */
    public MeFetchTask(Map<Item, Integer> wanted, @Nullable MeNetwork.Terminal terminal) {
        this.wanted = new LinkedHashMap<>(wanted);
        this.terminal = terminal;
        this.walk = terminal == null ? null : new WalkPhase(terminal.pos());
        this.phase = terminal == null ? Phase.OPEN : Phase.WALK;
    }

    public boolean wireless() {
        return this.terminal == null;
    }

    @Override
    public String label() {
        return "ME fetch " + MaterialPlanner.describe(this.wanted, 2);
    }

    @Override
    public String detail() {
        String where = this.terminal == null ? "wireless terminal" : "ME terminal " + this.terminal.pos().toShortString();
        return switch (this.phase) {
            case WALK -> "walking to " + where;
            case OPEN, WAIT_OPEN, SETTLE -> "opening " + where;
            case TAKE, ARRIVE -> "taking items";
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
                WalkPhase.Result r = this.walk.tick(client);
                if (r == WalkPhase.Result.UNREACHABLE) {
                    Messages.warn("Can't reach the ME terminal at " + this.terminal.pos().toShortString());
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
                    if (Ae2.isTerminal(player.currentScreenHandler) && this.terminal == null) {
                        // A terminal is already open (the player's own): use it.
                        this.handler = player.currentScreenHandler;
                        this.phase = Phase.SETTLE;
                        this.timer = 0;
                        return Status.RUNNING;
                    }
                    if (++this.timer > CLOSE_OTHER_TIMEOUT_TICKS) {
                        Messages.warn("Couldn't close the open screen to use the ME terminal");
                        return Status.FAILED;
                    }
                    player.closeHandledScreen();
                    return Status.RUNNING;
                }
                if (this.terminal == null) {
                    if (this.hotkeys == null) {
                        this.hotkeys = new java.util.ArrayList<>(Ae2.wirelessHotkeys());
                        if (workingHotkey != null && this.hotkeys.remove(workingHotkey)) {
                            this.hotkeys.add(0, workingHotkey);
                        }
                    }
                    if (this.hotkeyIndex >= this.hotkeys.size() || !Ae2.pressHotkey(this.hotkeys.get(this.hotkeyIndex))) {
                        MeNetwork.get().wirelessFailed();
                        Messages.warn("The wireless terminal didn't open (carry a linked one, in range of its network)");
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
                ScreenHandler open = player.currentScreenHandler;
                if (open != player.playerScreenHandler && Ae2.isTerminal(open)) {
                    this.handler = open;
                    if (this.terminal == null) {
                        MeNetwork.get().wirelessOpened();
                        if (this.hotkeys != null && this.hotkeyIndex < this.hotkeys.size()) {
                            workingHotkey = this.hotkeys.get(this.hotkeyIndex);
                        }
                    }
                    this.phase = Phase.SETTLE;
                    this.timer = 0;
                } else if (++this.timer > (this.terminal == null ? HOTKEY_TIMEOUT_TICKS : OPEN_TIMEOUT_TICKS)) {
                    if (this.terminal == null) {
                        // That hotkey wasn't for a terminal the player carries: try the next kind.
                        this.hotkeyIndex++;
                        this.phase = Phase.OPEN;
                        this.timer = 0;
                        return Status.RUNNING;
                    }
                    Messages.warn("The ME terminal at " + this.terminal.pos().toShortString() + " didn't open");
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            case SETTLE -> {
                if (!stillOpen(player)) {
                    return Status.FAILED;
                }
                this.timer++;
                int n = Ae2.hasContents(this.handler) ? Ae2.entries(this.handler).size() : -1;
                if (n >= 0 && n == this.lastEntryCount) {
                    this.quiet++;
                } else {
                    this.quiet = 0;
                }
                this.lastEntryCount = n;
                boolean settled = this.timer >= SETTLE_MIN_TICKS && n > 0 && this.quiet >= 3;
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
                    Map.Entry<Long, Integer> e = it.next();
                    if (!Ae2.takeStack(this.handler, e.getKey())) {
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
    private void planRequests(PlayerInventory inv) {
        this.requests.clear();
        int freeSlots = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            if (inv.main.get(i).isEmpty()) {
                freeSlots++;
            }
        }
        List<Ae2.Entry> entries = Ae2.entries(this.handler);
        for (Map.Entry<Item, Integer> w : this.wanted.entrySet()) {
            Item item = w.getKey();
            int need = w.getValue();
            int perStack = Math.max(1, item.getMaxCount());
            // Biggest entry first (an item can be split by NBT, e.g. damaged or named copies).
            List<Ae2.Entry> holders = entries.stream().filter(e -> e.item() == item)
                    .sorted((a, b) -> Long.compare(b.amount(), a.amount())).toList();
            for (Ae2.Entry e : holders) {
                if (need <= 0 || freeSlots <= 0) {
                    break;
                }
                int stacks = (int) Math.min((need + perStack - 1) / perStack, (e.amount() + perStack - 1) / perStack);
                stacks = Math.min(stacks, freeSlots);
                if (stacks > 0) {
                    this.requests.merge(e.serial(), stacks, Integer::sum);
                    freeSlots -= stacks;
                    need -= (int) Math.min((long) stacks * perStack, e.amount());
                }
            }
        }
    }

    private Map<Item, Integer> counts(PlayerInventory inv) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Item item : this.wanted.keySet()) {
            out.put(item, inv.count(item));
        }
        return out;
    }

    /** Click the middle of the terminal's face, just inside the block where the part sits. */
    private static BlockHitResult faceHit(MeNetwork.Terminal t) {
        Vec3d c = Vec3d.ofCenter(t.pos());
        Vec3d hit = c.add(t.side().getOffsetX() * 0.45, t.side().getOffsetY() * 0.45, t.side().getOffsetZ() * 0.45);
        return new BlockHitResult(hit, t.side(), t.pos(), true);
    }

    private boolean stillOpen(ClientPlayerEntity player) {
        if (player.currentScreenHandler != this.handler) {
            // AE2 closes a wireless terminal by itself once the player is out of the access point's range.
            Messages.warn(this.terminal == null ? "The wireless terminal closed (out of range of the network?)" : "The ME terminal was closed early");
            return false;
        }
        return true;
    }

    private void close(ClientPlayerEntity player) {
        if (player.currentScreenHandler == this.handler && this.handler != null) {
            player.closeHandledScreen();
        }
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (client.player != null) {
            close(client.player);
        }
    }
}
