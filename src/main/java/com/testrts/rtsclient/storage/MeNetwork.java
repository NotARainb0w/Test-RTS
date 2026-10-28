package com.testrts.rtsclient.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.config.RtsConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's storage network of one mod ({@link GridMod}: AE2, Refined Storage): the terminals they
 * linked, whether a wireless terminal reaches it, and the last contents we saw in any of its terminals.
 * One network per mod and world: whatever terminal was opened last tells us what it holds.
 */
public final class MeNetwork {
    private static final List<MeNetwork> ALL = Grids.all().stream().map(MeNetwork::new).toList();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** After a wireless terminal didn't open, don't try again for this long. */
    private static final long WIRELESS_RETRY_MS = 60_000;
    /**
     * What a backpack holds changes without it being opened (pickup / magnet upgrades), and looking again
     * is quick: past this age its last contents no longer rule out an item.
     */
    private static final long PORTABLE_STALE_MS = 20_000;

    /** A terminal part on one face of a cable block. */
    public record Terminal(BlockPos pos, Direction side) {
    }

    private final List<Terminal> terminals = new ArrayList<>();
    private final Map<Item, Long> items = new LinkedHashMap<>();
    private boolean indexed;
    private long indexedAt;
    /** A wireless terminal has opened for us here before (it may live in a Curios/Trinkets slot). */
    private boolean wirelessWorked;
    private long wirelessFailedAt;
    /** A storage tried without seeing it (a worn backpack) didn't open this session. */
    private boolean alwaysTryFailed;
    private String worldKey;
    private boolean dirty;
    private int saveCooldown;
    @Nullable
    private ScreenHandler seenHandler;
    /** Ticks the terminal in {@link #seenHandler} has been open. */
    private int seenTicks;

    private final GridMod mod;

    private MeNetwork(GridMod mod) {
        this.mod = mod;
    }

    /** Every storage mod's network, in the order the builder tries them. */
    public static List<MeNetwork> all() {
        return ALL;
    }

    /** The AE2 network. */
    public static MeNetwork get() {
        return of(Ae2.GRID);
    }

    public static MeNetwork of(GridMod mod) {
        for (MeNetwork n : ALL) {
            if (n.mod == mod) {
                return n;
            }
        }
        throw new IllegalArgumentException(mod.name());
    }

    public GridMod mod() {
        return this.mod;
    }

    public static void tickAll(MinecraftClient client) {
        for (MeNetwork n : ALL) {
            n.tick(client);
        }
    }

    public static void flushAll() {
        for (MeNetwork n : ALL) {
            n.flush();
        }
    }

