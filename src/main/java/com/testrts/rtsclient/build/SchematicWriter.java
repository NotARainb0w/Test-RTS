package com.testrts.rtsclient.build;

import net.minecraft.SharedConstants;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link Blueprint} as a schematic file other tools can open, the counterpart of
 * {@link SchematicLoader}:
 * <ul>
 *   <li>{@code .schem}: Sponge schematic v3 (WorldEdit, FAWE, Litematica, Axiom...)</li>
 *   <li>{@code .nbt}: vanilla structure file (structure blocks, Create schematics...)</li>
 * </ul>
 * Only blocks are written; no chest contents or entities.
 */
public final class SchematicWriter {
    public enum Format {
        SCHEM(".schem", "WorldEdit .schem"),
        NBT(".nbt", "structure .nbt");

        private final String extension;
        private final String label;

        Format(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }

        public String extension() {
            return this.extension;
        }

        public String label() {
            return this.label;
        }
    }

    private SchematicWriter() {
    }

    /**
     * Write {@code bp} into {@code dir} as {@code name} plus the format's extension. An existing file
     * is never overwritten: "-2", "-3"... is added instead.
     *
     * @return the file written
     */
    public static Path write(Blueprint bp, Path dir, String name, Format format) throws IOException {
        Files.createDirectories(dir);
        String base = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.isEmpty()) {
            base = "schematic";
        }
        Path file = dir.resolve(base + format.extension());
        for (int i = 2; Files.exists(file); i++) {
            file = dir.resolve(base + "-" + i + format.extension());
        }
        NbtCompound root = format == Format.SCHEM ? sponge(bp, name) : structure(bp);
        try (OutputStream out = Files.newOutputStream(file)) {
            NbtIo.writeCompressed(root, out);
        }
        return file;
    }

    private static int dataVersion() {
        return SharedConstants.getGameVersion().getSaveVersion().getId();
    }

    /** Sponge v3: { Schematic: { Version, DataVersion, Width, Height, Length, Offset, Blocks: {Palette, Data} } }. */
    private static NbtCompound sponge(Blueprint bp, String name) {
        BlockState air = Blocks.AIR.getDefaultState();
        NbtCompound palette = new NbtCompound();
        Map<BlockState, Integer> ids = new HashMap<>();
        ids.put(air, 0);
        palette.putInt(BlockArgumentParser.stringifyBlockState(air), 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream(bp.sizeX() * bp.sizeY() * bp.sizeZ());
        Map<BlockPos, BlockState> blocks = bp.blocks();
        BlockPos.Mutable m = new BlockPos.Mutable();
        // Index order is x fastest, then z, then y.
        for (int y = 0; y < bp.sizeY(); y++) {
            for (int z = 0; z < bp.sizeZ(); z++) {
                for (int x = 0; x < bp.sizeX(); x++) {
                    BlockState state = blocks.getOrDefault(m.set(x, y, z), air);
                    Integer id = ids.get(state);
                    if (id == null) {
                        id = ids.size();
                        ids.put(state, id);
                        palette.putInt(BlockArgumentParser.stringifyBlockState(state), id);
                    }
                    writeVarInt(data, id);
                }
            }
        }
        NbtCompound container = new NbtCompound();
        container.put("Palette", palette);
        container.putByteArray("Data", data.toByteArray());
        container.put("BlockEntities", new NbtList());

        NbtCompound meta = new NbtCompound();
        meta.putString("Name", name);
        meta.putString("Author", "RTS Client Builder");
        meta.putLong("Date", System.currentTimeMillis());

        NbtCompound schem = new NbtCompound();
        schem.putInt("Version", 3);
        schem.putInt("DataVersion", dataVersion());
        schem.putShort("Width", (short) bp.sizeX());
        schem.putShort("Height", (short) bp.sizeY());
        schem.putShort("Length", (short) bp.sizeZ());
        schem.putIntArray("Offset", new int[]{0, 0, 0});
        schem.put("Metadata", meta);
        schem.put("Blocks", container);

        NbtCompound root = new NbtCompound();
        root.put("Schematic", schem);
        return root;
    }

    /** Vanilla structure: { DataVersion, size, palette: [{Name, Properties}], blocks: [{pos, state}], entities }. */
    private static NbtCompound structure(Blueprint bp) {
        NbtList palette = new NbtList();
        Map<BlockState, Integer> ids = new HashMap<>();
        NbtList blocks = new NbtList();
        for (Map.Entry<BlockPos, BlockState> e : bp.blocks().entrySet()) {
            Integer id = ids.get(e.getValue());
            if (id == null) {
                id = ids.size();
                ids.put(e.getValue(), id);
                palette.add(NbtHelper.fromBlockState(e.getValue()));
            }
            BlockPos p = e.getKey();
            NbtCompound entry = new NbtCompound();
            entry.put("pos", ints(p.getX(), p.getY(), p.getZ()));
            entry.putInt("state", id);
            blocks.add(entry);
        }
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", dataVersion());
        root.put("size", ints(bp.sizeX(), bp.sizeY(), bp.sizeZ()));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new NbtList());
        return root;
    }

    private static NbtList ints(int a, int b, int c) {
        NbtList list = new NbtList();
        list.add(NbtInt.of(a));
        list.add(NbtInt.of(b));
        list.add(NbtInt.of(c));
        return list;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** "my_house.schem" style suggestion from a blueprint name. */
    public static String suggestName(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "schematic" : s;
    }
}
