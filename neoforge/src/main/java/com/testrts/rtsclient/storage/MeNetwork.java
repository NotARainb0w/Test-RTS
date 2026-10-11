package com.testrts.rtsclient.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * The player's Applied Energistics 2 network: the ME terminals they linked, whether a wireless
 * terminal reaches it, and the last contents we saw in any of its terminals. Treated as one network
 * per world: whatever terminal was opened last tells us what it holds.
 */
public final class MeNetwork {
    private static final MeNetwork INSTANCE = new MeNetwork();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** After a wireless terminal didn't open, don't try again for this long. */
    private static final long WIRELESS_RETRY_MS = 60_000;

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
    private String worldKey;
    private boolean dirty;
    private int saveCooldown;
    @Nullable
    private AbstractContainerMenu seenHandler;

    public static MeNetwork get() {
        return INSTANCE;
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

    public void tick(Minecraft client) {
        String key = client.level == null ? null : StorageManager.computeWorldKey(client);
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
        // Any ME terminal the player has open (ours or opened by hand) shows us what the network holds.
        LocalPlayer player = client.player;
        AbstractContainerMenu open = player == null ? null : player.containerMenu;
        if (open != null && open != player.inventoryMenu && Ae2.isTerminal(open) && Ae2.hasContents(open)) {
            if (this.seenHandler != open) {
                this.seenHandler = open;
                if (Ae2.isWireless(open)) {
                    wirelessOpened();
                }
            }
            record(Ae2.totals(Ae2.entries(open)));
        } else {
            this.seenHandler = null;
        }
    }

    private void clear() {
        this.terminals.clear();
        this.items.clear();
        this.indexed = false;
        this.indexedAt = 0;
        this.wirelessWorked = false;
        this.wirelessFailedAt = 0;
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
        return Platform.configDir().resolve("rtsclient").resolve("storage").resolve(this.worldKey + ".me.json");
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
                        this.terminals.add(new Terminal(new BlockPos(t[0], t[1], t[2]), Direction.from3DDataValue(t[3])));
                    }
                }
            }
            this.indexed = saved.indexed;
            this.indexedAt = saved.indexedAt;
            this.wirelessWorked = saved.wirelessWorked;
            if (saved.items != null) {
                saved.items.forEach((id, n) -> {
                    ResourceLocation ident = ResourceLocation.tryParse(id);
                    if (ident != null && BuiltInRegistries.ITEM.containsKey(ident) && n != null) {
                        this.items.merge(BuiltInRegistries.ITEM.get(ident), n, Long::sum);
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
            s.terminals.add(new int[]{t.pos.getX(), t.pos.getY(), t.pos.getZ(), t.side.get3DDataValue()});
        }
        s.indexed = this.indexed;
        s.indexedAt = this.indexedAt;
        s.wirelessWorked = this.wirelessWorked;
        this.items.forEach((item, n) -> s.items.put(BuiltInRegistries.ITEM.getKey(item).toString(), n));
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
        Terminal t = new Terminal(pos.immutable(), side);
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
    public int pruneMissing(ClientLevel world) {
        int before = this.terminals.size();
        this.terminals.removeIf(t -> world.hasChunkAt(t.pos) && !Ae2.isTerminalPart(world.getBlockEntity(t.pos), t.side));
        int removed = before - this.terminals.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    @Nullable
    public Terminal nearestTerminal(BlockPos near) {
        return this.terminals.stream().min(Comparator.comparingDouble(t -> t.pos.distSqr(near))).orElse(null);
    }

    // ---------------------------------------------------------------- reaching it

    /** Using AE2 for materials is on and the mod is there. */
    public static boolean enabled() {
        return RtsConfig.get().useAe2 && Ae2.loaded();
    }

    public static boolean carriesWirelessTerminal(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (Ae2.isWirelessTerminal(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** Worth trying the wireless terminal: carried (or it opened before) and it didn't just fail. */
    public boolean wirelessUsable(Minecraft client) {
        if (!enabled() || client.player == null) {
            return false;
        }
        if (this.wirelessFailedAt != 0 && System.currentTimeMillis() - this.wirelessFailedAt < WIRELESS_RETRY_MS) {
            return false;
        }
        return this.wirelessWorked || carriesWirelessTerminal(client.player);
    }

    public void wirelessFailed() {
        this.wirelessFailedAt = System.currentTimeMillis();
    }

    public void wirelessOpened() {
        this.wirelessFailedAt = 0;
        if (!this.wirelessWorked) {
            this.wirelessWorked = true;
            markDirty();
        }
    }

    /** Some way to reach the network: a wireless terminal or a linked ME terminal. */
    public boolean reachable(Minecraft client) {
        return enabled() && (wirelessUsable(client) || !this.terminals.isEmpty());
    }

    // ---------------------------------------------------------------- contents

    public boolean indexed() {
        return this.indexed;
    }

    /** Last known count, 0 if unknown or not there (or AE2 use is off). */
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
    public boolean mightHave(Minecraft client, Item item) {
        return reachable(client) && (count(item) > 0 || !this.indexed);
    }

    public void record(Map<Item, Long> contents) {
        if (this.indexed && this.items.equals(contents)) {
            return;
        }
        this.items.clear();
        this.items.putAll(contents);
        this.indexed = true;
        this.indexedAt = System.currentTimeMillis();
        markDirty();
    }
}
