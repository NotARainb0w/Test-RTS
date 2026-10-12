package com.testrts.rtsclient.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.testrts.rtsclient.Platform;
import com.testrts.rtsclient.RtsClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's "storage network": containers they linked, plus the last contents we saw in each.
 * Contents are only known after the container was opened (by hand or by an index task), since a
 * client can't read a chest it hasn't opened.
 */
public final class StorageManager {
    private static final StorageManager INSTANCE = new StorageManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<List<SavedContainer>>() {
    }.getType();

    private final Map<BlockPos, LinkedContainer> containers = new LinkedHashMap<>();
    private String worldKey;
    private boolean dirty;
    private int saveCooldown;

    public static StorageManager get() {
        return INSTANCE;
    }

    /** Contents of one linked container. */
    public static final class LinkedContainer {
        private final BlockPos pos;
        private final Map<Item, Integer> items = new LinkedHashMap<>();
        private boolean indexed;
        private long indexedAt;

        LinkedContainer(BlockPos pos) {
            this.pos = pos;
        }

        public BlockPos pos() {
            return this.pos;
        }

        public Map<Item, Integer> items() {
            return Collections.unmodifiableMap(this.items);
        }

        public boolean indexed() {
            return this.indexed;
        }

        public long indexedAt() {
            return this.indexedAt;
        }

        public int count(Item item) {
            return this.items.getOrDefault(item, 0);
        }
    }

    /** JSON shape on disk. */
    private static final class SavedContainer {
        int x;
        int y;
        int z;
        boolean indexed;
        long indexedAt;
        Map<String, Integer> items = new LinkedHashMap<>();
    }

    // ---------------------------------------------------------------- lifecycle

    /** Called every client tick: follows world/dimension changes and flushes saves. */
    public void tick(Minecraft client) {
        String key = client.level == null ? null : computeWorldKey(client);
        if (key == null ? this.worldKey != null : !key.equals(this.worldKey)) {
            flush();
            this.containers.clear();
            this.worldKey = key;
            if (key != null) {
                load();
            }
        }
        if (this.dirty && --this.saveCooldown <= 0) {
            flush();
        }
        if (client.level != null && ++this.pruneTicks >= 20) {
            this.pruneTicks = 0;
            int gone = pruneGone(client.level);
            if (gone > 0) {
                com.testrts.rtsclient.util.Messages.info(gone + " linked storage block(s) are gone - unlinked");
            }
        }
    }

    /** How long a linked block must be missing before it's unlinked (chunk data can arrive late). */
    private static final long GONE_MS = 3000;
    private final Map<BlockPos, Long> missingSince = new HashMap<>();
    private int pruneTicks;

    /**
     * Unlink containers that were removed: their chunk is loaded and there's no container there any more,
     * for a few seconds in a row.
     */
    private int pruneGone(ClientLevel world) {
        long now = System.currentTimeMillis();
        int before = this.containers.size();
        this.containers.values().removeIf(c -> {
            if (!world.hasChunkAt(c.pos) || isContainer(world, c.pos)) {
                this.missingSince.remove(c.pos);
                return false;
            }
            return now - this.missingSince.computeIfAbsent(c.pos, p -> now) >= GONE_MS;
        });
        int removed = before - this.containers.size();
        if (removed > 0) {
            this.missingSince.keySet().retainAll(this.containers.keySet());
            markDirty();
        }
        return removed;
    }

