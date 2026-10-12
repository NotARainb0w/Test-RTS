package com.testrts.rtsclient.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.TaskQueue;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Protected areas: rectangles (at every height) where the builder never breaks a block, so it can't
 * dig through a base. Placing into empty space there is still fine. Saved per world and dimension.
 */
public final class Protection {
    private static final Protection INSTANCE = new Protection();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<List<Area>>() {
    }.getType();

    /** A rectangle in x/z, protected from the bottom of the world to the top. */
    public record Area(int minX, int minZ, int maxX, int maxZ) {
        public static Area of(BlockPos a, BlockPos b) {
            return new Area(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
        }

        public boolean contains(int x, int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }

        public int width() {
            return this.maxX - this.minX + 1;
        }

        public int depth() {
            return this.maxZ - this.minZ + 1;
        }
    }

    private final List<Area> areas = new ArrayList<>();
    private String worldKey;

    public static Protection get() {
        return INSTANCE;
    }

    /** Called every client tick: follows world/dimension changes. */
    public void tick(MinecraftClient client) {
        String key = client.world == null ? null : StorageManager.computeWorldKey(client);
        if (key == null ? this.worldKey != null : !key.equals(this.worldKey)) {
            this.areas.clear();
            this.worldKey = key;
            if (key != null) {
                load();
            }
        }
    }

    public List<Area> areas() {
        return Collections.unmodifiableList(this.areas);
    }

    public boolean isProtected(BlockPos pos) {
        for (Area a : this.areas) {
            if (a.contains(pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Breaking {@code pos} must be refused: it's protected and RTS orders are running (the player's own
     * mining is left alone when nothing is queued).
     */
    public boolean blocksBreaking(BlockPos pos) {
        return !this.areas.isEmpty() && TaskQueue.get().isStarted() && !TaskQueue.get().isEmpty() && isProtected(pos);
    }

    @Nullable
    public Area areaAt(BlockPos pos) {
        for (Area a : this.areas) {
            if (a.contains(pos.getX(), pos.getZ())) {
                return a;
            }
        }
        return null;
    }

    public void add(Area area) {
        this.areas.add(area);
        save();
    }

    public void remove(Area area) {
        if (this.areas.remove(area)) {
            save();
        }
    }

    public void clear() {
        this.areas.clear();
        save();
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtsclient").resolve("storage").resolve(this.worldKey + ".protect.json");
    }

    private void load() {
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<Area> saved = GSON.fromJson(reader, FILE_TYPE);
            if (saved != null) {
                this.areas.addAll(saved);
            }
        } catch (IOException | RuntimeException e) {
            RtsClientMod.LOGGER.warn("Could not read protected areas {}", file, e);
        }
    }

    private void save() {
        if (this.worldKey == null) {
            return;
        }
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this.areas, FILE_TYPE, writer);
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not write protected areas {}", file, e);
        }
    }
}
