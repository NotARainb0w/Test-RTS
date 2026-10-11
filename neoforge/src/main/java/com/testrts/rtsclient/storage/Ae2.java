package com.testrts.rtsclient.storage;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.Platform;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applied Energistics 2, through reflection so the mod works without it. Everything here is AE2's
 * own client-side API: an ME terminal screen keeps a copy of the network's items (the "client repo"),
 * and asking for an item is a shift-click on one of its entries, which AE2 sends to the server.
 * Wireless terminals (AE2's own and ae2wtlib's) are opened with AE2's hotkey packet, so they work
 * from any inventory slot, Curios or Trinkets.
 */
public final class Ae2 {
    private Ae2() {
    }

    /** One stack of something the network holds. */
    public record Entry(long serial, Item item, long amount) {
    }

    private static boolean tried;
    private static boolean ok;
    private static Class<?> menuClass;
    private static Method getClientRepo;
    private static Method getAllEntries;
    private static Method getSerial;
    private static Method getWhat;
    private static Method getStoredAmount;
    private static Method getHost;
    private static Class<?> itemKeyClass;
    private static Method getItem;
    private static Method handleInteraction;
    private static Object shiftClick;
    private static Class<?> wirelessItemClass;
    private static Class<?> partHostClass;
    private static Method getPart;
    private static Class<?> terminalHostClass;
    private static Method selectPartWorld;
    private static java.lang.reflect.Field selectedPart;
    private static java.lang.reflect.Field selectedSide;

    public static boolean loaded() {
        return Platform.isModLoaded("ae2") && init();
    }

    private static synchronized boolean init() {
        if (tried) {
            return ok;
        }
        tried = true;
        try {
            menuClass = Class.forName("appeng.menu.me.common.MEStorageMenu");
            getClientRepo = menuClass.getMethod("getClientRepo");
            getHost = menuClass.getMethod("getHost");
            Class<?> repo = Class.forName("appeng.menu.me.common.IClientRepo");
            getAllEntries = repo.getMethod("getAllEntries");
            Class<?> entry = Class.forName("appeng.menu.me.common.GridInventoryEntry");
            getSerial = entry.getMethod("getSerial");
            getWhat = entry.getMethod("getWhat");
            getStoredAmount = entry.getMethod("getStoredAmount");
            itemKeyClass = Class.forName("appeng.api.stacks.AEItemKey");
            getItem = itemKeyClass.getMethod("getItem");
            Class<?> action = Class.forName("appeng.helpers.InventoryAction");
            handleInteraction = menuClass.getMethod("handleInteraction", long.class, action);
            shiftClick = action.getField("SHIFT_CLICK").get(null);
            wirelessItemClass = Class.forName("appeng.items.tools.powered.WirelessTerminalItem");
            partHostClass = Class.forName("appeng.api.parts.IPartHost");
            getPart = partHostClass.getMethod("getPart", Direction.class);
            terminalHostClass = Class.forName("appeng.api.storage.ITerminalHost");
            selectPartWorld = partHostClass.getMethod("selectPartWorld", net.minecraft.world.phys.Vec3.class);
            Class<?> selected = Class.forName("appeng.api.parts.SelectedPart");
            selectedPart = selected.getField("part");
            selectedSide = selected.getField("side");
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RtsClientMod.LOGGER.warn("Applied Energistics 2 is installed but works differently than expected; ME terminals won't be used", e);
        }
        return ok;
    }

