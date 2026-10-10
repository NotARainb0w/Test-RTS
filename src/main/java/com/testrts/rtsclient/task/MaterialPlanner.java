package com.testrts.rtsclient.task;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Works out which items a build still needs and how many are missing from the inventory. */
public final class MaterialPlanner {
    private MaterialPlanner() {
    }

    /** Items needed for the positions that don't already hold the right block. */
    public static Map<Item, Integer> required(ClientWorld world, Map<BlockPos, BlockState> blocks) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockState wanted = e.getValue();
            if (wanted.isAir()) {
                continue;
            }
            if (world.isChunkLoaded(e.getKey()) && world.getBlockState(e.getKey()).getBlock() == wanted.getBlock()) {
                continue;
            }
            Item item = wanted.getBlock().asItem();
            if (item != Items.AIR) {
                need.merge(item, 1, Integer::sum);
            }
        }
        return need;
    }

    /** {@link #required} minus what the player is carrying. */
    public static Map<Item, Integer> missing(MinecraftClient client, Map<BlockPos, BlockState> blocks) {
        Map<Item, Integer> missing = new LinkedHashMap<>();
        if (client.player == null || client.world == null) {
            return missing;
        }
        PlayerInventory inv = client.player.getInventory();
        required(client.world, blocks).forEach((item, count) -> {
            int shortBy = count - inv.count(item);
            if (shortBy > 0) {
                missing.put(item, shortBy);
            }
        });
        return missing;
    }

    /** Positions that still differ from the order (for builds: wrong block; for clears: not air). */
    public static int remaining(ClientWorld world, Map<BlockPos, BlockState> blocks) {
        int left = 0;
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            if (!world.isChunkLoaded(e.getKey())) {
                continue;
            }
            BlockState current = world.getBlockState(e.getKey());
            BlockState wanted = e.getValue();
            // Baritone doesn't mine fluids, so a cleared block that filled with water counts as done.
            boolean ok = wanted.isAir()
                    ? current.isAir() || current.getBlock() instanceof FluidBlock
                    : current.getBlock() == wanted.getBlock();
            if (!ok) {
                left++;
            }
        }
        return left;
    }

    public static String describe(Map<Item, Integer> items, int max) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Item, Integer> e : items.entrySet()) {
            if (shown == max) {
                sb.append(", ...");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append("x ").append(e.getKey().getName().getString());
            shown++;
        }
        return sb.toString();
    }
}
