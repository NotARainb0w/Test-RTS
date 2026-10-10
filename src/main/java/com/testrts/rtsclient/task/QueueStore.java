package com.testrts.rtsclient.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Saves the order queue to a file and loads it back, so orders can be carried over to another
 * session. Orders hold world positions, so queues are kept per world (or server) and dimension,
 * under {@code config/rtsclient/queues/}.
 * <p>
 * Build and clear orders are saved block by block (a half-finished one carries on where it got to),
 * and area jobs (quarry, harvest, fell trees) and smelt/sort/store by what they were set up with.
 * Short helper steps a running order adds by itself (fetching materials, picking up drops, opening
 * chests) are left out; they come back when the order runs again.
 * <p>
 * When you leave a world with orders still queued, they are kept as {@link #LAST_SESSION}.
 */
public final class QueueStore {
    public static final String LAST_SESSION = "Last session";
    private static final Gson GSON = new GsonBuilder().create();

    /** The world we're in (or were in last), as {@link StorageManager#computeWorldKey}. */
    @Nullable
    private static String worldKey;

    /** A saved queue, as listed. */
    public record Saved(String name, Path file, int orders, long savedAt) {
    }

    private static final class SavedQueue {
        String name;
        long savedAt;
        List<SavedOrder> orders = new ArrayList<>();
    }

    private static final class SavedOrder {
        String type;
        String label;
        boolean clearing;
        boolean paused;
        int[] a;
        int[] b;
        boolean replant;
        List<String> palette;
        /** x, y, z, palette index - flattened. */
        List<Integer> data;
    }

    private QueueStore() {
    }

    /** Every client tick: keep track of the world; on arriving, mention orders left from last time. */
    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }
        String key = StorageManager.computeWorldKey(client);
        if (key.equals(worldKey)) {
            return;
        }
        worldKey = key;
        Saved last = find(LAST_SESSION);
        if (last != null && TaskQueue.get().isEmpty()) {
            Messages.info(last.orders() + " order(s) were still queued when you left - Load queue to carry on");
        }
    }

    /**
     * Save the current queue as {@code name} (replacing a saved queue of that name).
     *
     * @return {orders saved, orders that can't be saved}
     */
    public static int[] save(String name) throws IOException {
        if (worldKey == null) {
            throw new IOException("Not in a world");
        }
        SavedQueue q = new SavedQueue();
        q.name = name;
        q.savedAt = System.currentTimeMillis();
        int skipped = 0;
        TaskQueue tasks = TaskQueue.get();
        for (RtsTask t : tasks.snapshot()) {
            SavedOrder o = encode(t);
            if (o == null) {
                skipped++;
                continue;
            }
            o.paused = tasks.isPaused(t);
            q.orders.add(o);
        }
        if (q.orders.isEmpty()) {
            return new int[]{0, skipped};
        }
        Path file = dir().resolve(fileName(name));
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(q, w);
        }
        return new int[]{q.orders.size(), skipped};
    }

    /** The queue is about to be dropped (leaving the world, quitting): keep it as {@link #LAST_SESSION}. */
    public static void autosave() {
        if (worldKey == null || TaskQueue.get().isEmpty()) {
            return;
        }
        try {
            int[] r = save(LAST_SESSION);
            RtsClientMod.LOGGER.info("Kept {} queued order(s) as \"{}\"", r[0], LAST_SESSION);
        } catch (IOException | RuntimeException e) {
            RtsClientMod.LOGGER.warn("Could not keep the order queue", e);
        }
    }

    /** Saved queues for this world, newest first ({@link #LAST_SESSION} on top). */
    public static List<Saved> list() {
        List<Saved> out = new ArrayList<>();
        if (worldKey == null || !Files.isDirectory(dir())) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir())) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                try {
                    SavedQueue q = read(f);
                    out.add(new Saved(q.name, f, q.orders.size(), q.savedAt));
                } catch (IOException | RuntimeException e) {
                    RtsClientMod.LOGGER.warn("Could not read saved queue {}", f, e);
                }
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not list saved queues", e);
        }
        out.sort(Comparator.comparing((Saved s) -> !LAST_SESSION.equals(s.name())).thenComparing(s -> -s.savedAt()));
        return out;
    }

    @Nullable
    public static Saved find(String name) {
        for (Saved s : list()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Add a saved queue's orders to the end of the current queue. {@link #LAST_SESSION} is used up
     * by loading it.
     *
     * @return how many orders were added
     */
    public static int load(Saved saved) throws IOException {
        SavedQueue q = read(saved.file());
        int n = 0;
        for (SavedOrder o : q.orders) {
            RtsTask t = decode(o);
            if (t == null) {
                continue;
            }
            TaskQueue.get().add(t);
            if (o.paused) {
                TaskQueue.get().togglePause(t);
            }
            n++;
        }
        if (LAST_SESSION.equals(saved.name())) {
            delete(saved);
        }
        return n;
    }

    public static void delete(Saved saved) {
        try {
            Files.deleteIfExists(saved.file());
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not delete saved queue {}", saved.file(), e);
        }
    }

    // ---------------------------------------------------------------- orders <-> file

    @Nullable
    private static SavedOrder encode(RtsTask t) {
        SavedOrder o = new SavedOrder();
        o.label = t.label();
        if (t instanceof BuildTask build) {
            o.type = "build";
            o.clearing = build.clearing();
            o.palette = new ArrayList<>();
            o.data = new ArrayList<>();
            Map<BlockState, Integer> index = new HashMap<>();
            for (Map.Entry<BlockPos, BlockState> e : build.blocks().entrySet()) {
                Integer i = index.get(e.getValue());
                if (i == null) {
                    i = o.palette.size();
                    index.put(e.getValue(), i);
                    o.palette.add(Blueprint.stateToString(e.getValue()));
                }
                BlockPos p = e.getKey();
                o.data.add(p.getX());
                o.data.add(p.getY());
                o.data.add(p.getZ());
                o.data.add(i);
            }
        } else if (t instanceof QuarryTask quarry) {
            area(o, "quarry", quarry.min(), quarry.max());
        } else if (t instanceof HarvestTask harvest) {
            area(o, "harvest", harvest.min(), harvest.max());
        } else if (t instanceof TreeFellTask fell) {
            area(o, "fell", fell.min(), fell.max());
            o.replant = fell.replant();
        } else if (t instanceof SmeltTask) {
            o.type = "smelt";
        } else if (t instanceof SortStorageTask) {
            o.type = "sort";
        } else if (t instanceof DepositTask) {
            o.type = "deposit";
        } else {
            return null;
        }
        return o;
    }

    private static void area(SavedOrder o, String type, BlockPos min, BlockPos max) {
        o.type = type;
        o.a = new int[]{min.getX(), min.getY(), min.getZ()};
        o.b = new int[]{max.getX(), max.getY(), max.getZ()};
    }

    @Nullable
    private static RtsTask decode(SavedOrder o) {
        if (o.type == null) {
            return null;
        }
        return switch (o.type) {
            case "build" -> {
                if (o.palette == null || o.data == null) {
                    yield null;
                }
                List<BlockState> palette = new ArrayList<>();
                for (String s : o.palette) {
                    palette.add(Blueprint.stateFromString(s));
                }
                Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
                for (int i = 0; i + 3 < o.data.size(); i += 4) {
                    BlockState state = palette.get(o.data.get(i + 3));
                    // A block this game doesn't know (mod removed) reads as air: don't turn it into mining.
                    if (state.isAir() && !o.clearing) {
                        continue;
                    }
                    blocks.put(new BlockPos(o.data.get(i), o.data.get(i + 1), o.data.get(i + 2)), state);
                }
                yield blocks.isEmpty() ? null : new BuildTask(o.label, blocks, o.clearing);
            }
            case "quarry" -> o.a == null || o.b == null ? null : new QuarryTask(pos(o.a), pos(o.b));
            case "harvest" -> o.a == null || o.b == null ? null : new HarvestTask(pos(o.a), pos(o.b));
            case "fell" -> o.a == null || o.b == null ? null : new TreeFellTask(pos(o.a), pos(o.b), o.replant);
            case "smelt" -> new SmeltTask();
            case "sort" -> new SortStorageTask();
            case "deposit" -> new DepositTask();
            default -> null;
        };
    }

    private static BlockPos pos(int[] v) {
        return new BlockPos(v[0], v[1], v[2]);
    }

    private static SavedQueue read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            SavedQueue q = GSON.fromJson(r, SavedQueue.class);
            if (q == null || q.orders == null) {
                throw new IOException("Not a saved queue: " + file.getFileName());
            }
            if (q.name == null) {
                q.name = file.getFileName().toString().replace(".json", "");
            }
            return q;
        }
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtsclient").resolve("queues").resolve(worldKey);
    }

    private static String fileName(String name) {
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return (safe.isEmpty() ? "queue" : safe) + ".json";
    }
}
