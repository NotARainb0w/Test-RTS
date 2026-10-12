package com.testrts.rtsclient.storage;

import com.testrts.rtsclient.Platform;
import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Refined Storage 2, through reflection so the mod works without it. A grid screen (grid, crafting
 * grid, wireless grid, and the wireless crafting grid of RS Quartz Arsenal) keeps a copy of the
 * network's resources; taking a stack is the grid's own "extract into inventory". Wireless grids are
 * opened the way RS's own key does it, which finds them in any slot (Curios too).
 */
public final class Rs2 {
    private Rs2() {
    }

    private static boolean tried;
    private static boolean ok;
    private static Class<?> menuClass;
    private static Method getRepository;
    private static Method copyBackingList;
    private static Method getAll;
    private static Method getAmount;
    private static Class<?> itemResourceClass;
    private static Method item;
    private static Method onExtract;
    private static Object entireResource;
    private static Object api;
    private static Method useSlotReferencedItem;
    private static Class<?> gridBlockEntityClass;

    public static boolean loaded() {
        return Platform.isModLoaded("refinedstorage") && init();
    }

    private static synchronized boolean init() {
        if (tried) {
            return ok;
        }
        tried = true;
        try {
            menuClass = Class.forName("com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu");
            getRepository = menuClass.getMethod("getRepository");
            copyBackingList = Class.forName("com.refinedmods.refinedstorage.api.resource.repository.ResourceRepository").getMethod("copyBackingList");
            Class<?> list = Class.forName("com.refinedmods.refinedstorage.api.resource.list.ResourceList");
            Class<?> key = Class.forName("com.refinedmods.refinedstorage.api.resource.ResourceKey");
            getAll = list.getMethod("getAll");
            getAmount = list.getMethod("get", key);
            itemResourceClass = Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource");
            item = itemResourceClass.getMethod("item");
            Class<?> mode = Class.forName("com.refinedmods.refinedstorage.api.network.node.grid.GridExtractMode");
            entireResource = mode.getField("ENTIRE_RESOURCE").get(null);
            onExtract = menuClass.getMethod("onExtract",
                    Class.forName("com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey"), mode, boolean.class);
            Class<?> apiClass = Class.forName("com.refinedmods.refinedstorage.common.api.RefinedStorageApi");
            api = apiClass.getField("INSTANCE").get(null);
            useSlotReferencedItem = apiClass.getMethod("useSlotReferencedItem", Player.class, Item[].class);
            gridBlockEntityClass = Class.forName("com.refinedmods.refinedstorage.common.grid.AbstractGridBlockEntity");
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RtsClientMod.LOGGER.warn("Refined Storage is installed but works differently than expected; its grids won't be used", e);
        }
        return ok;
    }

    /**
     * A wireless grid item, by id: any Refined Storage mod's item named like one (wireless grid,
     * wireless crafting grid, and their creative versions). Matching by class didn't work at runtime.
     */
    private static boolean isWirelessGrid(Item i) {
        var id = BuiltInRegistries.ITEM.getKey(i);
        return id.getNamespace().startsWith("refinedstorage") && id.getPath().contains("wireless") && id.getPath().contains("grid")
                && !id.getPath().contains("autocrafting");
    }

    /** Wireless grid items, crafting ones first. */
    private static Item[] wirelessItems() {
        List<Item> crafting = new ArrayList<>();
        List<Item> plain = new ArrayList<>();
        for (Item i : BuiltInRegistries.ITEM) {
            if (isWirelessGrid(i)) {
                (BuiltInRegistries.ITEM.getKey(i).getPath().contains("crafting") ? crafting : plain).add(i);
            }
        }
        crafting.addAll(plain);
        return crafting.toArray(new Item[0]);
    }

    public static final GridMod GRID = new GridMod() {
        @Override
        public String name() {
            return "RS";
        }

        @Override
        public String terminalName() {
            return "grid";
        }

        @Override
        public String fileSuffix() {
            return "rs";
        }

        @Override
        public boolean enabled() {
            return RtsConfig.get().useRefinedStorage && loaded();
        }

        @Override
        public int color() {
            return 0xFF9A3C;
        }

        @Override
        public boolean isTerminal(@Nullable AbstractContainerMenu menu) {
            return menu != null && loaded() && menuClass.isInstance(menu);
        }

        @Override
        public boolean hasContents(AbstractContainerMenu menu) {
            try {
                return getRepository.invoke(menu) != null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public List<Entry> entries(AbstractContainerMenu menu) {
            List<Entry> out = new ArrayList<>();
            try {
                Object repository = getRepository.invoke(menu);
                if (repository == null) {
                    return out;
                }
                // The whole network, not just what the grid's search box shows.
                Object list = copyBackingList.invoke(repository);
                for (Object key : (Collection<?>) getAll.invoke(list)) {
                    if (itemResourceClass.isInstance(key)) {
                        long amount = (long) getAmount.invoke(list, key);
                        if (amount > 0) {
                            out.add(new Entry(key, (Item) item.invoke(key), amount));
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                RtsClientMod.LOGGER.debug("Couldn't read the Refined Storage grid", e);
            }
            return out;
        }

        @Override
        public boolean takeStack(AbstractContainerMenu menu, Object handle) {
            try {
                onExtract.invoke(menu, handle, entireResource, false);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                RtsClientMod.LOGGER.warn("Couldn't take items from the Refined Storage grid", e);
                return false;
            }
        }

        @Override
        public boolean isWireless(AbstractContainerMenu menu) {
            return menu.getClass().getSimpleName().startsWith("Wireless");
        }

        @Override
        public boolean isWirelessItem(ItemStack stack) {
            if (stack.isEmpty() || !loaded()) {
                return false;
            }
            return isWirelessGrid(stack.getItem());
        }

        @Override
        public List<String> wirelessOpeners() {
            return List.of("grid"); // one call finds any wireless grid the player carries
        }

        @Override
        public boolean openWireless(String opener) {
            Player player = Minecraft.getInstance().player;
            Item[] items = wirelessItems();
            if (player == null || items.length == 0) {
                RtsClientMod.LOGGER.debug("No Refined Storage wireless grid items found to open");
                return false;
            }
            try {
                int carried = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (isWirelessItem(player.getInventory().getItem(i))) {
                        carried++;
                    }
                }
                RtsClientMod.LOGGER.debug("Opening the RS wireless grid: {} kinds of wireless grid, {} carried", items.length, carried);
                useSlotReferencedItem.invoke(api, player, (Object) items);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                RtsClientMod.LOGGER.warn("Couldn't open the wireless grid", e);
                return false;
            }
        }

        @Override
        public @Nullable Direction terminalSideAt(@Nullable BlockEntity be, Vec3 hit, Direction clickedSide) {
            // A grid is a whole block: any side opens it.
            return be != null && loaded() && gridBlockEntityClass.isInstance(be) ? clickedSide : null;
        }

        @Override
        public boolean isTerminalAt(@Nullable BlockEntity be, Direction side) {
            return be != null && loaded() && gridBlockEntityClass.isInstance(be);
        }
    };
}
