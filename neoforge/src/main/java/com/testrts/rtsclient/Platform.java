package com.testrts.rtsclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** The few things that differ between mod loaders. */
public final class Platform {
    private Platform() {
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isModLoaded(String id) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(id);
    }

    /** The key a mapping is bound to right now (after the player changed it in Controls). */
    public static InputConstants.Key boundKey(KeyMapping mapping) {
        return mapping.getKey();
    }
}
