package com.testrts.rtsclient.devtest;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.BuildMode;
import com.testrts.rtsclient.build.BuildShape;
import com.testrts.rtsclient.build.OrderPlanner;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.camera.RtsCamera;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.MaterialPlanner;
import com.testrts.rtsclient.task.TaskQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Live tests, dev runs only: {@code ./gradlew runClient -Pautotest=dome6,sphere5} creates a flat
 * survival world, builds each scenario with the real task queue and engine, then writes the
 * outcome to {@code run/autotest/results.txt} and a screenshot per scenario.
 */
public final class AutoTest {
    private static final String WORLD = "rts-autotest";
    /** Feet level on the default superflat (bedrock, 2 dirt, grass at y -61). */
    private static final BlockPos O = new BlockPos(0, -60, 0);
    private static final int ARENA = 22;

    /** @param preset a preset building to place at {@code a} instead of a shape, or null */
    private record Scenario(String name, BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow,
                            String engine, boolean airPlace, BlockPos start, int timeoutTicks, String preset) {
    }

    private static final Map<String, Scenario> SCENARIOS = new java.util.LinkedHashMap<>();
    /**
     * Scenarios that start with no building blocks: stone bricks (and dirt for scaffolding) are in two
     * chests at the edge of the arena, found with Scan nearby and fetched by the build itself.
     */
    private static final java.util.Set<String> FROM_STORAGE = new java.util.HashSet<>();
    private static final int STORED_BRICKS = 7 * 64;
    /**
     * Scenarios whose bricks and dirt are only in an Applied Energistics 2 network built next to the arena:
     * through a linked wireless terminal the player carries, or at an ME terminal linked with the Link tool.
     */
    private static final java.util.Set<String> FROM_ME = new java.util.HashSet<>();
    /** Controller of the test network (energy cell south, drive east, access point on top, terminal cable west). */
    private static final BlockPos ME_CONTROLLER = O.offset(-10, 0, 8);
    private static int meStage;
    /**
     * Scenarios whose bricks and dirt are only in a Refined Storage network on the other side of the arena:
     * through a bound wireless crafting grid (RS Quartz Arsenal) or at a crafting grid linked with the Link tool.
     */
    private static final java.util.Set<String> FROM_RS = new java.util.HashSet<>();
    /** Creative controller of the RS test network (disk drive east, crafting grid west, wireless transmitter on top). */
    private static final BlockPos RS_CONTROLLER = O.offset(-10, 0, -8);
    private static int rsStage;
    /** Scenarios run in creative mode (creative flight and creative supply). */
    private static final java.util.Set<String> CREATIVE = new java.util.HashSet<>();

    private static void scenario(String name, BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow,
                                 String engine, boolean airPlace) {
        scenario(name, shape, a, b, height, hollow, engine, airPlace, new BlockPos(-12, 0, -3));
    }

    /** @param start where the player stands at the start (relative to the arena origin) */
    private static void scenario(String name, BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow,
                                 String engine, boolean airPlace, BlockPos start) {
        SCENARIOS.put(name, new Scenario(name, shape, O.offset(a), O.offset(b), height, hollow, engine, airPlace,
                O.offset(start), 20 * 60 * 5, null));
    }

    /** Scenarios whose blocks come from code (relative to {@code a}) instead of a shape or preset. */
    private static final Map<String, java.util.function.Function<BlockPos, Map<BlockPos, BlockState>>> CUSTOM = new java.util.HashMap<>();

