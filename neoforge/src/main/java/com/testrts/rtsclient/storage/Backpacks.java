package com.testrts.rtsclient.storage;

import com.testrts.rtsclient.Platform;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sophisticated Backpacks: the backpack you carry (inventory, worn or Curios) as a storage the builder
 * takes from, without walking anywhere. It's opened the way the mod's own key does it, and stacks are
 * shift-clicked out of its storage slots.
 */
public final class Backpacks {
    private Backpacks() {
    }

    private static boolean tried;
    private static Class<?> menuClass;
    private static Class<?> itemClass;

    public static boolean loaded() {
        if (!Platform.isModLoaded("sophisticatedbackpacks")) {
            return false;
        }
        if (!tried) {
            tried = true;
            try {
                menuClass = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer");
                itemClass = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem");
            } catch (ReflectiveOperationException | LinkageError e) {
                RtsClientMod.LOGGER.warn("Sophisticated Backpacks is installed but works differently than expected; backpacks won't be used", e);
            }
        }
        return menuClass != null && itemClass != null;
    }

    /** A backpack slot that holds stored items (not upgrades, filters, tools, fluids or crafting). */
    private static boolean storageSlot(Slot slot) {
        if (slot.container instanceof Inventory) {
            return false;
        }
        String name = slot.getClass().getSimpleName();
        return !(name.contains("Upgrade") || name.contains("Filter") || name.contains("Tool") || name.contains("Fluid")
                || name.contains("Crafting") || name.contains("Result") || name.contains("Disabled"));
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
            return true; // may be worn or in a Curios slot, where we can't see it
        }

        @Override
        public boolean portable() {
            return true;
        }

        @Override
        public boolean isTerminal(@Nullable AbstractContainerMenu menu) {
            return menu != null && loaded() && menuClass.isInstance(menu);
        }

        @Override
        public boolean hasContents(AbstractContainerMenu menu) {
            return true;
        }

        @Override
        public List<Entry> entries(AbstractContainerMenu menu) {
            List<Entry> out = new ArrayList<>();
            for (Slot slot : menu.slots) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && storageSlot(slot)) {
                    out.add(new Entry(slot.index, stack.getItem(), stack.getCount()));
                }
            }
            return out;
        }

        @Override
        public boolean takeStack(AbstractContainerMenu menu, Object handle) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) {
                return false;
            }
            client.gameMode.handleInventoryMouseClick(menu.containerId, (Integer) handle, 0, ClickType.QUICK_MOVE, client.player);
            return true;
        }

        @Override
        public boolean isWireless(AbstractContainerMenu menu) {
            return true; // opened from the item, wherever it is
        }

        @Override
        public boolean isWirelessItem(ItemStack stack) {
            return !stack.isEmpty() && loaded() && itemClass.isInstance(stack.getItem());
        }

        @Override
        public List<String> wirelessOpeners() {
            return List.of("open");
        }

        @Override
        public boolean openWireless(String opener) {
            try {
                // What the mod's own "open backpack" key sends: opens the first backpack found on the player.
                Object payload = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackOpenPayload")
                        .getConstructor().newInstance();
                net.neoforged.neoforge.network.PacketDistributor.sendToServer((CustomPacketPayload) payload);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                RtsClientMod.LOGGER.warn("Couldn't open the backpack", e);
                return false;
            }
        }

        @Override
        public @Nullable Direction terminalSideAt(@Nullable BlockEntity be, Vec3 hit, Direction clickedSide) {
            return null;
        }

        @Override
        public boolean isTerminalAt(@Nullable BlockEntity be, Direction side) {
            return false;
        }
    };
}
