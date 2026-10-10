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
    /** Upper bound for one shape order, so a misclick can't queue a million blocks. */
    public int maxShapeBlocks = 16384;
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
