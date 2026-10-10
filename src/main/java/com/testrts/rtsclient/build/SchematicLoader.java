package com.testrts.rtsclient.build;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads schematic files into a {@link Blueprint}:
 * <ul>
 *   <li>{@code .litematic} (Litematica, all regions merged)</li>
 *   <li>{@code .schem} (Sponge v2/v3, WorldEdit and FAWE)</li>
 *   <li>{@code .nbt} (vanilla structure block files)</li>
 * </ul>
 * Block entity contents (chest items, sign text) are not imported; only the blocks.
 */
public final class SchematicLoader {
    public static final String[] EXTENSIONS = {".litematic", ".schem", ".nbt"};

    private SchematicLoader() {
    }

    public static boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** @throws IOException for unreadable or unsupported files, or ones over {@code limit} blocks */
    public static Blueprint load(Path file, int limit) throws IOException {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        NbtCompound root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in);
        }
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        if (lower.endsWith(".litematic")) {
            litematic(root, blocks, limit);
        } else if (lower.endsWith(".schem")) {
            sponge(root.contains("Schematic", NbtElement.COMPOUND_TYPE) ? root.getCompound("Schematic") : root, blocks, limit);
        } else if (lower.endsWith(".nbt")) {
            structure(root, blocks, limit);
        } else {
            throw new IOException("Unsupported file type: " + fileName);
        }
        if (blocks.isEmpty()) {
            throw new IOException("No blocks in " + fileName);
        }
        return normalize(name, blocks);
    }

    /** Shift everything so the minimum corner is 0,0,0, and work out the size. */
    private static Blueprint normalize(String name, Map<BlockPos, BlockState> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks.keySet()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>(blocks.size() * 2);
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            out.put(new BlockPos(p.getX() - minX, p.getY() - minY, p.getZ() - minZ), e.getValue());
        }
        return new Blueprint(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, out);
    }

    private static void put(Map<BlockPos, BlockState> blocks, int x, int y, int z, BlockState state, int limit) throws IOException {
        if (state.isAir()) {
            return;
        }
        blocks.put(new BlockPos(x, y, z), state);
        if (blocks.size() > limit) {
            throw new IOException("Schematic has more than " + limit + " blocks (raise Max shape size in Settings)");
        }
    }

    private static BlockState paletteState(NbtCompound entry) {
        try {
            return NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), entry);
        } catch (RuntimeException e) {
            return Blocks.AIR.getDefaultState();
        }
    }

    // ------------------------------------------------------------------ Litematica

    private static void litematic(NbtCompound root, Map<BlockPos, BlockState> blocks, int limit) throws IOException {
        NbtCompound regions = root.getCompound("Regions");
        for (String key : regions.getKeys()) {
            NbtCompound region = regions.getCompound(key);
            NbtCompound pos = region.getCompound("Position");
            NbtCompound size = region.getCompound("Size");
            int sx = size.getInt("x");
            int sy = size.getInt("y");
            int sz = size.getInt("z");
            int ax = Math.abs(sx);
            int ay = Math.abs(sy);
            int az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) {
                continue;
            }
            // Negative sizes mean the region extends the other way from its position.
            int minX = pos.getInt("x") + (sx < 0 ? sx + 1 : 0);
            int minY = pos.getInt("y") + (sy < 0 ? sy + 1 : 0);
            int minZ = pos.getInt("z") + (sz < 0 ? sz + 1 : 0);

            NbtList paletteNbt = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
            BlockState[] palette = new BlockState[paletteNbt.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = paletteState(paletteNbt.getCompound(i));
            }
            if (palette.length == 0) {
                continue;
            }
            long[] data = region.getLongArray("BlockStates");
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.length - 1));
            long mask = (1L << bits) - 1L;
            long volume = (long) ax * ay * az;
            long layer = (long) ax * az;
            for (long index = 0; index < volume; index++) {
                int value = (int) packedAt(data, index, bits, mask);
                if (value < 0 || value >= palette.length) {
                    continue;
                }
                int y = (int) (index / layer);
                int rem = (int) (index % layer);
                int z = rem / ax;
                int x = rem % ax;
                put(blocks, minX + x, minY + y, minZ + z, palette[value], limit);
            }
        }
    }

    /** Litematica packs entries back to back, so one entry may span two longs. */
    private static long packedAt(long[] data, long index, int bits, long mask) {
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) (((index + 1) * bits - 1) >> 6);
        int startBit = (int) (startOffset & 63);
        if (startArr >= data.length || endArr >= data.length) {
            return 0;
        }
        if (startArr == endArr) {
            return data[startArr] >>> startBit & mask;
        }
        int endOffset = 64 - startBit;
        return (data[startArr] >>> startBit | data[endArr] << endOffset) & mask;
    }

    // ------------------------------------------------------------------ Sponge (.schem)

    private static void sponge(NbtCompound root, Map<BlockPos, BlockState> blocks, int limit) throws IOException {
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        NbtCompound paletteNbt;
        byte[] data;
        if (root.contains("Blocks", NbtElement.COMPOUND_TYPE)) {
            // Version 3
            NbtCompound container = root.getCompound("Blocks");
            paletteNbt = container.getCompound("Palette");
            data = container.getByteArray("Data");
        } else {
            paletteNbt = root.getCompound("Palette");
            data = root.getByteArray("BlockData");
        }
        Map<Integer, BlockState> palette = new java.util.HashMap<>();
        for (String key : paletteNbt.getKeys()) {
            palette.put(paletteNbt.getInt(key), parseState(key));
        }
        int index = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            int y = index / (width * length);
            int rem = index % (width * length);
            int z = rem / width;
            int x = rem % width;
            if (y < height) {
                BlockState state = palette.get(value);
                if (state != null) {
                    put(blocks, x, y, z, state, limit);
                }
            }
            index++;
        }
    }

    private static BlockState parseState(String text) {
        try {
            return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), text, false).blockState();
        } catch (Exception e) {
            return Blocks.AIR.getDefaultState();
        }
    }

    // ------------------------------------------------------------------ vanilla structure (.nbt)

    private static void structure(NbtCompound root, Map<BlockPos, BlockState> blocks, int limit) throws IOException {
        NbtList paletteNbt;
        if (root.contains("palette", NbtElement.LIST_TYPE)) {
            paletteNbt = root.getList("palette", NbtElement.COMPOUND_TYPE);
        } else {
            NbtList palettes = root.getList("palettes", NbtElement.LIST_TYPE);
            paletteNbt = palettes.isEmpty() ? new NbtList() : palettes.getList(0);
        }
        BlockState[] palette = new BlockState[paletteNbt.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = paletteState(paletteNbt.getCompound(i));
        }
        NbtList list = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            int state = entry.getInt("state");
            NbtList pos = entry.getList("pos", NbtElement.INT_TYPE);
            if (state < 0 || state >= palette.length || pos.size() < 3) {
                continue;
            }
            BlockState s = palette[state];
            // Structure voids mean "leave whatever is there".
            if (s.isOf(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            put(blocks, pos.getInt(0), pos.getInt(1), pos.getInt(2), s, limit);
        }
    }
}
