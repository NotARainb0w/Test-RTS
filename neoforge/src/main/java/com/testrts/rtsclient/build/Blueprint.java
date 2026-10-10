package com.testrts.rtsclient.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.testrts.rtsclient.Platform;
import com.testrts.rtsclient.RtsClientMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** A captured region of blocks that can be pasted elsewhere, rotated. Saved under config/rtsclient/blueprints. */
public final class Blueprint {
    private static final Gson GSON = new GsonBuilder().create();

    private final String name;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    /** Positions relative to the minimum corner. */
    private final Map<BlockPos, BlockState> blocks;

    public Blueprint(String name, int sizeX, int sizeY, int sizeZ, Map<BlockPos, BlockState> blocks) {
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = blocks;
    }

    public String name() {
        return this.name;
    }

    public int blockCount() {
        return this.blocks.size();
    }

    public String size() {
        return this.sizeX + "x" + this.sizeY + "x" + this.sizeZ;
    }

    /** Footprint after {@code quarterTurns} clockwise turns: "W x L x H". */
    public String size(int quarterTurns) {
        boolean swapped = Math.floorMod(quarterTurns, 2) == 1;
        int w = swapped ? this.sizeZ : this.sizeX;
        int l = swapped ? this.sizeX : this.sizeZ;
        return w + " x " + l + " x " + this.sizeY;
    }

    private Map<net.minecraft.world.item.Item, Integer> materials;

    /** Items needed to build it, most-used first. */
    public Map<net.minecraft.world.item.Item, Integer> materials() {
        if (this.materials == null) {
            Map<net.minecraft.world.item.Item, Integer> counts = OrderPlanner.materials(this.blocks);
            Map<net.minecraft.world.item.Item, Integer> sorted = new LinkedHashMap<>();
            counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            this.materials = sorted;
        }
        return this.materials;
    }

    public Blueprint renamed(String newName) {
        return new Blueprint(newName, this.sizeX, this.sizeY, this.sizeZ, this.blocks);
    }

