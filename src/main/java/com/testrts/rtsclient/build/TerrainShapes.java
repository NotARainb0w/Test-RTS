package com.testrts.rtsclient.build;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.world.LightType;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans for the newer Terrain modes (road, room, farm, mob-proofing, hill, lake, river, terrace, cave, island
 * bridge, decorate): blocks to mine first, blocks to place after, and spots to use an item on (hoe, seeds,
 * water bucket). Everything reads the world as it is, so the preview shows exactly what gets done.
 */
public final class TerrainShapes {
    private TerrainShapes() {
    }

    /**
     * @param clear  blocks to mine (top down)
     * @param fill   blocks to place (bottom up)
     * @param till   ground to hoe into farmland
     * @param seeds  farmland to plant on
     * @param water  spots to fill with water (water buckets)
     */
    public record Plan(Map<BlockPos, BlockState> clear, Map<BlockPos, BlockState> fill, List<BlockPos> till,
                       List<BlockPos> seeds, List<BlockPos> water, boolean truncated) {
        static Plan of(Map<BlockPos, BlockState> clear, Map<BlockPos, BlockState> fill) {
            return new Plan(clear, fill, List.of(), List.of(), List.of(), false);
        }

        public boolean isEmpty() {
            return this.clear.isEmpty() && this.fill.isEmpty() && this.till.isEmpty() && this.seeds.isEmpty() && this.water.isEmpty();
        }
    }

    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    /** Most blocks one terrain order may touch, so a huge drag can't freeze planning. */
    private static final int LIMIT = 60_000;

    /** The plan of one of the newer Terrain modes (Restyle and Island bridge are planned elsewhere). */
    public static Plan plan(TerrainMode mode, ClientWorld world, BlockPos a, BlockPos b, int size, BlockState brush) {
        return reconcile(world, switch (mode) {
            case ROAD -> road(world, a, b, Math.max(1, size), brush);
            case ROOM -> room(world, a, b, brush);
            case FARM -> farm(world, a, b);
            case MOB_PROOF -> mobProof(world, a, b, brush);
            case HILL -> hill(world, a, b, Math.max(1, size));
            case LAKE -> lake(world, a, b, Math.max(1, size));
            case RIVER -> river(world, a, b, Math.max(1, size));
            case TERRACE -> terrace(world, a, b, Math.max(1, size));
            case CAVE -> cave(world, a, b, Math.max(2, size));
            case DECORATE -> decorate(world, a, b, Math.max(1, size));
            default -> Plan.of(Map.of(), Map.of());
        });
    }