    /** An ME terminal screen (any kind: plain, crafting, pattern, wireless), but not a portable cell. */
    public static boolean isTerminal(@Nullable AbstractContainerMenu handler) {
        if (handler == null || !loaded() || !menuClass.isInstance(handler)) {
            return false;
        }
        try {
            Object host = getHost.invoke(handler);
            return host == null || !host.getClass().getName().contains("PortableCell");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** A terminal opened from a wireless terminal item (AE2's or ae2wtlib's), not a block. */
    public static boolean isWireless(AbstractContainerMenu handler) {
        try {
            Object host = getHost.invoke(handler);
            String name = host == null ? "" : host.getClass().getName();
            return name.contains("Wireless") || name.contains("ae2wtlib");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Whether the terminal's copy of the network has arrived (the screen sets it up when it opens). */
    public static boolean hasContents(AbstractContainerMenu handler) {
        try {
            return getClientRepo.invoke(handler) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Items the open terminal shows (other kinds of stacks, like fluids, are left out). */
    public static List<Entry> entries(AbstractContainerMenu handler) {
        List<Entry> out = new ArrayList<>();
        try {
            Object repo = getClientRepo.invoke(handler);
            if (repo == null) {
                return out;
            }
            for (Object e : (Collection<?>) getAllEntries.invoke(repo)) {
                Object what = getWhat.invoke(e);
                long amount = (long) getStoredAmount.invoke(e);
                if (amount > 0 && itemKeyClass.isInstance(what)) {
                    out.add(new Entry((long) getSerial.invoke(e), (Item) getItem.invoke(what), amount));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RtsClientMod.LOGGER.debug("Couldn't read the ME terminal", e);
        }
        return out;
    }

    public static Map<Item, Long> totals(List<Entry> entries) {
        Map<Item, Long> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            out.merge(e.item(), e.amount(), Long::sum);
        }
        return out;
    }

    /** Ask the network for one stack of an entry, straight into the player's inventory (shift-click). */
    public static boolean takeStack(AbstractContainerMenu handler, long serial) {
        try {
            handleInteraction.invoke(handler, serial, shiftClick);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RtsClientMod.LOGGER.warn("Couldn't take items from the ME terminal", e);
            return false;
        }
    }

    public static boolean isWirelessTerminal(ItemStack stack) {
        return !stack.isEmpty() && loaded() && wirelessItemClass.isInstance(stack.getItem());
    }

    /** Whether the face {@code side} of this block holds an ME terminal part (on a cable). */
    public static boolean isTerminalPart(@Nullable BlockEntity be, Direction side) {
        if (be == null || !loaded() || !partHostClass.isInstance(be)) {
            return false;
        }
        try {
            Object part = getPart.invoke(be, side);
            return part != null && terminalHostClass.isInstance(part);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** A block that holds AE2 parts (cables, terminals, buses...). */
    public static boolean isPartHost(@Nullable BlockEntity be) {
        return be != null && loaded() && partHostClass.isInstance(be);
    }

    /**
     * The face of the ME terminal a click on this block hit, or null if it didn't hit one. Asks AE2
     * which part is at the clicked point (as AE2 does for a right-click), so the rim or edge of a
     * terminal counts too; otherwise the clicked face, or the only terminal on the block.
     */
    @Nullable
    public static Direction terminalSideAt(@Nullable BlockEntity be, net.minecraft.world.phys.Vec3 hit, Direction clickedSide) {
        if (!isPartHost(be)) {
            return null;
        }
        try {
            Object selected = selectPartWorld.invoke(be, hit);
            if (selected != null) {
                Object part = selectedPart.get(selected);
                Object side = selectedSide.get(selected);
                if (part != null && side instanceof Direction d && terminalHostClass.isInstance(part)) {
                    return d;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            RtsClientMod.LOGGER.debug("Couldn't ask AE2 which part was clicked", e);
        }
        if (isTerminalPart(be, clickedSide)) {
            return clickedSide;
        }
        Direction only = null;
        for (Direction d : Direction.values()) {
            if (isTerminalPart(be, d)) {
                if (only != null) {
                    return null; // several terminals: we can't tell which one was meant
                }
                only = d;
            }
        }
        return only;
    }

    /**
     * AE2 hotkeys that open a wireless terminal, best first. AE2's own is "wireless_terminal";
     * ae2wtlib adds one per terminal kind. Other hotkeys (portable cells, restock, magnet) are left out.
     */
    public static List<String> wirelessHotkeys() {
        List<String> out = new ArrayList<>();
        out.add("wireless_terminal");
        try {
            Map<?, ?> registry = (Map<?, ?>) Class.forName("appeng.hotkeys.HotkeyActions").getField("REGISTRY").get(null);
            for (Object key : registry.keySet()) {
                String name = String.valueOf(key);
                if (!out.contains(name) && !name.contains("portable") && !name.contains("restock")
                        && !name.contains("magnet") && !name.contains("stow")) {
                    out.add(name);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RtsClientMod.LOGGER.debug("Couldn't list AE2 hotkeys", e);
        }
        // Terminals with the network's item list first (crafting, plain, pattern encoding); the pattern
        // access terminal has none, so it goes last.
        out.sort(java.util.Comparator.comparingInt(Ae2::hotkeyRank));
        return out;
    }

    private static int hotkeyRank(String name) {
        if (name.contains("crafting")) {
            return 0;
        }
        if (name.equals("wireless_terminal")) {
            return 1;
        }
        if (name.contains("encoding")) {
            return 2;
        }
        return name.contains("access") ? 4 : 3;
    }

    /** Press an AE2 hotkey for the player: the server opens the matching terminal if they carry one. */
    public static boolean pressHotkey(String name) {
        try {
            Object packet = Class.forName("appeng.core.network.serverbound.HotkeyPacket").getConstructor(String.class).newInstance(name);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    (net.minecraft.network.protocol.common.custom.CustomPacketPayload) packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RtsClientMod.LOGGER.warn("Couldn't open the wireless terminal", e);
            return false;
        }
    }
}
