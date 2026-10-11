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
    }

    private enum Phase { TITLE, JOINING, SETUP, START, RUN, SHOT, NEXT, FINISHED }

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
                setup(mc, current);
                wait = 60;
                phase = Phase.START;
            }
            case START -> {
                // The arena clear runs on the server; plan only once it has reached us, or leftovers of the
                // last scenario count as "already built" (a dome came out with a hole in its top that way).
                if (!arenaClear(mc) && ++clearWaits < 20) {
                    wait = 10;
                    return;
                }
                clearWaits = 0;
                Scenario s = current;
                RtsConfig cfg = RtsConfig.get();
                cfg.engine = s.engine();
                cfg.airPlace = s.airPlace();
                OrderPlanner.Request r = new OrderPlanner.Request(ToolMode.BUILD, s.shape(), BuildMode.FILL, s.a(), s.b(),
                        s.height(), s.hollow(), (FROM_STORAGE.contains(s.name()) ? Blocks.STONE_BRICKS : Blocks.STONE).defaultBlockState(),
                        null, 100_000, List.of(), true,
                        com.testrts.rtsclient.build.MixPattern.RANDOM);
                if (s.preset() != null) {
                    // A preset building, pasted like the Blueprint tool does (only what isn't there already).
                    var bp = com.testrts.rtsclient.build.Presets.all().stream().filter(p -> p.name().equals(s.preset()))
                            .findFirst().orElseThrow().build().get();
                    order = new java.util.LinkedHashMap<>();
                    bp.placed(s.a(), 0).forEach((pos, st) -> {
                        if (!MaterialPlanner.matches(mc.level.getBlockState(pos), st)) {
                            order.put(pos, st);
                        }
                    });
                    cmd(mc, "clear @s minecraft:stone"); // room in the inventory for the preset's blocks
                    giveMaterials(mc, order);
                } else {
                    order = OrderPlanner.plan(mc.level, r).changes();
                }
                if (FROM_STORAGE.contains(s.name())) {
                    // Like pressing Scan nearby: link the chests and read them; the build then fetches.
                    new com.testrts.rtsclient.screen.WorldActions(mc, RtsController.get()).scanNearby();
                } else if (s.preset() == null) {
                    // Railings, lanterns...: anything besides the stone every scenario gets.
                    Map<BlockPos, BlockState> extra = new java.util.HashMap<>(order);
                    extra.values().removeIf(st -> st.is(Blocks.STONE));
                    giveMaterials(mc, extra);
                }
                task = new BuildTask("autotest " + s.name(), order, false);
                TaskQueue.get().add(task);
                ticks = 0;
                minHealth = mc.player.getHealth();
                log("start %s: %d blocks, engine=%s air=%s", s.name(), order.size(), s.engine(), s.airPlace());
                phase = Phase.RUN;
            }
            case RUN -> {
                ticks++;
                minHealth = Math.min(minHealth, mc.player.getHealth());
                if (ticks % 100 == 0) {
                    int left = MaterialPlanner.remaining(mc.level, order);
                    log("  t=%ds left=%d status=%s task=%s pos=%s", ticks / 20, left, Engine.status(),
                            task.detail(),
                            mc.player.blockPosition().toShortString());
                }
                // Only our own order counts: someone may queue things by hand in the same window.
                boolean running = TaskQueue.get().snapshot().contains(task);
                // Bigger orders get longer: roughly a second a block on top of the base limit.
                int limit = Math.max(current.timeoutTicks(), 20 * (120 + order.size()));
                if (!running || ticks > limit) {
                    TaskQueue.get().remove(mc, task);
                    boolean timedOut = running;
                    verify(mc, timedOut);
                    showStructure(mc);
                    wait = 30;
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
                    if (!st.isAir() && !st.is(Blocks.CHEST)) {
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
        if (CREATIVE.contains(s.name())) {
            cmd(mc, "gamemode creative");
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
                    boolean ours = FROM_STORAGE.contains(current.name()) && level.getBlockState(p).is(Blocks.CHEST);
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
                "RESULT %-16s %s blocks=%d missing=%d stray=%d time=%ds minHealth=%.0f feet=%s inside=%s status=%s",
                current.name(), timedOut ? "TIMEOUT" : missing == 0 && stray == 0 ? "PASS" : "FAIL",
                order.size(), missing, stray, ticks / 20, minHealth, feet.toShortString(), trapped, Engine.status());
        report(line);
        if (FROM_STORAGE.contains(current.name())) {
            int stored = com.testrts.rtsclient.storage.StorageManager.get().total(net.minecraft.world.item.Items.STONE_BRICKS);
            int carried = mc.player.getInventory().countItem(net.minecraft.world.item.Items.STONE_BRICKS);
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
