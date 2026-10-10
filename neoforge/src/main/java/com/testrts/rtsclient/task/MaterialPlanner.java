package com.testrts.rtsclient.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Works out which items a build still needs and how many are missing from the inventory. */
public final class MaterialPlanner {
    private MaterialPlanner() {
    }

    /** Items needed for the positions that don't already hold the right block. */
    public static Map<Item, Integer> required(ClientLevel world, Map<BlockPos, BlockState> blocks) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockState wanted = e.getValue();
            if (wanted.isAir()) {
                continue;
            }
            if (world.hasChunkAt(e.getKey()) && matches(world.getBlockState(e.getKey()), wanted)) {
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
    public static Map<Item, Integer> missing(Minecraft client, Map<BlockPos, BlockState> blocks) {
        if (com.testrts.rtsclient.exec.Creative.supplies(client)) {
            // Creative supply spawns whatever is needed; nothing to fetch.
            return new java.util.LinkedHashMap<>();
        }
        Map<Item, Integer> missing = new LinkedHashMap<>();
        if (client.player == null || client.level == null) {
            return missing;
        }
        Inventory inv = client.player.getInventory();
        required(client.level, blocks).forEach((item, count) -> {
            int shortBy = count - inv.countItem(item);
            if (shortBy > 0) {
                missing.put(item, shortBy);
            }
        });
        return missing;
    }

    /** Positions that still differ from the order (for builds: wrong block; for clears: not air). */
    public static int remaining(ClientLevel world, Map<BlockPos, BlockState> blocks) {
        int left = 0;
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            if (!world.hasChunkAt(e.getKey())) {
                continue;
            }
            BlockState current = world.getBlockState(e.getKey());
            BlockState wanted = e.getValue();
            // Baritone doesn't mine fluids, so a cleared block that filled with water counts as done.
            boolean ok = matches(current, wanted);
            if (!ok) {
                left++;
            }
        }
        return left;
    }

    /**
     * Whether {@code current} counts as the block an order wants. Same item counts too: a torch placed
     * on a wall is a wall torch, a sign on a wall a wall sign, and so on. Treating those as wrong made
     * the builder mine and re-place them forever.
     */
    public static boolean matches(BlockState current, BlockState wanted) {
        if (wanted.isAir()) {
            return current.isAir() || current.getBlock() instanceof LiquidBlock;
        }
        if (current.getBlock() == wanted.getBlock()) {
            return true;
        }
        // Soil turns into other soil by itself (dirt grows grass, grass under a block or farmland left
        // dry goes back to dirt): rebuilding it would dig the grass up over and over.
        if (soil(current) && soil(wanted)) {
            return true;
        }
        Item item = wanted.getBlock().asItem();
        return item != Items.AIR && current.getBlock().asItem() == item;
    }

    private static boolean soil(BlockState s) {
        var b = s.getBlock();
        return b == net.minecraft.world.level.block.Blocks.DIRT || b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK || b == net.minecraft.world.level.block.Blocks.PODZOL || b == net.minecraft.world.level.block.Blocks.MYCELIUM
                || b == net.minecraft.world.level.block.Blocks.DIRT_PATH || b == net.minecraft.world.level.block.Blocks.FARMLAND;
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
            sb.append(e.getValue()).append("x ").append(e.getKey().getDescription().getString());
            shown++;
        }
        return sb.toString();
    }
}