    /** Forget one link (the block isn't a container any more). */
    public void unlink(ClientLevel world, BlockPos pos) {
        if (this.containers.remove(canonical(world, pos)) != null) {
            markDirty();
        }
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

    /** Server or singleplayer world plus dimension, safe for a file name. */
    public static String computeWorldKey(Minecraft client) {
        String server;
        if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
            server = "sp_" + client.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            ServerData info = client.getCurrentServer();
            server = "mp_" + (info != null ? info.ip : "unknown");
        }
        String dim = client.level.dimension().location().toString();
        return (server + "_" + dim).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path file() {
        return Platform.configDir().resolve("rtsclient").resolve("storage").resolve(this.worldKey + ".json");
    }

    private void load() {
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<SavedContainer> saved = GSON.fromJson(reader, FILE_TYPE);
            if (saved == null) {
                return;
            }
            for (SavedContainer s : saved) {
                LinkedContainer c = new LinkedContainer(new BlockPos(s.x, s.y, s.z));
                c.indexed = s.indexed;
                c.indexedAt = s.indexedAt;
                if (s.items != null) {
                    for (Map.Entry<String, Integer> e : s.items.entrySet()) {
                        ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                            c.items.merge(BuiltInRegistries.ITEM.get(id), e.getValue(), Integer::sum);
                        }
                    }
                }
                this.containers.put(c.pos, c);
            }
        } catch (IOException | RuntimeException e) {
            RtsClientMod.LOGGER.warn("Could not read storage file {}", file, e);
        }
    }

    private void save() {
        Path file = file();
        List<SavedContainer> out = new ArrayList<>();
        for (LinkedContainer c : this.containers.values()) {
            SavedContainer s = new SavedContainer();
            s.x = c.pos.getX();
            s.y = c.pos.getY();
            s.z = c.pos.getZ();
            s.indexed = c.indexed;
            s.indexedAt = c.indexedAt;
            for (Map.Entry<Item, Integer> e : c.items.entrySet()) {
                s.items.put(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue());
            }
            out.add(s);
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(out, FILE_TYPE, writer);
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not write storage file {}", file, e);
        }
    }

    // ---------------------------------------------------------------- queries

    /**
     * Storage we can link: any block with an inventory the player can open. Vanilla containers, and
     * modded storage that offers its items to hoppers and pipes (Sophisticated Storage, Iron Chests,
     * crates...). Not drawers or AE2 blocks (see {@link #notStorage}).
     */
    public static boolean isContainer(ClientLevel world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock());
        if (notStorage(id.getNamespace(), id.getPath())) {
            return false;
        }
        if (be instanceof Container) {
            return true;
        }
        try {
            return world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null) != null;
        } catch (RuntimeException e) {
            return false; // some mods' capabilities only work on the server
        }
    }

    /** For Scan nearby: vanilla chests, barrels and shulker boxes, and modded storage named like storage. */
    public static boolean looksLikeStorage(ClientLevel world, BlockEntity be) {
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock());
        return storageName(id.getPath()) && isContainer(world, be.getBlockPos());
    }

    /** Words in a block's id that mean storage, for Scan nearby (chests, barrels, crates... from any mod). */
    private static final String[] STORAGE_WORDS = {"chest", "barrel", "crate", "shulker", "storage", "cabinet", "locker", "safe", "backpack"};
    /**
     * Blocks never linked as storage: drawers (a right-click puts the held item in instead of opening
     * them), the parts of storage networks that aren't storage themselves, and machines.
     */
    private static final String[] NOT_STORAGE_WORDS = {"drawer", "controller", "terminal", "interface", "connector", "importer",
            "exporter", "trash", "void", "hopper", "furnace", "smoker", "compacting"};

    private static boolean notStorage(String namespace, String path) {
        // AE2 blocks (ME chest, drive...) have their own support through the ME network.
        if (namespace.equals("ae2")) {
            return true;
        }
        for (String word : NOT_STORAGE_WORDS) {
            if (path.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean storageName(String path) {
        for (String word : STORAGE_WORDS) {
            if (path.contains(word)) {
                return true;
            }
        }
        return false;
    }


    /** Both halves of a double chest map to the same key, so its items aren't counted twice. */
    public static BlockPos canonical(ClientLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction toOther = ChestBlock.getConnectedDirection(state);
            BlockPos other = pos.relative(toOther);
            return other.asLong() < pos.asLong() ? other.immutable() : pos.immutable();
        }
        return pos.immutable();
    }

    public boolean isLinked(ClientLevel world, BlockPos pos) {
        return this.containers.containsKey(canonical(world, pos));
    }

    public Collection<LinkedContainer> linked() {
        return Collections.unmodifiableCollection(this.containers.values());
    }

    @Nullable
    public LinkedContainer container(BlockPos canonicalPos) {
        return this.containers.get(canonicalPos);
    }

    /** @return true if now linked, false if it was unlinked. */
    public boolean toggle(ClientLevel world, BlockPos pos) {
        BlockPos key = canonical(world, pos);
        markDirty();
        if (this.containers.remove(key) != null) {
            return false;
        }
        this.containers.put(key, new LinkedContainer(key));
        return true;
    }

    public void link(ClientLevel world, BlockPos pos) {
        BlockPos key = canonical(world, pos);
        if (!this.containers.containsKey(key)) {
            this.containers.put(key, new LinkedContainer(key));
            markDirty();
        }
    }

    public void unlinkAll() {
        this.containers.clear();
        markDirty();
    }

    /** Drops links whose block is loaded but no longer a container (broken chests). */
    public int pruneMissing(ClientLevel world) {
        int before = this.containers.size();
        this.containers.values().removeIf(c -> world.hasChunkAt(c.pos) && !isContainer(world, c.pos));
        int removed = before - this.containers.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    /** Links every chest, barrel and shulker box in loaded chunks within {@code radius} blocks. */
    public int scanNearby(ClientLevel world, BlockPos center, int radius) {
        int added = 0;
        int r2 = radius * radius;
        int minCx = (center.getX() - radius) >> 4;
        int maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4;
        int maxCz = (center.getZ() + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!world.getChunkSource().hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = world.getChunk(cx, cz);
                if (!(chunk instanceof LevelChunk worldChunk)) {
                    continue;
                }
                for (BlockEntity be : new ArrayList<>(worldChunk.getBlockEntities().values())) {
                    if (!looksLikeStorage(world, be)) {
                        continue;
                    }
                    BlockPos pos = be.getBlockPos();
                    if (pos.distSqr(center) > r2) {
                        continue;
                    }
                    BlockPos key = canonical(world, pos);
                    if (!this.containers.containsKey(key)) {
                        this.containers.put(key, new LinkedContainer(key));
                        added++;
                    }
                }
            }
        }
        if (added > 0) {
            markDirty();
        }
        return added;
    }

    /** Sum of everything we know is stored, across indexed containers and the storage networks (AE2, RS). */
    public Map<Item, Integer> totals() {
        Map<Item, Integer> totals = new HashMap<>();
        for (LinkedContainer c : this.containers.values()) {
            c.items.forEach((item, count) -> totals.merge(item, count, Integer::sum));
        }
        for (MeNetwork network : MeNetwork.all()) {
            network.items().forEach((item, count) ->
                    totals.merge(item, (int) Math.min(Integer.MAX_VALUE, count), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b)));
        }
        return totals;
    }

    public int total(Item item) {
        int sum = MeNetwork.countAll(item);
        for (LinkedContainer c : this.containers.values()) {
            sum += c.count(item);
        }
        return sum;
    }

    /** Linked containers that hold {@code item}, nearest first. */
    public List<LinkedContainer> containersWith(Item item, BlockPos near) {
        List<LinkedContainer> out = new ArrayList<>();
        for (LinkedContainer c : this.containers.values()) {
            if (c.count(item) > 0) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparingDouble(c -> c.pos.distSqr(near)));
        return out;
    }

    /**
     * The item a linked container mostly holds (by count, from its index), for labels in the UI.
     * Either half of a double chest works. Null if not linked, not indexed yet, or empty.
     */
    @Nullable
    public Item labelFor(BlockPos pos) {
        LinkedContainer c = this.containers.get(pos);
        if (c == null) {
            ClientLevel world = Minecraft.getInstance().level;
            if (world != null) {
                c = this.containers.get(canonical(world, pos));
            }
        }
        if (c == null || !c.indexed) {
            return null;
        }
        Item best = null;
        int bestCount = 0;
        for (Map.Entry<Item, Integer> e : c.items.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    public List<LinkedContainer> unindexed() {
        List<LinkedContainer> out = new ArrayList<>();
        for (LinkedContainer c : this.containers.values()) {
            if (!c.indexed) {
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- updates

    /** Replace what we know about a container with what its open screen shows right now. */
    public void record(BlockPos canonicalPos, Map<Item, Integer> contents) {
        LinkedContainer c = this.containers.get(canonicalPos);
        if (c == null) {
            return;
        }
        if (c.indexed && c.items.equals(contents)) {
            return;
        }
        c.items.clear();
        c.items.putAll(contents);
        c.indexed = true;
        c.indexedAt = System.currentTimeMillis();
        markDirty();
    }
}