    /** Copy every non-air block between two corners. */
    public static Blueprint capture(ClientLevel world, BlockPos a, BlockPos b, String name, int limit) {
        int x0 = Math.min(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY());
        int z0 = Math.min(a.getZ(), b.getZ());
        int x1 = Math.max(a.getX(), b.getX());
        int y1 = Math.max(a.getY(), b.getY());
        int z1 = Math.max(a.getZ(), b.getZ());
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState s = world.getBlockState(m.set(x, y, z));
                    // Skip air and loose fluids; keep everything that has an item to place it with.
                    if (!s.isAir() && s.getBlock().asItem() != net.minecraft.world.item.Items.AIR) {
                        out.put(new BlockPos(x - x0, y - y0, z - z0), s);
                        if (out.size() >= limit) {
                            return new Blueprint(name, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, out);
                        }
                    }
                }
            }
        }
        return new Blueprint(name, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, out);
    }

    /** Where the minimum corner goes so the blueprint is centred on {@code cursor} (bottom on its level). */
    public BlockPos originFor(BlockPos cursor, int quarterTurns) {
        boolean swapped = Math.floorMod(quarterTurns, 2) == 1;
        int w = swapped ? this.sizeZ : this.sizeX;
        int l = swapped ? this.sizeX : this.sizeZ;
        return cursor.offset(-(w - 1) / 2, 0, -(l - 1) / 2);
    }

    public long volume() {
        return (long) this.sizeX * this.sizeY * this.sizeZ;
    }

    /**
     * The empty cells inside the blueprint's box, placed like {@link #placed}: pasting with "clear air"
     * mines whatever is there so the result matches the original exactly.
     */
    public java.util.List<BlockPos> placedAir(BlockPos origin, int quarterTurns) {
        return placedAir(origin, quarterTurns, false);
    }

    public java.util.List<BlockPos> placedAir(BlockPos origin, int quarterTurns, boolean mirror) {
        int r = Math.floorMod(quarterTurns, 4);
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = 0; y < this.sizeY; y++) {
            for (int x = 0; x < this.sizeX; x++) {
                for (int z = 0; z < this.sizeZ; z++) {
                    if (this.blocks.containsKey(m.set(x, y, z))) {
                        continue;
                    }
                    int mx = mirror ? this.sizeX - 1 - x : x;
                    out.add(origin.offset(rotX(mx, z, r), y, rotZ(mx, z, r)));
                }
            }
        }
        return out;
    }

    private int rotX(int x, int z, int r) {
        return switch (r) {
            case 1 -> this.sizeZ - 1 - z;
            case 2 -> this.sizeX - 1 - x;
            case 3 -> z;
            default -> x;
        };
    }

    private int rotZ(int x, int z, int r) {
        return switch (r) {
            case 1 -> x;
            case 2 -> this.sizeZ - 1 - z;
            case 3 -> this.sizeX - 1 - x;
            default -> z;
        };
    }

    /**
     * World positions and states when pasted with its minimum corner at {@code origin}, turned
     * {@code quarterTurns} times clockwise (seen from above).
     */
    public Map<BlockPos, BlockState> placed(BlockPos origin, int quarterTurns) {
        return placed(origin, quarterTurns, false);
    }

    /** @param mirror flip left-right (along X) before rotating */
    public Map<BlockPos, BlockState> placed(BlockPos origin, int quarterTurns, boolean mirror) {
        int r = Math.floorMod(quarterTurns, 4);
        Rotation rotation = switch (r) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        Map<BlockPos, BlockState> out = new LinkedHashMap<>(this.blocks.size() * 2);
        for (Map.Entry<BlockPos, BlockState> e : this.blocks.entrySet()) {
            BlockPos p = e.getKey();
            int x = mirror ? this.sizeX - 1 - p.getX() : p.getX();
            BlockState state = mirror ? e.getValue().mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK) : e.getValue();
            out.put(origin.offset(rotX(x, p.getZ(), r), p.getY(), rotZ(x, p.getZ(), r)), state.rotate(rotation));
        }
        return out;
    }

    // ---------------------------------------------------------------- files

    private static final class Saved {
        String name;
        int sx;
        int sy;
        int sz;
        List<String> palette = new ArrayList<>();
        /** x, y, z, palette index - flattened. */
        List<Integer> data = new ArrayList<>();
    }

    public static Path dir() {
        return Platform.configDir().resolve("rtsclient").resolve("blueprints");
    }

    private static String fileName(String name) {
        String safe = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return (safe.isEmpty() ? "blueprint" : safe) + ".json";
    }

    public void save() {
        Saved s = new Saved();
        s.name = this.name;
        s.sx = this.sizeX;
        s.sy = this.sizeY;
        s.sz = this.sizeZ;
        Map<BlockState, Integer> index = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : this.blocks.entrySet()) {
            Integer i = index.get(e.getValue());
            if (i == null) {
                i = s.palette.size();
                index.put(e.getValue(), i);
                s.palette.add(NbtUtils.writeBlockState(e.getValue()).toString());
            }
            s.data.add(e.getKey().getX());
            s.data.add(e.getKey().getY());
            s.data.add(e.getKey().getZ());
            s.data.add(i);
        }
        Path file = dir().resolve(fileName(this.name));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(s, w);
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not save blueprint {}", file, e);
        }
    }

    public static List<Blueprint> loadAll() {
        List<Blueprint> out = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json")).sorted()::iterator) {
                Blueprint bp = load(f);
                if (bp != null) {
                    out.add(bp);
                }
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not list blueprints", e);
        }
        return out;
    }

    private static Blueprint load(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            Saved s = GSON.fromJson(r, Saved.class);
            if (s == null || s.palette == null || s.data == null) {
                return null;
            }
            List<BlockState> palette = new ArrayList<>();
            for (String snbt : s.palette) {
                palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), TagParser.parseTag(snbt)));
            }
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (int i = 0; i + 3 < s.data.size(); i += 4) {
                blocks.put(new BlockPos(s.data.get(i), s.data.get(i + 1), s.data.get(i + 2)), palette.get(s.data.get(i + 3)));
            }
            return new Blueprint(s.name, s.sx, s.sy, s.sz, blocks);
        } catch (Exception e) {
            RtsClientMod.LOGGER.warn("Could not read blueprint {}", file, e);
            return null;
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(dir().resolve(fileName(this.name)));
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not delete blueprint {}", this.name, e);
        }
    }
}