    /**
     * Every spot to build on that something solid is in now (grass where the road goes, the side of a hill a terrace
     * cuts into) is mined first: the builder doesn't mine a different block out of the way to put its own in.
     */
    private static Plan reconcile(ClientWorld world, Plan p) {
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>(p.clear());
        for (Map.Entry<BlockPos, BlockState> e : p.fill().entrySet()) {
            BlockPos f = e.getKey();
            BlockState s = world.getBlockState(f);
            if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty() && !s.isOf(e.getValue().getBlock())
                    && s.getHardness(world, f) >= 0 && !Protection.get().isProtected(f)) {
                clear.put(f, AIR);
            }
        }
        return new Plan(clear, p.fill(), p.till(), p.seeds(), p.water(), p.truncated());
    }

    /** Modes planned by {@link #plan}. */
    public static boolean planned(TerrainMode mode) {
        return switch (mode) {
            case ROAD, ROOM, FARM, MOB_PROOF, HILL, LAKE, RIVER, TERRACE, CAVE, DECORATE -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ helpers

    /** The top solid (non-foliage, non-plant) block of a column, near {@code nearY}. */
    public static int groundY(ClientWorld world, int x, int z, int nearY) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.Mutable m = new BlockPos.Mutable(x, top, z);
        for (int i = 0; i < 48 && m.getY() > world.getBottomY(); i++) {
            BlockState s = world.getBlockState(m);
            if (!s.isAir() && !s.isReplaceable() && !s.isIn(BlockTags.LOGS) && !s.isIn(BlockTags.LEAVES) && s.getFluidState().isEmpty()) {
                return m.getY();
            }
            m.move(Direction.DOWN);
        }
        return nearY;
    }

    /** Natural ground the terrain modes may dig or reshape (not buildings, ores aside). */
    static boolean natural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.SAND) || s.isOf(Blocks.GRAVEL)
                || s.isOf(Blocks.CLAY) || s.isIn(BlockTags.SNOW) || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)
                || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS) || s.isIn(BlockTags.REPLACEABLE)
                || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.COPPER_ORES)
                || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.TERRACOTTA) || s.isOf(Blocks.CALCITE) || s.isOf(Blocks.MUD);
    }

    /** Full block, stairs, slab and wall of the brush's material (whatever of them exists). */
    public record Family(BlockState full, @Nullable BlockState stairs, @Nullable BlockState slab, @Nullable BlockState wall) {
    }

    public static Family family(BlockState brush) {
        Block block = brush.getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        String ns = id.getNamespace();
        String path = id.getPath();
        String base = path;
        for (String suffix : new String[]{"_stairs", "_slab", "_wall"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        String stem = base.endsWith("_planks") ? base.substring(0, base.length() - "_planks".length())
                : base.endsWith("_bricks") ? base.substring(0, base.length() - 1)
                : base.endsWith("_tiles") ? base.substring(0, base.length() - 1)
                : base.endsWith("s") && !base.endsWith("ss") ? base.substring(0, base.length() - 1) : base;
        BlockState full = block instanceof StairsBlock || block instanceof SlabBlock || block instanceof WallBlock
                ? first(ns, base, base + "s", base + "_planks", stem + "s", stem) : brush;
        if (full == null) {
            full = brush;
        }
        return new Family(full, first(ns, base + "_stairs", stem + "_stairs"), first(ns, base + "_slab", stem + "_slab"),
                first(ns, base + "_wall", stem + "_wall"));
    }

    @Nullable
    private static BlockState first(String ns, String... paths) {
        for (String p : paths) {
            Identifier id = Identifier.tryParse(ns + ":" + p);
            if (id != null && Registries.BLOCK.containsId(id)) {
                return Registries.BLOCK.get(id).getDefaultState();
            }
        }
        return null;
    }

    /** Stable 0..n-1 noise per position. */
    static int hash(int x, int y, int z, int n) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return Math.floorMod(h ^ h >>> 15, n);
    }

    /** Cells of a straight line from a to b in x/z (Bresenham), with their index. */
    static List<int[]> line(int x0, int z0, int x1, int z1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            out.add(new int[]{x, z});
            if (x == x1 && z == z1 || out.size() > 4096) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
        return out;
    }

    private static void clearAt(ClientWorld world, Map<BlockPos, BlockState> clear, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (!s.isAir() && s.getFluidState().isEmpty() && s.getHardness(world, p) >= 0
                && !Protection.get().isProtected(p) && (natural(s) || s.isReplaceable())) {
            clear.put(p.toImmutable(), AIR);
        }
    }

    private static void fillAt(ClientWorld world, Map<BlockPos, BlockState> fill, BlockPos p, BlockState st) {
        BlockState s = world.getBlockState(p);
        if ((s.isAir() || s.isReplaceable() || natural(s) && !s.isOf(st.getBlock())) && !Protection.get().isProtected(p)) {
            fill.put(p.toImmutable(), st);
        }
    }

    // ------------------------------------------------------------------ road

    /**
     * A road from a to b, {@code width} wide, following the ground: smoothed so it never climbs more than a
     * block per step (stairs where it does), borders either side with lanterns every 8 blocks, and a bridge
     * on posts where the ground drops away or there's water.
     */
    public static Plan road(ClientWorld world, BlockPos a, BlockPos b, int width, BlockState brush) {
        Family f = family(brush.isAir() ? Blocks.COBBLESTONE.getDefaultState() : brush);
        BlockState border = Blocks.POLISHED_ANDESITE.getDefaultState();
        List<int[]> cells = line(a.getX(), a.getZ(), b.getX(), b.getZ());
        int n = cells.size();
        int[] ground = new int[n];
        boolean[] gap = new boolean[n];
        for (int i = 0; i < n; i++) {
            int[] c = cells.get(i);
            ground[i] = groundY(world, c[0], c[1], a.getY());
            BlockPos above = new BlockPos(c[0], ground[i] + 1, c[1]);
            gap[i] = !world.getBlockState(above).getFluidState().isEmpty();
        }
        // Deck heights: follow the ground, a block per step at most; over water or a drop, stay level (a bridge).
        int[] deck = new int[n];
        deck[0] = a.getY() - 1 >= ground[0] - 1 && Math.abs(a.getY() - 1 - ground[0]) <= 2 ? ground[0] : a.getY() - 1;
        for (int i = 1; i < n; i++) {
            int want = gap[i] || ground[i] < deck[i - 1] - 3 ? deck[i - 1] : ground[i];
            deck[i] = Math.max(deck[i - 1] - 1, Math.min(deck[i - 1] + 1, want));
        }
        boolean xMajor = Math.abs(b.getX() - a.getX()) >= Math.abs(b.getZ() - a.getZ());
        Direction forward = xMajor ? (b.getX() >= a.getX() ? Direction.EAST : Direction.WEST)
                : (b.getZ() >= a.getZ() ? Direction.SOUTH : Direction.NORTH);
        int half = Math.max(0, (width - 1) / 2);
        int hi = width - 1 - half;
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        for (int i = 0; i < n && fill.size() < LIMIT; i++) {
            int[] c = cells.get(i);
            int y = deck[i];
            boolean bridge = deck[i] > ground[i] + 2 || gap[i];
            boolean up = i + 1 < n && deck[i + 1] > y;
            boolean downNext = i > 0 && deck[i - 1] > y;
            for (int w = -half - 1; w <= hi + 1; w++) {
                int x = xMajor ? c[0] : c[0] + w;
                int z = xMajor ? c[1] + w : c[1];
                BlockPos p = new BlockPos(x, y, z);
                boolean edge = w == -half - 1 || w == hi + 1;
                for (int k = 1; k <= 3; k++) {
                    clearAt(world, clear, p.up(k));
                }
                fillAt(world, fill, p, edge ? border : f.full());
                if (!edge && f.stairs() != null && (up || downNext)) {
                    // A step: stairs on top of the lower deck, climbing toward the higher one.
                    Direction face = up ? forward : forward.getOpposite();
                    fill.put(p.up(), f.stairs().with(Properties.HORIZONTAL_FACING, face)
                            .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM));
                    clear.remove(p.up());
                }
                if (edge) {
                    if (bridge) {
                        fill.put(p.up(), Blocks.SPRUCE_FENCE.getDefaultState());
                        clear.remove(p.up());
                    }
                    if (i % 8 == 0) {
                        BlockPos post = bridge ? p.up(2) : p.up();
                        if (bridge) {
                            fill.put(p.up(), Blocks.SPRUCE_FENCE.getDefaultState());
                        } else {
                            fill.put(post, Blocks.SPRUCE_FENCE.getDefaultState());
                            post = post.up();
                        }
                        fill.put(post, Blocks.LANTERN.getDefaultState());
                        clear.remove(post);
                    }
                }
                // Bridge posts down to the ground every 6 blocks; elsewhere fill the bit under a raised deck.
                if (bridge && (i % 6 == 0 && (w == -half || w == hi))) {
                    for (int yy = y - 1; yy > ground[i] && yy > y - 24; yy--) {
                        fillAt(world, fill, new BlockPos(x, yy, z), Blocks.STONE_BRICKS.getDefaultState());
                    }
                } else if (!bridge) {
                    for (int yy = y - 1; yy > ground[i]; yy--) {
                        fillAt(world, fill, new BlockPos(x, yy, z), Blocks.DIRT.getDefaultState());
                    }
                }
            }
        }
        fill.keySet().forEach(clear::remove);
        return Plan.of(clear, fill);
    }

    // ------------------------------------------------------------------ room

    /**
     * Furnish the room between two floor corners: floor where it's missing, a lantern in each corner, torches
     * on the walls every 4 blocks, and chests along the wall at the far side from the first corner.
     */
    public static Plan room(ClientWorld world, BlockPos a, BlockPos b, BlockState brush) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int floorY = Math.min(a.getY(), b.getY());
        BlockState floor = brush.isAir() ? Blocks.SPRUCE_PLANKS.getDefaultState() : brush;
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos p = new BlockPos(x, floorY, z);
                BlockState s = world.getBlockState(p);
                if (s.isAir() || s.isReplaceable()) {
                    fill.put(p, floor);
                }
            }
        }
        // Corner lanterns.
        for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            BlockPos p = new BlockPos(c[0], floorY + 1, c[1]);
            if (world.getBlockState(p).isAir()) {
                fill.put(p, Blocks.LANTERN.getDefaultState());
            }
        }
        // Torches on the walls round the room, every 4 blocks, at head height.
        int y = floorY + 2;
        for (int x = x0; x <= x1; x += 4) {
            torch(world, fill, new BlockPos(x, y, z0), Direction.NORTH);
            torch(world, fill, new BlockPos(x, y, z1), Direction.SOUTH);
        }
        for (int z = z0; z <= z1; z += 4) {
            torch(world, fill, new BlockPos(x0, y, z), Direction.WEST);
            torch(world, fill, new BlockPos(x1, y, z), Direction.EAST);
        }
        // Chests along the far wall from the first corner, a crafting table at the end.
        boolean farZ = Math.abs(b.getZ() - a.getZ()) >= Math.abs(b.getX() - a.getX());
        int wallZ = b.getZ() >= a.getZ() ? z1 : z0;
        int wallX = b.getX() >= a.getX() ? x1 : x0;
        Direction facing = farZ ? (wallZ == z1 ? Direction.NORTH : Direction.SOUTH) : (wallX == x1 ? Direction.WEST : Direction.EAST);
        int from = farZ ? x0 + 1 : z0 + 1;
        int to = farZ ? x1 - 1 : z1 - 1;
        for (int i = from; i <= to; i++) {
            BlockPos p = farZ ? new BlockPos(i, floorY + 1, wallZ) : new BlockPos(wallX, floorY + 1, i);
            if (!world.getBlockState(p).isAir()) {
                continue;
            }
            fill.put(p, i == to ? Blocks.CRAFTING_TABLE.getDefaultState()
                    : Blocks.CHEST.getDefaultState().with(Properties.HORIZONTAL_FACING, facing));
        }
        return Plan.of(Map.of(), fill);
    }

    /** A wall torch in {@code p} on the wall beyond it in direction {@code wall}, if there is one. */
    private static void torch(ClientWorld world, Map<BlockPos, BlockState> fill, BlockPos p, Direction wall) {
        BlockPos behind = p.offset(wall);
        if (world.getBlockState(p).isAir() && world.getBlockState(behind).isSideSolidFullSquare(world, behind, wall.getOpposite())) {
            fill.putIfAbsent(p, Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, wall.getOpposite()));
        }
    }

    // ------------------------------------------------------------------ farm

    /**
     * A field between two corners at the ground's height: water channels every 9 rows (each wets 4 rows
     * either side), the rest dirt to be tilled and sown, a fence round it with a gate on the first corner's side
     * and torches on the corner posts.
     */
    public static Plan farm(ClientWorld world, BlockPos a, BlockPos b) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int y = Math.min(a.getY(), b.getY());
        boolean rowsAlongX = x1 - x0 >= z1 - z0;
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        List<BlockPos> till = new ArrayList<>();
        List<BlockPos> seeds = new ArrayList<>();
        List<BlockPos> water = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int across = rowsAlongX ? z - z0 : x - x0;
                BlockPos p = new BlockPos(x, y, z);
                for (int k = 1; k <= 2; k++) {
                    clearAt(world, clear, p.up(k));
                }
                if (across % 9 == 4) {
                    // Water channel: dig it, keep a floor under it, the bucket fills it.
                    clearAt(world, clear, p);
                    fillAt(world, fill, p.down(), Blocks.DIRT.getDefaultState());
                    water.add(p);
                } else {
                    BlockState s = world.getBlockState(p);
                    if (!s.isOf(Blocks.DIRT) && !s.isOf(Blocks.GRASS_BLOCK) && !s.isOf(Blocks.FARMLAND)) {
                        if (!s.isAir() && !s.isReplaceable()) {
                            clearAt(world, clear, p);
                        }
                        fill.put(p, Blocks.DIRT.getDefaultState());
                    }
                    if (!s.isOf(Blocks.FARMLAND)) {
                        till.add(p);
                    }
                    seeds.add(p);
                }
            }
        }
        // Fence ring one block out, a gate in the middle of the first corner's side.
        int gx = (x0 + x1) / 2;
        int gz = (z0 + z1) / 2;
        boolean gateOnZ = rowsAlongX;
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                if (x != x0 - 1 && x != x1 + 1 && z != z0 - 1 && z != z1 + 1) {
                    continue;
                }
                BlockPos p = new BlockPos(x, y + 1, z);
                boolean corner = (x == x0 - 1 || x == x1 + 1) && (z == z0 - 1 || z == z1 + 1);
                boolean gate = gateOnZ ? x == gx && z == (a.getZ() <= b.getZ() ? z0 - 1 : z1 + 1)
                        : z == gz && x == (a.getX() <= b.getX() ? x0 - 1 : x1 + 1);
                clearAt(world, clear, p);
                if (world.getBlockState(p.down()).isAir()) {
                    fillAt(world, fill, p.down(), Blocks.DIRT.getDefaultState());
                }
                fill.put(p, gate ? Blocks.OAK_FENCE_GATE.getDefaultState().with(Properties.HORIZONTAL_FACING,
                        gateOnZ ? Direction.SOUTH : Direction.EAST) : Blocks.OAK_FENCE.getDefaultState());
                if (corner) {
                    fill.put(p.up(), Blocks.TORCH.getDefaultState());
                }
            }
        }
        fill.keySet().forEach(clear::remove);
        return new Plan(clear, fill, till, seeds, water, false);
    }

    // ------------------------------------------------------------------ mob-proofing

    /**
     * Every dark spot in the area a mob could spawn on gets a slab (or carpet or button: the brush, if it's
     * one of those; else smooth stone slabs): nothing spawns on a bottom slab, carpet or button.
     */
    public static Plan mobProof(ClientWorld world, BlockPos a, BlockPos b, BlockState brush) {
        Block bb = brush.getBlock();
        BlockState cover = bb instanceof SlabBlock ? brush.with(Properties.SLAB_TYPE, SlabType.BOTTOM)
                : brush.isIn(BlockTags.WOOL_CARPETS) || bb instanceof net.minecraft.block.ButtonBlock ? brush
                : Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        int y0 = Math.min(a.getY(), b.getY()) - 2;
        int y1 = Math.max(a.getY(), b.getY()) + 6;
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = y0; y <= y1 && fill.size() < 4000; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    m.set(x, y, z);
                    BlockPos below = m.down();
                    if (world.getBlockState(m).isAir() && world.getBlockState(m.up()).isAir()
                            && world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
                            && world.getLightLevel(LightType.BLOCK, m) < 1 && !world.getBlockState(below).isOf(Blocks.BEDROCK)
                            && cover.canPlaceAt(world, m)) {
                        fill.put(m.toImmutable(), cover);
                    }
                }
            }
        }
        return Plan.of(Map.of(), fill);
    }

    // ------------------------------------------------------------------ hill

    /**
     * A hill on the oval between two corners, rising {@code height} blocks in the middle: grass on 3 dirt on
     * stone, snow on top where it rises more than 20 blocks. Lumpy edges, so it doesn't look turned on a lathe.
     */
    public static Plan hill(ClientWorld world, BlockPos a, BlockPos b, int height) {
        double cx = (a.getX() + b.getX()) / 2.0;
        double cz = (a.getZ() + b.getZ()) / 2.0;
        double rx = Math.max(2, Math.abs(b.getX() - a.getX()) / 2.0);
        double rz = Math.max(2, Math.abs(b.getZ() - a.getZ()) / 2.0);
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        for (int x = (int) Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
            for (int z = (int) Math.floor(cz - rz); z <= Math.ceil(cz + rz); z++) {
                double dx = (x + 0.5 - cx) / rx;
                double dz = (z + 0.5 - cz) / rz;
                double d = Math.sqrt(dx * dx + dz * dz) * (0.9 + 0.2 * hash(x >> 1, 7, z >> 1, 100) / 100.0);
                if (d >= 1.0) {
                    continue;
                }
                double t = 1 - d * d;
                int rise = (int) Math.round(height * t * Math.sqrt(t) * (0.85 + 0.3 * hash(x >> 2, 3, z >> 2, 100) / 100.0));
                if (rise <= 0) {
                    continue;
                }
                int g = groundY(world, x, z, a.getY());
                int top = g + rise;
                for (int y = g + 1; y <= top && fill.size() < LIMIT; y++) {
                    BlockState st = y == top ? (rise > 20 ? Blocks.SNOW_BLOCK : Blocks.GRASS_BLOCK).getDefaultState()
                            : y >= top - 3 ? Blocks.DIRT.getDefaultState()
                            : (hash(x, y, z, 12) == 0 ? Blocks.ANDESITE : Blocks.STONE).getDefaultState();
                    fillAt(world, fill, new BlockPos(x, y, z), st);
                }
            }
        }
        return Plan.of(Map.of(), fill);
    }

    // ------------------------------------------------------------------ lake / river

    /** An oval basin {@code depth} deep between two corners: sand near the edge, clay and gravel deeper; water poured in. */
    public static Plan lake(ClientWorld world, BlockPos a, BlockPos b, int depth) {
        double cx = (a.getX() + b.getX()) / 2.0;
        double cz = (a.getZ() + b.getZ()) / 2.0;
        double rx = Math.max(1.5, Math.abs(b.getX() - a.getX()) / 2.0);
        double rz = Math.max(1.5, Math.abs(b.getZ() - a.getZ()) / 2.0);
        int level = Math.min(a.getY(), b.getY());
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        List<BlockPos> water = new ArrayList<>();
        for (int x = (int) Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
            for (int z = (int) Math.floor(cz - rz); z <= Math.ceil(cz + rz); z++) {
                double dx = (x + 0.5 - cx) / rx;
                double dz = (z + 0.5 - cz) / rz;
                double d = Math.sqrt(dx * dx + dz * dz) * (0.92 + 0.16 * hash(x >> 1, 9, z >> 1, 100) / 100.0);
                if (d >= 1.0) {
                    continue;
                }
                int deep = Math.max(1, (int) Math.round(depth * Math.sqrt(1 - d * d)));
                basinColumn(world, x, z, level, deep, d, clear, fill, water);
            }
        }
        return new Plan(clear, fill, List.of(), List.of(), water, false);
    }

    /** A riverbed from a to b, {@code width} wide, winding a little; water poured in. */
    public static Plan river(ClientWorld world, BlockPos a, BlockPos b, int width) {
        int level = Math.min(a.getY(), b.getY());
        List<int[]> cells = line(a.getX(), a.getZ(), b.getX(), b.getZ());
        boolean xMajor = Math.abs(b.getX() - a.getX()) >= Math.abs(b.getZ() - a.getZ());
        double half = Math.max(1, width) / 2.0;
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        List<BlockPos> water = new ArrayList<>();
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            double wobble = Math.sin(i / 7.0) * Math.min(3.0, cells.size() / 12.0);
            for (int w = (int) -Math.ceil(half + 1); w <= Math.ceil(half + 1); w++) {
                double off = w - wobble;
                double d = Math.abs(off) / half;
                if (d >= 1.0) {
                    continue;
                }
                int x = xMajor ? c[0] : c[0] + w;
                int z = xMajor ? c[1] + w : c[1];
                if (!done.add(BlockPos.asLong(x, 0, z))) {
                    continue;
                }
                int deep = d < 0.5 ? 3 : 2;
                basinColumn(world, x, z, level, deep, d, clear, fill, water);
            }
        }
        return new Plan(clear, fill, List.of(), List.of(), water, false);
    }

    private static void basinColumn(ClientWorld world, int x, int z, int level, int deep, double d,
                                    Map<BlockPos, BlockState> clear, Map<BlockPos, BlockState> fill, List<BlockPos> water) {
        int g = groundY(world, x, z, level);
        for (int y = Math.max(g, level) + 2; y > level - deep; y--) {
            clearAt(world, clear, new BlockPos(x, y, z));
        }
        BlockState bottom = d > 0.7 ? Blocks.SAND.getDefaultState()
                : hash(x, 5, z, 4) == 0 ? Blocks.GRAVEL.getDefaultState() : Blocks.CLAY.getDefaultState();
        BlockPos floor = new BlockPos(x, level - deep, z);
        clear.remove(floor);
        fillAt(world, fill, floor, bottom);
        for (int y = level - deep + 1; y <= level; y++) {
            water.add(new BlockPos(x, y, z));
        }
    }

    // ------------------------------------------------------------------ terrace

    /**
     * Terraces between two corners: flat steps {@code step} high, stepping down from the first corner's
     * ground to the second's, each cut into the slope (mined above) and built up where needed (filled below),
     * grass on top.
     */
    public static Plan terrace(ClientWorld world, BlockPos a, BlockPos b, int step) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        boolean alongX = x1 - x0 >= z1 - z0;
        int len = (alongX ? x1 - x0 : z1 - z0) + 1;
        int high = Math.max(a.getY(), b.getY());
        int low = Math.min(a.getY(), b.getY());
        int steps = Math.max(1, (int) Math.ceil((high - low) / (double) Math.max(1, step)));
        int stripLen = Math.max(1, (int) Math.ceil(len / (double) (steps + 1)));
        boolean fromA = alongX ? a.getX() == x0 : a.getZ() == z0;
        boolean aHigh = a.getY() >= b.getY();
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int along = alongX ? x - x0 : z - z0;
                if (!fromA) {
                    along = len - 1 - along;
                }
                int strip = Math.min(steps, along / stripLen);
                int level = aHigh ? high - strip * step : low + strip * step;
                level = Math.max(low, Math.min(high, level));
                int g = groundY(world, x, z, level);
                for (int y = Math.max(g, level) + 6; y > level && clear.size() < LIMIT; y--) {
                    clearAt(world, clear, new BlockPos(x, y, z));
                }
                for (int y = g + 1; y <= level && fill.size() < LIMIT; y++) {
                    fillAt(world, fill, new BlockPos(x, y, z), y == level ? Blocks.GRASS_BLOCK.getDefaultState()
                            : y >= level - 2 ? Blocks.DIRT.getDefaultState() : Blocks.STONE.getDefaultState());
                }
                if (g >= level) {
                    // Cut down to the step: its top becomes grass.
                    BlockPos top = new BlockPos(x, level, z);
                    if (!world.getBlockState(top).isOf(Blocks.GRASS_BLOCK)) {
                        fill.put(top, Blocks.GRASS_BLOCK.getDefaultState());
                    }
                }
            }
        }
        fill.keySet().forEach(clear::remove);
        return Plan.of(clear, fill);
    }

    // ------------------------------------------------------------------ cave / tunnel

    /** A rounded tunnel {@code size} across from a to b, a flat floor, torches every 8 blocks. */
    public static Plan cave(ClientWorld world, BlockPos a, BlockPos b, int size) {
        double r = Math.max(1.0, size / 2.0);
        double lx = b.getX() - a.getX();
        double ly = b.getY() - a.getY();
        double lz = b.getZ() - a.getZ();
        double len2 = Math.max(1e-6, lx * lx + ly * ly + lz * lz);
        int pad = (int) Math.ceil(r) + 1;
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        for (int x = Math.min(a.getX(), b.getX()) - pad; x <= Math.max(a.getX(), b.getX()) + pad; x++) {
            for (int y = Math.min(a.getY(), b.getY()) - pad; y <= Math.max(a.getY(), b.getY()) + pad; y++) {
                for (int z = Math.min(a.getZ(), b.getZ()) - pad; z <= Math.max(a.getZ(), b.getZ()) + pad; z++) {
                    double t = Math.max(0, Math.min(1, ((x - a.getX()) * lx + (y - a.getY()) * ly + (z - a.getZ()) * lz) / len2));
                    double px = a.getX() + lx * t - x;
                    double py = a.getY() + ly * t - y;
                    double pz = a.getZ() + lz * t - z;
                    // Floor flat at the line's height: nothing dug below it.
                    if (py > 0.5) {
                        continue;
                    }
                    double rough = r * (0.85 + 0.3 * hash(x >> 1, y >> 1, z >> 1, 100) / 100.0);
                    if (px * px + py * py + pz * pz <= rough * rough && clear.size() < LIMIT) {
                        clearAt(world, clear, new BlockPos(x, y, z));
                    }
                }
            }
        }
        // Torches on the floor along the middle.
        List<int[]> cells = line(a.getX(), a.getZ(), b.getX(), b.getZ());
        for (int i = 4; i < cells.size(); i += 8) {
            double t = cells.size() <= 1 ? 0 : i / (double) (cells.size() - 1);
            int y = (int) Math.round(a.getY() + ly * t);
            int[] c = cells.get(i);
            BlockPos p = new BlockPos(c[0], y, c[1]);
            fill.put(p, Blocks.TORCH.getDefaultState());
            clear.remove(p);
            if (!world.getBlockState(p.down()).isSideSolidFullSquare(world, p.down(), Direction.UP)) {
                fillAt(world, fill, p.down(), Blocks.COBBLESTONE.getDefaultState());
            }
        }
        return Plan.of(clear, fill);
    }

    // ------------------------------------------------------------------ bridge between islands

    /**
     * The two ends of a bridge between the islands under {@code a} and {@code b}: from each, the last block of
     * its ground heading toward the other one, at the clicked height. Null if either is already a cliff edge.
     */
    public static BlockPos[] islandEdges(ClientWorld world, BlockPos a, BlockPos b) {
        return new BlockPos[]{edge(world, a, b), edge(world, b, a)};
    }

    private static BlockPos edge(ClientWorld world, BlockPos from, BlockPos toward) {
        double dx = toward.getX() - from.getX();
        double dz = toward.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            return from;
        }
        BlockPos last = from;
        for (int i = 1; i < len; i++) {
            int x = (int) Math.floor(from.getX() + 0.5 + dx * i / len);
            int z = (int) Math.floor(from.getZ() + 0.5 + dz * i / len);
            // Ground within a couple of blocks of the clicked height: still on the island.
            boolean ground = false;
            for (int y = from.getY() + 1; y >= from.getY() - 2; y--) {
                BlockState s = world.getBlockState(new BlockPos(x, y, z));
                if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) {
                    ground = true;
                    break;
                }
            }
            if (!ground) {
                break;
            }
            last = new BlockPos(x, from.getY(), z);
        }
        return last;
    }

    // ------------------------------------------------------------------ decorate

    /**
     * Grass, flowers, ferns, little bushes and rocks scattered on the grass between two corners. {@code density}
     * 1..10: how thick (roughly a tenth of the spots per step).
     */
    public static Plan decorate(ClientWorld world, BlockPos a, BlockPos b, int density) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER,
                Blocks.ALLIUM, Blocks.RED_TULIP, Blocks.WHITE_TULIP, Blocks.LILY_OF_THE_VALLEY};
        int chance = Math.max(1, Math.min(10, density)) * 6; // of 100
        Map<BlockPos, BlockState> fill = new LinkedHashMap<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int g = groundY(world, x, z, a.getY());
                BlockPos ground = new BlockPos(x, g, z);
                BlockPos p = ground.up();
                if (!world.getBlockState(ground).isOf(Blocks.GRASS_BLOCK) || !world.getBlockState(p).isAir()
                        || hash(x, 1, z, 100) >= chance) {
                    continue;
                }
                int pick = hash(x, 2, z, 100);
                BlockState st;
                if (pick < 55) {
                    st = Blocks.GRASS.getDefaultState();
                } else if (pick < 75) {
                    st = flowers[hash(x >> 2, 3, z >> 2, flowers.length)].getDefaultState();
                } else if (pick < 85) {
                    st = Blocks.FERN.getDefaultState();
                } else if (pick < 93) {
                    st = (pick % 2 == 0 ? Blocks.AZALEA_LEAVES : Blocks.OAK_LEAVES).getDefaultState()
                            .with(Properties.PERSISTENT, true);
                } else {
                    st = (pick % 2 == 0 ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).getDefaultState();
                }
                fill.put(p, st);
            }
        }
        return Plan.of(Map.of(), fill);
    }

    // ------------------------------------------------------------------ restyle

    /**
     * Queued builds inside the box, redone in the brush's material: full blocks become the brush, stairs its
     * stairs, slabs its slab, walls its wall (keeping how they face). Glass, lights and anything with contents
     * are left alone.
     *
     * @return position -> new state, for the orders to take over
     */
    public static Map<BlockPos, BlockState> restyle(Map<BlockPos, BlockState> queued, BlockPos a, BlockPos b, BlockState brush) {
        Family f = family(brush);
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY()) - 64;
        int y1 = Math.max(a.getY(), b.getY()) + 64;
        int z0 = Math.min(a.getZ(), b.getZ());
        int z1 = Math.max(a.getZ(), b.getZ());
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : queued.entrySet()) {
            BlockPos p = e.getKey();
            if (p.getX() < x0 || p.getX() > x1 || p.getY() < y0 || p.getY() > y1 || p.getZ() < z0 || p.getZ() > z1) {
                continue;
            }
            BlockState s = e.getValue();
            Block block = s.getBlock();
            BlockState to = null;
            if (block instanceof StairsBlock && f.stairs() != null) {
                to = f.stairs().with(Properties.HORIZONTAL_FACING, s.get(Properties.HORIZONTAL_FACING))
                        .with(Properties.BLOCK_HALF, s.get(Properties.BLOCK_HALF))
                        .with(Properties.STAIR_SHAPE, s.get(Properties.STAIR_SHAPE));
            } else if (block instanceof SlabBlock && f.slab() != null) {
                to = f.slab().with(Properties.SLAB_TYPE, s.get(Properties.SLAB_TYPE));
            } else if (block instanceof WallBlock && f.wall() != null) {
                to = f.wall();
            } else if (!s.hasBlockEntity() && s.getLuminance() == 0 && !s.isIn(BlockTags.IMPERMEABLE)
                    && s.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN)
                    && !(block instanceof net.minecraft.block.PillarBlock)) {
                to = f.full();
            }
            if (to != null && to != s) {
                out.put(p, to);
            }
        }
        return out;
    }
}
