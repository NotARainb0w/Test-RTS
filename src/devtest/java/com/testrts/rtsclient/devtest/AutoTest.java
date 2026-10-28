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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorLayer;
import net.minecraft.world.level.LevelInfo;

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
    /** Scenarios run in creative mode (creative flight and creative supply). */
    private static final java.util.Set<String> CREATIVE = new java.util.HashSet<>();
    /** Scenarios run in survival with flight from an "item": the server lets the player fly (like an Angel Ring). */
    private static final java.util.Set<String> ITEM_FLIGHT = new java.util.HashSet<>();

    private static void scenario(String name, BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow,
                                 String engine, boolean airPlace) {
        scenario(name, shape, a, b, height, hollow, engine, airPlace, new BlockPos(-12, 0, -3));
    }

    /** @param start where the player stands at the start (relative to the arena origin) */
    private static void scenario(String name, BuildShape shape, BlockPos a, BlockPos b, int height, boolean hollow,
                                 String engine, boolean airPlace, BlockPos start) {
        SCENARIOS.put(name, new Scenario(name, shape, O.add(a), O.add(b), height, hollow, engine, airPlace,
                O.add(start), 20 * 60 * 5, null));
    }

    /** Scenarios whose blocks come from code (relative to {@code a}) instead of a shape or preset. */
    private static final Map<String, java.util.function.Function<BlockPos, Map<BlockPos, BlockState>>> CUSTOM = new java.util.HashMap<>();

    private static void custom(String name, java.util.function.Function<BlockPos, Map<BlockPos, BlockState>> blocks) {
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O.add(-5, 0, -5), O, 1, false, "builtin", true,
                O.add(-12, 0, -10), 20 * 60 * 5, null));
        CUSTOM.put(name, blocks);
    }

    /**
     * A building over several floors: 11x11 stone-brick walls with windows, plank floors with one
     * 1x2 stairwell hole each (no stairs: getting up is the builder's job), a door gap on the ground
     * floor and a roof overhanging the walls by one block all round.
     */
    private static Map<BlockPos, BlockState> storeys(BlockPos o, int floors) {
        Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
        BlockState wall = Blocks.STONE_BRICKS.getDefaultState();
        BlockState floor = Blocks.OAK_PLANKS.getDefaultState();
        BlockState glass = Blocks.GLASS.getDefaultState();
        int top = floors * 4;
        for (int y = 0; y <= top; y++) {
            for (int x = 0; x <= 10; x++) {
                for (int z = 0; z <= 10; z++) {
                    boolean edge = x == 0 || x == 10 || z == 0 || z == 10;
                    BlockPos p = o.add(x, y, z);
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
                    m.put(o.add(x, top, z), wall);
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
                m.put(o.add(x, y, 0), Blocks.STONE_BRICKS.getDefaultState());
            }
        }
        return m;
    }

    private static void preset(String name, String preset) {
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O.add(-7, 0, -6), O, 1, false, "builtin", true,
                O.add(-12, 0, -10), 20 * 60 * 5, preset));
    }

    static {
        scenario("dome6", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(6, 0, 0), 1, true, "builtin", true);
        scenario("dome10", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        scenario("sphere5", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", true);
        scenario("floor", BuildShape.FLOOR, new BlockPos(-3, 6, -3), new BlockPos(3, 6, 3), 1, false, "builtin", true);
        scenario("line", BuildShape.LINE, new BlockPos(-8, 4, 0), new BlockPos(8, 9, 4), 1, false, "builtin", true);
        scenario("dome6-noair", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(6, 0, 0), 1, true, "builtin", false);
        scenario("sphere5-noair", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", false);
        // Baritone builds: mid-air placing off, or the built-in engine takes over builds (Baritone can't do it).
        scenario("dome6-baritone", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(6, 0, 0), 1, true, "baritone", false);
        scenario("box-baritone", BuildShape.BOX, new BlockPos(-2, 0, -2), new BlockPos(2, 0, 2), 3, true, "baritone", false);
        // Starts inside: the finished dome closes around the player, who must open a doorway and close it from outside.
        scenario("dome6-inside", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(6, 0, 0), 1, true, "builtin", true, new BlockPos(1, 0, 1));
        scenario("pyramid", BuildShape.PYRAMID, BlockPos.ORIGIN, new BlockPos(5, 0, 0), 1, true, "builtin", true);
        scenario("cone", BuildShape.CONE, BlockPos.ORIGIN, new BlockPos(4, 0, 0), 8, true, "builtin", true);
        scenario("torus", BuildShape.TORUS, new BlockPos(0, 3, 0), new BlockPos(6, 3, 0), 2, true, "builtin", true);
        scenario("arch", BuildShape.ARCH, new BlockPos(-6, 0, 0), new BlockPos(6, 0, 0), 1, true, "builtin", true);
        scenario("bridge", BuildShape.BRIDGE, new BlockPos(-10, 0, 2), new BlockPos(10, 0, 2), 3, false, "builtin", true);
        scenario("stairs", BuildShape.STAIRS, new BlockPos(-6, 0, 0), new BlockPos(6, 6, 0), 2, false, "builtin", true);
        scenario("spiral", BuildShape.SPIRAL, BlockPos.ORIGIN, new BlockPos(3, 0, 0), 12, false, "builtin", true);
        // Materials from chests: a hollow box needing several stacks, and a dome with mid-air placing off
        // whose scaffolding (dirt) is only in storage.
        scenario("storage-box", BuildShape.BOX, new BlockPos(-3, 0, -3), new BlockPos(3, 0, 3), 4, true, "builtin", true);
        scenario("storage-dome", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(5, 0, 0), 1, true, "builtin", false);
        FROM_STORAGE.add("storage-box");
        FROM_STORAGE.add("storage-dome");
        // Creative mode: flies to the blocks instead of walking and climbing.
        scenario("fly-sphere", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", true);
        scenario("fly-dome10", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        CREATIVE.add("fly-sphere");
        CREATIVE.add("fly-dome10");
        // Survival with a flight item: should fly just like creative.
        scenario("flyitem-sphere", BuildShape.SPHERE, new BlockPos(0, 8, 0), new BlockPos(5, 8, 0), 1, true, "builtin", true);
        scenario("flyitem-dome10", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        ITEM_FLIGHT.add("flyitem-sphere");
        ITEM_FLIGHT.add("flyitem-dome10");
        // Builds up in the sky, starting on the ground: only reachable by flying.
        scenario("fly-sky-floor", BuildShape.FLOOR, new BlockPos(-4, 20, -4), new BlockPos(4, 20, 4), 1, false, "builtin", true);
        scenario("flyitem-sky-floor", BuildShape.FLOOR, new BlockPos(-4, 20, -4), new BlockPos(4, 20, 4), 1, false, "builtin", true);
        scenario("fly-sky-box", BuildShape.BOX, new BlockPos(-3, 24, -3), new BlockPos(3, 24, 3), 5, true, "builtin", true);
        scenario("flyitem-sky-box", BuildShape.BOX, new BlockPos(-3, 24, -3), new BlockPos(3, 24, 3), 5, true, "builtin", true);
        CREATIVE.add("fly-sky-floor");
        CREATIVE.add("fly-sky-box");
        ITEM_FLIGHT.add("flyitem-sky-floor");
        ITEM_FLIGHT.add("flyitem-sky-box");
        // Multi-place speed: the same builds with several blocks a tick.
        scenario("fly-dome10-multi4", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        scenario("fly-dome10-multi8", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        CREATIVE.add("fly-dome10-multi4");
        CREATIVE.add("fly-dome10-multi8");
        scenario("dome6-multi4", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(6, 0, 0), 1, true, "builtin", true);
        scenario("fly-sky-box-multi4", BuildShape.BOX, new BlockPos(-3, 24, -3), new BlockPos(3, 24, 3), 5, true, "builtin", true);
        scenario("flyitem-sky-box-multi4", BuildShape.BOX, new BlockPos(-3, 24, -3), new BlockPos(3, 24, 3), 5, true, "builtin", true);
        CREATIVE.add("fly-sky-box-multi4");
        // Fly above the layer off, to compare.
        scenario("fly-dome10-multi4-low", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        scenario("fly-dome10-low", BuildShape.DOME, BlockPos.ORIGIN, new BlockPos(10, 0, 0), 1, true, "builtin", true);
        CREATIVE.add("fly-dome10-multi4-low");
        CREATIVE.add("fly-dome10-low");
        // A floating island: its underside widens upward, so each layer's middle is only reachable from above.
        scenario("fly-island-multi8", BuildShape.ISLAND, new BlockPos(0, 15, 0), new BlockPos(8, 15, 0), 1, false, "builtin", true);
        scenario("fly-island", BuildShape.ISLAND, new BlockPos(0, 15, 0), new BlockPos(8, 15, 0), 1, false, "builtin", true);
        CREATIVE.add("fly-island-multi8");
        CREATIVE.add("fly-island");
        ITEM_FLIGHT.add("flyitem-sky-box-multi4");
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
        // Blend brush: a grassy mound and a pit, painted over and smoothed into the flat ground around them.
        SCENARIOS.put("blend", new Scenario("blend", BuildShape.BLOCK, O, O, 1, false, "builtin", true,
                O.add(-12, 0, -10), 20 * 60 * 5, null));
        // Level brush: the same ground levelled one block above the floor (mines the mound, fills the pit and
        // raises the floor), with a sloped edge.
        SCENARIOS.put("level", new Scenario("level", BuildShape.BLOCK, O, O, 1, false, "builtin", true,
                O.add(-12, 0, -10), 20 * 60 * 5, null));
        // Hold-Z: a row of stairs each way round, upright and upside down, placed by holding the key.
        SCENARIOS.put("zstairs", new Scenario("zstairs", BuildShape.BLOCK, O, O, 1, false, "builtin", true,
                O.add(0, 0, 3), 20 * 30, null));
        // A roof planned on the hologram of a queued (not yet built) room.
        SCENARIOS.put("ghost-roof", new Scenario("ghost-roof", BuildShape.BLOCK, O, O, 1, false, "builtin", true,
                O.add(-12, 0, -10), 20 * 10, null));
    }

    /** The terrain brush scenarios (blend, level): painted ground instead of a shape. */
    private static boolean brushScenario(String name) {
        return name.equals("blend") || name.equals("level");
    }

    /** Level scenario: the painted columns, and the height they should end at. */
    private static java.util.Set<Long> levelPainted = java.util.Set.of();
    private static final int LEVEL_Y = O.getY();

    /** Blend scenario: the clear order that runs before the fill order (tracked as {@link #task}). */
    private static BuildTask blendClear;
    /** Blend scenario: steepest step between neighbouring painted columns before blending. */
    private static int blendStepBefore;
    private static final int BLEND_X = 9;
    private static final int BLEND_Z = 5;

    /** Top solid block of a column in the arena. */
    private static int groundY(MinecraftClient mc, int x, int z) {
        for (int y = O.getY() + 10; y > O.getY() - 4; y--) {
            if (!mc.world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y;
            }
        }
        return O.getY() - 4;
    }

    /** Steepest height step between neighbouring columns of the painted area. */
    private static int maxStep(MinecraftClient mc) {
        int max = 0;
        for (int x = -BLEND_X; x <= BLEND_X; x++) {
            for (int z = -BLEND_Z; z <= BLEND_Z; z++) {
                int h = groundY(mc, x, z);
                if (x < BLEND_X) {
                    max = Math.max(max, Math.abs(h - groundY(mc, x + 1, z)));
                }
                if (z < BLEND_Z) {
                    max = Math.max(max, Math.abs(h - groundY(mc, x, z + 1)));
                }
            }
        }
        return max;
    }

    private enum Phase { TITLE, JOINING, SETUP, START, RUN, ZRUN, WRUN, SHOT, NEXT, FINISHED }

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
        SCENARIOS.put(name, new Scenario(name, BuildShape.BLOCK, O, O, 1, false, "builtin", true, O.add(-20, 0, -20), 20 * 90, null));
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
    private static void startWalk(MinecraftClient mc, Walk w) {
        var level = mc.world;
        BlockPos goal = O.add(w.to());
        var search = com.testrts.rtsclient.exec.PathFinder.start(level,
                com.testrts.rtsclient.exec.Walkability.feetNode(level, mc.player), p -> p.getSquaredDistance(goal) <= 1.0,
                net.minecraft.util.math.Vec3d.ofBottomCenter(goal));
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

    private static void verifyWalk(MinecraftClient mc, Walk w, boolean timedOut) {
        BlockPos feet = mc.player.getBlockPos();
        BlockPos goal = O.add(w.to());
        boolean arrived = feet.getSquaredDistance(goal) <= 2.0;
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
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException ex) {
                RtsClientMod.LOGGER.error("[AUTOTEST] crashed", ex);
                report("CRASH " + ex);
                phase = Phase.FINISHED;
            }
        });
    }

    private static void tick(MinecraftClient mc) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case TITLE -> {
                if (mc.currentScreen instanceof TitleScreen) {
                    out = mc.runDirectory.toPath().resolve("autotest");
                    createWorld(mc);
                    phase = Phase.JOINING;
                }
            }
            case JOINING -> {
                if (mc.player != null && mc.world != null && mc.currentScreen == null) {
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
                        mc.scheduleStop();
                    }
                    return;
                }
                current = queue.remove(0);
                setup(mc, current);
                wait = 60;
                phase = Phase.START;
            }
            case START -> {
                // The arena clear runs on the server; plan only once it has reached us, or leftovers of the
                // last scenario count as "already built" (a dome came out with a hole in its top that way).
                if (!brushScenario(current.name()) && !walkReady && !arenaClear(mc) && ++clearWaits < 20) {
                    wait = 10;
                    return;
                }
                clearWaits = 0;
                Scenario s = current;
                RtsConfig cfg = RtsConfig.get();
                cfg.engine = s.engine();
                cfg.airPlace = s.airPlace();
                // "-low": Fly above the layer off.
                cfg.flyAboveLayer = !s.name().contains("-low");
                // "-multiN": Multi-place, N blocks at once.
                cfg.multiPlace = multiPlaceOf(s.name());
                if (WALKS.containsKey(s.name())) {
                    Walk w = WALKS.get(s.name());
                    if (!walkReady) {
                        // Shape the ground, then stand at the start once the blocks have reached us.
                        cmd(mc, "kill @e[type=cow]");
                        w.setup().apply(O.getY()).forEach(c -> cmd(mc, c));
                        BlockPos f = O.add(w.from());
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
                if (s.name().equals("zstairs")) {
                    startZStairs(mc);
                    return;
                }
                if (s.name().equals("ghost-roof")) {
                    ghostRoof(mc);
                    wait = 10;
                    phase = Phase.SHOT;
                    return;
                }
                OrderPlanner.Request r = new OrderPlanner.Request(ToolMode.BUILD, s.shape(), BuildMode.FILL, s.a(), s.b(),
                        s.height(), s.hollow(), (FROM_STORAGE.contains(s.name()) ? Blocks.STONE_BRICKS : Blocks.STONE).getDefaultState(),
                        null, 100_000, List.of(), true,
                        com.testrts.rtsclient.build.MixPattern.RANDOM);
                if (brushScenario(s.name())) {
                    // Paint like dragging the brush over the area, then plan as Enter does.
                    com.testrts.rtsclient.build.BuildSession paint = new com.testrts.rtsclient.build.BuildSession();
                    paint.blendRadius = 2;
                    for (int x = -BLEND_X + 2; x <= BLEND_X - 2; x += 2) {
                        for (int z = -BLEND_Z + 2; z <= BLEND_Z - 2; z += 2) {
                            paint.paintBlend(new BlockPos(x, O.getY() - 1, z), false);
                        }
                    }
                    var mode = s.name().equals("level") ? com.testrts.rtsclient.build.TerrainMode.LEVEL
                            : com.testrts.rtsclient.build.TerrainMode.BLEND;
                    levelPainted = new java.util.HashSet<>(paint.blendPaint.keySet());
                    var plan = com.testrts.rtsclient.screen.WorldActions.brushPlan(mc.world, paint.blendPaint, mode,
                            paint.blendStrength, LEVEL_Y, paint.levelRamp, paint.blendKeepTop, 100_000);
                    blendStepBefore = maxStep(mc);
                    order = new java.util.LinkedHashMap<>(plan.clear());
                    order.putAll(plan.fill());
                    log(s.name() + ": %d columns painted, mine %d, fill %d, steepest step %d",
                            paint.blendPaint.size(), plan.clear().size(), plan.fill().size(), blendStepBefore);
                    blendClear = plan.clear().isEmpty() ? null : new BuildTask("autotest " + s.name() + ": mine", plan.clear(), true);
                    if (blendClear != null) {
                        TaskQueue.get().add(blendClear);
                    }
                    task = new BuildTask("autotest " + s.name() + ": fill", plan.fill(), false);
                    TaskQueue.get().add(task);
                    TaskQueue.get().start();
                    ticks = 0;
                    lastLeft = Integer.MAX_VALUE;
                    lastProgress = 0;
                    maxStall = 0;
                    stalls = 0;
                    minHealth = mc.player.getHealth();
                    phase = Phase.RUN;
                    return;
                } else if (CUSTOM.containsKey(s.name())) {
                    order = new java.util.LinkedHashMap<>();
                    CUSTOM.get(s.name()).apply(s.a()).forEach((pos, st) -> {
                        if (!MaterialPlanner.matches(mc.world.getBlockState(pos), st)) {
                            order.put(pos, st);
                        }
                    });
                    cmd(mc, "clear @s minecraft:stone");
                    giveMaterials(mc, order);
                } else if (s.preset() != null) {
                    // A preset building, pasted like the Blueprint tool does (only what isn't there already).
                    var bp = com.testrts.rtsclient.build.Presets.all().stream().filter(p -> p.name().equals(s.preset()))
                            .findFirst().orElseThrow().build().get();
                    order = new java.util.LinkedHashMap<>();
                    bp.placed(s.a(), 0).forEach((pos, st) -> {
                        if (!MaterialPlanner.matches(mc.world.getBlockState(pos), st)) {
                            order.put(pos, st);
                        }
                    });
                    cmd(mc, "clear @s minecraft:stone"); // room in the inventory for the preset's blocks
                    giveMaterials(mc, order);
                } else {
                    order = OrderPlanner.plan(mc.world, r).changes();
                }
                if (FROM_STORAGE.contains(s.name())) {
                    // Like pressing Scan nearby: link the chests and read them; the build then fetches.
                    new com.testrts.rtsclient.screen.WorldActions(mc, RtsController.get()).scanNearby();
                } else if (s.preset() == null && !CUSTOM.containsKey(s.name())) {
                    // Railings, lanterns...: anything besides the stone every scenario gets.
                    Map<BlockPos, BlockState> extra = new java.util.HashMap<>(order);
                    extra.values().removeIf(st -> st.isOf(Blocks.STONE));
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
                if (ITEM_FLIGHT.contains(current.name())) {
                    grantFlight(mc);
                }
                minHealth = Math.min(minHealth, mc.player.getHealth());
                if (ticks % 20 == 0) {
                    // Stalls: stretches without a single block done (stuck getting to one, mostly).
                    int left = MaterialPlanner.remaining(mc.world, order);
                    // Once everything is built, the scaffolding comes down: leftovers show as stray, not as a stall.
                    if (left < lastLeft || left == 0) {
                        lastLeft = left;
                        lastProgress = ticks;
                    } else if (ticks - lastProgress == 20 * 15) {
                        stalls++;
                        log("  STALL 15s at left=%d status=%s pos=%s", left, Engine.status(), mc.player.getBlockPos().subtract(O).toShortString());
                        log("  STALL state: %s", com.testrts.rtsclient.exec.NativeEngine.get().debugState(mc));
                    }
                    maxStall = Math.max(maxStall, ticks - lastProgress);
                }
                if (ticks % 100 == 0) {
                    int left = MaterialPlanner.remaining(mc.world, order);
                    log("  t=%ds left=%d status=%s task=%s pos=%s fly=%s/%s", ticks / 20, left, Engine.status(),
                            task.detail(),
                            mc.player.getBlockPos().toShortString(), mc.player.getAbilities().flying, mc.player.getAbilities().allowFlying);
                }
                // Only our own order counts: someone may queue things by hand in the same window.
                boolean running = TaskQueue.get().snapshot().contains(task)
                        || blendClear != null && TaskQueue.get().snapshot().contains(blendClear);
                // Bigger orders get longer: roughly a second a block on top of the base limit.
                int limit = Math.max(current.timeoutTicks(), 20 * (120 + order.size()));
                if (!running || ticks > limit) {
                    TaskQueue.get().remove(mc, task);
                    if (blendClear != null) {
                        TaskQueue.get().remove(mc, blendClear);
                    }
                    boolean timedOut = running;
                    verify(mc, timedOut);
                    showStructure(mc);
                    wait = 30;
                    phase = Phase.SHOT;
                }
            }
            case ZRUN -> {
                ticks++;
                int left = 0;
                for (Map.Entry<BlockPos, BlockState> e : order.entrySet()) {
                    if (!mc.world.getBlockState(e.getKey()).isOf(e.getValue().getBlock())) {
                        left++;
                    }
                }
                if (left == 0 || ticks > current.timeoutTicks()) {
                    RtsClientMod.autoPlaceKey.setPressed(false);
                    TaskQueue.get().remove(mc, task);
                    int missing = 0;
                    int wrong = 0;
                    for (Map.Entry<BlockPos, BlockState> e : order.entrySet()) {
                        BlockState now = mc.world.getBlockState(e.getKey());
                        BlockState want = e.getValue();
                        if (!now.isOf(want.getBlock())) {
                            missing++;
                            report("    missing " + e.getKey().subtract(O).toShortString() + " " + describeStair(want));
                        } else if (now.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING) != want.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING)
                                || now.get(net.minecraft.state.property.Properties.BLOCK_HALF) != want.get(net.minecraft.state.property.Properties.BLOCK_HALF)) {
                            wrong++;
                            report("    wrong   " + e.getKey().subtract(O).toShortString() + " wanted " + describeStair(want) + ", got " + describeStair(now));
                        }
                    }
                    report(String.format(Locale.ROOT, "RESULT %-16s %s stairs=%d missing=%d wrong-way=%d time=%ds",
                            "zstairs", missing == 0 && wrong == 0 ? "PASS" : "FAIL", order.size(), missing, wrong, ticks / 20));
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
                    log("  t=%ds status=%s pos=%s", ticks / 20, Engine.status(), mc.player.getBlockPos().subtract(O).toShortString());
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
                ScreenshotRecorder.saveScreenshot(mc.runDirectory, "autotest-" + current.name() + ".png", mc.getFramebuffer(), c -> {
                });
                RtsController.get().disable(mc);
                wait = 10;
                phase = Phase.NEXT;
            }
            case FINISHED -> {
            }
        }
    }

    private static String describeStair(BlockState s) {
        return s.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING).asString() + "/"
                + s.get(net.minecraft.state.property.Properties.BLOCK_HALF).asString();
    }

    /** Hold-Z test: queue (don't start) a build of stairs facing every way, upright and upside down, and hold the key. */
    private static void startZStairs(MinecraftClient mc) {
        RtsConfig cfg = RtsConfig.get();
        cfg.serverSafePlacing = false;
        cfg.autoPlaceRange = 0;
        cfg.autoPlaceSpeed = 10;
        cfg.autoStart = false;
        cfg.autoPlaceLayers = true;
        Map<BlockPos, BlockState> stairs = new java.util.LinkedHashMap<>();
        net.minecraft.util.math.Direction[] dirs = {net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.EAST,
                net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.WEST};
        for (int i = 0; i < 4; i++) {
            int x = -3 + 2 * i;
            for (int[] row : new int[][]{{1, 0}, {5, 1}}) {
                stairs.put(O.add(x, 0, row[0]), Blocks.STONE_BRICK_STAIRS.getDefaultState()
                        .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, dirs[i])
                        .with(net.minecraft.state.property.Properties.BLOCK_HALF,
                                row[1] == 1 ? net.minecraft.block.enums.BlockHalf.TOP : net.minecraft.block.enums.BlockHalf.BOTTOM));
            }
        }
        order = stairs;
        cmd(mc, "clear @s minecraft:stone");
        cmd(mc, "give @s stone_brick_stairs 16");
        cmd(mc, String.format(Locale.ROOT, "tp @s %d %d %d 0 30", 0, O.getY(), 3));
        task = new BuildTask("autotest zstairs", stairs, false);
        TaskQueue.get().add(task);
        RtsClientMod.autoPlaceKey.setPressed(true);
        ticks = 0;
        log("start zstairs: %d stairs, hold-Z", stairs.size());
        phase = Phase.ZRUN;
    }

    /** Roof on a hologram: a queued 7x7 room 3 high that isn't built; the roof planner should still find its walls. */
    private static void ghostRoof(MinecraftClient mc) {
        Map<BlockPos, BlockState> walls = new java.util.LinkedHashMap<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x == -3 || x == 3 || z == -3 || z == 3) {
                    for (int y = 0; y <= 2; y++) {
                        walls.put(O.add(x, y, z), Blocks.STONE_BRICKS.getDefaultState());
                    }
                }
            }
        }
        RtsConfig.get().autoStart = false;
        order = walls;
        task = new BuildTask("autotest ghost-roof", walls, false);
        TaskQueue.get().add(task);
        String[] err = new String[1];
        var roof = com.testrts.rtsclient.build.RoofPlanner.plan(mc.world, O.add(-3, 2, 0), Blocks.OAK_STAIRS.getDefaultState(),
                com.testrts.rtsclient.build.RoofPlanner.Style.GABLE, err);
        TaskQueue.get().remove(mc, task);
        String[] err2 = new String[1];
        var without = com.testrts.rtsclient.build.RoofPlanner.plan(mc.world, O.add(-3, 2, 0), Blocks.OAK_STAIRS.getDefaultState(),
                com.testrts.rtsclient.build.RoofPlanner.Style.GABLE, err2);
        boolean pass = roof != null && roof.width() == 7 && roof.depth() == 7 && without == null;
        report(String.format(Locale.ROOT, "RESULT %-16s %s roof=%s, with the order gone: %s", "ghost-roof", pass ? "PASS" : "FAIL",
                roof == null ? "none (" + err[0] + ")" : roof.width() + "x" + roof.depth() + ", " + roof.blueprint().blockCount() + " blocks",
                without == null ? "no roof (" + err2[0] + ")" : "a roof anyway"));
    }

    private static void createWorld(MinecraftClient mc) {
        Path dir = mc.runDirectory.toPath().resolve("saves").resolve(WORLD);
        deleteTree(dir);
        LevelInfo settings = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(),
                DataConfiguration.SAFE_MODE);
        GeneratorOptions options = new GeneratorOptions(1234L, false, false);
        mc.createIntegratedServerLoader().createAndStart(WORLD, settings, options, AutoTest::flatDimensions);
    }

    private static DimensionOptionsRegistryHolder flatDimensions(DynamicRegistryManager drm) {
        var biomes = drm.get(RegistryKeys.BIOME).getReadOnlyWrapper();
        FlatChunkGeneratorConfig flat = new FlatChunkGeneratorConfig(Optional.empty(), FlatChunkGeneratorConfig.getPlains(biomes), List.of());
        flat.getLayers().add(new FlatChunkGeneratorLayer(1, Blocks.BEDROCK));
        flat.getLayers().add(new FlatChunkGeneratorLayer(2, Blocks.DIRT));
        flat.getLayers().add(new FlatChunkGeneratorLayer(1, Blocks.GRASS_BLOCK));
        flat.updateLayerBlocks();
        return WorldPresets.createDemoOptions(drm).with(drm, new FlatChunkGenerator(flat));
    }

    /** Nothing but air above the arena floor (chests of storage scenarios aside). */
    private static boolean arenaClear(MinecraftClient mc) {
        for (int x = -ARENA; x <= ARENA; x++) {
            for (int z = -ARENA; z <= ARENA; z++) {
                for (int y = O.getY(); y < O.getY() + 30; y++) {
                    BlockState st = mc.world.getBlockState(new BlockPos(x, y, z));
                    if (!st.isAir() && !st.isOf(Blocks.CHEST)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Survival flight the way an Angel Ring gives it: the server lets the player fly. Re-applied every tick,
     * as those items do (a game mode change resets it).
     */
    private static void grantFlight(MinecraftClient mc) {
        var server = mc.getServer();
        if (server == null || mc.player == null) {
            return;
        }
        java.util.UUID id = mc.player.getUuid();
        server.execute(() -> {
            var sp = server.getPlayerManager().getPlayer(id);
            if (sp != null && !sp.getAbilities().allowFlying) {
                sp.getAbilities().allowFlying = true;
                sp.sendAbilitiesUpdate();
            }
        });
    }

    /** "-multiN" in a scenario name: Multi-place N at once (1 without). */
    private static int multiPlaceOf(String name) {
        int at = name.indexOf("-multi");
        if (at < 0) {
            return 1;
        }
        int end = at + 6;
        while (end < name.length() && Character.isDigit(name.charAt(end))) {
            end++;
        }
        return Integer.parseInt(name.substring(at + 6, end));
    }

    private static void setup(MinecraftClient mc, Scenario s) {
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
        if (FROM_STORAGE.contains(s.name())) {
            // Nothing to build with in the inventory: it's all in two chests by the arena's edge.
            cmd(mc, "clear @s minecraft:stone");
            cmd(mc, "clear @s minecraft:dirt");
            String bricks = "{Slot:%db,id:\"minecraft:stone_bricks\",Count:64b}";
            String dirt = "{Slot:%db,id:\"minecraft:dirt\",Count:64b}";
            cmd(mc, String.format(Locale.ROOT, "setblock -18 %d 8 minecraft:chest{Items:[%s,%s,%s,%s,%s]}", y0,
                    String.format(bricks, 0), String.format(bricks, 1), String.format(bricks, 2), String.format(bricks, 3),
                    String.format(bricks, 4)));
            cmd(mc, String.format(Locale.ROOT, "setblock -18 %d 11 minecraft:chest{Items:[%s,%s,%s,%s]}", y0,
                    String.format(dirt, 0), String.format(dirt, 1), String.format(bricks, 2), String.format(bricks, 3)));
        }
        if (CREATIVE.contains(s.name())) {
            cmd(mc, "gamemode creative");
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
        if (brushScenario(s.name())) {
            // A 5x5 mound three high (grass on dirt) east of the centre, a 4x4 pit two deep west of it.
            cmd(mc, String.format(Locale.ROOT, "fill 3 %d -2 7 %d 2 dirt", y0, y0 + 1));
            cmd(mc, String.format(Locale.ROOT, "fill 3 %d -2 7 %d 2 grass_block", y0 + 2, y0 + 2));
            cmd(mc, String.format(Locale.ROOT, "fill -7 %d -2 -4 %d 1 air", y0 - 2, y0 - 1));
            cmd(mc, "give @s grass_block 320");
        }
        cmd(mc, String.format(Locale.ROOT, "tp @s %d %d %d", s.start().getX(), s.start().getY(), s.start().getZ()));
    }

    private static void verify(MinecraftClient mc, boolean timedOut) {
        var level = mc.world;
        if (brushScenario(current.name())) {
            // Ground is left on purpose (the mound becomes a gentle hill), so no stray check: every planned
            // block done, and the steepest step smaller than before.
            int missing = 0;
            List<BlockPos> missed = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> e : order.entrySet()) {
                if (!MaterialPlanner.matches(level.getBlockState(e.getKey()), e.getValue())) {
                    missing++;
                    if (missed.size() < 12) {
                        missed.add(e.getKey().subtract(O));
                    }
                }
            }
            int after = maxStep(mc);
            boolean levelled = current.name().equals("level");
            int offLevel = 0;
            if (levelled) {
                for (long k : levelPainted) {
                    int kx = com.testrts.rtsclient.build.TerrainBlend.keyX(k);
                    int kz = com.testrts.rtsclient.build.TerrainBlend.keyZ(k);
                    int gy = groundY(mc, kx, kz);
                    if (gy != LEVEL_Y) {
                        offLevel++;
                        report(String.format(Locale.ROOT, "    off-level column %d %d: ground %d, top block %s", kx, kz, gy - LEVEL_Y,
                                level.getBlockState(new BlockPos(kx, gy, kz)).getBlock().getName().getString()));
                    }
                }
            }
            boolean pass = missing == 0 && (levelled ? offLevel == 0 : after < blendStepBefore && after <= 1);
            StringBuilder profile = new StringBuilder();
            for (int x = -BLEND_X - 2; x <= BLEND_X + 2; x++) {
                profile.append(groundY(mc, x, 0) - (O.getY() - 1)).append(' ');
            }
            report(String.format(Locale.ROOT, "RESULT %-16s %s blocks=%d missing=%d steepest step %d -> %d%s time=%ds maxStall=%ds status=%s",
                    current.name(), timedOut ? "TIMEOUT" : pass ? "PASS" : "FAIL",
                    order.size(), missing, blendStepBefore, after,
                    levelled ? String.format(Locale.ROOT, " off-level=%d/%d", offLevel, levelPainted.size()) : "",
                    ticks / 20, maxStall / 20, Engine.status()));
            report("    heights along z=0, x=" + (-BLEND_X - 2) + ".." + (BLEND_X + 2) + ": " + profile.toString().trim());
            if (!missed.isEmpty()) {
                report("    missed: " + missed.stream().map(BlockPos::toShortString).toList());
            }
            blendClear = null;
            return;
        }
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
                    boolean otherHalf = order.containsKey(p.down())
                            && level.getBlockState(p).getBlock() == level.getBlockState(p.down()).getBlock();
                    boolean ours = FROM_STORAGE.contains(current.name()) && level.getBlockState(p).isOf(Blocks.CHEST);
                    if (!level.getBlockState(p).isAir() && !order.containsKey(p) && !otherHalf && !ours) {
                        stray++;
                        if (strays.size() < 12) {
                            strays.add(p);
                        }
                    }
                }
            }
        }
        BlockPos feet = mc.player.getBlockPos();
        boolean trapped = feet.getX() >= minX && feet.getX() <= maxX && feet.getZ() >= minZ && feet.getZ() <= maxZ
                && feet.getY() >= minY && feet.getY() <= maxY;
        String line = String.format(Locale.ROOT,
                "RESULT %-16s %s blocks=%d missing=%d stray=%d time=%ds maxStall=%ds stalls=%d minHealth=%.0f feet=%s inside=%s status=%s",
                current.name(), timedOut ? "TIMEOUT" : missing == 0 && stray == 0 ? "PASS" : "FAIL",
                order.size(), missing, stray, ticks / 20, maxStall / 20, stalls, minHealth, feet.toShortString(), trapped, Engine.status());
        report(line);
        if (FROM_STORAGE.contains(current.name())) {
            int stored = com.testrts.rtsclient.storage.StorageManager.get().total(net.minecraft.item.Items.STONE_BRICKS);
            int carried = mc.player.getInventory().count(net.minecraft.item.Items.STONE_BRICKS);
            report(String.format(Locale.ROOT, "    storage: %d linked, stone bricks %d left in chests (of %d), %d carried, %d placed",
                    com.testrts.rtsclient.storage.StorageManager.get().linked().size(), stored, STORED_BRICKS, carried,
                    order.size() - missing));
        }
        if (!missed.isEmpty()) {
            report("    missed: " + missed.stream().map(p -> p.subtract(O).toShortString()).toList());
        }
        if (!strays.isEmpty()) {
            report("    stray:  " + strays.stream().map(p -> p.subtract(O).toShortString() + "=" + level.getBlockState(p).getBlock().getName().getString()).toList());
        }
    }

    /** RTS camera looking at the structure from above one side, for the screenshot. */
    private static void showStructure(MinecraftClient mc) {
        RtsController.get().enable(mc);
        double cx = 0.5, cy = O.getY() + 4, cz = 0.5;
        double x = cx - 18, y = cy + 14, z = cz - 18;
        double dx = cx - x, dy = cy - y, dz = cz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        RtsController.get().camera().setPose(mc, new RtsCamera.Pose(x, y, z, yaw, pitch));
    }

    /** Survival: give everything the order needs (stone and dirt come with every scenario). */
    private static void giveMaterials(MinecraftClient mc, Map<BlockPos, BlockState> blocks) {
        Map<net.minecraft.item.Item, Integer> need = OrderPlanner.materials(blocks);
        need.forEach((item, n) -> cmd(mc, "give @s " + net.minecraft.registry.Registries.ITEM.getId(item) + " " + (n + 8)));
    }

    private static void cmd(MinecraftClient mc, String command) {
        mc.player.networkHandler.sendChatCommand(command);
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
