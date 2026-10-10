package com.testrts.rtsclient.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.testrts.rtsclient.RtsClientMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
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
    public void tick(MinecraftClient client) {
        String key = client.world == null ? null : computeWorldKey(client);
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
    public static String computeWorldKey(MinecraftClient client) {
        String server;
        if (client.isInSingleplayer() && client.getServer() != null) {
            server = "sp_" + client.getServer().getSaveProperties().getLevelName();
        } else {
            ServerInfo info = client.getCurrentServerEntry();
            server = "mp_" + (info != null ? info.address : "unknown");
        }
        String dim = client.world.getRegistryKey().getValue().toString();
        return (server + "_" + dim).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtsclient").resolve("storage").resolve(this.worldKey + ".json");
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
                        Identifier id = Identifier.tryParse(e.getKey());
                        if (id != null && Registries.ITEM.containsId(id)) {
                            c.items.merge(Registries.ITEM.get(id), e.getValue(), Integer::sum);
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
                s.items.put(Registries.ITEM.getId(e.getKey()).toString(), e.getValue());
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

    public static boolean isContainer(ClientWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        return be instanceof Inventory;
    }

    /** Both halves of a double chest map to the same key, so its items aren't counted twice. */
    public static BlockPos canonical(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.contains(ChestBlock.CHEST_TYPE)
                && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            Direction toOther = ChestBlock.getFacing(state);
            BlockPos other = pos.offset(toOther);
            return other.asLong() < pos.asLong() ? other.toImmutable() : pos.toImmutable();
        }
        return pos.toImmutable();
    }

    public boolean isLinked(ClientWorld world, BlockPos pos) {
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
    public boolean toggle(ClientWorld world, BlockPos pos) {
        BlockPos key = canonical(world, pos);
        markDirty();
        if (this.containers.remove(key) != null) {
            return false;
        }
        this.containers.put(key, new LinkedContainer(key));
        return true;
    }

    public void link(ClientWorld world, BlockPos pos) {
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
    public int pruneMissing(ClientWorld world) {
        int before = this.containers.size();
        this.containers.values().removeIf(c -> world.isChunkLoaded(c.pos) && !isContainer(world, c.pos));
        int removed = before - this.containers.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    /** Links every chest, barrel and shulker box in loaded chunks within {@code radius} blocks. */
    public int scanNearby(ClientWorld world, BlockPos center, int radius) {
        int added = 0;
        int r2 = radius * radius;
        int minCx = (center.getX() - radius) >> 4;
        int maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4;
        int maxCz = (center.getZ() + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunk(cx, cz);
                if (!(chunk instanceof WorldChunk worldChunk)) {
                    continue;
                }
                for (BlockEntity be : new ArrayList<>(worldChunk.getBlockEntities().values())) {
                    if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity)) {
                        continue;
                    }
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(center) > r2) {
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

    /** Sum of everything we know is stored, across indexed containers. */
    public Map<Item, Integer> totals() {
        Map<Item, Integer> totals = new HashMap<>();
        for (LinkedContainer c : this.containers.values()) {
            c.items.forEach((item, count) -> totals.merge(item, count, Integer::sum));
        }
        return totals;
    }

    public int total(Item item) {
        int sum = 0;
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
        out.sort(Comparator.comparingDouble(c -> c.pos.getSquaredDistance(near)));
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
            ClientWorld world = MinecraftClient.getInstance().world;
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
