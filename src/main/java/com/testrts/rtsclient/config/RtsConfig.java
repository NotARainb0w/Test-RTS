package com.testrts.rtsclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.testrts.rtsclient.RtsClientMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Player preferences, stored as JSON in config/rtsclient/config.json. */
public final class RtsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RtsConfig instance = new RtsConfig();

    /** Camera pan speed in blocks per second. */
    public double cameraSpeed = 18.0;
    /** How far the camera may drift from the player, in blocks. */
    public double maxCameraDistance = 128.0;
    /** Degrees of rotation per pixel of right-drag. */
    public float rotateSensitivity = 0.35F;
    /** Blocks moved per scroll notch when zooming. */
    public double zoomStep = 2.5;
    /** Upper bound for one shape order, so a misclick can't queue millions of blocks. */
    public int maxShapeBlocks = 262_144;
    /** Shapes up to this many blocks are previewed as real (see-through) blocks; bigger ones as a tinted surface. */
    public int ghostBlockLimit = 24_000;
    /** Build one Y layer at a time (bottom-up; clearing goes top-down). */
    public boolean buildInLayers = true;
    /** Cheap blocks the built-in engine may use as temporary scaffolding, in order of preference. */
    public List<String> scaffoldBlocks = new ArrayList<>(List.of(
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:stone",
            "minecraft:deepslate", "minecraft:blackstone", "minecraft:basalt"));
    /**
     * Hollow spheres/circles get a shell that is thicker on the inside where needed, so every block
     * touches its neighbours face to face and can actually be placed. Looks the same from outside.
     */
    public boolean buildableShells = true;
    /** Mine the scaffolding back out when an order is finished. */
    public boolean removeScaffolding = true;
    /** After a clear order, walk over the dropped items to pick them up. */
    public boolean collectDrops = true;
    /** Stop the camera at solid blocks. Off: the camera flies through walls. */
    public boolean cameraCollision = false;
    /** Radius used by "Scan nearby" when looking for containers. */
    public int scanRadius = 32;
    /** When false, Baritone may place oriented blocks (stairs, logs) in any direction it can manage. */
    public boolean strictOrientation = false;
    /** Let the mod flip the Baritone settings it relies on (allowInventory, allowPlace, allowBreak). */
    public boolean manageBaritoneSettings = true;
    /** Who walks, places and mines: "auto" (Baritone if installed), "builtin" or "baritone". */
    public String engine = "auto";
    /** Item ids pinned to the quick bar. */
    public List<String> pinned = new ArrayList<>();

    public static RtsConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtsclient").resolve("config.json");
    }

    public static void load() {
        Path file = path();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                RtsConfig loaded = GSON.fromJson(reader, RtsConfig.class);
                if (loaded != null) {
                    if (loaded.pinned == null) {
                        loaded.pinned = new ArrayList<>();
                    }
                    if (loaded.scaffoldBlocks == null || loaded.scaffoldBlocks.isEmpty()) {
                        loaded.scaffoldBlocks = new RtsConfig().scaffoldBlocks;
                    }
                    instance = loaded;
                }
            } catch (IOException | RuntimeException e) {
                RtsClientMod.LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }
        save();
    }

    public static void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            RtsClientMod.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