    private static void custom(String name, java.util.function.Function<BlockPos, Map<BlockPos, BlockState>> blocks) {
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O.offset(-5, 0, -5), O, 1, false, "builtin", true,
                O.offset(-12, 0, -10), 20 * 60 * 5, null));
        CUSTOM.put(name, blocks);
    }

    /**
     * A building over several floors: 11x11 stone-brick walls with windows, plank floors with one
     * 1x2 stairwell hole each (no stairs: getting up is the builder's job), a door gap on the ground
     * floor and a roof overhanging the walls by one block all round.
     */
    private static Map<BlockPos, BlockState> storeys(BlockPos o, int floors) {
        Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState floor = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        int top = floors * 4;
        for (int y = 0; y <= top; y++) {
            for (int x = 0; x <= 10; x++) {
                for (int z = 0; z <= 10; z++) {
                    boolean edge = x == 0 || x == 10 || z == 0 || z == 10;
                    BlockPos p = o.offset(x, y, z);
                    if (y % 4 == 0) {
                        if (y == 0 || y == top || edge) {
                            m.put(p, edge ? wall : floor);
                        } else if (!(x == 8 && (z == 2 || z == 3))) {
                            m.put(p, floor); // the stairwell hole stays open
                        }
                    } else if (edge) {
                        boolean door = z == 0 && x == 5 && y <= 2;
                        boolean window = y % 4 == 2 && (x == 3 || x == 7 || z == 3 || z == 7) && !(x == 5);
                        if (!door) {
                            m.put(p, window ? glass : wall);
                        }
                    }
                }
            }
        }
        // Roof overhang: a ring one block out from the walls, level with the roof.
        for (int x = -1; x <= 11; x++) {
            for (int z = -1; z <= 11; z++) {
                if (x == -1 || x == 11 || z == -1 || z == 11) {
                    m.put(o.offset(x, top, z), wall);
                }
            }
        }
        return m;
    }

    /** Scenarios that carry 64 scaffolding as well as the usual dirt. */
    private static final java.util.Set<String> WITH_SCAFFOLDING = new java.util.HashSet<>();
    /** Climbing-aid scenarios: the only thing besides the wall's own blocks to climb with (no dirt). */
    private static final Map<String, String> CLIMB_ITEM = new java.util.HashMap<>();

    /** A stone-brick wall 7 long and 10 high. */
    private static Map<BlockPos, BlockState> tallWall(BlockPos o) {
        Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
        for (int x = 0; x <= 6; x++) {
            for (int y = 0; y <= 9; y++) {
                m.put(o.offset(x, y, 0), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        return m;
    }

    private static void preset(String name, String preset) {
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O.offset(-7, 0, -6), O, 1, false, "builtin", true,
                O.offset(-12, 0, -10), 20 * 60 * 5, preset));
    }

    static {
        scenario("dome6", BuildShape.DOME, BlockPos.ZERO, new BlockPos(6, 0, 0), 1, true, "builtin", true);
        scenario("dome10", BuildShape.DOME, BlockPos.ZERO, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        scenario("sphere5", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", true);
        scenario("floor", BuildShape.FLOOR, new BlockPos(-3, 6, -3), new BlockPos(3, 6, 3), 1, false, "builtin", true);
        scenario("line", BuildShape.LINE, new BlockPos(-8, 4, 0), new BlockPos(8, 9, 4), 1, false, "builtin", true);
        scenario("dome6-noair", BuildShape.DOME, BlockPos.ZERO, new BlockPos(6, 0, 0), 1, true, "builtin", false);
        scenario("sphere5-noair", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", false);
        // Baritone builds: mid-air placing off, or the built-in engine takes over builds (Baritone can't do it).
        scenario("dome6-baritone", BuildShape.DOME, BlockPos.ZERO, new BlockPos(6, 0, 0), 1, true, "baritone", false);
        scenario("box-baritone", BuildShape.BOX, new BlockPos(-2, 0, -2), new BlockPos(2, 0, 2), 3, true, "baritone", false);
        // Starts inside: the finished dome closes around the player, who must open a doorway and close it from outside.
        scenario("dome6-inside", BuildShape.DOME, BlockPos.ZERO, new BlockPos(6, 0, 0), 1, true, "builtin", true, new BlockPos(1, 0, 1));
        scenario("pyramid", BuildShape.PYRAMID, BlockPos.ZERO, new BlockPos(5, 0, 0), 1, true, "builtin", true);
        scenario("cone", BuildShape.CONE, BlockPos.ZERO, new BlockPos(4, 0, 0), 8, true, "builtin", true);
        scenario("torus", BuildShape.TORUS, new BlockPos(0, 3, 0), new BlockPos(6, 3, 0), 2, true, "builtin", true);
        scenario("arch", BuildShape.ARCH, new BlockPos(-6, 0, 0), new BlockPos(6, 0, 0), 1, true, "builtin", true);
        scenario("bridge", BuildShape.BRIDGE, new BlockPos(-10, 0, 2), new BlockPos(10, 0, 2), 3, false, "builtin", true);
        scenario("stairs", BuildShape.STAIRS, new BlockPos(-6, 0, 0), new BlockPos(6, 6, 0), 2, false, "builtin", true);
        scenario("spiral", BuildShape.SPIRAL, BlockPos.ZERO, new BlockPos(3, 0, 0), 12, false, "builtin", true);
        // Materials from chests: a hollow box needing several stacks, and a dome with mid-air placing off
        // whose scaffolding (dirt) is only in storage.
        scenario("storage-box", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        scenario("storage-dome", BuildShape.DOME, BlockPos.ZERO, new BlockPos(5, 0, 0), 1, true, "builtin", false);
        FROM_STORAGE.add("storage-box");
        FROM_STORAGE.add("storage-dome");
        // Materials from an AE2 network (needs AE2 in run/mods).
        scenario("me-wireless", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        scenario("me-terminal", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        FROM_ME.add("me-wireless");
        FROM_ME.add("me-terminal");
        // Only an ae2wtlib terminal (pattern encoding): AE2's own hotkey can't open it, ae2wtlib's must.
        scenario("me-wtlib", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        FROM_ME.add("me-wtlib");
        // Materials from a Refined Storage network (needs RS and RS Quartz Arsenal in run/mods).
        scenario("rs-wireless", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        scenario("rs-grid", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        FROM_RS.add("rs-wireless");
        FROM_RS.add("rs-grid");
        // A stone block, half of it protected, cleared: the protected half must stay.
        scenario("protect-clear", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 2, 3), 1, false, "builtin", true);
        // Same, but keeping the stone and dirt every scenario starts with (a full inventory: blocks and tools
        // have to be swapped into the hand from the main inventory).
        scenario("me-terminal-full", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        FROM_ME.add("me-terminal-full");
        // Creative mode: flies to the blocks instead of walking and climbing.
        scenario("fly-sphere", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", true);
        scenario("fly-dome10", BuildShape.DOME, BlockPos.ZERO, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        CREATIVE.add("fly-sphere");
        CREATIVE.add("fly-dome10");
        preset("p-machine-hall", "Machine hall");
        preset("p-power-room", "Power room");
        preset("p-storage-core", "Storage core");
        preset("p-greenhouse", "Greenhouse");
        preset("p-launch-pad", "Launch pad");
        preset("p-rail-station", "Rail station");
        preset("p-bee-yard", "Bee yard");
        // Builds over several levels: platforms, roofs, walkways, rooms to get in and out of.
        preset("p-starter-house", "Starter house");
        preset("p-watchtower", "Watchtower");
        preset("p-iron-farm", "Iron farm frame");
        preset("p-castle-wall", "Castle wall");
        preset("p-trading-hall", "Trading hall");
        preset("p-mob-grinder", "Mob grinder shell");
        preset("p-smelter-room", "Smelter room");
        // FramedBlocks presets (needs FramedBlocks in run/mods): built, then the camo pass right-clicks every frame.
        preset("pf-cottage", "Cottage");
        preset("pf-workshop", "Workshop");
        preset("pf-tower", "Watchtower");
        custom("storeys2", o -> storeys(o, 2));
        custom("storeys3", o -> storeys(o, 3));
        // Climbing aids: a wall too tall to reach from the ground, with only scaffolding / only ladders to climb on.
        custom("wall-scaffolding", AutoTest::tallWall);
        custom("wall-ladders", AutoTest::tallWall);
        CLIMB_ITEM.put("wall-scaffolding", "scaffolding");
        CLIMB_ITEM.put("wall-ladders", "ladder");
        // Dirt and scaffolding both carried: scaffolding should be what goes up (supports, climbs).
        scenario("sphere5-scaffolding", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", false);
        custom("storeys2-scaffolding", o -> storeys(o, 2));
        WITH_SCAFFOLDING.add("sphere5-scaffolding");
        WITH_SCAFFOLDING.add("storeys2-scaffolding");
    }

    private enum Phase { TITLE, JOINING, SETUP, START, RUN, TRUN, WRUN, SHOT, NEXT, FINISHED }

    // ------------------------------------------------------------------ Terrain modes (the newer ones)

    /** A Terrain mode scenario: the mode, its two clicks and size, commands that shape the ground first, items given. */
    private record Terra(com.testrts.rtsclient.build.TerrainMode mode, BlockPos a, BlockPos b, int size,
                         List<String> setup, List<String> give, String brush) {
    }

    private static final Map<String, Terra> TERRA = new java.util.LinkedHashMap<>();
    private static com.testrts.rtsclient.build.TerrainShapes.Plan terraPlan;
    private static Map<BlockPos, BlockState> terraExpect;

    private static void terra(String name, com.testrts.rtsclient.build.TerrainMode mode, BlockPos a, BlockPos b, int size,
                              List<String> setup, List<String> give, String brush) {
        TERRA.put(name, new Terra(mode, O.offset(a), O.offset(b), size, setup, give, brush));
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O, O, 1, false, "builtin", true, O.offset(-12, 0, -10), 20 * 60 * 6, null));
    }

    static {
        var M = com.testrts.rtsclient.build.TerrainMode.class;
        int y = O.getY();
        terra("t-road", com.testrts.rtsclient.build.TerrainMode.ROAD, new BlockPos(-14, -1, 0), new BlockPos(14, -1, 0), 3,
                List.of(String.format("fill 0 %d -3 4 %d 3 dirt", y, y + 2), String.format("fill 7 %d -4 10 %d 4 water", y - 2, y - 1)),
                List.of("cobblestone 256", "cobblestone_stairs 32", "polished_andesite 128", "spruce_fence 32", "lantern 16", "stone_bricks 64"), "");
        terra("t-hill", com.testrts.rtsclient.build.TerrainMode.HILL, new BlockPos(-6, -1, -6), new BlockPos(6, -1, 6), 6,
                List.of(), List.of("grass_block 128", "dirt 512", "stone 512", "andesite 64"), "");
        terra("t-lake", com.testrts.rtsclient.build.TerrainMode.LAKE, new BlockPos(-5, -1, -4), new BlockPos(5, -1, 4), 3,
                List.of(), List.of("water_bucket 2", "sand 64", "clay 128", "gravel 64"), "");
        terra("t-river", com.testrts.rtsclient.build.TerrainMode.RIVER, new BlockPos(-12, -1, 0), new BlockPos(12, -1, 3), 3,
                List.of(), List.of("water_bucket 2", "sand 128", "clay 128", "gravel 64"), "");
        terra("t-farm", com.testrts.rtsclient.build.TerrainMode.FARM, new BlockPos(-5, -1, -4), new BlockPos(5, -1, 4), 0,
                List.of(), List.of("iron_hoe 1", "wheat_seeds 128", "water_bucket 2", "oak_fence 64", "oak_fence_gate 2", "torch 8"), "");
        terra("t-mobproof", com.testrts.rtsclient.build.TerrainMode.MOB_PROOF, new BlockPos(-4, -1, -4), new BlockPos(4, -1, 4), 0,
                List.of(), List.of("smooth_stone_slab 128"), "");
        terra("t-terrace", com.testrts.rtsclient.build.TerrainMode.TERRACE, new BlockPos(-8, 5, -3), new BlockPos(8, -1, 3), 2,
                List.of(String.format("fill -8 %d -3 -4 %d 3 dirt", y, y + 5), String.format("fill -3 %d -3 1 %d 3 dirt", y, y + 3),
                        String.format("fill 2 %d -3 5 %d 3 dirt", y, y + 1)),
                List.of("grass_block 256", "dirt 256", "stone 256"), "");
        terra("t-cave", com.testrts.rtsclient.build.TerrainMode.CAVE, new BlockPos(-10, 1, 0), new BlockPos(10, 2, 0), 3,
                List.of(String.format("fill -10 %d -4 10 %d 4 stone", y, y + 6)), List.of("torch 16", "cobblestone 16"), "");
        terra("t-room", com.testrts.rtsclient.build.TerrainMode.ROOM, new BlockPos(-3, -1, -3), new BlockPos(3, -1, 3), 0,
                List.of(String.format("fill -4 %d -4 4 %d 4 stone_bricks hollow", y, y + 3)),
                List.of("lantern 8", "torch 16", "chest 16", "crafting_table 2", "spruce_planks 64"), "");
        terra("t-islandbridge", com.testrts.rtsclient.build.TerrainMode.ISLAND_BRIDGE, new BlockPos(-9, 5, 0), new BlockPos(9, 5, 0), 3,
                List.of(String.format("fill -14 %d -3 -6 %d 3 stone", y + 5, y + 5), String.format("fill 6 %d -3 14 %d 3 stone", y + 5, y + 5)),
                List.of("spruce_planks 128", "spruce_stairs 64", "spruce_log 64", "stripped_spruce_log 64", "spruce_fence 64", "lantern 16"), "");
        terra("t-decorate", com.testrts.rtsclient.build.TerrainMode.DECORATE, new BlockPos(-6, -1, -6), new BlockPos(6, -1, 6), 6,
                List.of(), List.of("short_grass 128", "dandelion 16", "poppy 16", "azure_bluet 16", "oxeye_daisy 16", "cornflower 16",
                        "allium 16", "red_tulip 16", "white_tulip 16", "lily_of_the_valley 16", "fern 64", "azalea_leaves 32",
                        "oak_leaves 32", "cobblestone 32", "mossy_cobblestone 32"), "");
        terra("t-restyle", com.testrts.rtsclient.build.TerrainMode.RESTYLE, new BlockPos(-4, 0, -4), new BlockPos(4, 3, 4), 0,
                List.of(), List.of("spruce_planks 256"), "spruce_planks");
    }

    /** Start a Terrain-mode scenario: shape the ground, give the items, then pick the mode and click like the player. */
    /** Ground shaped and items given for the current Terrain scenario (the blocks reach the client a moment later). */
    private static boolean terraReady;

    private static void startTerra(Minecraft mc, Terra t) {
        RtsConfig.get().autoStart = false;
        var s = RtsController.get().session();
        s.tool = ToolMode.TERRAIN;
        s.terrainMode = t.mode();
        s.terrainSize = t.size();
        s.brush = t.brush().isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY
                : new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.parse("minecraft:" + t.brush())));
        terraExpect = new java.util.LinkedHashMap<>();
        if (t.mode() == com.testrts.rtsclient.build.TerrainMode.RESTYLE) {
            // A queued (not started) box of stone bricks, then restyle it.
            Map<BlockPos, BlockState> box = new java.util.LinkedHashMap<>();
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int yy = 0; yy <= 2; yy++) {
                        if (x == -3 || x == 3 || z == -3 || z == 3) {
                            box.put(O.offset(x, yy, z), Blocks.STONE_BRICKS.defaultBlockState());
                        }
                    }
                }
            }
            task = new BuildTask("autotest restyle box", box, false);
            TaskQueue.get().add(task);
            box.keySet().forEach(p -> terraExpect.put(p, Blocks.SPRUCE_PLANKS.defaultBlockState()));
            terraPlan = null;
        } else if (t.mode() == com.testrts.rtsclient.build.TerrainMode.ISLAND_BRIDGE) {
            BlockPos[] ends = com.testrts.rtsclient.build.TerrainShapes.islandEdges(mc.level, t.a(), t.b());
            terraExpect.putAll(OrderPlanner.plan(mc.level, com.testrts.rtsclient.screen.WorldActions.islandBridgeRequest(s, ends[0], ends[1])).changes());
            terraPlan = null;
        } else {
            terraPlan = com.testrts.rtsclient.build.TerrainShapes.plan(t.mode(), mc.level, t.a(), t.b(), t.size(),
                    com.testrts.rtsclient.screen.WorldActions.terrainBrush(s));
            terraExpect.putAll(terraPlan.clear());
            terraExpect.putAll(terraPlan.fill());
        }
        s.anchorA = t.a();
        s.anchorB = t.b();
        new com.testrts.rtsclient.screen.WorldActions(mc, RtsController.get()).confirm();
        TaskQueue.get().start();
        ticks = 0;
        log("start %s: %d blocks planned%s", current.name(), terraExpect.size(), terraPlan == null ? ""
                : String.format(", till %d, seeds %d, water %d", terraPlan.till().size(), terraPlan.seeds().size(), terraPlan.water().size()));
    }

    private static void verifyTerra(Minecraft mc, boolean timedOut) {
        var level = mc.level;
        int missing = 0;
        List<String> missed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : terraExpect.entrySet()) {
            if (!MaterialPlanner.matches(level.getBlockState(e.getKey()), e.getValue())) {
                missing++;
                if (missed.size() < 10) {
                    missed.add(e.getKey().subtract(O).toShortString() + " " + level.getBlockState(e.getKey()).getBlock().getName().getString()
                            + " (want " + e.getValue().getBlock().getName().getString() + ")");
                }
            }
        }
        int dry = 0;
        int untilled = 0;
        int unsown = 0;
        if (terraPlan != null) {
            for (BlockPos w : terraPlan.water()) {
                var f = level.getFluidState(w);
                if (!(f.is(net.minecraft.tags.FluidTags.WATER) && f.isSource())) {
                    dry++;
                }
            }
            for (BlockPos p : terraPlan.till()) {
                if (!level.getBlockState(p).is(Blocks.FARMLAND)) {
                    untilled++;
                }
            }
            for (BlockPos p : terraPlan.seeds()) {
                if (level.getBlockState(p.above()).isAir()) {
                    unsown++;
                }
            }
        }
        boolean pass = !timedOut && missing == 0 && dry == 0 && untilled == 0 && unsown == 0 && !terraExpect.isEmpty();
        report(String.format(java.util.Locale.ROOT, "RESULT %-16s %s blocks=%d missing=%d water-missing=%d untilled=%d unsown=%d time=%ds status=%s",
                current.name(), timedOut ? "TIMEOUT" : pass ? "PASS" : "FAIL", terraExpect.size(), missing, dry, untilled, unsown,
                ticks / 20, Engine.status()));
        if (!missed.isEmpty()) {
            report("    missed: " + missed);
        }
    }

    // ------------------------------------------------------------------ Walking (the built-in pathfinder)

    /**
     * A walk order on shaped ground: setup commands (given the feet level), where it starts and ends (relative to
     * the arena origin), whether it should get there, a step type the plan must use (or null), whether parkour is
     * on, cells the plan must not go through, and commands run two seconds into the walk.
     */
    private record Walk(java.util.function.IntFunction<List<String>> setup, BlockPos from, BlockPos to, boolean reachable,
                        com.testrts.rtsclient.exec.PathFinder.Action uses, boolean parkour, List<BlockPos> avoid,
                        java.util.function.IntFunction<List<String>> after) {
    }

    private static final Map<String, Walk> WALKS = new java.util.LinkedHashMap<>();
    private static boolean walkReady;
    private static float walkHealth;

    private static void walk(String name, java.util.function.IntFunction<List<String>> setup, BlockPos from, BlockPos to,
                             boolean reachable, com.testrts.rtsclient.exec.PathFinder.Action uses) {
        walk(name, setup, from, to, reachable, uses, true, List.of(), y -> List.of());
    }

    private static void walk(String name, java.util.function.IntFunction<List<String>> setup, BlockPos from, BlockPos to,
                             boolean reachable, com.testrts.rtsclient.exec.PathFinder.Action uses, boolean parkour,
                             List<BlockPos> avoid, java.util.function.IntFunction<List<String>> after) {
        WALKS.put(name, new Walk(setup, from, to, reachable, uses, parkour, avoid, after));
        // Starts out of the way of the ground being shaped; moved to the walk's start once it's done.
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O, O, 1, false, "builtin", true, O.offset(-20, 0, -20), 20 * 90, null));
    }

    /** A stone platform along x (top at feet level 3, feet at 4) with an air gap {@code width} wide at x = 0. */
    private static List<String> gap(int y, int width) {
        return List.of(String.format(Locale.ROOT, "fill -15 %d -2 15 %d 2 stone", y, y + 3),
                String.format(Locale.ROOT, "fill 0 %d -2 %d %d 2 air", y, width - 1, y + 3));
    }

    static {
        var P = com.testrts.rtsclient.exec.PathFinder.Action.PARKOUR;
        BlockPos ga = new BlockPos(-12, 4, 0);
        BlockPos gb = new BlockPos(12, 4, 0);
        walk("w-gap1", y -> gap(y, 1), ga, gb, true, P);
        walk("w-gap2", y -> gap(y, 2), ga, gb, true, P);
        walk("w-gap3", y -> gap(y, 3), ga, gb, true, P);
        // One-block gap, then a platform one higher: jump up onto it.
        walk("w-gap-up", y -> List.of(String.format(Locale.ROOT, "fill -15 %d -2 -1 %d 2 stone", y, y + 3),
                String.format(Locale.ROOT, "fill 1 %d -2 15 %d 2 stone", y, y + 4)), ga, new BlockPos(12, 5, 0), true, P);
        // A 1-wide beam ending in mid-air, and another beam off to the side (2 or 3 along, 2 across): an angled jump.
        walk("w-angle1", y -> List.of(String.format(Locale.ROOT, "fill -15 %d 0 -1 %d 0 stone", y, y + 3),
                String.format(Locale.ROOT, "fill 1 %d 2 15 %d 2 stone", y, y + 3)), ga, new BlockPos(12, 4, 2), true, P);
        walk("w-angle2", y -> List.of(String.format(Locale.ROOT, "fill -15 %d 0 -1 %d 0 stone", y, y + 3),
                String.format(Locale.ROOT, "fill 2 %d 2 15 %d 2 stone", y, y + 3)), ga, new BlockPos(12, 4, 2), true, P);
        // Lava at the bottom of the gap (kept in by low walls at its ends): never jumped.
        walk("w-gap-lava", y -> List.of(gap(y, 1).get(0), gap(y, 1).get(1),
                String.format(Locale.ROOT, "fill 0 %d -3 0 %d 3 stone", y, y),
                String.format(Locale.ROOT, "fill 0 %d -2 0 %d 2 lava", y, y)), ga, gb, false, null);
        walk("w-gap-off", y -> gap(y, 1), ga, gb, false, null, false, List.of(), yy -> List.of());
        // A 12-high tower with a pool at its foot: jump off into the water; with no pool there's no way down.
        walk("w-water-drop", y -> List.of(String.format(Locale.ROOT, "fill -15 %d -3 -8 %d 3 stone", y, y + 11),
                        String.format(Locale.ROOT, "fill -7 %d -2 -3 %d 2 stone", y, y + 1),
                        String.format(Locale.ROOT, "fill -7 %d -1 -4 %d 1 water", y, y + 1)),
                new BlockPos(-12, 12, 0), new BlockPos(10, 0, 0), true, null);
        walk("w-dry-drop", y -> List.of(String.format(Locale.ROOT, "fill -15 %d -3 -8 %d 3 stone", y, y + 11)),
                new BlockPos(-12, 12, 0), new BlockPos(10, 0, 0), false, null);
        // Cows standing in the straight line: the plan goes round them.
        walk("w-mobs", y -> List.of(String.format(Locale.ROOT, "summon cow 0.5 %d -0.5 {NoAI:1b}", y),
                        String.format(Locale.ROOT, "summon cow 0.5 %d 0.5 {NoAI:1b}", y),
                        String.format(Locale.ROOT, "summon cow 0.5 %d 1.5 {NoAI:1b}", y)),
                new BlockPos(-12, 0, 0), new BlockPos(12, 0, 0), true, null, true,
                List.of(new BlockPos(0, 0, -1), new BlockPos(0, 0, 0), new BlockPos(0, 0, 1)), y -> List.of());
        // A wall goes up across the route while walking it: plan again and go round.
        walk("w-reroute", y -> List.of(), new BlockPos(-12, 0, 0), new BlockPos(12, 0, 0), true, null, true, List.of(),
                y -> List.of(String.format(Locale.ROOT, "fill 4 %d -2 4 %d 2 stone", y, y + 1)));
    }

    /** Plan the walk right away (to report the route), then hand it to the engine like a walk order. */
    private static void startWalk(Minecraft mc, Walk w) {
        var level = mc.level;
        BlockPos goal = O.offset(w.to());
        var search = com.testrts.rtsclient.exec.PathFinder.start(level,
                com.testrts.rtsclient.exec.Walkability.feetNode(level, mc.player), p -> p.distSqr(goal) <= 1.0,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(goal));
        for (int i = 0; i < 1000 && search.state() == com.testrts.rtsclient.exec.PathFinder.Search.State.RUNNING; i++) {
            search.step(100_000);
        }
        Map<com.testrts.rtsclient.exec.PathFinder.Action, Integer> kinds = new java.util.EnumMap<>(com.testrts.rtsclient.exec.PathFinder.Action.class);
        String through = "";
        boolean usesOk = w.uses() == null;
        if (search.state() == com.testrts.rtsclient.exec.PathFinder.Search.State.FOUND) {
            search.actions().forEach(a -> kinds.merge(a, 1, Integer::sum));
            usesOk = w.uses() == null || kinds.containsKey(w.uses());
            for (BlockPos p : search.result()) {
                if (w.avoid().contains(p.subtract(O))) {
                    through += " " + p.subtract(O).toShortString();
                }
            }
        }
        planNote = String.format(Locale.ROOT, "plan=%s steps=%d %s%s", search.state(),
                search.result() == null ? 0 : search.result().size(), kinds, through.isEmpty() ? "" : " THROUGH" + through);
        planOk = usesOk && through.isEmpty()
                && (search.state() == com.testrts.rtsclient.exec.PathFinder.Search.State.FOUND) == w.reachable();
        Engine.goTo(goal);
        ticks = 0;
        walkHealth = mc.player.getHealth();
        minHealth = walkHealth;
        log("start %s: %s", current.name(), planNote);
    }

    private static String planNote;
    private static boolean planOk;

    private static void verifyWalk(Minecraft mc, Walk w, boolean timedOut) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos goal = O.offset(w.to());
        boolean arrived = feet.distSqr(goal) <= 2.0;
        float lost = walkHealth - minHealth;
        // Unreachable: it must stay put (on the platform or tower), not fall or wander off.
        boolean stayed = feet.getY() >= O.getY() + w.from().getY() - 1;
        boolean pass = !timedOut && planOk && lost <= 0.0F && (w.reachable() ? arrived : !arrived && stayed);
        report(String.format(Locale.ROOT, "RESULT %-16s %s %s arrived=%s hurt=%.1f time=%ds at=%s status=%s",
                current.name(), timedOut ? "TIMEOUT" : pass ? "PASS" : "FAIL", planNote, arrived, lost, ticks / 20,
                feet.subtract(O).toShortString(), Engine.status()));
        RtsConfig.get().parkour = true;
    }

    private static Phase phase = Phase.TITLE;
    private static final List<Scenario> queue = new ArrayList<>();
    private static Scenario current;
    private static int wait;
    private static int ticks;
    private static float minHealth;
    private static Map<BlockPos, BlockState> order;
    /** FramedBlocks presets: camo per frame, and the camo pass once the build is done. */
    private static Map<BlockPos, BlockState> camoOrder = Map.of();
    private static com.testrts.rtsclient.task.CamoTask camoTask;
    private static int camoStart = -1;
    private static BuildTask task;
    private static Path out;
    private static int clearWaits;
    /** Blocks still to do at the last check, when that last went down, and the longest wait for it (ticks). */
    private static int lastLeft;
    private static int lastProgress;
    private static int maxStall;
    private static int stalls;

    private AutoTest() {
    }

    /** Called reflectively from {@link RtsClientMod} when {@code -Drtsclient.autotest} is set. */
    public static void install() {
        String spec = System.getProperty("rtsclient.autotest", "all");
        for (String name : spec.split(",")) {
            name = name.trim();
            if (name.equals("all")) {
                queue.addAll(SCENARIOS.values());
            } else if (SCENARIOS.containsKey(name)) {
                queue.add(SCENARIOS.get(name));
            } else if (!name.isEmpty()) {
                RtsClientMod.LOGGER.error("[AUTOTEST] unknown scenario {}", name);
            }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            try {
                tick(Minecraft.getInstance());
            } catch (RuntimeException ex) {
                RtsClientMod.LOGGER.error("[AUTOTEST] crashed", ex);
                report("CRASH " + ex);
                phase = Phase.FINISHED;
            }
        });
    }

    private static void tick(Minecraft mc) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen) {
                    out = mc.gameDirectory.toPath().resolve("autotest");
                    createWorld(mc);
                    phase = Phase.JOINING;
                }
            }
            case JOINING -> {
                if (mc.player != null && mc.level != null && mc.screen == null) {
                    cmd(mc, "gamerule doDaylightCycle false");
                    cmd(mc, "gamerule doWeatherCycle false");
                    cmd(mc, "gamerule doMobSpawning false");
                    cmd(mc, "gamerule sendCommandFeedback false");
                    cmd(mc, "time set noon");
                    wait = 60;
                    phase = Phase.NEXT;
                }
            }
            case NEXT -> {
                if (queue.isEmpty()) {
                    report("ALL DONE");
                    phase = Phase.FINISHED;
                    if (Boolean.getBoolean("rtsclient.autotest.quit")) {
                        mc.stop();
                    }
                    return;
                }
                current = queue.remove(0);
                camoOrder = Map.of();
                camoTask = null;
                camoStart = -1;
                setup(mc, current);
                wait = 60;
                phase = Phase.START;
            }
            case START -> {
                // The arena clear runs on the server; plan only once it has reached us, or leftovers of the
                // last scenario count as "already built" (a dome came out with a hole in its top that way).
                if (!current.name().equals("protect-clear") && !walkReady && !arenaClear(mc) && ++clearWaits < 20) {
                    wait = 10;
                    return;
                }
                clearWaits = 0;
                Scenario s = current;
                if (FROM_ME.contains(s.name()) && meStage < 2) {
                    // Build the network, let it boot, then fill it and hand out the terminal.
                    if (meStage == 0) {
                        forgetStorage();
                        meBuild(mc);
                        wait = 60;
                    } else {
                        meFill(mc, s.name().equals("me-wireless") ? "ae2:wireless_terminal"
                                : s.name().equals("me-wtlib") ? "ae2wtlib:wireless_pattern_encoding_terminal" : null);
                        if (s.name().startsWith("me-terminal")) {
                            // As the Link tool gets it: a click on the terminal's rim (its east edge), not its screen.
                            BlockPos t = ME_CONTROLLER.west();
                            var rim = net.minecraft.world.phys.Vec3.atLowerCornerOf(t).add(0.875, 0.5, 0.06);
                            var side = com.testrts.rtsclient.storage.Ae2.terminalSideAt(mc.level.getBlockEntity(t), rim, net.minecraft.core.Direction.EAST);
                            report("    Link tool on the crafting terminal's rim: side " + side + (side == net.minecraft.core.Direction.NORTH ? " (right)" : " (WRONG)"));
                            com.testrts.rtsclient.storage.MeNetwork.get().toggle(t, side != null ? side : net.minecraft.core.Direction.NORTH);
                        }
                        wait = 20;
                    }
                    meStage++;
                    return;
                }
                meStage = 0;
                if (FROM_RS.contains(s.name()) && rsStage < 3) {
                    // Build the network and hand over a disk (it gets its storage in the inventory), put the
                    // disk in the drive, then fill the network and hand out / link what the scenario uses.
                    if (rsStage == 0) {
                        forgetStorage();
                        rsBuild(mc);
                        wait = 40;
                    } else if (rsStage == 1) {
                        rsDisk(mc);
                        wait = 40;
                    } else {
                        rsFill(mc, s.name().equals("rs-wireless"));
                        if (s.name().equals("rs-grid")) {
                            BlockPos g = RS_CONTROLLER.west();
                            var side = com.testrts.rtsclient.storage.Rs2.GRID.terminalSideAt(mc.level.getBlockEntity(g),
                                    net.minecraft.world.phys.Vec3.atCenterOf(g).add(0, 0, -0.5), net.minecraft.core.Direction.NORTH);
                            report("    Link tool on the RS crafting grid: " + (side != null ? "linked" : "NOT a grid"));
                            com.testrts.rtsclient.storage.MeNetwork.of(com.testrts.rtsclient.storage.Rs2.GRID).toggle(g,
                                    side != null ? side : net.minecraft.core.Direction.NORTH);
                        }
                        wait = 20;
                    }
                    rsStage++;
                    return;
                }
                rsStage = 0;
                RtsConfig cfg = RtsConfig.get();
                cfg.engine = s.engine();
                cfg.airPlace = s.airPlace();
                if (WALKS.containsKey(s.name())) {
                    Walk w = WALKS.get(s.name());
                    if (!walkReady) {
                        // Shape the ground, then stand at the start once the blocks have reached us.
                        cmd(mc, "kill @e[type=cow]");
                        w.setup().apply(O.getY()).forEach(c -> cmd(mc, c));
                        BlockPos f = O.offset(w.from());
                        cmd(mc, String.format(Locale.ROOT, "tp @s %.1f %d %.1f", f.getX() + 0.5, f.getY(), f.getZ() + 0.5));
                        walkReady = true;
                        wait = 40;
                        return;
                    }
                    walkReady = false;
                    cfg.parkour = w.parkour();
                    startWalk(mc, w);
                    phase = Phase.WRUN;
                    return;
                }
                if (TERRA.containsKey(s.name())) {
                    Terra t = TERRA.get(s.name());
                    if (!terraReady) {
                        // Shape the ground first; plan only once the server's blocks have reached us.
                        t.setup().forEach(c -> cmd(mc, c));
                        cmd(mc, "clear @s minecraft:stone");
                        t.give().forEach(g -> cmd(mc, "give @s " + g));
                        terraReady = true;
                        wait = 40;
                        return;
                    }
                    terraReady = false;
                    startTerra(mc, t);
                    phase = Phase.TRUN;
                    return;
                }
                OrderPlanner.Request r = new OrderPlanner.Request(ToolMode.BUILD, s.shape(), BuildMode.FILL, s.a(), s.b(),
                        s.height(), s.hollow(), (FROM_STORAGE.contains(s.name()) || FROM_ME.contains(s.name()) || FROM_RS.contains(s.name()) ? Blocks.STONE_BRICKS : Blocks.STONE).defaultBlockState(),
                        null, 100_000, List.of(), true,
                        com.testrts.rtsclient.build.MixPattern.RANDOM);
                if (CUSTOM.containsKey(s.name())) {
                    order = new java.util.LinkedHashMap<>();
                    CUSTOM.get(s.name()).apply(s.a()).forEach((pos, st) -> {
                        if (!MaterialPlanner.matches(mc.level.getBlockState(pos), st)) {
                            order.put(pos, st);
                        }
                    });
                    cmd(mc, "clear @s minecraft:stone");
                    giveMaterials(mc, order);
                } else if (s.preset() != null) {
                    // A preset building, pasted like the Blueprint tool does (only what isn't there already).
                    var bp = com.testrts.rtsclient.build.Presets.everything().stream().filter(p -> p.name().equals(s.preset()))
                            .findFirst().orElseThrow().build().get();
                    order = new java.util.LinkedHashMap<>();
                    bp.placed(s.a(), 0).forEach((pos, st) -> {
                        if (!MaterialPlanner.matches(mc.level.getBlockState(pos), st)) {
                            order.put(pos, st);
                        }
                    });
                    cmd(mc, "clear @s minecraft:stone"); // room in the inventory for the preset's blocks
                    giveMaterials(mc, order);
                    camoOrder = bp.placedCamo(s.a(), 0, false);
                    giveMaterials(mc, camoOrder);
                    if (!camoOrder.isEmpty()) {
                        // Made now, like pasting does: the builder puts camo on each frame as it places it.
                        camoTask = new com.testrts.rtsclient.task.CamoTask("autotest camo", camoOrder);
                    }
                } else {
                    order = OrderPlanner.plan(mc.level, r).changes();
                }
                if (s.name().equals("protect-clear")) {
                    // Clear the whole block, with x <= 0 protected.
                    com.testrts.rtsclient.build.Protection.get().clear();
                    com.testrts.rtsclient.build.Protection.get().add(com.testrts.rtsclient.build.Protection.Area.of(O.offset(-3, 0, -3), O.offset(0, 0, 3)));
                    order = new java.util.LinkedHashMap<>();
                    for (BlockPos p : BlockPos.betweenClosed(O.offset(-3, 0, -3), O.offset(3, 2, 3))) {
                        order.put(p.immutable(), Blocks.AIR.defaultBlockState());
                    }
                    task = new BuildTask("autotest " + s.name(), order, true);
                    TaskQueue.get().add(task);
                    TaskQueue.get().start();
                    ticks = 0;
                    lastLeft = Integer.MAX_VALUE;
                    lastProgress = 0;
                    maxStall = 0;
                    stalls = 0;
                    minHealth = mc.player.getHealth();
                    log("start %s: %d blocks to clear, x <= 0 protected", s.name(), order.size());
                    phase = Phase.RUN;
                    return;
                }
                if (FROM_STORAGE.contains(s.name())) {
                    // Like pressing Scan nearby: link the chests and read them; the build then fetches.
                    new com.testrts.rtsclient.screen.WorldActions(mc, RtsController.get()).scanNearby();
                } else if (FROM_ME.contains(s.name()) || FROM_RS.contains(s.name())) {
                    // Nothing to give: the build has to get its bricks from the network.
                } else if (s.preset() == null && !CUSTOM.containsKey(s.name())) {
                    // Railings, lanterns...: anything besides the stone every scenario gets.
                    Map<BlockPos, BlockState> extra = new java.util.HashMap<>(order);
                    extra.values().removeIf(st -> st.is(Blocks.STONE));
                    giveMaterials(mc, extra);
                }
                task = new BuildTask("autotest " + s.name(), order, false);
                TaskQueue.get().add(task);
                TaskQueue.get().start();
                ticks = 0;
                lastLeft = Integer.MAX_VALUE;
                lastProgress = 0;
                maxStall = 0;
                stalls = 0;
                minHealth = mc.player.getHealth();
                log("start %s: %d blocks, engine=%s air=%s", s.name(), order.size(), s.engine(), s.airPlace());
                phase = Phase.RUN;
            }
            case RUN -> {
                ticks++;
                minHealth = Math.min(minHealth, mc.player.getHealth());
                if (ticks % 20 == 0) {
                    // Stalls: stretches without a single block done (stuck getting to one, mostly).
                    int left = MaterialPlanner.remaining(mc.level, order);
                    // Once everything is built, the scaffolding comes down: leftovers show as stray, not as a stall.
                    if (left < lastLeft || left == 0) {
                        lastLeft = left;
                        lastProgress = ticks;
                    } else if (ticks - lastProgress == 20 * 15) {
                        stalls++;
                        log("  STALL 15s at left=%d status=%s pos=%s", left, Engine.status(), mc.player.blockPosition().subtract(O).toShortString());
                    }
                    maxStall = Math.max(maxStall, ticks - lastProgress);
                }
                if (ticks % 100 == 0) {
                    int left = MaterialPlanner.remaining(mc.level, order);
                    var head = TaskQueue.get().snapshot().isEmpty() ? null : TaskQueue.get().snapshot().get(0);
                    log("  t=%ds left=%d status=%s task=%s head=%s pos=%s", ticks / 20, left, Engine.status(),
                            task.detail(), head == null || head == task ? "-" : head.label() + " (" + head.detail() + ")",
                            mc.player.blockPosition().toShortString());
                }
                // FramedBlocks presets: once built, the camo pass (as pasting queues it).
                if (!TaskQueue.get().snapshot().contains(task) && camoTask != null && camoStart < 0) {
                    int already = 0;
                    for (Map.Entry<BlockPos, BlockState> e : camoOrder.entrySet()) {
                        if (com.testrts.rtsclient.task.CamoTask.hasCamo(mc.level, e.getKey(), e.getValue())) {
                            already++;
                        }
                    }
                    log("  camo put on while building: %d/%d", already, camoOrder.size());
                    TaskQueue.get().add(camoTask);
                    TaskQueue.get().start();
                    camoStart = ticks;
                    log("  built in %ds, camo pass for %d frames", ticks / 20, camoOrder.size());
                }
                // Only our own order counts: someone may queue things by hand in the same window.
                boolean running = TaskQueue.get().snapshot().contains(task)
                        || camoTask != null && TaskQueue.get().snapshot().contains(camoTask);
                // Bigger orders get longer: roughly a second a block on top of the base limit.
                int limit = Math.max(current.timeoutTicks(), 20 * (120 + order.size() + camoOrder.size()));
                if (!running || ticks > limit) {
                    TaskQueue.get().remove(mc, task);
                    if (camoTask != null) {
                        TaskQueue.get().remove(mc, camoTask);
                    }
                    boolean timedOut = running;
                    verify(mc, timedOut);
                    if (!camoOrder.isEmpty()) {
                        verifyCamo(mc);
                    }
                    showStructure(mc);
                    wait = 30;
                    phase = Phase.SHOT;
                }
            }
            case TRUN -> {
                ticks++;
                if (ticks % 100 == 0) {
                    var head = TaskQueue.get().snapshot().isEmpty() ? null : TaskQueue.get().snapshot().get(0);
                    log("  t=%ds queue=%d head=%s pos=%s", ticks / 20, TaskQueue.get().snapshot().size(),
                            head == null ? "-" : head.label() + " (" + head.detail() + ")", mc.player.blockPosition().toShortString());
                }
                int limit = 20 * (180 + terraExpect.size() + (terraPlan == null ? 0 : terraPlan.water().size() * 2));
                if (TaskQueue.get().snapshot().isEmpty() || ticks > limit) {
                    boolean timedOut = !TaskQueue.get().snapshot().isEmpty();
                    for (var t2 : TaskQueue.get().snapshot()) {
                        TaskQueue.get().remove(mc, t2);
                    }
                    verifyTerra(mc, timedOut);
                    wait = 30;
                    phase = Phase.SHOT;
                }
            }
            case WRUN -> {
                ticks++;
                minHealth = Math.min(minHealth, mc.player.getHealth());
                Walk w = WALKS.get(current.name());
                if (ticks == 40) {
                    w.after().apply(O.getY()).forEach(c -> cmd(mc, c));
                }
                if (ticks % 40 == 0) {
                    log("  t=%ds status=%s pos=%s", ticks / 20, Engine.status(), mc.player.blockPosition().subtract(O).toShortString());
                }
                boolean moving = Engine.isMoving();
                if (ticks > 20 && !moving || ticks > current.timeoutTicks()) {
                    Engine.stop();
                    verifyWalk(mc, w, moving);
                    wait = 10;
                    phase = Phase.SHOT;
                }
            }
            case SHOT -> {
                Screenshot.grab(mc.gameDirectory, "autotest-" + current.name() + ".png", mc.getMainRenderTarget(), c -> {
                });
                RtsController.get().disable(mc);
                wait = 10;
                phase = Phase.NEXT;
            }
            case FINISHED -> {
            }
        }
    }

    private static void createWorld(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        deleteTree(dir);
        GameRules rules = new GameRules();
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, rules,
                WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1234L, false, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, AutoTest::flatDimensions, mc.screen);
    }

    private static WorldDimensions flatDimensions(RegistryAccess ra) {
        var biomes = ra.lookupOrThrow(Registries.BIOME);
        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.empty(),
                FlatLevelGeneratorSettings.getDefaultBiome(biomes), List.of());
        flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
        flat.getLayersInfo().add(new FlatLayerInfo(2, Blocks.DIRT));
        flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.GRASS_BLOCK));
        flat.updateLayers();
        return WorldPresets.createNormalWorldDimensions(ra).replaceOverworldGenerator(ra, new FlatLevelSource(flat));
    }

    /** Nothing but air above the arena floor (chests of storage scenarios aside). */
    private static boolean arenaClear(Minecraft mc) {
        for (int x = -ARENA; x <= ARENA; x++) {
            for (int z = -ARENA; z <= ARENA; z++) {
                for (int y = O.getY(); y < O.getY() + 30; y++) {
                    BlockState st = mc.level.getBlockState(new BlockPos(x, y, z));
                    if (!st.isAir() && !st.is(Blocks.CHEST) && !isAe2(st)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void setup(Minecraft mc, Scenario s) {
        int r = ARENA;
        int y0 = O.getY();
        // Clear the arena in slices (fill is capped at 32768 blocks).
        for (int y = y0; y < y0 + 30; y += 10) {
            cmd(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", -r, y, -r, r, y + 9, r));
        }
        cmd(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d grass_block", -r, y0 - 1, -r, r, y0 - 1, r));
        cmd(mc, "kill @e[type=item]");
        cmd(mc, "gamemode survival");
        cmd(mc, "clear @s");
        cmd(mc, "effect clear @s");
        cmd(mc, "effect give @s instant_health 1 5");
        cmd(mc, "effect give @s saturation 1 20");
        cmd(mc, "give @s stone 1024");
        cmd(mc, "give @s dirt 256");
        cmd(mc, "give @s diamond_pickaxe");
        cmd(mc, "give @s diamond_shovel");
        if ((FROM_ME.contains(s.name()) || FROM_RS.contains(s.name())) && !s.name().endsWith("-full")) {
            // Bricks and scaffolding dirt are only in the AE2 network.
            cmd(mc, "clear @s minecraft:stone");
            cmd(mc, "clear @s minecraft:dirt");
        }
        if (FROM_STORAGE.contains(s.name())) {
            // Nothing to build with in the inventory: it's all in two chests by the arena's edge.
            cmd(mc, "clear @s minecraft:stone");
            cmd(mc, "clear @s minecraft:dirt");
            String bricks = "{Slot:%db,id:\"minecraft:stone_bricks\",count:64}";
            String dirt = "{Slot:%db,id:\"minecraft:dirt\",count:64}";
            cmd(mc, String.format(Locale.ROOT, "setblock -18 %d 8 minecraft:chest{Items:[%s,%s,%s,%s,%s]}", y0,
                    String.format(bricks, 0), String.format(bricks, 1), String.format(bricks, 2), String.format(bricks, 3),
                    String.format(bricks, 4)));
            cmd(mc, String.format(Locale.ROOT, "setblock -18 %d 11 minecraft:chest{Items:[%s,%s,%s,%s]}", y0,
                    String.format(dirt, 0), String.format(dirt, 1), String.format(bricks, 2), String.format(bricks, 3)));
        }
        if (WITH_SCAFFOLDING.contains(s.name())) {
            cmd(mc, "give @s scaffolding 64");
            RtsConfig.get().climbingAids = true;
        }
        if (CLIMB_ITEM.containsKey(s.name())) {
            cmd(mc, "clear @s minecraft:dirt");
            cmd(mc, "give @s " + CLIMB_ITEM.get(s.name()) + " 64");
            RtsConfig.get().climbingAids = true;
        }
        if (CREATIVE.contains(s.name())) {
            cmd(mc, "gamemode creative");
        }
        if (s.name().equals("protect-clear")) {
            cmd(mc, String.format(Locale.ROOT, "fill -3 %d -3 3 %d 3 stone", y0, y0 + 2));
        }
        cmd(mc, String.format(Locale.ROOT, "tp @s %d %d %d", s.start().getX(), s.start().getY(), s.start().getZ()));
    }

    private static void verify(Minecraft mc, boolean timedOut) {
        var level = mc.level;
        int missing = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        List<BlockPos> missed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : order.entrySet()) {
            BlockPos p = e.getKey();
            if (!MaterialPlanner.matches(level.getBlockState(p), e.getValue())) {
                missing++;
                if (missed.size() < 12) {
                    missed.add(p);
                }
            }
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        // Leftovers: anything solid in the arena above ground that isn't part of the order.
        int stray = 0;
        List<BlockPos> strays = new ArrayList<>();
        for (int x = -ARENA; x <= ARENA; x++) {
            for (int z = -ARENA; z <= ARENA; z++) {
                for (int y = O.getY(); y < O.getY() + 30; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    // The top half of a door, bed or tall plant comes with the order's bottom half.
                    boolean otherHalf = order.containsKey(p.below())
                            && level.getBlockState(p).getBlock() == level.getBlockState(p.below()).getBlock();
                    boolean ours = FROM_STORAGE.contains(current.name()) && level.getBlockState(p).is(Blocks.CHEST)
                            || (FROM_ME.contains(current.name()) || FROM_RS.contains(current.name())) && isAe2(level.getBlockState(p));
                    if (!level.getBlockState(p).isAir() && !order.containsKey(p) && !otherHalf && !ours) {
                        stray++;
                        if (strays.size() < 12) {
                            strays.add(p);
                        }
                    }
                }
            }
        }
        BlockPos feet = mc.player.blockPosition();
        boolean trapped = feet.getX() >= minX && feet.getX() <= maxX && feet.getZ() >= minZ && feet.getZ() <= maxZ
                && feet.getY() >= minY && feet.getY() <= maxY;
        String line = String.format(Locale.ROOT,
                "RESULT %-16s %s blocks=%d missing=%d stray=%d time=%ds maxStall=%ds stalls=%d minHealth=%.0f feet=%s inside=%s status=%s",
                current.name(), timedOut ? "TIMEOUT" : missing == 0 && stray == 0 ? "PASS" : "FAIL",
                order.size(), missing, stray, ticks / 20, maxStall / 20, stalls, minHealth, feet.toShortString(), trapped, Engine.status());
        report(line);
        if (FROM_STORAGE.contains(current.name())) {
            int stored = com.testrts.rtsclient.storage.StorageManager.get().total(net.minecraft.world.item.Items.STONE_BRICKS);
            int carried = mc.player.getInventory().countItem(net.minecraft.world.item.Items.STONE_BRICKS);
            report(String.format(Locale.ROOT, "    storage: %d linked, stone bricks %d left in chests (of %d), %d carried, %d placed",
                    com.testrts.rtsclient.storage.StorageManager.get().linked().size(), stored, STORED_BRICKS, carried,
                    order.size() - missing));
        }
        if (FROM_ME.contains(current.name())) {
            int carried = mc.player.getInventory().countItem(net.minecraft.world.item.Items.STONE_BRICKS);
            report(String.format(Locale.ROOT, "    ME: stone bricks %d left in the network (of %d), %d carried, %d placed; client sees %d, wireless=%s, terminals=%d",
                    meCount(mc, net.minecraft.world.item.Items.STONE_BRICKS), STORED_BRICKS, carried, order.size() - missing,
                    com.testrts.rtsclient.storage.MeNetwork.get().count(net.minecraft.world.item.Items.STONE_BRICKS),
                    com.testrts.rtsclient.storage.MeNetwork.get().wirelessUsable(mc),
                    com.testrts.rtsclient.storage.MeNetwork.get().terminals().size()));
        }
        if (current.name().equals("protect-clear")) {
            int kept = 0;
            int keptProtected = 0;
            int protectedTotal = 0;
            for (BlockPos p : order.keySet()) {
                boolean prot = p.getX() <= 0;
                boolean stone = level.getBlockState(p).is(Blocks.STONE);
                if (prot) {
                    protectedTotal++;
                    if (stone) {
                        keptProtected++;
                    }
                } else if (stone) {
                    kept++;
                }
            }
            report(String.format(Locale.ROOT, "    PROTECT: %d of %d protected blocks still stone (want all), %d unprotected left (want 0) -> %s",
                    keptProtected, protectedTotal, kept, keptProtected == protectedTotal && kept == 0 ? "OK" : "WRONG"));
            com.testrts.rtsclient.build.Protection.get().clear();
        }
        if (FROM_RS.contains(current.name())) {
            var rs = com.testrts.rtsclient.storage.MeNetwork.of(com.testrts.rtsclient.storage.Rs2.GRID);
            int carried = mc.player.getInventory().countItem(net.minecraft.world.item.Items.STONE_BRICKS);
            report(String.format(Locale.ROOT, "    RS: stone bricks %d left in the network (of %d), %d carried, %d placed; client sees %d, wireless=%s, grids=%d",
                    rsCount(mc, net.minecraft.world.item.Items.STONE_BRICKS), STORED_BRICKS, carried, order.size() - missing,
                    rs.count(net.minecraft.world.item.Items.STONE_BRICKS), rs.wirelessUsable(mc), rs.terminals().size()));
        }
        if (!missed.isEmpty()) {
            report("    missed: " + missed.stream().map(p -> p.subtract(O).toShortString()).toList());
        }
        if (!strays.isEmpty()) {
            report("    stray:  " + strays.stream().map(p -> p.subtract(O).toShortString() + "=" + level.getBlockState(p).getBlock().getName().getString()).toList());
        }
    }

    // ------------------------------------------------------------------ AE2 test network (server side, by reflection)

    private static boolean isAe2(BlockState st) {
        String ns = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace();
        return ns.equals("ae2") || ns.equals("refinedstorage");
    }

    private static net.minecraft.world.item.Item ae2Item(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ae2", id));
    }

    private static BlockState ae2Block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ae2", id)).defaultBlockState();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState st, String property, String value) {
        net.minecraft.world.level.block.state.properties.Property prop = st.getBlock().getStateDefinition().getProperty(property);
        if (prop == null) {
            return st;
        }
        Optional<Comparable> v = prop.getValue(value);
        return v.isPresent() ? st.setValue(prop, v.get()) : st;
    }

    private static void setSlot(Object blockEntity, int slot, net.minecraft.world.item.ItemStack stack) throws ReflectiveOperationException {
        Object inv = blockEntity.getClass().getMethod("getInternalInventory").invoke(blockEntity);
        // insertItem (not setItemDirect) so the block entity hears about it (the access point recalculates its range).
        Class.forName("appeng.api.inventories.InternalInventory").getMethod("insertItem", int.class, net.minecraft.world.item.ItemStack.class, boolean.class)
                .invoke(inv, slot, stack, false);
    }

    private static void onServer(Minecraft mc, String what, ThrowingRunnable body) {
        var server = mc.getSingleplayerServer();
        try {
            server.submit(() -> {
                try {
                    body.run(server.overworld(), server.getPlayerList().getPlayers().get(0));
                } catch (Exception e) {
                    RtsClientMod.LOGGER.error("[AUTOTEST] AE2 {} failed", what, e);
                    report("    AE2 " + what + " failed: " + e);
                }
            }).join();
        } catch (RuntimeException e) {
            report("    AE2 " + what + " failed: " + e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run(net.minecraft.server.level.ServerLevel level, net.minecraft.server.level.ServerPlayer player) throws Exception;
    }

    /** Controller, creative energy cell, drive with a 64k cell, boosted access point, and a terminal on a cable. */
    private static void meBuild(Minecraft mc) {
        onServer(mc, "network", (level, player) -> {
            BlockPos c = ME_CONTROLLER;
            level.setBlockAndUpdate(c, ae2Block("controller"));
            level.setBlockAndUpdate(c.south(), ae2Block("creative_energy_cell"));
            level.setBlockAndUpdate(c.east(), ae2Block("drive"));
            level.setBlockAndUpdate(c.above(), with(ae2Block("wireless_access_point"), "facing", "up"));
            setSlot(level.getBlockEntity(c.east()), 0, new net.minecraft.world.item.ItemStack(ae2Item("item_storage_cell_64k")));
            setSlot(level.getBlockEntity(c.above()), 0, new net.minecraft.world.item.ItemStack(ae2Item("wireless_booster"), 8));
            Class<?> partItem = Class.forName("appeng.api.parts.IPartItem");
            var setPart = Class.forName("appeng.api.parts.PartHelper").getMethod("setPart", net.minecraft.server.level.ServerLevel.class,
                    BlockPos.class, net.minecraft.core.Direction.class, net.minecraft.world.entity.player.Player.class, partItem);
            setPart.invoke(null, level, c.west(), null, player, ae2Item("fluix_glass_cable"));
            setPart.invoke(null, level, c.west(), net.minecraft.core.Direction.NORTH, player, ae2Item("crafting_terminal"));
            log("AE2 network placed at %s", c.toShortString());
        });
    }

    private static Object meStorage(net.minecraft.server.level.ServerLevel level) throws ReflectiveOperationException {
        Object be = level.getBlockEntity(ME_CONTROLLER);
        if (be == null) {
            throw new IllegalStateException("no block entity at the controller, block there is " + level.getBlockState(ME_CONTROLLER));
        }
        Object managed = be.getClass().getMethod("getMainNode").invoke(be);
        Class<?> managedClass = Class.forName("appeng.api.networking.IManagedGridNode");
        Object node = managedClass.getMethod("getNode").invoke(managed);
        Object grid = managedClass.getMethod("getGrid").invoke(managed);
        log("AE2 controller ready=%s active=%s", managedClass.getMethod("isReady").invoke(managed),
                node == null ? "no node" : Class.forName("appeng.api.networking.IGridNode").getMethod("isActive").invoke(node));
        if (grid == null) {
            throw new IllegalStateException("the controller has no grid yet");
        }
        Object service = Class.forName("appeng.api.networking.IGrid").getMethod("getStorageService").invoke(grid);
        return Class.forName("appeng.api.networking.storage.IStorageService").getMethod("getInventory").invoke(service);
    }

    private static Object itemKey(net.minecraft.world.item.Item item) throws ReflectiveOperationException {
        return Class.forName("appeng.api.stacks.AEItemKey").getMethod("of", net.minecraft.world.level.ItemLike.class).invoke(null, item);
    }

    private static Object actionable(String name) throws ReflectiveOperationException {
        return Class.forName("appeng.api.config.Actionable").getField(name).get(null);
    }

    private static Object noSource() throws ReflectiveOperationException {
        return Class.forName("appeng.api.networking.security.IActionSource").getMethod("empty").invoke(null);
    }

    /** Put the bricks and dirt into the network; for the wireless test, give a linked, charged wireless terminal. */
    private static void meFill(Minecraft mc, String wirelessItem) {
        onServer(mc, "fill", (level, player) -> {
            Object storage = meStorage(level);
            Class<?> keyClass = Class.forName("appeng.api.stacks.AEKey");
            var insert = Class.forName("appeng.api.storage.MEStorage").getMethod("insert", keyClass, long.class,
                    Class.forName("appeng.api.config.Actionable"), Class.forName("appeng.api.networking.security.IActionSource"));
            long bricks = (long) insert.invoke(storage, itemKey(net.minecraft.world.item.Items.STONE_BRICKS), (long) STORED_BRICKS, actionable("MODULATE"), noSource());
            long dirt = (long) insert.invoke(storage, itemKey(net.minecraft.world.item.Items.DIRT), 128L, actionable("MODULATE"), noSource());
            log("AE2 network filled: %d stone bricks, %d dirt", bricks, dirt);
            if (wirelessItem != null) {
                var stack = new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.parse(wirelessItem)));
                Class<?> terminal = Class.forName("appeng.items.tools.powered.WirelessTerminalItem");
                Object handler = terminal.getField("LINKABLE_HANDLER").get(null);
                Class.forName("appeng.api.features.IGridLinkableHandler").getMethod("link", net.minecraft.world.item.ItemStack.class, net.minecraft.core.GlobalPos.class)
                        .invoke(handler, stack, net.minecraft.core.GlobalPos.of(level.dimension(), ME_CONTROLLER.above()));
                Class.forName("appeng.api.implementations.items.IAEItemPowerStorage").getMethod("injectAEPower",
                        net.minecraft.world.item.ItemStack.class, double.class, Class.forName("appeng.api.config.Actionable"))
                        .invoke(stack.getItem(), stack, 10_000_000.0, actionable("MODULATE"));
                // Out of the hotbar, so it isn't in the way of the blocks being placed.
                player.getInventory().setItem(20, stack);
                log("AE2 wireless terminal %s linked and given", wirelessItem);
            }
        });
    }

    /** How many of {@code item} the network holds right now (asked on the server). */
    private static long meCount(Minecraft mc, net.minecraft.world.item.Item item) {
        long[] out = {-1};
        onServer(mc, "count", (level, player) -> {
            Object storage = meStorage(level);
            Class<?> keyClass = Class.forName("appeng.api.stacks.AEKey");
            out[0] = (long) Class.forName("appeng.api.storage.MEStorage").getMethod("extract", keyClass, long.class,
                    Class.forName("appeng.api.config.Actionable"), Class.forName("appeng.api.networking.security.IActionSource"))
                    .invoke(storage, itemKey(item), Long.MAX_VALUE, actionable("SIMULATE"), noSource());
        });
        return out[0];
    }

    // ------------------------------------------------------------------ Refined Storage test network

    /** Forget every link and remembered content (left over from earlier scenarios or runs). */
    private static void forgetStorage() {
        com.testrts.rtsclient.storage.StorageManager.get().unlinkAll();
        for (com.testrts.rtsclient.storage.MeNetwork n : com.testrts.rtsclient.storage.MeNetwork.all()) {
            n.reset();
        }
    }

    private static net.minecraft.world.item.Item modItem(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
    }

    private static BlockState modBlock(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id)).defaultBlockState();
    }

    /** Creative controller, disk drive, crafting grid and wireless transmitter; a 64k disk into the inventory. */
    private static void rsBuild(Minecraft mc) {
        onServer(mc, "RS network", (level, player) -> {
            BlockPos c = RS_CONTROLLER;
            level.setBlockAndUpdate(c, modBlock("refinedstorage:creative_controller"));
            level.setBlockAndUpdate(c.east(), modBlock("refinedstorage:disk_drive"));
            level.setBlockAndUpdate(c.west(), modBlock("refinedstorage:crafting_grid"));
            // The transmitter's state is the one a player placing it on top of the controller would get, so its
            // direction (and with it, the side it connects on) is what RS expects.
            var transmitter = new net.minecraft.world.item.ItemStack(modItem("refinedstorage:wireless_transmitter"));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, transmitter);
            var top = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(c).add(0, 0.5, 0),
                    net.minecraft.core.Direction.UP, c, false);
            var block = ((net.minecraft.world.item.BlockItem) transmitter.getItem()).getBlock();
            BlockState placedState = block.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(
                    player, net.minecraft.world.InteractionHand.MAIN_HAND, transmitter, top));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            level.setBlockAndUpdate(c.above(), placedState);
            log("RS wireless transmitter placed as %s", level.getBlockState(c.above()));
            // A new disk gets its storage while it ticks in an inventory.
            player.getInventory().setItem(30, new net.minecraft.world.item.ItemStack(modItem("refinedstorage:64k_storage_disk")));
            log("RS network placed at %s", c.toShortString());
        });
    }

    private static void rsDisk(Minecraft mc) {
        onServer(mc, "RS disk", (level, player) -> {
            var disk = player.getInventory().getItem(30).copy();
            player.getInventory().setItem(30, net.minecraft.world.item.ItemStack.EMPTY);
            Object drive = level.getBlockEntity(RS_CONTROLLER.east());
            var inv = (net.minecraft.world.Container) drive.getClass().getMethod("getDiskInventory").invoke(drive);
            inv.setItem(0, disk);
            log("RS disk in the drive: %s", disk);
        });
    }

    /** The RS network the block at {@code pos} is part of, or null. */
    private static Object rsNetwork(net.minecraft.server.level.ServerLevel level, BlockPos pos) throws ReflectiveOperationException {
        Object be = level.getBlockEntity(pos);
        Class<?> containerClass = Class.forName("com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity");
        if (!containerClass.isInstance(be)) {
            return null;
        }
        var field = containerClass.getDeclaredField("mainNetworkNode");
        field.setAccessible(true);
        Object node = field.get(be);
        return Class.forName("com.refinedmods.refinedstorage.api.network.node.NetworkNode").getMethod("getNetwork").invoke(node);
    }

    private static Object rsStorage(net.minecraft.server.level.ServerLevel level) throws ReflectiveOperationException {
        Object network = rsNetwork(level, RS_CONTROLLER);
        if (network == null) {
            throw new IllegalStateException("the controller has no network");
        }
        Class<?> component = Class.forName("com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent");
        return Class.forName("com.refinedmods.refinedstorage.api.core.component.ComponentAccessor")
                .getMethod("getComponent", Class.class).invoke(network, component);
    }

    private static Object rsKey(net.minecraft.world.item.Item item) throws ReflectiveOperationException {
        return Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource")
                .getConstructor(net.minecraft.world.item.Item.class).newInstance(item);
    }

    private static long rsMove(Object storage, String method, Object key, long amount, String action) throws ReflectiveOperationException {
        Class<?> keyClass = Class.forName("com.refinedmods.refinedstorage.api.resource.ResourceKey");
        Class<?> actionClass = Class.forName("com.refinedmods.refinedstorage.api.core.Action");
        Class<?> actorClass = Class.forName("com.refinedmods.refinedstorage.api.storage.Actor");
        Object actor = actorClass.getField("EMPTY").get(null);
        String iface = method.equals("insert") ? "InsertableStorage" : "ExtractableStorage";
        return (long) Class.forName("com.refinedmods.refinedstorage.api.storage." + iface).getMethod(method, keyClass, long.class, actionClass, actorClass)
                .invoke(storage, key, amount, actionClass.getField(action).get(null), actor);
    }

    /** Bricks and dirt into the network; for the wireless test, a bound creative wireless crafting grid. */
    private static void rsFill(Minecraft mc, boolean wireless) {
        onServer(mc, "RS fill", (level, player) -> {
            Object storage = rsStorage(level);
            log("RS transmitter on the controller's network: %s", rsNetwork(level, RS_CONTROLLER.above()) == rsNetwork(level, RS_CONTROLLER)
                    && rsNetwork(level, RS_CONTROLLER) != null);
            long bricks = rsMove(storage, "insert", rsKey(net.minecraft.world.item.Items.STONE_BRICKS), STORED_BRICKS, "EXECUTE");
            long dirt = rsMove(storage, "insert", rsKey(net.minecraft.world.item.Items.DIRT), 128, "EXECUTE");
            log("RS network filled: %d stone bricks, %d dirt", bricks, dirt);
            if (wireless) {
                var stack = new net.minecraft.world.item.ItemStack(modItem("refinedstorage_quartz_arsenal:creative_wireless_crafting_grid"));
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                BlockPos drive = RS_CONTROLLER.east();
                var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(drive).add(0, 0.5, 0),
                        net.minecraft.core.Direction.UP, drive, false);
                var result = stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
                var bound = player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).copy();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                // Out of the hotbar, so it isn't in the way of the blocks being placed.
                player.getInventory().setItem(20, bound);
                log("RS wireless crafting grid bound (%s) and given", result);
            }
        });
    }

    /** How many of {@code item} the RS network holds right now (asked on the server). */
    private static long rsCount(Minecraft mc, net.minecraft.world.item.Item item) {
        long[] out = {-1};
        onServer(mc, "RS count", (level, player) -> out[0] = rsMove(rsStorage(level), "extract", rsKey(item), Long.MAX_VALUE, "SIMULATE"));
        return out[0];
    }

    /** RTS camera looking at the structure from above one side, for the screenshot. */
    private static void showStructure(Minecraft mc) {
        RtsController.get().enable(mc);
        double cx = 0.5, cy = O.getY() + 4, cz = 0.5;
        double x = cx - 18, y = cy + 14, z = cz - 18;
        double dx = cx - x, dy = cy - y, dz = cz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        RtsController.get().camera().setPose(mc, new RtsCamera.Pose(x, y, z, yaw, pitch));
    }

    /** Survival: give everything the order needs (stone and dirt come with every scenario). */
    /** FramedBlocks presets: every frame wears the camo it should. */
    private static void verifyCamo(Minecraft mc) {
        int ok = 0;
        List<String> bad = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : camoOrder.entrySet()) {
            if (com.testrts.rtsclient.task.CamoTask.hasCamo(mc.level, e.getKey(), e.getValue())) {
                ok++;
            } else if (bad.size() < 10) {
                bad.add(e.getKey().subtract(O).toShortString() + " " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getValue().getBlock())
                        + " on " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(e.getKey()).getBlock()));
            }
        }
        report(String.format(Locale.ROOT, "RESULT %-16s %s camo=%d/%d camoTime=%ds", current.name() + "-camo",
                ok == camoOrder.size() ? "PASS" : "FAIL", ok, camoOrder.size(), camoStart < 0 ? 0 : (ticks - camoStart) / 20));
        if (!bad.isEmpty()) {
            report("    no camo: " + bad);
        }
    }

    private static void giveMaterials(Minecraft mc, Map<BlockPos, BlockState> blocks) {
        Map<net.minecraft.world.item.Item, Integer> need = OrderPlanner.materials(blocks);
        need.forEach((item, n) -> cmd(mc, "give @s " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item) + " " + (n + 8)));
    }

    private static void cmd(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }

    private static void log(String fmt, Object... args) {
        String line = String.format(Locale.ROOT, fmt, args);
        RtsClientMod.LOGGER.info("[AUTOTEST] {}", line);
    }

    private static void report(String line) {
        RtsClientMod.LOGGER.info("[AUTOTEST] {}", line);
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("results.txt"), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            RtsClientMod.LOGGER.error("[AUTOTEST] can't write results", e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