    /** Last known count across every network. */
    public static int countAll(Item item) {
        long sum = 0;
        for (MeNetwork n : ALL) {
            sum += n.count(item);
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /** Some network might give us {@code item} (see {@link #mightHave}). */
    public static boolean anyMightHave(MinecraftClient client, Item item) {
        for (MeNetwork n : ALL) {
            if (n.mightHave(client, item)) {
                return true;
            }
        }
        return false;
    }

    /** The network (if any) with a terminal at {@code pos}. */
    @Nullable
    public static MeNetwork linkedAt(BlockPos pos) {
        for (MeNetwork n : ALL) {
            if (n.isLinked(pos)) {
                return n;
            }
        }
        return null;
    }

    /** JSON shape on disk. */
    private static final class Saved {
        List<int[]> terminals = new ArrayList<>();
        boolean indexed;
        long indexedAt;
        boolean wirelessWorked;
        Map<String, Long> items = new LinkedHashMap<>();
    }

    // ---------------------------------------------------------------- lifecycle

    public void tick(MinecraftClient client) {
        String key = client.world == null ? null : StorageManager.computeWorldKey(client);
        if (key == null ? this.worldKey != null : !key.equals(this.worldKey)) {
            flush();
            clear();
            this.worldKey = key;
            if (key != null) {
                load();
            }
        }
        if (this.dirty && --this.saveCooldown <= 0) {
            flush();
        }
        // Only while the mod is there: without it nothing would count as a terminal.
        if (client.world != null && enabled() && ++this.pruneTicks >= 20) {
            this.pruneTicks = 0;
            int gone = pruneGone(client.world);
            if (gone > 0) {
                com.testrts.rtsclient.util.Messages.info(gone + " linked " + this.mod.terminalName() + "(s) are gone - unlinked");
            }
        }
        // Any ME terminal the player has open (ours or opened by hand) shows us what the network holds.
        ClientPlayerEntity player = client.player;
        ScreenHandler open = player == null ? null : player.currentScreenHandler;
        if (open != null && open != player.playerScreenHandler && this.mod.isTerminal(open) && this.mod.hasContents(open)) {
            if (this.seenHandler != open) {
                this.seenHandler = open;
                this.seenTicks = 0;
                if (this.mod.isWireless(open)) {
                    wirelessOpened();
                }
            }
            this.seenTicks++;
            Map<Item, Long> contents = GridMod.totals(this.mod.entries(open));
            // A terminal that just opened may not have its items yet: an empty list only counts once
            // it has stayed empty for a few seconds.
            if (!contents.isEmpty() || this.seenTicks >= 60) {
                record(contents);
            }
        } else {
            this.seenHandler = null;
        }
    }

    /** How long a linked terminal must be missing before it's unlinked (chunk data can arrive late). */
    private static final long GONE_MS = 3000;
    private final Map<BlockPos, Long> missingSince = new java.util.HashMap<>();
    private int pruneTicks;

    /** Unlink terminals that were removed: chunk loaded, no terminal there, for a few seconds in a row. */
    private int pruneGone(ClientWorld world) {
        long now = System.currentTimeMillis();
        int before = this.terminals.size();
        this.terminals.removeIf(t -> {
            if (!world.isChunkLoaded(t.pos) || this.mod.isTerminalAt(world.getBlockEntity(t.pos), t.side)) {
                this.missingSince.remove(t.pos);
                return false;
            }
            return now - this.missingSince.computeIfAbsent(t.pos, p -> now) >= GONE_MS;
        });
        int removed = before - this.terminals.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    /** Forget the terminal at {@code pos} (it isn't there any more). */
    public void unlink(BlockPos pos) {
        if (this.terminals.removeIf(t -> t.pos.equals(pos))) {
            markDirty();
        }
    }

    private void clear() {
        this.terminals.clear();
        this.items.clear();
        this.indexed = false;
        this.indexedAt = 0;
        this.wirelessWorked = false;
        this.wirelessFailedAt = 0;
        this.alwaysTryFailed = false;
    }

    /** Forget everything about this world's network (links, contents, wireless). */
    public void reset() {
        clear();
        markDirty();
    }

    public void flush() {
        if (this.dirty && this.worldKey != null) {
            save();
        }
        this.dirty = false;
    }

    private void markDirty() {
        if (!this.dirty) {
            this.saveCooldown = 100;
        }
        this.dirty = true;
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtsclient").resolve("storage").resolve(this.worldKey + "." + this.mod.fileSuffix() + ".json");
    }

    private void load() {
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Saved saved = GSON.fromJson(reader, Saved.class);
            if (saved == null) {
                return;
            }
            if (saved.terminals != null) {
                for (int[] t : saved.terminals) {
                    if (t != null && t.length == 4 && t[3] >= 0 && t[3] < 6) {
                        this.terminals.add(new Terminal(new BlockPos(t[0], t[1], t[2]), Direction.byId(t[3])));
                    }
                }
            }
            this.indexed = saved.indexed;
            this.indexedAt = saved.indexedAt;
            this.wirelessWorked = saved.wirelessWorked;
            if (saved.items != null) {
                saved.items.forEach((id, n) -> {
                    Identifier ident = Identifier.tryParse(id);
                    if (ident != null && Registries.ITEM.containsId(ident) && n != null) {
                        this.items.merge(Registries.ITEM.get(ident), n, Long::sum);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            RtsClientMod.LOGGER.warn("Could not read ME network file {}", file, e);
        }
    }

    private void save() {
        Path file = file();
        Saved s = new Saved();
        for (Terminal t : this.terminals) {
            s.terminals.add(new int[]{t.pos.getX(), t.pos.getY(), t.pos.getZ(), t.side.getId()});
        }
        s.indexed = this.indexed;
        s.indexedAt = this.indexedAt;
        s.wirelessWorked = this.wirelessWorked;
        this.items.forEach((item, n) -> s.items.put(Registries.ITEM.getId(item).toString(), n));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(s, writer);
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not write ME network file {}", file, e);
        }
    }

    // ---------------------------------------------------------------- terminals

    public List<Terminal> terminals() {
        return Collections.unmodifiableList(this.terminals);
    }

    public boolean isLinked(BlockPos pos) {
        for (Terminal t : this.terminals) {
            if (t.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if now linked, false if it was unlinked. */
    public boolean toggle(BlockPos pos, Direction side) {
        markDirty();
        Terminal t = new Terminal(pos.toImmutable(), side);
        if (this.terminals.remove(t)) {
            return false;
        }
        this.terminals.add(t);
        return true;
    }

    public void unlinkAll() {
        this.terminals.clear();
        markDirty();
    }

    /** Drops links whose block is loaded but no longer holds a terminal on that face. */
    public int pruneMissing(ClientWorld world) {
        int before = this.terminals.size();
        this.terminals.removeIf(t -> world.isChunkLoaded(t.pos) && !this.mod.isTerminalAt(world.getBlockEntity(t.pos), t.side));
        int removed = before - this.terminals.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    @Nullable
    public Terminal nearestTerminal(BlockPos near) {
        return this.terminals.stream().min(Comparator.comparingDouble(t -> t.pos.getSquaredDistance(near))).orElse(null);
    }

    // ---------------------------------------------------------------- reaching it

    /** The mod is there and using it for materials is on. */
    public boolean enabled() {
        return this.mod.enabled();
    }

    public boolean carriesWirelessTerminal(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (this.mod.isWirelessItem(inv.getStack(i))) {
                return true;
            }
        }
        return false;
    }

    /** Worth trying the wireless terminal: carried (or it opened before) and it didn't just fail. */
    public boolean wirelessUsable(MinecraftClient client) {
        if (!enabled() || client.player == null) {
            return false;
        }
        if (this.wirelessFailedAt != 0 && System.currentTimeMillis() - this.wirelessFailedAt < WIRELESS_RETRY_MS) {
            return false;
        }
        // A worn backpack can't be seen: try it, but after it didn't open only once one is carried or used again.
        return this.wirelessWorked || carriesWirelessTerminal(client.player) || this.mod.alwaysTry() && !this.alwaysTryFailed;
    }

    public void wirelessFailed() {
        this.wirelessFailedAt = System.currentTimeMillis();
        this.alwaysTryFailed = true;
    }

    public void wirelessOpened() {
        this.wirelessFailedAt = 0;
        this.alwaysTryFailed = false;
        if (!this.wirelessWorked) {
            this.wirelessWorked = true;
            markDirty();
        }
    }

    /** Some way to reach the network: a wireless terminal or a linked terminal. */
    public boolean reachable(MinecraftClient client) {
        return enabled() && (wirelessUsable(client) || !this.terminals.isEmpty());
    }

    // ---------------------------------------------------------------- contents

    public boolean indexed() {
        return this.indexed;
    }

    /** Last known count, 0 if unknown or not there (or this mod is off). */
    public int count(Item item) {
        if (!enabled()) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, this.items.getOrDefault(item, 0L));
    }

    public Map<Item, Long> items() {
        return enabled() ? Collections.unmodifiableMap(this.items) : Map.of();
    }

    /**
     * Might the network give us {@code item}? Known to hold it, or reachable but never looked
     * into yet (then the only way to find out is to open a terminal).
     */
    public boolean mightHave(MinecraftClient client, Item item) {
        return reachable(client) && (count(item) > 0 || !this.indexed
                || this.mod.portable() && System.currentTimeMillis() - this.indexedAt > PORTABLE_STALE_MS);
    }

    public void record(Map<Item, Long> contents) {
        if (this.indexed && this.items.equals(contents)) {
            this.indexedAt = System.currentTimeMillis(); // still current
            return;
        }
        this.items.clear();
        this.items.putAll(contents);
        this.indexed = true;
        this.indexedAt = System.currentTimeMillis();
        markDirty();
    }
}
