package com.testrts.rtsclient.storage;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Inventories;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Traveler's Backpack and Sophisticated Backpacks (unofficial Fabric port): the backpack you wear (or
 * carry) as a storage the builder takes from, without walking anywhere. It's opened the way the mod's
 * own key does it (worn) or by using it (carried), and stacks are shift-clicked out of its storage slots.
 */
public final class Backpacks {
    private Backpacks() {
    }

    private static boolean triedTravelers;
    private static Class<?> travelersMenu;
    private static Class<?> travelersItem;

    private static boolean triedSophisticated;
    private static Class<?> sophisticatedMenu;
    private static Class<?> sophisticatedItem;
    /** Sophisticated Core's slot for stored items; null falls back to guessing from slot class names. */
    private static @Nullable Class<?> sophisticatedStorageSlot;

    private static boolean travelers() {
        if (!FabricLoader.getInstance().isModLoaded("travelersbackpack")) {
            return false;
        }
        if (!triedTravelers) {
            triedTravelers = true;
            try {
                travelersMenu = Class.forName("com.tiviacz.travelersbackpack.inventory.menu.AbstractBackpackMenu");
                travelersItem = Class.forName("com.tiviacz.travelersbackpack.items.TravelersBackpackItem");
            } catch (ReflectiveOperationException | LinkageError e) {
                RtsClientMod.LOGGER.warn("Traveler's Backpack is installed but works differently than expected; its backpacks won't be used", e);
            }
        }
        return travelersMenu != null && travelersItem != null;
    }

    private static boolean sophisticated() {
        if (!FabricLoader.getInstance().isModLoaded("sophisticatedbackpacks")) {
            return false;
        }
        if (!triedSophisticated) {
            triedSophisticated = true;
            try {
                sophisticatedMenu = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer");
                sophisticatedItem = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem");
            } catch (ReflectiveOperationException | LinkageError e) {
                RtsClientMod.LOGGER.warn("Sophisticated Backpacks is installed but works differently than expected; its backpacks won't be used", e);
            }
            try {
                sophisticatedStorageSlot = Class.forName("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot");
            } catch (ReflectiveOperationException | LinkageError e) {
                sophisticatedStorageSlot = null;
            }
        }
        return sophisticatedMenu != null && sophisticatedItem != null;
    }

    public static boolean loaded() {
        return travelers() || sophisticated();
    }

    private static boolean isSophisticated(ScreenHandler menu) {
        return sophisticated() && sophisticatedMenu.isInstance(menu);
    }

    /** A backpack slot that holds stored items (not upgrades, tools, filters, fluids or crafting). */
    private static boolean storageSlot(ScreenHandler menu, Slot slot) {
        if (slot.inventory instanceof PlayerInventory) {
            return false;
        }
        if (sophisticatedStorageSlot != null && isSophisticated(menu)) {
            return sophisticatedStorageSlot.isInstance(slot);
        }
        String name = slot.getClass().getSimpleName();
        return !(name.contains("Upgrade") || name.contains("Filter") || name.contains("Tool") || name.contains("Fluid")
                || name.contains("Crafting") || name.contains("Result") || name.contains("Disabled") || name.contains("Supplied"));
    }

    private static int carriedSlot(ClientPlayerEntity player) {
        if (!travelers()) {
            return -1;
        }
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (travelersItem.isInstance(inv.getStack(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    public static final GridMod GRID = new GridMod() {
        @Override
        public String name() {
            return "Backpack";
        }

        @Override
        public String terminalName() {
            return "backpack";
        }

        @Override
        public String fileSuffix() {
            return "backpack";
        }

        @Override
        public boolean enabled() {
            return RtsConfig.get().useBackpacks && loaded();
        }

        @Override
        public int color() {
            return 0xC08040;
        }

        @Override
        public boolean alwaysTry() {
            return true; // usually worn on the back (or in a Trinkets slot), where we can't see it
        }

        @Override
        public boolean portable() {
            return true;
        }

        @Override
        public boolean isTerminal(@Nullable ScreenHandler menu) {
            return menu != null && (travelers() && travelersMenu.isInstance(menu) || isSophisticated(menu));
        }

        @Override
        public boolean hasContents(ScreenHandler menu) {
            return true;
        }

        @Override
        public List<Entry> entries(ScreenHandler menu) {
            List<Entry> out = new ArrayList<>();
            for (Slot slot : menu.slots) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && storageSlot(menu, slot)) {
                    out.add(new Entry(slot.id, stack.getItem(), stack.getCount()));
                }
            }
            return out;
        }

        @Override
        public boolean takeStack(ScreenHandler menu, Object handle) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) {
                return false;
            }
            client.interactionManager.clickSlot(menu.syncId, (Integer) handle, 0, SlotActionType.QUICK_MOVE, client.player);
            return true;
        }

        @Override
        public boolean isWireless(ScreenHandler menu) {
            // Worn or carried; a backpack placed as a block isn't "on us".
            if (isSophisticated(menu)) {
                try {
                    Object context = menu.getClass().getMethod("getBackpackContext").invoke(menu);
                    return !context.getClass().getSimpleName().startsWith("Block");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return true;
                }
            }
            return !menu.getClass().getSimpleName().contains("BlockEntity");
        }

        @Override
        public boolean isWirelessItem(ItemStack stack) {
            return !stack.isEmpty() && (travelers() && travelersItem.isInstance(stack.getItem())
                    || sophisticated() && sophisticatedItem.isInstance(stack.getItem()));
        }

        @Override
        public List<String> wirelessOpeners() {
            List<String> out = new ArrayList<>();
            if (sophisticated()) {
                out.add("sophisticated"); // finds one anywhere on the player, like its key
            }
            if (travelers()) {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                // One in the inventory is opened by using it; otherwise the worn one, like the mod's key.
                if (player != null && carriedSlot(player) >= 0) {
                    out.add("held");
                }
                out.add("worn");
            }
            return out;
        }

        @Override
        public boolean openWireless(String opener) {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null) {
                return false;
            }
            try {
                if (opener.equals("sophisticated")) {
                    // What the mod's own "open backpack" key sends: opens the first backpack found on the player.
                    Object message = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackOpenMessage")
                            .getConstructor().newInstance();
                    Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.network.SBPPacketHandler")
                            .getMethod("sendToServer", Object.class).invoke(null, message);
                    return true;
                }
                if (opener.equals("held")) {
                    int slot = carriedSlot(player);
                    if (slot < 0 || !Inventories.hold(client, player.getInventory().getStack(slot).getItem())) {
                        return false;
                    }
                    client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                    return true;
                }
                Class<?> packet = Class.forName("com.tiviacz.travelersbackpack.network.ServerboundActionTagPacket");
                int open = packet.getField("OPEN_SCREEN").getInt(null);
                packet.getMethod("create", int.class, Object[].class).invoke(null, open, new Object[0]);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                RtsClientMod.LOGGER.warn("Couldn't open the backpack", e);
                return false;
            }
        }

        @Override
        public @Nullable Direction terminalSideAt(@Nullable BlockEntity be, Vec3d hit, Direction clickedSide) {
            return null;
        }

        @Override
        public boolean isTerminalAt(@Nullable BlockEntity be, Direction side) {
            return false;
        }
    };
}
