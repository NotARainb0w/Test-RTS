package com.testrts.rtsclient.storage;

import net.minecraft.util.math.Direction;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A storage mod whose terminals the builder can take items from: Applied Energistics 2 (ME terminals)
 * or Refined Storage (grids). Each has a {@link MeNetwork} that remembers its links and contents.
 */
public interface GridMod {
    /** One kind of item the open terminal shows; {@code handle} is what {@link #takeStack} needs. */
    record Entry(Object handle, Item item, long amount) {
    }

    /** Short name for the UI ("ME", "RS"). */
    String name();

    /** Name of the mod's terminal, for messages ("ME terminal", "grid"). */
    String terminalName();

    /** Part of the save file name. */
    String fileSuffix();

    /** The mod is installed and using it for materials is on. */
    boolean enabled();

    /** Overlay colour of linked terminals (0xRRGGBB). */
    int color();

    /** A screen of this mod's terminals that shows the network's items (not portable cells and such). */
    boolean isTerminal(@Nullable ScreenHandler menu);

    /** The terminal's copy of the network has arrived. */
    boolean hasContents(ScreenHandler menu);

    List<Entry> entries(ScreenHandler menu);

    /** Ask for one stack of an entry, straight into the player's inventory. */
    boolean takeStack(ScreenHandler menu, Object handle);

    /** The terminal was opened from a wireless item, not a block. */
    boolean isWireless(ScreenHandler menu);

    boolean isWirelessItem(ItemStack stack);

    /** Ways to open a carried wireless terminal, best first (each tried in turn until one opens). */
    List<String> wirelessOpeners();

    boolean openWireless(String opener);

    /** The side to click for the terminal a click at {@code hit} on this block hit, or null. */
    @Nullable
    Direction terminalSideAt(@Nullable BlockEntity be, Vec3d hit, Direction clickedSide);

    /** Whether a linked terminal is still there. */
    boolean isTerminalAt(@Nullable BlockEntity be, Direction side);

    static Map<Item, Long> totals(List<Entry> entries) {
        Map<Item, Long> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            out.merge(e.item(), e.amount(), Long::sum);
        }
        return out;
    }
}
